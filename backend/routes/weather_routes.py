from flask import Blueprint, request, jsonify
import requests
import math
import traceback

weather_bp = Blueprint('weather', __name__)

def get_distance(lat1, lon1, lat2, lon2):
    """
    Calculate Euclidean distance between two points.
    Returns degrees (multiply by ~69 for miles).
    """
    return math.sqrt((lat2 - lat1)**2 + (lon2 - lon1)**2)

@weather_bp.route('/api/weather', methods=['GET'])
def get_local_weather():
    """
    Weather Fetch Strategy:
    1. Truncate coordinates to whole numbers (Integer Lat/Lon).
    2. Define a 1-degree bounding box around that integer point.
    3. Find nearest station -> Get METAR.
    """
    try:
        # 1. Parse Coordinates
        lat_str = request.args.get('lat')
        lng_str = request.args.get('lng')
        
        if not lat_str or not lng_str:
            return jsonify({'error': 'Missing lat/lng'}), 400

        # Cast to Float first to handle the input string
        raw_lat = float(lat_str)
        raw_lng = float(lng_str)

        # 2. INTEGER TRUNCATION (Remove decimal part)
        # e.g., 33.9 -> 33, -84.1 -> -84
        lat_int = int(raw_lat)
        lng_int = int(raw_lng)

        # 3. Define Bounding Box (Large 1-degree / ~60 mile box)
        # We use a larger delta because truncating might shift our center point away from the user.
        delta = 1
        
        min_lon = lng_int - delta
        min_lat = lat_int - delta
        max_lon = lng_int + delta
        max_lat = lat_int + delta
        
        # BBox format: "minLat,minLon,maxLat,maxLon" (Integers only)
        bbox = f"{min_lat},{min_lon},{max_lat},{max_lon}"
        
        headers = {'User-Agent': 'AvTacTools/1.0 (contact@example.com)'}

        # --- STEP 1: Find Stations ---
        station_url = "https://aviationweather.gov/api/data/stationinfo"
        station_params = {
            "bbox": bbox,
            "format": "json"
        }
        
        print(f"DEBUG: Finding stations in INTEGER BBOX: {bbox}")
        s_resp = requests.get(station_url, params=station_params, headers=headers, timeout=5)
        
        if s_resp.status_code != 200:
             print(f"ERROR: Station fetch failed: {s_resp.text}")
             return jsonify({'error': 'Failed to fetch station list'}), 502
             
        stations = s_resp.json()
        if not stations:
            return jsonify({'error': f'No stations found in grid area {bbox}'}), 404
            
        # --- Sort by Distance from ORIGINAL (Precision) Coordinates ---
        # Even though we searched using integers, we sort using the user's exact location.
        best_station = None
        min_dist = float('inf')
        
        for st in stations:
            if 'lat' not in st or 'lon' not in st: continue
            
            # Use raw_lat/raw_lng for sorting accuracy
            dist = get_distance(raw_lat, raw_lng, st['lat'], st['lon'])
            
            if dist < min_dist:
                min_dist = dist
                best_station = st
                
        if not best_station:
            return jsonify({'error': 'Could not determine nearest station'}), 404
            
        station_id = best_station.get('icaoId') or best_station.get('stationId')
        station_name = best_station.get('site', 'Unknown')
        
        print(f"DEBUG: Nearest Station: {station_id} | Dist: {round(min_dist*69, 1)} miles")

        # --- STEP 2: Get METAR ---
        metar_url = "https://aviationweather.gov/api/data/metar"
        metar_params = {
            "ids": station_id,
            "format": "json",
            "hours": "1.5"
        }
        
        m_resp = requests.get(metar_url, params=metar_params, headers=headers, timeout=5)
        
        if m_resp.status_code != 200:
             return jsonify({'error': f'Failed to fetch METAR for {station_id}'}), 502
             
        metar_data = m_resp.json()
        
        if not metar_data:
            return jsonify({
                'error': f'Station {station_id} found, but no METAR available.',
                'station_id': station_id,
                'distance_miles': round(min_dist * 69, 1)
            }), 404
            
        report = metar_data[0]

        # --- STEP 3: Format Data ---
        pressure = 29.92
        if report.get('altim'):
            try:
                pressure = round(float(report['altim']) * 0.02953, 2)
            except:
                pass

        return jsonify({
            'station_id': station_id,
            'name': station_name,
            'temp_c': report.get('temp'),
            'dewp_c': report.get('dewp'),
            'wind_spd_kts': report.get('wspd'),
            'wind_dir': report.get('wdir'),
            'wind_gust_kts': report.get('wgst'),
            'vis_sm': report.get('visib'),
            'pressure': pressure,
            'flight_category': report.get('fltcat'),
            'raw_metar': report.get('rawOb'),
            'distance_miles': round(min_dist * 69, 1)
        })

    except Exception as e:
        print("CRITICAL WEATHER ERROR:")
        traceback.print_exc()
        return jsonify({'error': str(e)}), 500