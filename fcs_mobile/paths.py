"""Project-local storage, with no fallback to the Windows system drive."""
import os
import tempfile
from pathlib import Path

DATA_ROOT = Path(__file__).resolve().parents[1] / "data"


def storage_path(value, relative_to=DATA_ROOT):
    path = Path(value).expanduser()
    if not path.is_absolute():
        path = Path(relative_to) / path
    path = path.resolve()
    if path.drive.casefold() == "c:":
        raise ValueError("Le stockage sur C: est interdit pour FCS Mobile. Choisir un dossier sur Y:.")
    return path


def prepare_process_storage():
    temporary = storage_path(DATA_ROOT / "tmp")
    temporary.mkdir(parents=True, exist_ok=True)
    os.environ["TEMP"] = os.environ["TMP"] = str(temporary)
    tempfile.tempdir = str(temporary)
