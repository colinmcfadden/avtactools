"""Where the app's bundled files live, whatever directory the process starts in."""

from pathlib import Path

APP_DIR = Path(__file__).resolve().parent
BACKEND_DIR = APP_DIR.parent
ASSETS_DIR = APP_DIR / "assets"

# Excel LZ card, filled in by /api/generate-excel.
LZ_CARD_TEMPLATE = ASSETS_DIR / "lz_template.xlsx"
# Cleaned AMPS threat database; exports copy it so the exact schema survives.
THREAT_TEMPLATE = ASSETS_DIR / "threat_template.ths"
