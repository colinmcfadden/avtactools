"""Aviation weather: METAR, TAF and NOTAM lookups, and the choices made from them."""

import math
from datetime import datetime, timezone

import requests


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


# A point's planned time this far past "now" (or later) is treated as a
# forecast and pulls from the TAF instead of the current METAR.
FORECAST_THRESHOLD_SEC = 1800


def num(value):
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def to_epoch(value):
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


def nearest_report(lat, lon, reports):
    """Nearest report (dict with lat/lon) to a point; returns (report, dist_deg)."""
    best = None
    best_dist = float('inf')
    for report in reports:
        r_lat = num(report.get('lat'))
        r_lon = num(report.get('lon'))
        if r_lat is None or r_lon is None:
            continue
        dist = get_distance(lat, lon, r_lat, r_lon)
        if dist < best_dist:
            best_dist = dist
            best = report
    return best, best_dist


def wind_from_fields(dir_field, spd_field):
    """Wind dict from raw report fields. 'VRB'/missing direction becomes 0."""
    direction = num(dir_field)
    speed = num(spd_field)
    return {
        'dirTrue': int(direction) if direction is not None else 0,
        'speedKts': speed if speed is not None else 0,
        'variable': direction is None,
    }


def fetch_awc(kind, bbox):
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


def select_taf_fcst(taf, target_epoch):
    """Picks the TAF forecast period covering target_epoch (prefers base groups)."""
    fcsts = taf.get('fcsts') or []
    covering = []
    for fcst in fcsts:
        start = to_epoch(fcst.get('timeFrom'))
        end = to_epoch(fcst.get('timeTo'))
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
    valid = [f for f in fcsts if to_epoch(f.get('timeFrom')) is not None]
    if valid:
        return min(valid, key=lambda f: abs(to_epoch(f.get('timeFrom')) - target_epoch))
    return None
