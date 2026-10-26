import hashlib
import io
import json
import tempfile
import time
import subprocess
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from PIL import Image
import imageio_ffmpeg
from fcs_mobile.library import TRASH_SECONDS
from fcs_mobile.server import create_app
from fcs_mobile.paths import prepare_process_storage

prepare_process_storage()


class LibraryTests(unittest.TestCase):
    def setUp(self):
        test_root = Path(__file__).resolve().parents[1] / "data" / "tests"
        test_root.mkdir(parents=True, exist_ok=True)
        self.scratch = tempfile.TemporaryDirectory(prefix="fcs-mobile-tests-", dir=test_root)
        self.root = Path(self.scratch.name).resolve()
        assert self.root.parent == test_root.resolve()
        self.app = create_app(self.root / "config", self.root / "library")
        self.app.testing = True
        self.library = self.app.extensions["library"]
        self.client = self.app.test_client()
        config = json.loads((self.root / "config/config.json").read_text())
        self.headers = {"Authorization": "Bearer " + config["api_key"]}

    def tearDown(self):
        self.scratch.cleanup()

    def metadata(self, content=b"photo", name="photo.jpg", **extras):
        return {"name": name, "mime": "image/jpeg", "sha256": hashlib.sha256(content).hexdigest(),
                "size": len(content), "taken_at": int(time.time()) - 60, "relative_path": "DCIM/Camera/", **extras}

    def upload(self, content=b"photo", name="photo.jpg", **extras):
        data = {"metadata": json.dumps(self.metadata(content, name, **extras)), "file": (io.BytesIO(content), name)}
        return self.client.post("/api/upload", data=data, headers=self.headers)

    def check(self, content):
        digest = hashlib.sha256(content).hexdigest()
        response = self.client.post("/api/check", json={"sha256": [digest]}, headers=self.headers)
        return response.json["files"][digest]

    def test_unauthenticated_and_public_access_rejected(self):
        self.assertEqual(self.client.get("/api/media").status_code, 401)
        self.assertEqual(self.client.get("/api/media", headers=self.headers, environ_overrides={"REMOTE_ADDR": "8.8.8.8"}).status_code, 403)
        self.assertEqual(self.client.get("/", environ_overrides={"REMOTE_ADDR": "192.168.1.20"}).status_code, 403)
        self.assertEqual(self.client.get("/api/status", headers=self.headers, environ_overrides={"REMOTE_ADDR": "192.168.1.20"}).status_code, 200)

    def test_admin_requires_csrf_even_on_loopback(self):
        response = self.client.post("/admin/config", json={"backup_path": str(self.root / "other")})
        self.assertEqual(response.status_code, 403)
        self.assertEqual(self.client.get("/", headers={"Host": "example.com"}).status_code, 403)

    def test_admin_page_and_health(self):
        self.assertIn(b"FCS Mobile", self.client.get("/").data)
        self.assertEqual(self.client.get("/health").json["protocol"], 2)

    def test_verified_upload_and_original(self):
        response = self.upload()
        self.assertEqual(response.status_code, 200)
        self.assertTrue(response.json["verified"])
        original = self.client.get(f"/api/media/{response.json['id']}/original", headers=self.headers)
        self.assertEqual(original.data, b"photo")
        self.assertEqual(original.headers["Cache-Control"], "no-store")
        original.close()

    def test_same_size_different_content_is_preserved(self):
        first, second = self.upload(b"AAAA"), self.upload(b"BBBB")
        self.assertNotEqual(first.json["id"], second.json["id"])
        self.assertEqual(self.library.stats()["media_count"], 2)

    def test_identical_content_different_names_deduplicated(self):
        first, second = self.upload(name="a.jpg"), self.upload(name="b.jpg")
        self.assertEqual(first.json["id"], second.json["id"])
        self.assertEqual(self.library.stats()["media_count"], 1)

    def test_unicode_names_and_source_paths_cannot_escape_storage(self):
        result = self.upload(name="../été 📷.jpg", relative_path="../../outside").json
        row = self.library.get(result["id"])
        self.assertEqual(row["name"], "été 📷.jpg")
        self.assertTrue(self.library.path(row).is_relative_to(self.library.originals))
        self.assertFalse((self.root / "outside").exists())

    def test_corrupt_or_partial_upload_leaves_no_media(self):
        for extras in ({"sha256": "0" * 64}, {"size": 7}, {"size": 1}):
            self.assertEqual(self.upload(**extras).status_code, 400)
        self.assertEqual(self.library.stats()["media_count"], 0)
        self.assertEqual(list((self.library.internal / "incoming").iterdir()), [])

    def test_check_never_quarantines_absent_files(self):
        self.upload()
        response = self.client.post("/api/check", json={"sha256": []}, headers=self.headers)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(self.library.stats()["media_count"], 1)
        self.assertEqual(self.library.stats()["trash_count"], 0)

    def test_invalid_inventory_is_rejected_without_mutation(self):
        self.upload()
        for body in ({}, {"sha256": ["bad"]}, {"sha256": "bad"}, {"sha256": ["0" * 64] * 501}):
            self.assertEqual(self.client.post("/api/check", json=body, headers=self.headers).status_code, 400)
        self.assertEqual(self.library.stats()["media_count"], 1)

    def test_missing_original_can_be_repaired(self):
        response = self.upload()
        self.library.path(self.library.get(response.json["id"])).unlink()
        self.assertEqual(self.check(b"photo")["state"], "missing")
        repaired = self.upload()
        self.assertEqual(repaired.json["id"], response.json["id"])
        self.assertTrue(repaired.json["verified"])
        self.assertEqual(self.check(b"photo")["state"], "saved")

    def test_trash_restore_and_idempotent_delete(self):
        media_id = self.upload().json["id"]
        self.client.post(f"/api/media/{media_id}/trash", headers=self.headers)
        first_deleted = self.library.get(media_id, True)["deleted_at"]
        self.client.post(f"/api/media/{media_id}/trash", headers=self.headers)
        self.assertEqual(self.library.get(media_id, True)["deleted_at"], first_deleted)
        self.assertEqual(self.library.stats()["media_count"], 0)
        trash = self.client.get("/api/media?trash=1", headers=self.headers).json
        self.assertEqual(trash["items"][0]["expires_at"], first_deleted + TRASH_SECONDS)
        self.assertEqual(self.client.post(f"/api/media/{media_id}/restore", headers=self.headers).status_code, 200)
        self.assertEqual(self.check(b"photo")["state"], "saved")

    def test_no_reimport_after_manual_deletion(self):
        media_id = self.upload().json["id"]
        self.library.trash(media_id)
        self.assertEqual(self.check(b"photo")["state"], "trash")
        self.assertEqual(self.upload().json["state"], "trash")
        self.assertEqual(self.library.stats()["media_count"], 0)

    def test_purge_exactly_30_days_preserves_tombstone(self):
        media_id = self.upload().json["id"]
        self.library.trash(media_id)
        row = self.library.get(media_id, True)
        original = self.library.path(row)
        self.assertEqual(self.library.purge_expired(row["deleted_at"] + TRASH_SECONDS - 1), 0)
        self.assertTrue(original.exists())
        self.assertEqual(self.library.purge_expired(row["deleted_at"] + TRASH_SECONDS), 1)
        self.assertFalse(original.exists())
        self.assertEqual(self.check(b"photo")["state"], "deleted")
        self.assertEqual(self.upload().json["state"], "deleted")
        self.assertEqual(self.client.post(f"/api/media/{media_id}/restore", headers=self.headers).status_code, 404)

    def test_expired_trash_cannot_be_restored(self):
        media_id = self.upload().json["id"]
        with self.library.db() as db:
            db.execute("UPDATE media SET deleted_at=? WHERE id=?", (int(time.time()) - TRASH_SECONDS, media_id))
        self.assertEqual(self.client.post(f"/api/media/{media_id}/restore", headers=self.headers).status_code, 400)

    def test_original_range_requests_for_video(self):
        media_id = self.upload(b"0123456789", "video.mp4", mime="video/mp4").json["id"]
        response = self.client.get(f"/api/media/{media_id}/original", headers={**self.headers, "Range": "bytes=2-5"})
        self.assertEqual(response.status_code, 206)
        self.assertEqual(response.data, b"2345")
        response.close()

    def test_thumbnails_preserve_original_and_orientation(self):
        data = io.BytesIO()
        image = Image.new("RGB", (800, 400), "red")
        exif = image.getexif()
        exif[274] = 6
        image.save(data, "JPEG", exif=exif)
        content = data.getvalue()
        media_id = self.upload(content).json["id"]
        response = self.client.get(f"/api/media/{media_id}/thumbnail", headers=self.headers)
        self.assertEqual(response.status_code, 200)
        with Image.open(io.BytesIO(response.data)) as thumbnail:
            self.assertEqual(thumbnail.size, (180, 360))
        response.close()
        self.assertEqual(self.library.path(self.library.get(media_id)).read_bytes(), content)

    def test_pagination(self):
        self.upload(b"one", taken_at=100)
        self.upload(b"two", taken_at=200)
        page = self.client.get("/api/media?limit=1&offset=0", headers=self.headers).json
        self.assertEqual(page["total"], 2)
        self.assertEqual(page["items"][0]["taken_at"], 200)
        self.assertEqual(self.client.get("/api/media?limit=0", headers=self.headers).status_code, 400)

    def test_neighbors_follow_listing_with_equal_dates_and_boundaries(self):
        for index, timestamp in enumerate([100, 200, 200, 200, 300]):
            self.upload(str(index).encode(), taken_at=timestamp)
        items = self.library.listing()["items"]
        for index, item in enumerate(items):
            response = self.client.get(f"/api/media/{item['id']}/neighbors", headers=self.headers)
            self.assertEqual(response.status_code, 200)
            self.assertEqual(response.json["previous"], items[index - 1] if index else None)
            self.assertEqual(response.json["next"], items[index + 1] if index + 1 < len(items) else None)
            self.assertEqual(response.headers["Cache-Control"], "no-store")

    def test_neighbors_continue_beyond_first_gallery_page(self):
        # Fixtures use verified uploads, including a video at the page boundary.
        for index in range(123):
            self.upload(str(index).encode(), taken_at=index + 100, **({"mime": "video/mp4"} if index == 102 else {}))
        items = self.library.listing(limit=200)["items"]
        neighbor = self.library.neighbors(items[119]["id"])["next"]
        self.assertEqual(neighbor, items[120])
        self.assertEqual(self.library.neighbors(neighbor["id"])["previous"], items[119])
        self.assertEqual(self.library.neighbors(items[19]["id"])["next"]["mime"], "video/mp4")

    def test_neighbors_use_trash_order_and_exclude_other_states(self):
        ids = [self.upload(str(index).encode(), taken_at=500 - index).json["id"] for index in range(5)]
        for media_id in ids[:4]:
            self.library.trash(media_id)
        with self.library.db() as db:
            for media_id, timestamp in zip(ids[:4], [100, 300, 200, 200]):
                db.execute("UPDATE media SET deleted_at=? WHERE id=?", (timestamp, media_id))
            db.execute("UPDATE media SET purged_at=400 WHERE id=?", (ids[0],))
        items = self.library.listing(trash=True)["items"]
        for index, item in enumerate(items):
            response = self.client.get(f"/api/media/{item['id']}/neighbors?trash=1", headers=self.headers)
            self.assertEqual(response.json["previous"], items[index - 1] if index else None)
            self.assertEqual(response.json["next"], items[index + 1] if index + 1 < len(items) else None)
        self.assertEqual(self.library.neighbors(ids[4]), {"previous": None, "next": None})
        for media_id, suffix in [(ids[0], "?trash=1"), (ids[1], ""), (ids[4], "?trash=1"), ("missing", "")]:
            self.assertEqual(self.client.get(f"/api/media/{media_id}/neighbors{suffix}", headers=self.headers).status_code, 404)

    def test_neighbors_stay_anchored_when_library_changes(self):
        ids = [self.upload(str(index).encode(), taken_at=100 + index * 100).json["id"] for index in range(3)]
        self.upload(b"newest", taken_at=400)
        self.library.trash(ids[0])
        self.assertEqual(self.library.neighbors(ids[1])["previous"]["id"], ids[2])
        self.assertIsNone(self.library.neighbors(ids[1])["next"])
        self.library.restore(ids[0])
        self.assertEqual(self.library.neighbors(ids[1])["next"]["id"], ids[0])

    def test_neighbors_require_authentication_and_local_network(self):
        media_id = self.upload().json["id"]
        route = f"/api/media/{media_id}/neighbors"
        self.assertEqual(self.client.get(route).status_code, 401)
        self.assertEqual(self.client.get(route, headers=self.headers, environ_overrides={"REMOTE_ADDR": "8.8.8.8"}).status_code, 403)

    def test_heif_preview(self):
        data = io.BytesIO()
        Image.new("RGB", (60, 40), "green").save(data, "HEIF")
        response = self.upload(data.getvalue(), "photo.heic", mime="image/heic")
        self.assertEqual(response.status_code, 200)
        thumb = self.client.get(f"/api/media/{response.json['id']}/thumbnail", headers=self.headers)
        self.assertEqual(thumb.status_code, 200)
        with Image.open(io.BytesIO(thumb.data)) as image:
            self.assertEqual(image.size, (60, 40))
        thumb.close()

    def test_video_preview(self):
        path = self.root / "video.mp4"
        subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(), "-v", "error", "-f", "lavfi", "-i", "color=c=blue:s=160x90:d=0.1", "-c:v", "libx264", "-y", str(path)], check=True, capture_output=True, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        response = self.upload(path.read_bytes(), "video.mp4", mime="video/mp4")
        thumb = self.client.get(f"/api/media/{response.json['id']}/thumbnail", headers=self.headers)
        self.assertEqual(thumb.status_code, 200)
        with Image.open(io.BytesIO(thumb.data)) as image:
            self.assertLessEqual(max(image.size), 360)
        thumb.close()

    def test_unlisted_media_extension_does_not_exclude_phone_media(self):
        self.assertEqual(self.upload(b"bytes", "photo.jfif", mime="image/jpeg").status_code, 200)

    def test_library_survives_restart(self):
        media_id = self.upload().json["id"]
        restarted = create_app(self.root / "config")
        self.assertEqual(restarted.extensions["library"].get(media_id)["name"], "photo.jpg")

    def test_import_preserves_source_and_is_repeatable(self):
        source = self.root / "source"
        source.mkdir()
        photo = source / "été.jpg"
        photo.write_bytes(b"archive")
        self.assertEqual(self.library.import_folder(source)["indexed"], 1)
        self.library.import_folder(source)
        self.assertEqual(self.library.stats()["media_count"], 1)
        self.assertEqual(photo.read_bytes(), b"archive")
        with self.assertRaises(ValueError):
            self.library.import_folder(self.library.root)

    def test_concurrent_duplicate_uploads(self):
        def save(_):
            return self.library.save(io.BytesIO(b"photo"), self.metadata())
        with ThreadPoolExecutor(max_workers=4) as pool:
            results = list(pool.map(save, range(8)))
        self.assertEqual(len({result["id"] for result in results}), 1)
        self.assertEqual(self.library.stats()["media_count"], 1)


if __name__ == "__main__":
    unittest.main()
