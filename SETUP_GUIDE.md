# Installation guide

[Project overview](README.md) · [Documentation](docs/README.md)

This guide starts from a fresh GitHub source checkout and uses the scripts included in the repository.

## What you need

| Component | Requirement |
| --- | --- |
| Server | Windows and [Python 3.11 or later](https://www.python.org/downloads/windows/) |
| Phone | Android 12 or later |
| Network | Local Wi-Fi through which the phone can reach the PC |
| Storage | A writable project and library location; `C:` requires explicit opt-in |
| Android source build | JDK 17, Android SDK Platform 34, Build-Tools 33.0.1, and Platform-Tools |

By default, the server rejects storage on `C:`. Any other writable drive letter may be used; no particular folder name or drive letter is required. Windows is the supported server platform.

The repository contains source code. Python environments, local settings, signing keys, and generated APKs are created locally and are not included in a source checkout.

## 1. Get the project

On the GitHub repository page, choose **Code → Download ZIP**, then extract it. Alternatively, clone the repository using its displayed Git URL.

Open PowerShell in the extracted project folder, where `setup.bat` and `backup_service.py` are located. Run all commands below from that folder.

### Optional: allow storage on C:

If you intentionally want to install the project or store its library on `C:`, enable this option **before setup**:

```powershell
$env:FCS_MOBILE_ALLOW_C_DRIVE = '1'
[Environment]::SetEnvironmentVariable('FCS_MOBILE_ALLOW_C_DRIVE', '1', 'User')
```

The first command enables it in the current terminal; the second preserves it for future sessions under your Windows account. Restart any external process manager before launching the server so it receives the updated environment. To restore the default restriction, remove the variable from the current session and your user environment, then restart the server. This option permits `C:`; it does not move existing data or change the default library path.

## 2. Install and start the server

Install Python and make sure this command reports version 3.11 or later:

```powershell
python --version
```

Prepare the environment and start the server:

```powershell
.\setup.bat
.\launch_service.bat
```

The setup creates `.env/`, updates the Python packaging tools, and installs `requirements.txt`. The launcher starts a background server; its log is `data/logs/server.log`. Internet access is required to download dependencies during setup.

Open **[http://127.0.0.1:5001/](http://127.0.0.1:5001/)** on the PC. Confirm the library folder and read the newly generated connection key. Keep this key private.

Choose the library location before the first transfer. The default is `data/library/` inside the project. The administration page is available only on the PC; the gallery is in the Android app.

## 3. Allow the local connection

1. Find the PC’s private IPv4 address using `ipconfig`.
2. Allow the project’s Python server through Windows Firewall on the **Private** network profile, TCP port **5001**.
3. Connect the phone to a Wi-Fi network that can reach the PC.

The phone needs the PC’s LAN address, not `127.0.0.1`. Guest Wi-Fi or client isolation may prevent devices from reaching each other. Internet port forwarding is not required.

## 4. Build the Android app

If you already have a compatible APK, continue to [Install and connect the phone](#5-install-and-connect-the-phone).

### Install the build tools

1. Install [JDK 17](https://adoptium.net/temurin/releases/?version=17).
2. Install [Android Studio](https://developer.android.com/studio/install) to obtain the Android SDK tools.
3. Open Android Studio’s **SDK Manager** and install **Android SDK Platform 34**.
4. In **SDK Tools**, enable package details and install **Android SDK Build-Tools 33.0.1** and **Android SDK Platform-Tools**. Accept the SDK licenses.
5. Note the Android SDK location shown in SDK Manager and the JDK installation folder.

The build uses the included Gradle wrapper. A separate Gradle installation is unnecessary. JDK 17 and Build-Tools 33.0.1 match the project’s [Android Gradle Plugin requirements](https://developer.android.com/build/releases/agp-8-1-0-release-notes).

### Compile the APK

In PowerShell, from the project folder, enter the JDK and SDK paths when prompted. Point to installation folders, not their `bin` subfolders.

```powershell
$env:JAVA_HOME = Read-Host 'JDK 17 installation folder'
$env:ANDROID_HOME = Read-Host 'Android SDK folder'
$env:GRADLE_USER_HOME = Join-Path $PWD 'data\cache\gradle'
$env:ANDROID_USER_HOME = Join-Path $PWD 'data\cache\android'
$env:TEMP = Join-Path $PWD 'data\tmp'
$env:TMP = $env:TEMP
New-Item -ItemType Directory -Path $env:TEMP -Force | Out-Null
$env:JAVA_TOOL_OPTIONS = '-Djava.io.tmpdir="' + $env:TEMP + '"'

.\gradlew.bat --no-daemon --console=plain assembleDebug
```

The first build downloads Gradle and Android dependencies. Do not use `--offline` until these are cached.

The generated APK is **`app/build/outputs/apk/debug/app-debug.apk`**. This is a development build, signed with a local debug key. Keep that key if you want to install future updates without uninstalling the app.

## 5. Install and connect the phone

Copy the APK to the phone and open it to install. Android may ask you to allow installation from the app used to open the file.

Alternatively, enable USB debugging, connect and authorize the phone, then run in the same PowerShell session:

```powershell
& "$env:ANDROID_HOME\platform-tools\adb.exe" devices
& "$env:ANDROID_HOME\platform-tools\adb.exe" install -r app/build/outputs/apk/debug/app-debug.apk
```

The application ID is `com.fcsmobile.app`. Once installed:

1. Open **Réglages** (Settings).
2. Enter the PC’s private IPv4 address and the key from its administration page.
3. Save and test the connection.
4. Grant access to **all photos and videos**. Enable notifications to see backup progress outside the app.
5. Exclude any media folders you do not want to archive.
6. Connect the phone to local Wi-Fi and plug it into power.

Connection settings are intentionally excluded from Android system backups and device transfers. Configure the connection again when setting up a new phone.

The first backup archives accessible existing media. Automatic and manual transfers require connected power and a reachable local server. Android decides when background work starts; plugging in does not guarantee an immediate run. Battery protection may pause charging while the phone remains connected to power.

## 6. Confirm the installation

Check that the PC administration page opens, the phone connects, a photo or video transfers successfully, and the archived media opens in the gallery. Then leave the app in the background to confirm a normal automatic run.

Media-location permission controls whether Android exposes location metadata. Files private to other apps may be inaccessible. Video playback depends on the phone’s codecs.

## Starting, stopping, and updates

| Script | Purpose |
| --- | --- |
| `launch_service.bat` | Start the server |
| `stop.bat` | Stop the server |
| `install_service.bat` | Optionally start the server at Windows sign-in |
| `uninstall_service.bat` | Remove that startup task and stop the server; retain the library |

The PC and service must be running for backup and gallery access. Use one startup method per instance. An external process manager may run `.env/Scripts/python.exe -u backup_service.py` with the project folder as its working directory.

Before updating server files, stop the server. Preserve `data/`, rerun `setup.bat` if dependencies changed, and restart. For Android updates, wait until backup activity stops and install an APK with the same application ID and signing key.

## Import or relocate a library

To import existing media, stop the server and run:

```powershell
.\.env\Scripts\python.exe backup_service.py --import-folder 'path\to\existing-photos'
```

Replace the example path with a source folder separate from the library. Importing copies files without modifying the source, deduplicates identical content, and can be repeated. Review the summary before restarting the server.

To relocate the library, stop the server, move the complete library folder including `Originals` and `.fcs`, and update `backup_path` in `data/config/config.json`. Preserve the connection key. Relative library paths resolve from `data/`.

If you move the project itself, update startup paths and recreate the Python environment with `setup.bat` if its launchers no longer work. For the built-in startup task, rerun `install_service.bat`.

## Troubleshooting

| Problem | Check |
| --- | --- |
| `python` is not recognized | Install Python, make it available on PATH, and reopen PowerShell |
| Dependency installation fails | Read the terminal error, confirm the Python version and Internet access; Python 3.11 is the baseline for reproducing dependency issues |
| Server does not open | Inspect `data/logs/server.log` and check whether port 5001 is already in use |
| Storage error mentioning `C:` | Use another writable drive, or explicitly enable `FCS_MOBILE_ALLOW_C_DRIVE=1` before setup and server startup |
| Phone cannot connect | PC LAN address, key, server status, Private firewall rule, and Wi-Fi isolation |
| Build cannot find Java or the SDK | Check `JAVA_HOME`, `ANDROID_HOME`, the SDK packages, and accepted licenses |
| APK update is rejected | Match the existing application ID and signing key |
| Automatic backup waits | Check connected power, full media permissions, automatic backups, and Android background restrictions |
| Gallery is unavailable away from the LAN | This version supports local-network access only |

The application interface is in French. For tests and development practices, see [Contributing](CONTRIBUTING.md).
