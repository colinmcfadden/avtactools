"""Golden light-data fixtures for the mission brief, computed by Skyfield.

The web (`frontend/src/feature/brief/light.js`) and Android (`core-brief`) work out BMNT, sunrise, sunset, EENT,
moonrise, moonset and the moon's illumination on the device from low-precision series. This script is their
independent reference: Skyfield over JPL's DE421 ephemeris, a different method entirely, so an error in the
series cannot hide in its own answers (the owner chose it on 2026-10-16; it is a development tool, never shipped).

    pip install skyfield==1.55
    python contracts/scripts/light_fixtures.py write   # regenerate the JSON
    python contracts/scripts/light_fixtures.py check   # fail if the JSON drifted

DE421 (17 MB) is downloaded once into EZPZ_SKYFIELD_DIR (default ~/.cache/ezpz-skyfield). Generation uses no clock
and no randomness, so `check` is exact.

Definitions, which the clients must share:
- A local day is the 24 hours from local midnight at the case's UTC offset.
- Sunrise and sunset: the sun's centre at -0.8333 degrees (refraction and semidiameter), the first of each that day.
- BMNT and EENT: the sun's centre at -12 degrees, the first of each that day.
- Moonrise and moonset: the moon's upper limb on the horizon, refraction included (Skyfield's own rule), the first
  of each that day.
- Illumination: the illuminated fraction of the moon's disk at the case's `at` instant.
- None of a body's events that day is null, never a guess.
"""

import json
import os
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

from skyfield import almanac
from skyfield.api import Loader, wgs84

OUT = Path(__file__).resolve().parent.parent / "fixtures" / "brief" / "light.json"

# How close the clients must come. The series are good to about 0.01 degree (sun) and 0.3 degree (moon); a degree
# of the moon's place is about four minutes of its rising, and the brief prints minutes and whole percents.
TOLERANCE = {"sunSeconds": 60, "moonSeconds": 180, "illumination": 0.01}

# (name, lat, lon, date, utc offset in minutes, the illumination instant as local HH:MM)
CASES = [
    ("north Georgia, autumn (the design's OP DK)", 34.37, -84.11, "2026-10-16", -240, "11:56"),
    ("north Georgia, winter", 34.37, -84.11, "2026-01-15", -300, "19:30"),
    ("Fort Novosel, summer", 31.33, -85.71, "2026-07-01", -300, "02:00"),
    ("Fort Novosel, a full moon night", 31.33, -85.71, "2026-08-28", -300, "23:00"),
    ("Fort Novosel, a new moon", 31.33, -85.71, "2026-09-11", -300, "12:00"),
    ("Fairbanks, midsummer: no nautical dark", 64.84, -147.72, "2026-06-21", -480, "12:00"),
    ("Fairbanks, midwinter", 64.84, -147.72, "2026-12-21", -540, "12:00"),
    ("Svalbard, polar night: no sunrise", 78.22, 15.65, "2026-12-21", 60, "12:00"),
    ("Svalbard, midnight sun", 78.22, 15.65, "2026-06-21", 120, "12:00"),
    ("the equator, an equinox", 0.0, 32.58, "2026-03-20", 180, "06:00"),
    ("Grafenwoehr, spring", 49.70, 11.94, "2026-04-02", 120, "21:00"),
    ("Camp Humphreys", 36.96, 127.03, "2026-11-05", 540, "04:00"),
    ("Schofield Barracks", 21.50, -158.06, "2026-05-10", -600, "20:00"),
    ("Sydney, the south", -33.87, 151.21, "2026-10-16", 660, "12:00"),
    ("Fiji, beside the date line", -17.80, 178.00, "2026-07-15", 720, "18:00"),
    ("Samoa, the other side", -13.83, -171.76, "2026-07-15", 780, "18:00"),
    ("Fort Wainwright, a day the moon neither rises nor sets", 64.83, -147.64, "2026-02-26", -540, "12:00"),
    ("north Georgia, a day the moon does not set", 34.37, -84.11, "2026-10-18", -240, "12:00"),
    ("north Georgia, a day the moon does not rise", 34.37, -84.11, "2026-11-30", -300, "12:00"),
    ("Fort Bliss, a crescent at dusk", 31.81, -106.42, "2026-10-12", -360, "19:00"),
]


def iso(t):
    return t.utc_datetime().replace(microsecond=0).strftime("%Y-%m-%dT%H:%M:%SZ")


def first(times, flags):
    for t, ok in zip(times, flags):
        if ok:
            return t
    return None


def compute():
    load = Loader(os.environ.get("EZPZ_SKYFIELD_DIR", str(Path.home() / ".cache" / "ezpz-skyfield")))
    ts = load.timescale(builtin=True)
    eph = load("de421.bsp")
    earth, sun, moon = eph["earth"], eph["sun"], eph["moon"]

    cases = []
    for name, lat, lon, date, offset, at in CASES:
        tz = timezone(timedelta(minutes=offset))
        y, m, d = (int(x) for x in date.split("-"))
        start = datetime(y, m, d, tzinfo=tz)
        t0, t1 = ts.from_datetime(start), ts.from_datetime(start + timedelta(days=1))
        observer = earth + wgs84.latlon(lat, lon)

        def event(find, body, **kw):
            times, flags = find(observer, body, t0, t1, **kw)
            t = first(times, flags)
            return iso(t) if t is not None else None

        hh, mm = (int(x) for x in at.split(":"))
        at_instant = start + timedelta(hours=hh, minutes=mm)
        cases.append({
            "name": name,
            "lat": lat, "lon": lon, "date": date, "utcOffsetMinutes": offset,
            "at": at_instant.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "expected": {
                "bmnt": event(almanac.find_risings, sun, horizon_degrees=-12.0),
                "sunrise": event(almanac.find_risings, sun, horizon_degrees=-0.8333),
                "sunset": event(almanac.find_settings, sun, horizon_degrees=-0.8333),
                "eent": event(almanac.find_settings, sun, horizon_degrees=-12.0),
                "moonrise": event(almanac.find_risings, moon),
                "moonset": event(almanac.find_settings, moon),
                "illumination": round(float(almanac.fraction_illuminated(eph, "moon", ts.from_datetime(at_instant))), 4),
            },
        })
    return {
        "generatedBy": "contracts/scripts/light_fixtures.py (Skyfield 1.55, JPL DE421)",
        "tolerance": TOLERANCE,
        "cases": cases,
    }


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "check"
    text = json.dumps(compute(), indent=2, ensure_ascii=False) + "\n"
    if mode == "write":
        OUT.parent.mkdir(parents=True, exist_ok=True)
        OUT.write_text(text, encoding="utf-8")
        print(f"wrote {OUT}")
    elif mode == "check":
        if OUT.read_text(encoding="utf-8") != text:
            sys.exit(f"{OUT} is not what Skyfield answers: run `python {sys.argv[0]} write` and review the diff")
        print("light fixtures match Skyfield")
    else:
        sys.exit("usage: light_fixtures.py write|check")


if __name__ == "__main__":
    main()
