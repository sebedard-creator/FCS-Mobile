"""Exercise the actual WSGI server and HTTP streaming on a temporary loopback port."""
import hashlib
import json
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
import socket
from pathlib import Path
from waitress import create_server
from fcs_mobile.server import create_app
from backup_service import create_listener
from fcs_mobile.paths import prepare_process_storage

prepare_process_storage()


class HttpTests(unittest.TestCase):
    def test_listener_refuses_port_sharing(self):
        with create_listener('127.0.0.1', 0) as listener:
            port = listener.getsockname()[1]
            with self.assertRaises(OSError):
                create_listener('127.0.0.1', port)
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as competitor:
                competitor.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                with self.assertRaises(OSError):
                    competitor.bind(('127.0.0.1', port))

    def test_authenticated_upload_streaming_and_trash(self):
        test_root = Path(__file__).resolve().parents[1] / "data" / "tests"
        test_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="fcs-mobile-http-", dir=test_root) as scratch:
            root = Path(scratch).resolve()
            assert root.parent == test_root.resolve()
            app = create_app(root / "config", root / "library")
            key = json.loads((root / "config/config.json").read_text())["api_key"]
            listener = create_listener('127.0.0.1', 0)
            server = create_server(app, sockets=[listener], threads=2)
            thread = threading.Thread(target=server.run, daemon=True)
            thread.start()
            base = f"http://127.0.0.1:{server.effective_port}"

            def request(path, data=None, headers=None):
                req = urllib.request.Request(base + path, data=data, headers={"Authorization": "Bearer " + key, **(headers or {})})
                return urllib.request.urlopen(req, timeout=10)

            try:
                content = b"0123456789" * 200_000
                metadata = {"name": "video.mp4", "mime": "video/mp4", "size": len(content), "taken_at": 100,
                            "relative_path": "Movies/", "sha256": hashlib.sha256(content).hexdigest()}
                boundary = "FCSMobileHttpTestBoundary"
                body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"metadata\"\r\n\r\n".encode()
                        + json.dumps(metadata).encode() + f"\r\n--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"video.mp4\"\r\nContent-Type: video/mp4\r\n\r\n".encode()
                        + content + f"\r\n--{boundary}--\r\n".encode())
                with request("/api/upload", body, {"Content-Type": f"multipart/form-data; boundary={boundary}"}) as response:
                    uploaded = json.load(response)
                self.assertTrue(uploaded["verified"])
                with request(f"/api/media/{uploaded['id']}/original", headers={"Range": "bytes=123-456"}) as response:
                    self.assertEqual(response.status, 206)
                    self.assertEqual(response.read(), content[123:457])
                with request(f"/api/media/{uploaded['id']}/trash", b"{}", {"Content-Type": "application/json"}) as response:
                    self.assertTrue(json.load(response)["success"])
                with request("/api/media?trash=1") as response:
                    self.assertEqual(json.load(response)["total"], 1)
                with self.assertRaises(urllib.error.HTTPError) as denied:
                    urllib.request.urlopen(base + "/api/media", timeout=5)
                self.assertEqual(denied.exception.code, 401)
            finally:
                server.close()
                server.task_dispatcher.shutdown(timeout=5)
                thread.join(timeout=3)


if __name__ == "__main__":
    unittest.main()
