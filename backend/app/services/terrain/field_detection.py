"""Finding the open ground around a click: satellite imagery, the SAM model, and
the elevation the LZ card prints."""

import threading

import cv2
import mercantile
import numpy as np
import requests

_model = None
_model_lock = threading.Lock()

# One analysis at a time. gunicorn runs threads so map and 3D requests do not
# queue behind each other, but SAM must not run twice at once: its predictor
# keeps state between calls, and each run holds about a gigabyte.
_predict_lock = threading.Lock()


def get_model():
    """The SAM model, loaded on first use.

    It used to load when the route module was imported, which made building the
    app (or a test app) pull in torch and a gigabyte of weights. Now the first
    caller loads it and the rest wait for that, and the app preloads it in the
    background at start so no one waits on a request.
    """
    global _model
    with _model_lock:
        if _model is None:
            # Imported here: it brings in torch, which nothing else needs.
            from ultralytics import SAM

            # Load the SAM model (This downloads 'sam_b.pt' on first run)
            # 'sam_b.pt' is the Base model (good balance of speed/accuracy)
            _model = SAM('sam_b.pt')
        return _model


def preload_async():
    """Load the model in the background so the first analysis does not wait."""
    def load():
        try:
            get_model()
            print("[sam] model ready")
        except Exception as error:  # noqa: BLE001 — the first request will try again
            print(f"[sam] preload failed ({error}); the first analysis will retry")

    thread = threading.Thread(target=load, name="sam-preload", daemon=True)
    thread.start()
    return thread


def predict(image, x, y):
    """Run SAM on one positive point prompt, a pixel of ``image``."""
    model = get_model()
    # We prompt with the calculated [x, y].
    # imgsz=512: the source tile is only 256px, so the default 1024
    # inference size just upscales it 4x and runs the encoder at 1024^2 —
    # ~3.3GB of memory (OOM-kills a 2GB machine) for no added detail.
    with _predict_lock:
        return model.predict(image, points=[[x, y]], labels=[1], conf=0.4, imgsz=512)


def elevation_label(lat, lon):
    """Ground elevation in feet, as the LZ card prints it ("TBD" if unknown)."""
    try:
        # Use the same API you used in terrain_analysis
        elev_url = f"https://api.opentopodata.org/v1/srtm30m?locations={lat},{lon}"
        elev_res = requests.get(elev_url).json()

        # Extract the value (default to 0 if API fails)
        results = elev_res.get('results', [])
        elevation_meters = results[0].get('elevation') if results else 0

        # Convert to Feet for aviation (optional, but standard for LZ cards)
        elevation_feet = int(elevation_meters * 3.28084) if elevation_meters else 0
        return f"{elevation_feet}"

    except Exception as e:
        print(f"Elevation Fetch Error: {e}")
        return "TBD"


def fetch_satellite_tile(lat, lon, zoom=16):
    """
    Downloads the specific map tile for a lat/lon
    """
    # 1. Calculate which "Tile" contains this coordinate
    tile = mercantile.tile(lon, lat, zoom)
    
    # 2. Public Satellite Endpoint (ArcGIS World Imagery)
    # Note: In production, use your Mapbox URL for better quality
    url = f"https://clarity.maptiles.arcgis.com/arcgis/rest/services/World_Imagery/MapServer/tile/{zoom}/{tile.y}/{tile.x}"
    
    response = requests.get(url)
    if response.status_code == 200:
        # Convert bytes to an image OpenCV can read
        image = np.asarray(bytearray(response.content), dtype="uint8")
        image = cv2.imdecode(image, cv2.IMREAD_COLOR)
        return image, tile
    return None, None
