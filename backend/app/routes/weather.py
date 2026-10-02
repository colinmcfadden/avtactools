from flask import Blueprint, request, jsonify
from flask_jwt_extended import jwt_required
import requests
import time
import traceback

from app.services.weather import (
    FORECAST_THRESHOLD_SEC,
    fetch_awc,
    get_distance,
    get_radial_notams,
    nearest_report,
    num,
    select_taf_fcst,
    to_epoch,
    wind_from_fields,
)

weather_bp = Blueprint('weather', __name__)


@weather_bp.route('/api/weather', methods=['GET'])
@jwt_required()
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

@weather_bp.route('/api/route-winds', methods=['POST'])
@jwt_required()
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
        points = [p for p in body.get('points', []) if num(p.get('lat')) is not None]
        if not points:
            return jsonify({'winds': {}})

        lats = [num(p['lat']) for p in points]
        lons = [num(p['lon']) for p in points]
        pad = 1.5  # ~90 nm, to reach nearby reporting stations
        bbox = f"{min(lats) - pad},{min(lons) - pad},{max(lats) + pad},{max(lons) + pad}"

        now = time.time()
        point_times = {p['id']: to_epoch(p.get('time')) for p in points}
        need_taf = any(
            t is not None and t > now + FORECAST_THRESHOLD_SEC
            for t in point_times.values()
        )

        metars = fetch_awc('metar', bbox)
        tafs = fetch_awc('taf', bbox) if need_taf else []

        winds = {}
        for point in points:
            pid = point['id']
            lat, lon = num(point['lat']), num(point['lon'])
            target = point_times.get(pid)
            use_taf = target is not None and target > now + FORECAST_THRESHOLD_SEC

            result = None
            if use_taf and tafs:
                taf, dist = nearest_report(lat, lon, tafs)
                fcst = select_taf_fcst(taf, target) if taf else None
                if fcst:
                    result = wind_from_fields(fcst.get('wdir'), fcst.get('wspd'))
                    metar, _ = nearest_report(lat, lon, metars)
                    result['tempC'] = num(metar.get('temp')) if metar else None
                    result['station'] = taf.get('icaoId')
                    result['source'] = 'TAF'
                    result['distanceMiles'] = round(dist * 69, 1)

            if result is None:
                metar, dist = nearest_report(lat, lon, metars)
                if metar:
                    result = wind_from_fields(metar.get('wdir'), metar.get('wspd'))
                    result['tempC'] = num(metar.get('temp'))
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
