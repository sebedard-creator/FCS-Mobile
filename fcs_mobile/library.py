"""Content-addressed archive. Phone inventories never remove media."""
import hashlib
import mimetypes
import os
import re
import sqlite3
import tempfile
import threading
import time
import uuid
from contextlib import contextmanager
from pathlib import Path
from .paths import storage_path

HASH = re.compile(r"^[0-9a-f]{64}$")
MEDIA_EXTENSIONS = {".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".heic", ".heif", ".avif", ".dng", ".mp4", ".mov", ".mkv", ".avi", ".webm", ".m4v", ".3gp", ".flv", ".wmv"}
TRASH_SECONDS = 30 * 24 * 60 * 60


class Library:
    def __init__(self, root):
        self.root = storage_path(root)
        self.internal = storage_path(self.root / ".fcs")
        self.originals = storage_path(self.root / "Originals")
        for path in (self.internal, self.originals, self.internal / "incoming", self.internal / "previews"):
            storage_path(path).mkdir(parents=True, exist_ok=True)
        self.lock = threading.RLock()
        self.preview_locks = [threading.Lock() for _ in range(64)]
        self.preview_slots = threading.BoundedSemaphore(2)
        with self.db() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS media (
                    id TEXT PRIMARY KEY, digest TEXT NOT NULL UNIQUE,
                    name TEXT NOT NULL, mime TEXT NOT NULL, size INTEGER NOT NULL,
                    taken_at INTEGER NOT NULL, uploaded_at INTEGER NOT NULL,
                    source_path TEXT NOT NULL, stored_path TEXT NOT NULL,
                    deleted_at INTEGER, purged_at INTEGER
                );
                CREATE INDEX IF NOT EXISTS media_timeline ON media(deleted_at, taken_at DESC, id);
            """)

    @contextmanager
    def db(self):
        db = sqlite3.connect(self.internal / "library.sqlite3", timeout=60)
        db.row_factory = sqlite3.Row
        try:
            with db:
                yield db
        finally:
            db.close()

    @staticmethod
    def state(row):
        if row is None:
            return "missing"
        if row["purged_at"] is not None:
            return "deleted"
        return "trash" if row["deleted_at"] is not None else "saved"

    def preview_lock(self, media_id):
        return self.preview_locks[hash(media_id) % len(self.preview_locks)]

    @staticmethod
    def public(row):
        result = {key: row[key] for key in ("id", "name", "mime", "size", "taken_at", "uploaded_at", "source_path", "deleted_at")}
        result["state"] = Library.state(row)
        result["expires_at"] = row["deleted_at"] + TRASH_SECONDS if row["deleted_at"] is not None else None
        return result

    def path(self, row):
        path = (self.root / row["stored_path"]).resolve()
        if not path.is_relative_to(self.originals.resolve()):
            raise ValueError("Chemin de stockage invalide")
        return path

    def states(self, digests):
        result = {}
        with self.db() as db:
            for digest in digests:
                if not isinstance(digest, str) or not HASH.fullmatch(digest):
                    raise ValueError("Empreinte SHA-256 invalide")
                row = db.execute("SELECT * FROM media WHERE digest=?", (digest,)).fetchone()
                state = self.state(row)
                if state == "saved" and not self.path(row).is_file():
                    state = "missing"
                result[digest] = {"state": state, "id": row["id"] if row else None}
        return result

    def get(self, media_id, include_trash=False):
        with self.db() as db:
            row = db.execute("SELECT * FROM media WHERE id=? AND purged_at IS NULL", (media_id,)).fetchone()
        if row is None or (row["deleted_at"] is not None and not include_trash):
            raise LookupError("Média introuvable")
        return row

    def listing(self, trash=False, offset=0, limit=120):
        condition = "deleted_at IS NOT NULL" if trash else "deleted_at IS NULL"
        order = "deleted_at DESC, id" if trash else "taken_at DESC, id"
        with self.db() as db:
            total = db.execute(f"SELECT count(*) FROM media WHERE {condition} AND purged_at IS NULL").fetchone()[0]
            rows = db.execute(f"SELECT * FROM media WHERE {condition} AND purged_at IS NULL ORDER BY {order} LIMIT ? OFFSET ?", (limit, offset)).fetchall()
        return {"items": [self.public(row) for row in rows], "total": total, "offset": offset, "limit": limit}

    def neighbors(self, media_id, trash=False):
        condition = "deleted_at IS NOT NULL" if trash else "deleted_at IS NULL"
        date = "deleted_at" if trash else "taken_at"
        # ID breaks date ties as in listing(), without offsets shifted by backups.
        with self.lock, self.db() as db:
            row = db.execute(f"SELECT * FROM media WHERE id=? AND {condition} AND purged_at IS NULL", (media_id,)).fetchone()
            if row is None:
                raise LookupError("Média introuvable dans cette galerie")
            result = {}
            for direction, comparison, id_comparison, order in (
                ("previous", ">", "<", f"{date} ASC, id DESC"),
                ("next", "<", ">", f"{date} DESC, id ASC"),
            ):
                neighbor = db.execute(
                    f"SELECT * FROM media WHERE {condition} AND purged_at IS NULL "
                    f"AND ({date}{comparison}? OR ({date}=? AND id{id_comparison}?)) "
                    f"ORDER BY {order} LIMIT 1", (row[date], row[date], media_id)
                ).fetchone()
                result[direction] = self.public(neighbor) if neighbor else None
        return result

    def save(self, stream, metadata):
        expected = metadata.get("sha256", "")
        if not isinstance(expected, str) or not HASH.fullmatch(expected):
            raise ValueError("Empreinte SHA-256 requise")
        name = str(metadata.get("name", "media")).replace("\\", "/").rsplit("/", 1)[-1][:240]
        suffix = Path(name).suffix.lower()
        mime = str(metadata.get("mime", ""))
        if not re.fullmatch(r"(?:image|video)/[a-zA-Z0-9.+-]+", mime):
            raise ValueError("Format photo ou vidéo non pris en charge")
        # Extension is cosmetic; it never determines the path or excludes a MediaStore format.
        if not re.fullmatch(r"\.[a-z0-9]{1,12}", suffix):
            suffix = ".media"
        size = int(metadata.get("size", -1))
        taken_at = int(metadata.get("taken_at", int(time.time())))
        if size <= 0 or taken_at < 0 or taken_at > int(time.time()) + 86400:
            raise ValueError("Taille ou date invalide")
        source_path = str(metadata.get("relative_path", ""))[:2000]
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(dir=self.internal / "incoming", delete=False) as target:
                temporary = Path(target.name)
                digest = hashlib.sha256()
                received = 0
                while chunk := stream.read(1024 * 1024):
                    received += len(chunk)
                    if received > size:
                        raise ValueError("Le transfert dépasse la taille annoncée")
                    digest.update(chunk)
                    target.write(chunk)
                target.flush()
                os.fsync(target.fileno())
            if received != size or digest.hexdigest() != expected:
                raise ValueError("Transfert incomplet ou empreinte différente")
            with self.lock, self.db() as db:
                row = db.execute("SELECT * FROM media WHERE digest=?", (expected,)).fetchone()
                state = self.state(row)
                if state in {"trash", "deleted"}:
                    return {"state": state, "id": row["id"], "sha256": expected, "verified": False}
                if row is not None and self.path(row).is_file():
                    return {"state": "saved", "id": row["id"], "sha256": expected, "size": size, "verified": True}
                media_id = row["id"] if row else uuid.uuid4().hex
                relative = row["stored_path"] if row else f"Originals/{expected[:2]}/{expected}{suffix}"
                destination = (self.root / relative).resolve()
                if not destination.is_relative_to(self.originals.resolve()):
                    raise ValueError("Chemin de stockage invalide")
                destination.parent.mkdir(parents=True, exist_ok=True)
                os.replace(temporary, destination)
                temporary = None
                if row is None:
                    db.execute("INSERT INTO media VALUES(?,?,?,?,?,?,?,?,?,NULL,NULL)", (media_id, expected, name, mime, size, taken_at, int(time.time()), source_path, relative))
                return {"state": "saved", "id": media_id, "sha256": expected, "size": size, "verified": True}
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)

    def trash(self, media_id):
        with self.lock, self.db() as db:
            row = db.execute("SELECT * FROM media WHERE id=? AND purged_at IS NULL", (media_id,)).fetchone()
            if row is None:
                raise LookupError("Média introuvable")
            db.execute("UPDATE media SET deleted_at=COALESCE(deleted_at, ?) WHERE id=?", (int(time.time()), media_id))

    def restore(self, media_id):
        with self.lock, self.db() as db:
            row = db.execute("SELECT * FROM media WHERE id=? AND purged_at IS NULL", (media_id,)).fetchone()
            if row is None or not self.path(row).is_file():
                raise LookupError("Original introuvable")
            if row["deleted_at"] is not None and row["deleted_at"] <= int(time.time()) - TRASH_SECONDS:
                raise ValueError("Le délai de restauration de 30 jours est dépassé")
            db.execute("UPDATE media SET deleted_at=NULL WHERE id=?", (media_id,))

    def purge_expired(self, now=None):
        now = int(time.time()) if now is None else int(now)
        with self.lock, self.db() as db:
            rows = db.execute("SELECT * FROM media WHERE deleted_at<=? AND purged_at IS NULL", (now - TRASH_SECONDS,)).fetchall()
            for row in rows:
                with self.preview_lock(row["id"]):
                    self.path(row).unlink(missing_ok=True)
                    for variant in ("thumb", "preview"):
                        (self.internal / "previews" / f"{row['id']}-{variant}.jpg").unlink(missing_ok=True)
                    db.execute("UPDATE media SET purged_at=? WHERE id=?", (now, row["id"]))
        return len(rows)

    def stats(self):
        with self.db() as db:
            active = db.execute("SELECT count(*), COALESCE(sum(size),0) FROM media WHERE deleted_at IS NULL AND purged_at IS NULL").fetchone()
            trash = db.execute("SELECT count(*) FROM media WHERE deleted_at IS NOT NULL AND purged_at IS NULL").fetchone()[0]
        return {"media_count": active[0], "bytes": active[1], "trash_count": trash}

    def import_folder(self, folder):
        source = Path(folder).expanduser().resolve(strict=True)
        if not source.is_dir() or source == self.root or source.is_relative_to(self.root) or self.root.is_relative_to(source):
            raise ValueError("Choisir un dossier source distinct de la bibliothèque")
        imported, errors = 0, []
        for path in source.rglob("*"):
            if not path.is_file() or path.suffix.lower() not in MEDIA_EXTENSIONS:
                continue
            try:
                with path.open("rb") as stream:
                    digest = hashlib.file_digest(stream, "sha256").hexdigest()
                    stream.seek(0)
                    mime = mimetypes.guess_type(path.name)[0] or ("video/mp4" if path.suffix.lower() in {".mkv", ".m4v"} else "image/jpeg")
                    result = self.save(stream, {"sha256": digest, "name": path.name, "mime": mime, "size": path.stat().st_size, "taken_at": int(path.stat().st_mtime), "relative_path": str(path.parent.relative_to(source))})
                    imported += result["state"] == "saved"
            except Exception as exc:
                errors.append({"file": str(path.relative_to(source)), "error": str(exc)})
        return {"indexed": imported, "errors": errors}
