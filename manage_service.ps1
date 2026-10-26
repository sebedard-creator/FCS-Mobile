param(
    [ValidateSet('Start','Stop','Status','Install','Uninstall','Run','Setup','Import')]
    [string]$Action = 'Start',
    [string]$Source
)
$ErrorActionPreference = 'Stop'
$env:PYTHONUTF8 = '1'
$taskName = 'FCSMobile'
$pythonPath = Join-Path $PSScriptRoot '.env\Scripts\python.exe'
$serverPath = Join-Path $PSScriptRoot 'backup_service.py'
$statePath = Join-Path $PSScriptRoot 'data'
$logPath = Join-Path $statePath 'logs'
$env:TEMP = Join-Path $statePath 'tmp'
$env:TMP = $env:TEMP
$env:PIP_CACHE_DIR = Join-Path $statePath 'cache\pip'
if ([IO.Path]::GetPathRoot([IO.Path]::GetFullPath($PSScriptRoot)) -ieq 'C:\' -and $env:FCS_MOBILE_ALLOW_C_DRIVE -ne '1') {
    throw 'Le stockage sur C: est interdit par défaut. Choisir un autre disque ou définir FCS_MOBILE_ALLOW_C_DRIVE=1.'
}
New-Item -ItemType Directory -Path $env:TEMP -Force | Out-Null
$powershellPath = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'

function Initialize-Runtime {
    if (-not (Test-Path -LiteralPath $pythonPath)) {
        & python -m venv (Join-Path $PSScriptRoot '.env')
        if ($LASTEXITCODE -ne 0) { throw 'Python 3.11 ou plus récent est requis.' }
    }
    & $pythonPath -c "import sys; sys.exit(0 if sys.version_info >= (3, 11) else 1)"
    if ($LASTEXITCODE -ne 0) { throw 'Python 3.11 ou plus récent est requis. Recréer le venv .env avec cette version.' }
    & $pythonPath -m pip install --upgrade 'pip>=26.2' 'setuptools>=84.1.0'
    if ($LASTEXITCODE -ne 0) { throw 'Échec de la mise à jour des outils Python.' }
    & $pythonPath -m pip install -r (Join-Path $PSScriptRoot 'requirements.txt')
    if ($LASTEXITCODE -ne 0) { throw "Échec de l'installation des dépendances." }
}

function Get-ServerProcess {
    $candidates = @(Get-CimInstance Win32_Process -Filter "Name='python.exe' OR Name='pythonw.exe'")
    $normalizedScript = $serverPath.Replace('/','\')
    $normalizedPython = $pythonPath.Replace('/','\')
    $direct = @($candidates | Where-Object {
        $command = if ($_.CommandLine) { $_.CommandLine.Replace('/','\') } else { '' }
        $command.IndexOf($normalizedScript, [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
            ($command.IndexOf($normalizedPython, [StringComparison]::OrdinalIgnoreCase) -ge 0 -and $command -match '\bbackup_service\.py\b')
    })
    $directIds = @($direct | Select-Object -ExpandProperty ProcessId)
    $candidates | Where-Object { $_.ProcessId -in $directIds -or $_.ParentProcessId -in $directIds }
}

switch ($Action) {
    'Setup' { Initialize-Runtime }
    'Run' {
        if (-not (Test-Path -LiteralPath $pythonPath)) { throw 'Exécuter setup.bat avant de lancer le serveur.' }
        New-Item -ItemType Directory -Path $logPath -Force | Out-Null
        Set-Location -LiteralPath $PSScriptRoot
        & $pythonPath -u $serverPath *>> (Join-Path $logPath 'server.log')
        exit $LASTEXITCODE
    }
    'Start' {
        if (Get-ServerProcess) { Write-Output 'FCS Mobile est déjà lancé.'; break }
        if (-not (Test-Path -LiteralPath $pythonPath)) { Initialize-Runtime }
        $runner = Join-Path $PSScriptRoot 'manage_service.ps1'
        Start-Process -FilePath $powershellPath -WindowStyle Hidden -WorkingDirectory $PSScriptRoot `
            -ArgumentList @('-NoProfile','-WindowStyle','Hidden','-ExecutionPolicy','Bypass','-File',('"' + $runner + '"'),'-Action','Run')
        Write-Output 'FCS Mobile démarre. Administration : http://127.0.0.1:5001'
    }
    'Stop' {
        $task = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
        if ($task) { Stop-ScheduledTask -TaskName $taskName }
        Get-ServerProcess | ForEach-Object { Stop-Process -Id $_.ProcessId -ErrorAction SilentlyContinue }
        Write-Output 'FCS Mobile arrêté.'
    }
    'Status' {
        try {
            $health = Invoke-RestMethod -Uri 'http://127.0.0.1:5001/health' -TimeoutSec 3
            if ($health.service -ne 'FCS Mobile' -or $health.protocol -ne 2) { throw 'Service incompatible sur le port 5001.' }
            Write-Output 'FCS Mobile est accessible. Administration : http://127.0.0.1:5001'
        } catch { Write-Output "Serveur inaccessible : $($_.Exception.Message)"; exit 1 }
    }
    'Install' {
        Initialize-Runtime
        $runner = Join-Path $PSScriptRoot 'manage_service.ps1'
        $arguments = '-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "' + $runner + '" -Action Run'
        $taskAction = New-ScheduledTaskAction -Execute $powershellPath -Argument $arguments -WorkingDirectory $PSScriptRoot
        $user = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
        $trigger = New-ScheduledTaskTrigger -AtLogOn -User $user
        $principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
        $taskSettings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
            -StartWhenAvailable -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero)
        Register-ScheduledTask -TaskName $taskName -Action $taskAction -Trigger $trigger -Principal $principal -Settings $taskSettings -Force | Out-Null
        if (-not (Get-ServerProcess)) { Start-ScheduledTask -TaskName $taskName }
        Write-Output 'FCS Mobile démarrera à chaque ouverture de session. Après déplacement du projet, relancer install_service.bat.'
    }
    'Uninstall' {
        & $PSCommandPath -Action Stop
        $task = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
        if ($task) { Unregister-ScheduledTask -TaskName $taskName -Confirm:$false }
        Write-Output 'Démarrage automatique retiré. La bibliothèque reste conservée.'
    }
    'Import' {
        if (-not $Source) { throw 'Indiquer un dossier source avec -Source.' }
        if (Get-ServerProcess) { throw 'Arrêter le serveur avec stop.bat avant un import.' }
        if (-not (Test-Path -LiteralPath $pythonPath)) { Initialize-Runtime }
        & $pythonPath $serverPath --import-folder $Source
        exit $LASTEXITCODE
    }
}
