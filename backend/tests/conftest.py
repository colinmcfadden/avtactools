"""Test-wide setup."""

import os

import pytest

# Settings the app reads from the environment. A developer's shell often has a
# few of these set from running the backend (a build service URL, DEM folder,
# a proxy name), and any of them changes what the app does: a TRUSTED_PROXY
# turns on the production start-up checks, a LIDAR_BUILDER_URL hides a warning
# the tests look for. Tests set what they need; they never inherit it.
APP_SETTINGS = (
    "ADMIN_SESSION_SECRET", "APP_ENV", "CORS_ORIGINS", "DATABASE_URL", "EMAIL_DELIVERY_MODE",
    "EMAIL_FROM", "FLY_APP_NAME", "FRONTEND_URL", "GOOGLE_CLIENT_ID", "JWT_SECRET_KEY",
    "NEW_ACCOUNT_NOTIFY_EMAIL", "RESEND_API_KEY", "SESSION_COOKIE_SECURE", "SUPER_ADMIN_EMAIL",
    "TRUSTED_PROXY", "LIDAR_BUILDER_TOKEN", "LIDAR_BUILDER_URL", "LIDAR_CACHE_DIR", "LIDAR_TILES_DIR",
)


@pytest.fixture(autouse=True)
def isolated_environment(monkeypatch):
    for name in APP_SETTINGS:
        monkeypatch.delenv(name, raising=False)
    for name in [n for n in os.environ if n.startswith("TERRAIN_")]:
        monkeypatch.delenv(name, raising=False)
