"""The API's client for the build service, against real HTTP.

The API and the build service deploy separately, so for a while one is always
newer than the other. Who is waiting on a build travels in headers so that a
service which predates them still finds the build: when it travelled in the
query string, an older service answered 404 and the 3D window gave up.
"""

import json
import os
import re
import shutil
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.services import lidar_client  # noqa: E402
from lidar.build import BuildRequest  # noqa: E402
from lidar.worker import Builder, make_handler  # noqa: E402

TOKEN = "client-test-token"
WATCHER = "tab-12345678"
TARGET = (34.596407, -84.128098, 250.0)
JOB = {"key": "0123456789abcdef", "state": "running", "stage": "processing points",
       "error": None}


class OlderService(BaseHTTPRequestHandler):
    """How the build service answered before watchers existed: the path had to
    be exactly /builds/<key>, and anything else was 404."""

    def log_message(self, *_args):
        return

    def do_GET(self):
        if re.fullmatch(r"/builds/[0-9a-f]{16}", self.path):
            body = json.dumps(JOB).encode()
            self.send_response(200)
        else:
            body = b'{"error": "not found"}'
            self.send_response(404)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def serve(handler):
    server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


class OlderServiceTests(unittest.TestCase):
    def setUp(self):
        self.server = serve(OlderService)
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        host, port = self.server.server_address
        env = patch.dict(os.environ, {"LIDAR_BUILDER_URL": f"http://{host}:{port}",
                                      "LIDAR_BUILDER_TOKEN": TOKEN})
        env.start()
        self.addCleanup(env.stop)

    def test_progress_is_still_found_by_a_service_that_predates_watchers(self):
        job = lidar_client.status(JOB["key"], watcher=WATCHER, keep=True)
        self.assertEqual(job, JOB)


class CurrentServiceTests(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(lambda: shutil.rmtree(self.root, ignore_errors=True))
        self.builder = Builder(
            self.root, make_request=lambda lat, lon, r: BuildRequest(lat=lat, lon=lon, radius_m=r),
            build=lambda *_a, **_k: None)
        self.server = serve(make_handler(self.builder, TOKEN))
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        host, port = self.server.server_address
        env = patch.dict(os.environ, {"LIDAR_BUILDER_URL": f"http://{host}:{port}",
                                      "LIDAR_BUILDER_TOKEN": TOKEN})
        env.start()
        self.addCleanup(env.stop)

    def test_leaving_drops_a_build_nobody_else_waits_for(self):
        job = lidar_client.submit(*TARGET, watcher=WATCHER)
        self.assertEqual(lidar_client.status(job["key"], watcher=WATCHER)["state"], "queued")
        self.assertEqual(lidar_client.release(job["key"], watcher=WATCHER)["state"],
                         "cancelled")

    def test_keeping_asked_for_while_polling_survives_leaving(self):
        """Saving the LZ while its point cloud builds."""
        job = lidar_client.submit(*TARGET, watcher=WATCHER)
        lidar_client.status(job["key"], watcher=WATCHER, keep=True)
        self.assertEqual(lidar_client.release(job["key"], watcher=WATCHER)["state"],
                         "queued")

    def test_an_unknown_build_is_none(self):
        self.assertIsNone(lidar_client.status("0" * 16, watcher=WATCHER))
        self.assertIsNone(lidar_client.release("0" * 16, watcher=WATCHER))


if __name__ == "__main__":
    unittest.main()
