from flask import Blueprint, request, jsonify
import pygeodesy
from pygeodesy import mgrs

location_bp = Blueprint('location', __name__)

@location_bp.route('/api/convert-grid', methods=['POST'])
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