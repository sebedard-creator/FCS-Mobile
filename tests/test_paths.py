"""Validate local storage defaults and the explicit C: opt-in without writing to C:."""
import json
import os
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from fcs_mobile.library import Library
from fcs_mobile.paths import DATA_ROOT, prepare_process_storage, storage_path
from fcs_mobile.server import create_app


class StorageTests(unittest.TestCase):
    def setUp(self):
        test_root = DATA_ROOT / "tests"
        test_root.mkdir(parents=True, exist_ok=True)
        self.scratch = tempfile.TemporaryDirectory(dir=test_root)
        self.addCleanup(self.scratch.cleanup)
        self.root = Path(self.scratch.name)

    def test_default_data_stays_with_project_after_move(self):
        original = self.root / "original"
        with patch.dict(os.environ, {}, clear=True), patch("fcs_mobile.server.DATA_ROOT", original):
            app = create_app()
        self.assertEqual(app.extensions["library"].root, original / "library")
        self.assertEqual(Path(app.config["UPLOAD_TEMP_PATH"]), original / "tmp")
        config = json.loads((original / "config/config.json").read_text())
        self.assertEqual(config["backup_path"], "library")
        relocated = self.root / "relocated"
        shutil.copytree(original, relocated)
        restarted = create_app(relocated / "config")
        self.assertEqual(restarted.extensions["library"].root, relocated / "library")
        self.assertEqual(json.loads((relocated / "config/config.json").read_text())["api_key"], config["api_key"])

    @unittest.skipUnless(os.name == "nt", "Windows drive validation")
    @patch.dict(os.environ, {"FCS_MOBILE_ALLOW_C_DRIVE": "0"})
    def test_c_drive_is_rejected_before_writing(self):
        for operation in (storage_path, Library, create_app):
            with self.subTest(operation=operation.__name__), self.assertRaisesRegex(ValueError, "C:"):
                operation(Path("C:/FCSMobileForbiddenStorage"))
        config_dir = self.root / "config"
        config_dir.mkdir()
        config_file = config_dir / "config.json"
        config_file.write_text(json.dumps({"backup_path": "C:/FCSMobileForbiddenStorage", "api_key": "test"}))
        with self.assertRaisesRegex(ValueError, "C:"):
            create_app(config_dir)

    @unittest.skipUnless(os.name == "nt", "Windows drive validation")
    def test_c_drive_requires_exact_opt_in_without_writing(self):
        candidate = Path("C:/FCSMobileStorageValidationOnly")
        for value in ("", "0", "true", "yes"):
            with self.subTest(value=value), patch.dict(os.environ, {"FCS_MOBILE_ALLOW_C_DRIVE": value}):
                with self.assertRaisesRegex(ValueError, "C:"):
                    storage_path(candidate)
        with patch.dict(os.environ, {"FCS_MOBILE_ALLOW_C_DRIVE": "1"}):
            self.assertEqual(storage_path(candidate), candidate)

    def test_large_upload_spills_into_local_temp(self):
        app = create_app(self.root / "config")
        with app.test_request_context():
            stream = app.request_class({})._get_file_stream(2_000_000, "video/mp4", "test.mp4")
            try:
                stream.write(b"x" * 600_000)
                self.assertTrue(stream._rolled)
                self.assertEqual(Path(stream.name).parent, self.root / "tmp")
            finally:
                stream.close()

    def test_process_temp_has_no_system_drive_fallback(self):
        with patch("fcs_mobile.paths.DATA_ROOT", self.root), patch.dict(os.environ), patch.object(tempfile, "tempdir", None):
            prepare_process_storage()
            self.assertEqual(Path(tempfile.gettempdir()), self.root / "tmp")
            self.assertEqual(os.environ["TEMP"], str(self.root / "tmp"))
            self.assertEqual(os.environ["TMP"], str(self.root / "tmp"))
