"""Writing an AMPS ``.ths`` threat file.

A ``.ths`` is a SQLite database. The export copies ``threat_template.ths`` (a
real file with its data removed, so the schema AMPS expects survives exactly) and
inserts one row per threat, plus its radars and its system entry.

Kept apart from the routes so the mapping from a threat to its rows can be
checked without Flask, numpy or a terrain source: ``contracts/scripts`` builds the
fixtures the native apps' exporters are held to from this function.
"""

import os
import shutil
import sqlite3
import tempfile
from datetime import datetime

TEMPLATE_PATH = os.path.join(os.path.dirname(__file__), 'threat_template.ths')


def _amps_dtg(dt=None):
    """AMPS .ths DATE_TIME format observed as DDHHMMSSMMYYYY."""
    dt = dt or datetime.utcnow()
    return dt.strftime('%d%H%M%S%m%Y')


def build_ths_bytes(threats, now=None):
    """Builds a .ths (SQLite) from the cleaned template and the given threats.

    ``now`` fixes the DATE_TIME written into each row (a test wants the same bytes
    twice); left out, it is the current time.
    """
    tmp = tempfile.NamedTemporaryFile(suffix='.ths', delete=False)
    tmp.close()
    try:
        shutil.copy(TEMPLATE_PATH, tmp.name)
        con = sqlite3.connect(tmp.name)
        cur = con.cursor()
        for t in ('THREATS', 'THREATRADAR', 'SYSTEM', 'LINKS'):
            cur.execute(f'DELETE FROM {t}')

        for i, threat in enumerate(threats, start=1):
            lat = float(threat['lat']); lon = float(threat['lon'])
            name = (threat.get('name') or f'Threat {i}')[:50]
            cur.execute(
                """INSERT INTO THREATS
                (ID,CORRELATION_CODE,MILSTD_ID,LATITUDE_DEG,LONGITUDE_DEG,DATE_TIME,OFFICIAL_NAME,
                 APPROVED_NICKNAME,ELLIPSE_ANGLE_DEG,ELLIPSE_SMAJ_NMI,ELLIPSE_SMIN_NMI,INFORMATION,
                 SHOW_THREAT,SHOW_ELLIPSES,ENABLE_EDIT,SOURCE,OB_TYPE,LABEL_TEXT_LEFT,LABEL_TEXT_RIGHT,geom)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                (i, int(threat.get('correlationCode', 2100000000 + i)),
                 (threat.get('milstdId') or 'SHGPEWMAI------')[:15], lat, lon, _amps_dtg(now),
                 name, (threat.get('nickname') or '')[:50], 0.0, 0.0, 0.0,
                 (threat.get('information') or '')[:255],
                 1 if threat.get('showThreat', True) else 0,
                 1 if threat.get('showEllipses', False) else 0,
                 1 if threat.get('enableEdit', True) else 0,
                 (threat.get('source') or 'SOF')[:32], int(threat.get('obType', 0)), '', '', None))

            for radar in threat.get('radars', []):
                bands = radar.get('bands', [])
                elevs = [int(b.get('altFt', 0)) for b in bands] + [0, 0, 0]
                colors = [int(b.get('colorIndex', d)) for b, d in zip(bands, (1, 3, 5))] + [1, 3, 5]
                views = [1 if b.get('viewable', True) else 0 for b in bands] + [1, 1, 1]
                cur.execute(
                    """INSERT INTO THREATRADAR
                    (ID,RADAR_TYPE,RADAR_LATITUDE_DEG,RADAR_LONGITUDE_DEG,SHOW_MASK,SHOW_RANGE_RINGS,
                     RANGE_NMI,RANGE_LIMITED,CUSTOM_RANGE_NMI,RADAR_ELEVATION,ANTENNAE_HEIGHT_FT,AGL_NOT_MSL,
                     ELEVATION1,ELEVATION2,ELEVATION3,DRAW_STYLE,BRUSH_STYLE,COLOR1,COLOR2,COLOR3,
                     MASK1_VIEWABLE,MASK2_VIEWABLE,MASK3_VIEWABLE,geom)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                    (i, int(radar.get('type', 0)), 0.0, 0.0,
                     1 if radar.get('showMask', True) else 0,
                     1 if radar.get('showRangeRings', True) else 0,
                     float(radar.get('rangeNmi', 0)), 0, 0.0, 0,
                     float(radar.get('antennaHeightFt', 0)),
                     1 if radar.get('aglNotMsl', False) else 0,
                     elevs[0], elevs[1], elevs[2], 2, 0,
                     colors[0], colors[1], colors[2], views[0], views[1], views[2], None))

            cur.execute(
                """INSERT INTO SYSTEM (SYSTEM_CODE,SYSTEM_GROUP,SYSTEM_NAME,EQUIPMENT_FKEY,
                   USE_ENGAGEMENT,USE_DETECTION) VALUES (?,?,?,?,?,?)""",
                (i, int(threat.get('systemGroup', 2)), name, i,
                 1 if any(r.get('type') == 1 for r in threat.get('radars', [])) else 0,
                 1 if any(r.get('type') == 0 for r in threat.get('radars', [])) else 0))

        con.commit()
        con.close()
        with open(tmp.name, 'rb') as f:
            return f.read()
    finally:
        try:
            os.unlink(tmp.name)
        except OSError:
            pass
