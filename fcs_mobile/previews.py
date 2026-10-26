"""Derived previews; originals are never recompressed or edited."""
import subprocess
import tempfile
import os
from pathlib import Path
from PIL import Image, ImageOps
from pillow_heif import register_heif_opener
import imageio_ffmpeg

register_heif_opener()


def preview(library, row, large=False):
    variant = "preview" if large else "thumb"
    destination = library.internal / "previews" / f"{row['id']}-{variant}.jpg"
    if destination.is_file():
        return destination
    with library.preview_lock(row["id"]):
        if destination.is_file():
            return destination
        # Recheck after waiting: a concurrent purge may have removed the archive.
        row = library.get(row["id"], include_trash=True)
        with library.preview_slots:
            return _render(library, row, destination, large)


def _render(library, row, destination, large):
    original = library.path(row)
    if not original.is_file():
        raise LookupError("Original introuvable")
    temporary = None
    output = None
    try:
        edge = 2048 if large else 360
        if row["mime"].startswith("video/"):
            with tempfile.NamedTemporaryFile(suffix=".jpg", dir=library.internal / "incoming", delete=False) as tmp:
                temporary = Path(tmp.name)
            subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(), "-v", "error", "-nostdin", "-threads", "1", "-i", str(original), "-frames:v", "1", "-filter_threads", "1", "-vf", f"scale={edge}:{edge}:force_original_aspect_ratio=decrease", "-y", str(temporary)], check=True, timeout=45, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0), stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            image_path = temporary
        else:
            image_path = original
        with Image.open(image_path) as source:
            # JPEG decoders can reduce before allocating the full-resolution image.
            source.draft("RGB", (edge, edge))
            image = ImageOps.exif_transpose(source)
            image.thumbnail((edge, edge), Image.Resampling.LANCZOS)
            if image.mode != "RGB":
                image = image.convert("RGB")
            with tempfile.NamedTemporaryFile(suffix=".jpg", dir=library.internal / "previews", delete=False) as tmp:
                output = Path(tmp.name)
            image.save(output, "JPEG", quality=88)
            os.replace(output, destination)
            output = None
        return destination
    finally:
        if temporary:
            temporary.unlink(missing_ok=True)
        if output:
            output.unlink(missing_ok=True)


def warm_thumbnails(current_library, stop, logger):
    """One background generator; interactive requests retain generation capacity."""
    failed = set()
    while not stop.is_set():
        library = current_library()
        try:
            with library.db() as db:
                rows = db.execute("SELECT * FROM media WHERE purged_at IS NULL ORDER BY taken_at DESC").fetchall()
            for row in rows:
                if stop.is_set():
                    return
                key = (str(library.root), row["id"])
                if key in failed or (library.internal / "previews" / f"{row['id']}-thumb.jpg").is_file():
                    continue
                try:
                    preview(library, row)
                except LookupError:
                    pass
                except Exception:
                    failed.add(key)
                    logger.warning("Miniature automatique indisponible pour un média")
                if stop.wait(.02):
                    return
        except Exception:
            logger.exception("Préparation des miniatures interrompue")
        stop.wait(30)
