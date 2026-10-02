"""Ground elevation for route points, from the open Terrarium DEM tiles.

The same source as the threat masks, so a point's elevation agrees with what
the mask was computed from.
"""

from concurrent.futures import ThreadPoolExecutor

import mercantile
import requests

from app.services.terrain.provider import TERRARIUM_URL, decode_terrarium

_DEM_HEADERS = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'}
_M_TO_FT = 3.28084
_ELEV_ZOOM = 13  # ~19 m/px; over the US this taps 3DEP/NED, ~bare-earth


def sample_elevations_ft(points, zoom=_ELEV_ZOOM):
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
            resp = requests.get(TERRARIUM_URL.format(z=zoom, x=t.x, y=t.y),
                                headers=_DEM_HEADERS, timeout=15)
            return key, (decode_terrarium(resp.content) if resp.status_code == 200 else None)
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
