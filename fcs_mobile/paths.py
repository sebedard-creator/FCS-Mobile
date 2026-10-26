"""Project-local storage; C: requires explicit administrator opt-in."""
import os
import tempfile
from pathlib import Path

DATA_ROOT = Path(__file__).resolve().parents[1] / "data"


def storage_path(value, relative_to=DATA_ROOT):
    path = Path(value).expanduser()
    if not path.is_absolute():
        path = Path(relative_to) / path
    path = path.resolve()
    if path.drive.casefold() == "c:" and os.environ.get("FCS_MOBILE_ALLOW_C_DRIVE", "").strip() != "1":
        raise ValueError("Le stockage sur C: est interdit par défaut. Choisir un autre disque ou définir FCS_MOBILE_ALLOW_C_DRIVE=1.")
    return path


def prepare_process_storage():
    temporary = storage_path(DATA_ROOT / "tmp")
    temporary.mkdir(parents=True, exist_ok=True)
    os.environ["TEMP"] = os.environ["TMP"] = str(temporary)
    tempfile.tempdir = str(temporary)
