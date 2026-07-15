from flask import Blueprint, request, jsonify
import numpy as np
import mercantile
import requests
import cv2
from ultralytics import SAM
import math
import traceback
from concurrent.futures import ThreadPoolExecutor

terrain_bp = Blueprint('terrain', __name__)

# Load the SAM model (This downloads 'sam_b.pt' on first run)
# 'sam_b.pt' is the Base model (good balance of speed/accuracy)
model = SAM('sam_b.pt')

# --- HELPER FUNCTIONS ---

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

def find_field_contour(image):
    """
    Uses Image Segmentation to find the large open area
    """
    # 1. Convert to Grayscale
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    
    # 2. Blur it slightly to remove "noise" (grass texture)
    blurred = cv2.GaussianBlur(gray, (5, 5), 0)
    
    # 3. Thresholding (The "Magic" part)
    # We assume fields are lighter/smoother than dense dark forests.
    # This separates the image into Black (Trees) and White (Fields).
    # You might need to tune these numbers (30, 255) based on your terrain.
    _, thresh = cv2.threshold(blurred, 100, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    
    # 4. Find Contours (Shapes)
    contours, _ = cv2.findContours(thresh, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    
    # 5. Find the largest shape (assuming the field is the biggest thing in the tile)
    if contours:
        largest_contour = max(contours, key=cv2.contourArea)
        
        # Simplify the shape (make it look like a tactical polygon, not a jagged mess)
        epsilon = 0.02 * cv2.arcLength(largest_contour, True)
        approx = cv2.approxPolyDP(largest_contour, epsilon, True)
        return approx
    return None

# --- ROUTES ---

@terrain_bp.route('/api/analyze-field', methods=['POST'])
def analyze_field():
    data = request.json
    try:
        lat = float(data.get('lat'))
        lon = float(data.get('lon'))
    except:
        return jsonify({"status": "error", "message": "Invalid Coords"}), 400
    
    try:
        # Use the same API you used in terrain_analysis
        elev_url = f"https://api.opentopodata.org/v1/srtm30m?locations={lat},{lon}"
        elev_res = requests.get(elev_url).json()
        
        # Extract the value (default to 0 if API fails)
        results = elev_res.get('results', [])
        elevation_meters = results[0].get('elevation') if results else 0
        
        # Convert to Feet for aviation (optional, but standard for LZ cards)
        elevation_feet = int(elevation_meters * 3.28084) if elevation_meters else 0
        elevation_str = f"{elevation_feet}"
        
    except Exception as e:
        print(f"Elevation Fetch Error: {e}")
        elevation_str = "TBD"

    # CHANGE 1: Zoom out to 15 or 16 to see the whole field context
    zoom_level = 14
    
    # 1. Get the Image
    image, tile = fetch_satellite_tile(lat, lon, zoom=zoom_level)
    if image is None:
        return jsonify({"status": "error", "message": "Map data unavailable"}), 500

    # 2. CALCULATE EXACT PIXEL (The Fix)
    # We map the lat/lon to the specific 0-256 pixel on the tile
    bounds = mercantile.bounds(tile)
    
    # Calculate percentage relative to tile bounds
    # (lon - West) / Width
    x_pct = (lon - bounds.west) / (bounds.east - bounds.west)
    # (North - lat) / Height (Note: Latitude grows upwards, pixels grow downwards)
    y_pct = (bounds.north - lat) / (bounds.north - bounds.south)
    
    # Convert to pixel coordinates (256x256 image)
    prompt_x = int(x_pct * 256)
    prompt_y = int(y_pct * 256)
    
    print(f"DEBUG: Prompting AI at pixel [{prompt_x}, {prompt_y}]")

    # 3. Run SAM AI with the specific point
    try:
        # We prompt with the calculated [prompt_x, prompt_y].
        # imgsz=512: the source tile is only 256px, so the default 1024
        # inference size just upscales it 4x and runs the encoder at 1024^2 —
        # ~3.3GB of memory (OOM-kills a 2GB machine) for no added detail.
        results = model.predict(image, points=[[prompt_x, prompt_y]], labels=[1], conf=0.4, imgsz=512)
        
        if results[0].masks is not None:
            # Get the mask with the highest score (usually the first one)
            # Sometimes SAM returns multiple options (small, medium, large). 
            # We sort by area to prefer the main field, or just take the first.
            best_mask = results[0].masks.xy[0]
            
            # 4. Convert Resulting Pixels back to Lat/Lon
            lz_polygon = []
            for point in best_mask:
                px, py = float(point[0]), float(point[1])
                
                p_lon = bounds.west + (px / 256) * (bounds.east - bounds.west)
                p_lat = bounds.north - (py / 256) * (bounds.north - bounds.south)
                
                lz_polygon.append([p_lat, p_lon])

            return jsonify({
                "status": "success",
                "suggested_lz": lz_polygon, 
                "elevation": elevation_str,
                "message": "Field detected"
            })
        else:
             return jsonify({"status": "error", "message": "No distinct field found at this point"}), 400

    except Exception as e:
        print(f"AI Error: {e}")
        return jsonify({"status": "error", "message": str(e)}), 500

@terrain_bp.route('/api/terrain-analysis', methods=['POST'])
def terrain_analysis():
    data = request.json
    polygon = data.get('polygon') # [[lat, lon], ...]
    
    # 1. Create a bounding box grid for the polygon
    lats = [p[0] for p in polygon]
    lons = [p[1] for p in polygon]
    
    # Sampling density (adjust for performance)
    steps = 10 
    lat_grid = np.linspace(min(lats), max(lats), steps)
    lon_grid = np.linspace(min(lons), max(lons), steps)

    # 2. Fetch Elevation via OpenTopoData (SRTM 30m dataset)
    # Note: For production, batch these requests
    loc_string = "|".join([f"{lat},{lon}" for lat in lat_grid for lon in lon_grid])
    url = f"https://api.opentopodata.org/v1/srtm30m?locations={loc_string}"
    
    res = requests.get(url).json()
    elevs = res.get('results', [])

    # 3. Process into a Heatmap Grid
    heatmap = []
    # (Simplified slope logic: compare neighbors in the grid)
    for i in range(len(lat_grid) - 1):
        for j in range(len(lon_grid) - 1):
            # Get 4 corners of a grid cell
            z1 = elevs[i * steps + j]['elevation']
            z2 = elevs[i * steps + (j+1)]['elevation']
            z3 = elevs[(i+1) * steps + j]['elevation']
            
            # Simple average slope calculation (rise/run)
            # Distance approx: 1 deg lat ~ 111km
            rise = abs(z1 - z2) 
            run = 30 # SRTM 30m resolution
            slope_deg = math.degrees(math.atan(rise / run))
            
            heatmap.append({
                "bounds": [[lat_grid[i], lon_grid[j]], [lat_grid[i+1], lon_grid[j+1]]],
                "slope": slope_deg
            })

    return jsonify({"status": "success", "heatmap": heatmap})

# --- Terrain elevations (open AWS Terrarium DEM, same source as threat masks) ---

_TERRARIUM_URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"
_DEM_HEADERS = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'}
_M_TO_FT = 3.28084
_ELEV_ZOOM = 13  # ~19 m/px; over the US this taps 3DEP/NED, ~bare-earth


def _decode_terrarium(png_bytes):
    """Terrarium RGB -> metres: elevation = R*256 + G + B/256 - 32768."""
    arr = cv2.imdecode(np.frombuffer(png_bytes, np.uint8), cv2.IMREAD_COLOR)
    if arr is None:
        return None
    b = arr[:, :, 0].astype(np.float32)
    g = arr[:, :, 1].astype(np.float32)
    r = arr[:, :, 2].astype(np.float32)
    return (r * 256.0 + g + b / 256.0) - 32768.0


def _sample_elevations_ft(points, zoom=_ELEV_ZOOM):
    """
    Ground elevation (feet) for each point, sampled from Terrarium DEM tiles.
    Each needed tile is fetched once and decoded, then points are sampled with
    nearest-pixel. No rate limit, and consistent with the threat terrain masks.
    """
    tiles = {}
    keys = []
    for p in points:
        t = mercantile.tile(p['lon'], p['lat'], zoom)
        key = (t.x, t.y)
        tiles[key] = t
        keys.append(key)

    def grab(item):
        key, t = item
        try:
            resp = requests.get(_TERRARIUM_URL.format(z=zoom, x=t.x, y=t.y),
                                headers=_DEM_HEADERS, timeout=15)
            return key, (_decode_terrarium(resp.content) if resp.status_code == 200 else None)
        except requests.exceptions.RequestException:
            return key, None

    dem_cache = {}
    with ThreadPoolExecutor(max_workers=6) as pool:
        for key, dem in pool.map(grab, tiles.items()):
            dem_cache[key] = dem

    out = []
    for p, key in zip(points, keys):
        dem = dem_cache.get(key)
        if dem is None:
            out.append(None)
            continue
        b = mercantile.bounds(tiles[key])
        px = min(255, max(0, int((p['lon'] - b.west) / (b.east - b.west) * 256)))
        py = min(255, max(0, int((b.north - p['lat']) / (b.north - b.south) * 256)))
        out.append(round(float(dem[py, px]) * _M_TO_FT))
    return out


@terrain_bp.route('/api/elevations', methods=['POST'])
def get_elevations():
    """
    Batch ground elevations (feet) for a route's points, sampled server-side
    from the open Terrarium DEM. Proxied through the backend so the browser
    doesn't hit an elevation API cross-origin (CORS), which previously left every
    exported point at the mission template's default elevation.

    Body: { points: [{lat, lon}, ...] }
    Returns: { elevationsFt: [ft | null, ...] }  (index-aligned with points)
    """
    data = request.get_json(silent=True) or {}
    points = data.get('points', [])
    if not points:
        return jsonify({'elevationsFt': []})

    try:
        pts = [{'lat': float(p['lat']), 'lon': float(p['lon'])} for p in points]
    except (KeyError, TypeError, ValueError):
        return jsonify({'error': 'Invalid points'}), 400

    try:
        return jsonify({'elevationsFt': _sample_elevations_ft(pts)})
    except Exception as e:  # noqa: BLE001
        traceback.print_exc()
        return jsonify({'error': str(e), 'elevationsFt': [None] * len(pts)})
