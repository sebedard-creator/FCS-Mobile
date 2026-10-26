"""Slow thumbnail work must not block cached images, uploads, or archive cleanup."""
import hashlib
import io
import tempfile
import threading
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import patch
from PIL import Image
from fcs_mobile.library import Library, TRASH_SECONDS
from fcs_mobile.paths import DATA_ROOT, prepare_process_storage
from fcs_mobile.previews import preview, _render

prepare_process_storage()


class PreviewTests(unittest.TestCase):
    def setUp(self):
        scratch = DATA_ROOT / "tests"
        scratch.mkdir(parents=True, exist_ok=True)
        self.scratch = tempfile.TemporaryDirectory(dir=scratch)
        self.addCleanup(self.scratch.cleanup)
        self.library = Library(Path(self.scratch.name) / "library")

    def photo(self, color="red", size=(80, 60), orientation=None):
        image = Image.new("RGB", size, color)
        output = io.BytesIO()
        exif = Image.Exif()
        if orientation:
            exif[274] = orientation
        image.save(output, "JPEG", exif=exif)
        content = output.getvalue()
        result = self.library.save(io.BytesIO(content), dict(name="test.jpg", mime="image/jpeg", size=len(content),
                                  sha256=hashlib.sha256(content).hexdigest(), taken_at=100))
        return self.library.get(result["id"]), content

    def test_cached_images_and_uploads_do_not_wait_for_a_slow_preview(self):
        slow, _ = self.photo()
        cached, _ = self.photo("blue")
        destination = preview(self.library, cached)
        entered, release = threading.Event(), threading.Event()

        def render(*args):
            entered.set()
            if not release.wait(5):
                raise TimeoutError("Test thumbnail release")
            return _render(*args)

        with patch("fcs_mobile.previews._render", side_effect=render), ThreadPoolExecutor(max_workers=3) as pool:
            pending = pool.submit(preview, self.library, slow)
            self.assertTrue(entered.wait(3))
            try:
                self.assertEqual(pool.submit(preview, self.library, cached).result(timeout=2), destination)
                uploaded, _ = pool.submit(self.photo, "green").result(timeout=2)
                self.assertTrue(self.library.path(uploaded).is_file())
            finally:
                release.set()
            self.assertTrue(pending.result(timeout=3).is_file())

    def test_concurrent_requests_generate_one_complete_thumbnail(self):
        row, _ = self.photo()
        with patch("fcs_mobile.previews._render", wraps=_render) as renderer, ThreadPoolExecutor(max_workers=8) as pool:
            paths = list(pool.map(lambda _: preview(self.library, row), range(8)))
        self.assertEqual(renderer.call_count, 1)
        self.assertEqual(len(set(paths)), 1)
        with Image.open(paths[0]) as thumbnail:
            thumbnail.verify()

    def test_purge_waits_for_render_and_leaves_no_derived_files(self):
        row, _ = self.photo()
        self.library.trash(row["id"])
        entered, release, purge_started = threading.Event(), threading.Event(), threading.Event()

        def render(*args):
            entered.set()
            if not release.wait(5):
                raise TimeoutError("Test thumbnail release")
            return _render(*args)

        def purge():
            purge_started.set()
            return self.library.purge_expired(int(time.time()) + TRASH_SECONDS)

        with patch("fcs_mobile.previews._render", side_effect=render), ThreadPoolExecutor(max_workers=2) as pool:
            rendering = pool.submit(preview, self.library, row)
            self.assertTrue(entered.wait(3))
            cleaning = pool.submit(purge)
            self.assertTrue(purge_started.wait(3))
            release.set()
            rendering.result(timeout=3)
            self.assertEqual(cleaning.result(timeout=3), 1)
        self.assertFalse(self.library.path(row).exists())
        self.assertFalse((self.library.internal / "previews" / f"{row['id']}-thumb.jpg").exists())

    def test_reduced_jpeg_decode_preserves_orientation_and_original(self):
        row, original = self.photo(size=(4000, 3000), orientation=6)
        destination = preview(self.library, row)
        with Image.open(destination) as thumbnail:
            self.assertEqual(thumbnail.size, (270, 360))
        self.assertEqual(self.library.path(row).read_bytes(), original)
