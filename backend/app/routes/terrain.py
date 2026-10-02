from flask import Blueprint, request, jsonify, make_response
from flask_jwt_extended import jwt_required
import mercantile
import numpy as np
import traceback

from app.services.terrain import field_detection
from app.services.terrain import tiles as terrain_tiles
from app.services.terrain.elevations import sample_elevations_ft
from app.services.terrain.provider import build_slope_analysis

terrain_bp = Blueprint('terrain', __name__)


@terrain_bp.route('/api/analyze-field', methods=['POST'])
@jwt_required()
@terrain_tiles.pauses_warming
def analyze_field():
    data = request.json
    try:
        lat = float(data.get('lat'))
        lon = float(data.get('lon'))
    except:
        return jsonify({"status": "error", "message": "Invalid Coords"}), 400
    
    elevation_str = field_detection.elevation_label(lat, lon)

    # CHANGE 1: Zoom out to 15 or 16 to see the whole field context
    zoom_level = 14
    
    # 1. Get the Image
    image, tile = field_detection.fetch_satellite_tile(lat, lon, zoom=zoom_level)
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
        results = field_detection.predict(image, prompt_x, prompt_y)
        
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
@jwt_required()
@terrain_tiles.pauses_warming
def terrain_analysis():
    """Return a continuous, polygon-clipped terrain slope raster."""
    data = request.get_json(silent=True) or {}
    polygon = data.get('polygon')
    if not isinstance(polygon, list) or len(polygon) < 3:
        return jsonify({"error": "polygon must contain at least three [lat, lon] points"}), 400

    try:
        heading = data.get('landingHeading')
        heading = None if heading in (None, "") else float(heading)
        analysis = build_slope_analysis(polygon, landing_heading_deg=heading)
        return jsonify({"status": "success", **analysis})
    except (TypeError, ValueError) as error:
        return jsonify({"error": f"Invalid terrain request: {error}"}), 400
    except RuntimeError as error:
        return jsonify({"error": str(error)}), 502
    except Exception as error:  # noqa: BLE001
        traceback.print_exc()
        return jsonify({"error": str(error)}), 500

@terrain_bp.route('/api/elevations', methods=['POST'])
@jwt_required()
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
        return jsonify({'elevationsFt': sample_elevations_ft(pts)})
    except Exception as e:  # noqa: BLE001
        traceback.print_exc()
        return jsonify({'error': str(e), 'elevationsFt': [None] * len(pts)})


@terrain_bp.route('/api/terrain/heightmap/<int:level>/<int:x>/<int:y>', methods=['GET'])
@jwt_required()
def terrain_heightmap(level, x, y):
    """Elevations for one terrain tile, as little-endian Int16 metres.

    Feeds Cesium's CustomHeightmapTerrainProvider in the 3D view. A tile
    outside the mounted DEM coverage answers 204, which the provider reads as
    "no data here" and renders flat — the DEMs cover part of one country and
    Cesium asks across the whole globe.

    Heights are ellipsoidal, matching the frame the LiDAR tiles are built in.
    """
    if level > terrain_tiles.MAX_LEVEL:
        # A sanity bound only — Cesium stops subdividing well before this.
        # Refusing a level Cesium actually wants leaves that tile flat, which
        # puts a hole in the globe rather than capping detail.
        return jsonify({'error': 'Level out of range.'}), 400

    try:
        data = terrain_tiles.tile_bytes(level, x, y)
    except terrain_tiles.TerrainTileError as error:
        return jsonify({'error': str(error)}), 400

    if data is None:
        return ('', 204)

    response = make_response(data)
    response.headers['Content-Type'] = 'application/octet-stream'
    response.headers['X-Terrain-Samples'] = str(terrain_tiles.TILE_SAMPLES)
    # The DEMs change only when new ones are mounted, and the response is
    # derived purely from level/x/y. Private because it is behind a token.
    response.cache_control.private = True
    response.cache_control.max_age = 7 * 24 * 3600
    return response


@terrain_bp.route('/api/terrain/heights', methods=['POST'])
@jwt_required()
def terrain_heights():
    """Ground and geoid separation at points, for placing graphics in 3D.

    Body: { points: [{lat, lon}, ...] }
    Returns: { groundM: [m | null, ...], geoidM: [m, ...] }  (index-aligned)

    Ground is ellipsoidal and comes from the same DEMs as the terrain tiles, so
    a point sits on the surface the viewer draws. It is deliberately separate
    from /api/elevations, which feeds the route planner and the AMPS export:
    changing that source would change exported altitudes.

    Coordinates travel in the body only, as for the LiDAR routes.
    """
    data = request.get_json(silent=True) or {}
    points = data.get('points')
    if not isinstance(points, list):
        return jsonify({'error': 'points must be a list'}), 400
    if len(points) > terrain_tiles.MAX_POINTS:
        return jsonify({'error': f'At most {terrain_tiles.MAX_POINTS} points per request.'}), 400

    try:
        coords = [(float(p['lat']), float(p['lon'])) for p in points]
    except (KeyError, TypeError, ValueError):
        return jsonify({'error': 'Invalid points'}), 400
    if any(not (np.isfinite(lat) and np.isfinite(lon) and -90 <= lat <= 90 and -180 <= lon <= 180)
           for lat, lon in coords):
        return jsonify({'error': 'Invalid points'}), 400

    try:
        ground, geoid = terrain_tiles.sample_points(coords)
    except terrain_tiles.TerrainTileError as error:
        return jsonify({'error': str(error)}), 503

    response = jsonify({'groundM': ground, 'geoidM': geoid})
    # Behind a token, and it describes where someone is planning to fly.
    response.cache_control.private = True
    response.cache_control.no_store = True
    return response
