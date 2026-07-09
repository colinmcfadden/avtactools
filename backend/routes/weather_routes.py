from flask import Blueprint, request, jsonify
import requests
import math
import time
import traceback
from datetime import datetime, timezone

weather_bp = Blueprint('weather', __name__)

AWC_HEADERS = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'}

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


# --- Per-point route winds (METAR now / TAF for the future) ---

# A point's planned time this far past "now" (or later) is treated as a
# forecast and pulls from the TAF instead of the current METAR.
FORECAST_THRESHOLD_SEC = 1800


def _num(value):
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _to_epoch(value):
    """Coerces AWC time fields (epoch seconds or ISO strings) to epoch seconds."""
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return float(value)
    try:
        dt = datetime.fromisoformat(str(value).replace('Z', '+00:00'))
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return dt.timestamp()
    except ValueError:
        return None


def _nearest_report(lat, lon, reports):
    """Nearest report (dict with lat/lon) to a point; returns (report, dist_deg)."""
    best = None
    best_dist = float('inf')
    for report in reports:
        r_lat = _num(report.get('lat'))
        r_lon = _num(report.get('lon'))
        if r_lat is None or r_lon is None:
            continue
        dist = get_distance(lat, lon, r_lat, r_lon)
        if dist < best_dist:
            best_dist = dist
            best = report
    return best, best_dist


def _wind_from_fields(dir_field, spd_field):
    """Wind dict from raw report fields. 'VRB'/missing direction becomes 0."""
    direction = _num(dir_field)
    speed = _num(spd_field)
    return {
        'dirTrue': int(direction) if direction is not None else 0,
        'speedKts': speed if speed is not None else 0,
        'variable': direction is None,
    }


def _fetch_awc(kind, bbox):
    """Fetches METARs or TAFs in a bbox from aviationweather.gov; [] on failure."""
    try:
        resp = requests.get(
            f"https://aviationweather.gov/api/data/{kind}",
            params={'bbox': bbox, 'format': 'json'},
            headers=AWC_HEADERS,
            timeout=10,
        )
        if resp.status_code != 200:
            return []
        data = resp.json()
        return data if isinstance(data, list) else []
    except (requests.exceptions.RequestException, ValueError):
        return []


def _select_taf_fcst(taf, target_epoch):
    """Picks the TAF forecast period covering target_epoch (prefers base groups)."""
    fcsts = taf.get('fcsts') or []
    covering = []
    for fcst in fcsts:
        start = _to_epoch(fcst.get('timeFrom'))
        end = _to_epoch(fcst.get('timeTo'))
        if start is None or target_epoch < start:
            continue
        if end is not None and target_epoch >= end:
            continue
        covering.append(fcst)

    def is_base(fcst):
        return (fcst.get('fcstChange') or '') not in ('TEMPO',) and not fcst.get('probability')

    base = [f for f in covering if is_base(f)]
    if base:
        return base[-1]
    if covering:
        return covering[-1]

    # No period contains the target (e.g. time beyond the TAF) — use the
    # forecast whose start time is closest.
    valid = [f for f in fcsts if _to_epoch(f.get('timeFrom')) is not None]
    if valid:
        return min(valid, key=lambda f: abs(_to_epoch(f.get('timeFrom')) - target_epoch))
    return None


@weather_bp.route('/api/route-winds', methods=['POST'])
def get_route_winds():
    """
    Per-point winds for route planning. Body: { points: [{id, lat, lon, time?}] }
    where `time` is an ISO timestamp for that point (its planned/clock time). For
    each point the nearest station is used; points planned more than ~30 min in
    the future draw wind from that station's TAF, everything else from the latest
    METAR. Returns { winds: { [id]: {dirTrue, speedKts, tempC, station, source,
    distanceMiles} } }.
    """
    try:
        body = request.get_json(silent=True) or {}
        points = [p for p in body.get('points', []) if _num(p.get('lat')) is not None]
        if not points:
            return jsonify({'winds': {}})

        lats = [_num(p['lat']) for p in points]
        lons = [_num(p['lon']) for p in points]
        pad = 1.5  # ~90 nm, to reach nearby reporting stations
        bbox = f"{min(lats) - pad},{min(lons) - pad},{max(lats) + pad},{max(lons) + pad}"

        now = time.time()
        point_times = {p['id']: _to_epoch(p.get('time')) for p in points}
        need_taf = any(
            t is not None and t > now + FORECAST_THRESHOLD_SEC
            for t in point_times.values()
        )

        metars = _fetch_awc('metar', bbox)
        tafs = _fetch_awc('taf', bbox) if need_taf else []

        winds = {}
        for point in points:
            pid = point['id']
            lat, lon = _num(point['lat']), _num(point['lon'])
            target = point_times.get(pid)
            use_taf = target is not None and target > now + FORECAST_THRESHOLD_SEC

            result = None
            if use_taf and tafs:
                taf, dist = _nearest_report(lat, lon, tafs)
                fcst = _select_taf_fcst(taf, target) if taf else None
                if fcst:
                    result = _wind_from_fields(fcst.get('wdir'), fcst.get('wspd'))
                    metar, _ = _nearest_report(lat, lon, metars)
                    result['tempC'] = _num(metar.get('temp')) if metar else None
                    result['station'] = taf.get('icaoId')
                    result['source'] = 'TAF'
                    result['distanceMiles'] = round(dist * 69, 1)

            if result is None:
                metar, dist = _nearest_report(lat, lon, metars)
                if metar:
                    result = _wind_from_fields(metar.get('wdir'), metar.get('wspd'))
                    result['tempC'] = _num(metar.get('temp'))
                    result['station'] = metar.get('icaoId')
                    result['source'] = 'METAR'
                    result['distanceMiles'] = round(dist * 69, 1)

            if result is not None:
                winds[pid] = result

        return jsonify({'winds': winds})

    except Exception as e:
        print("ROUTE WIND ERROR:")
        traceback.print_exc()
        return jsonify({'error': str(e)}), 500