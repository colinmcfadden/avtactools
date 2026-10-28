"""What every backend test runs under."""

import pytest


@pytest.fixture(autouse=True)
def _no_lidar_from_aws(monkeypatch):
    """No test reads the USGS LiDAR from AWS. app.py's load_dotenv() may bring a development .env's LIDAR_STREAM_CACHE_DIR, under which
    a Measure given no LiDAR source of its own (survey/obstacles.py's default) would fetch real pieces for the test's real Ellijay
    coordinates. A test that streams makes its own Streamer, or patches ``lidar.stream.streamer``."""
    monkeypatch.delenv("LIDAR_STREAM_CACHE_DIR", raising=False)
