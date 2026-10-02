from flask import Blueprint, request, jsonify
from flask_jwt_extended import jwt_required
import pygeodesy
from pygeodesy import mgrs
from pygeodesy.ellipsoidalExact import LatLon

location_bp = Blueprint('location', __name__)

@location_bp.route('/api/convert-grid', methods=['POST'])
@jwt_required()
def convert_grid():
    """Converts Military Grid (MGRS) to Lat/Lon using PyGeodesy"""
    data = request.json
    grid_string = data.get('grid')
    
    # Basic cleaning
    if not grid_string:
        return jsonify({"status": "error", "message": "No grid provided"}), 400
    
    # Remove spaces (e.g., "18T WL 123 456" -> "18TWL123456")
    clean_grid = grid_string.replace(" ", "").upper()

    try:
        # Parse and Convert
        # pygeodesy handles the complex math of 10-digit, 8-digit, or 6-digit grids automatically
        mgrs_obj = mgrs.parseMGRS(clean_grid)
        latlon = mgrs_obj.toLatLon()
        
        return jsonify({
            "status": "success", 
            "lat": latlon.lat, 
            "lon": latlon.lon
        })

    except Exception as e:
        print(f"Grid Error: {e}") # This will show in your terminal
        return jsonify({"status": "error", "message": str(e)}), 400


@location_bp.route('/api/convert-to-mgrs', methods=['POST'])
# If you don't use the /api prefix in your python app, just use '/convert-latlon'
@jwt_required()
def convert_latlon():
    data = request.json
    
    if not data or 'lat' not in data or 'lon' not in data:
        return jsonify({"error": "Missing coordinates in request"}), 400
        
    try:
        # 2. Force the incoming data to be decimal floats
        lat = float(data.get('lat'))
        lon = float(data.get('lon'))
        
        # 3. Create a strict PyGeodesy LatLon point
        p = LatLon(lat, lon)
        
        # 4. Convert the point to MGRS and format it with spaces
        mgrs_obj = p.toMgrs()
        mgrs_string = mgrs_obj.toStr(sep=' ')
        
        return jsonify({"mgrs": mgrs_string}), 200
        
    except ValueError:
        return jsonify({"error": "Coordinates must be valid numbers"}), 400
    except Exception as e:
        print(f"CRITICAL MGRS ERROR: {str(e)}") 
        return jsonify({"error": f"MGRS conversion failed: {str(e)}"}), 500
