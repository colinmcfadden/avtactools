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
    

def get_radial_notams(lat, lon, radius_nm=10):
    """
    Ping the public FAA NOTAM Search backend using a Lat/Lon Radius.
    Converts decimal degrees to Degrees, Minutes, Seconds.
    """
    # Math for Latitude DMS
    lat_abs = abs(lat)
    lat_deg = int(lat_abs)
    lat_min = int((lat_abs - lat_deg) * 60)
    lat_sec = int((lat_abs - lat_deg - lat_min / 60.0) * 3600.0)
    lat_dir = "N" if lat >= 0 else "S"
    
    # Math for Longitude DMS
    lon_abs = abs(lon)
    lon_deg = int(lon_abs)
    lon_min = int((lon_abs - lon_deg) * 60)
    lon_sec = int((lon_abs - lon_deg - lon_min / 60.0) * 3600.0)
    lon_dir = "E" if lon >= 0 else "W"

    url = "https://notams.aim.faa.gov/notamSearch/search"
    
    # searchType 3 = Lat/Lon Radius Search
    payload = {
        "searchType": 3,
        "latDegrees": lat_deg,
        "latMinutes": lat_min,
        "latSeconds": lat_sec,
        "latitudeDirection": lat_dir,
        "longDegrees": lon_deg,
        "longMinutes": lon_min,
        "longSeconds": lon_sec,
        "longitudeDirection": lon_dir,
        "radius": radius_nm,
        "radiusSearchOnDesignator": "false",
        "offset": 0
    }
    
    headers = {
        "Content-Type": "application/x-www-form-urlencoded",
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)" 
    }
    
    try:
        print(f"DEBUG: Fetching NOTAMs in a {radius_nm}nm radius of {lat_deg}°{lat_min}'{lat_dir} {lon_deg}°{lon_min}'{lon_dir}")
        resp = requests.post(url, data=payload, headers=headers, timeout=5)
        
        if resp.status_code != 200:
            return "NOTAM fetch failed."
            
        data = resp.json()

        if isinstance(data, list):
            notam_list = data
        # If the FAA returns a dictionary wrapper
        elif isinstance(data, dict):
            notam_list = data.get('notamList', [])
        else:
            notam_list = []
        
        if not notam_list:
            return {"Clear": ["No active NOTAMs in LZ area."]}
            
        grouped_notams = {}
        
        for item in notam_list: 
            # Make sure the item is actually a dictionary before we try to parse it
            if not isinstance(item, dict):
                continue
                
            category = item.get('featureName', 'General Airspace')
            text = item.get('traditionalMessage', '').strip()
            
            if not text:
                continue
                
            if category not in grouped_notams:
                grouped_notams[category] = []

            grouped_notams[category].append(text)
            
        return grouped_notams
        
    except Exception as e:
        print(f"NOTAM ERROR: {e}")
        return "NOTAMs currently unavailable."


@weather_bp.route('/api/weather', methods=['GET'])
def get_local_weather():
    try:
        # 1. Parse Coordinates
        lat_str = request.args.get('lat')
        lng_str = request.args.get('lng')
        
        if not lat_str or not lng_str:
            return jsonify({'error': 'Missing lat/lng'}), 400

        raw_lat = float(lat_str)
        raw_lng = float(lng_str)

        # 2. INTEGER TRUNCATION
        lat_int = int(raw_lat)
        lng_int = int(raw_lng)
        delta = 1
        
        min_lat = lat_int - delta
        min_lon = lng_int - delta
        max_lat = lat_int + delta
        max_lon = lng_int + delta
        
        # We are back to the correct NOAA format you found!
        bbox = f"{min_lat},{min_lon},{max_lat},{max_lon}"
        headers = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'}

        # --- DEFAULT VALUES (If NOAA times out) ---
        station_id = "TIMEOUT"
        station_name = "AviationWeather API Offline"
        best_report = {}
        pressure = "--"
        min_dist = float('inf')

        # --- THE SINGLE FETCH OPTIMIZATION ---
        try:
            metar_url = "https://aviationweather.gov/api/data/metar"
            metar_params = {
                "bbox": bbox, 
                "format": "json", 
                "hours": "1.5"
            }
            
            print(f"DEBUG: Fetching ALL METARs in BBOX: {bbox}")
            m_resp = requests.get(metar_url, params=metar_params, headers=headers, timeout=10)
            
            if m_resp.status_code == 200:
                metar_data = m_resp.json()
                
                if metar_data:
                    # Loop through all active METARs in the 60-mile radius
                    # and find the one physically closest to our exact LZ coordinates
                    for report in metar_data:
                        if 'lat' not in report or 'lon' not in report: continue
                        
                        dist = get_distance(raw_lat, raw_lng, report['lat'], report['lon'])
                        if dist < min_dist:
                            min_dist = dist
                            best_report = report
                            
                    if best_report:
                        station_id = best_report.get('icaoId', 'UNKNOWN')
                        station_name = best_report.get('name', station_id)
                        if best_report.get('altim'):
                            try:
                                pressure = round(float(best_report['altim']) * 0.02953, 2)
                            except:
                                pass
        except requests.exceptions.RequestException as we:
            print(f"WEATHER API TIMEOUT/ERROR CAUGHT: {we}")

        # --- PROTECTED NOTAM FETCH ---
        active_notams = get_radial_notams(raw_lat, raw_lng, radius_nm=10)

        return jsonify({
            'station_id': station_id,
            'name': station_name,
            'temp_c': best_report.get('temp'),
            'dewp_c': best_report.get('dewp'),
            'wind_spd_kts': best_report.get('wspd'),
            'wind_dir': best_report.get('wdir'),
            'wind_gust_kts': best_report.get('wgst'),
            'vis_sm': best_report.get('visib'),
            'pressure': pressure,
            'flight_category': best_report.get('fltcat'),
            'raw_metar': best_report.get('rawOb'),
            'distance_miles': round(min_dist * 69, 1) if min_dist != float('inf') else 0,
            'notams': active_notams
        })

    except Exception as e:
        print("CRITICAL SERVER ERROR:")
        traceback.print_exc()
        return jsonify({'error': str(e)}), 500