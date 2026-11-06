"""Build or search the places index by hand.

    python -m places refresh --dir /data/places          # fetch NASR's current cycle if not built yet
    python -m places build --dir ./places --zip APT.zip --zip NAV.zip --zip FIX.zip   # from zips on hand
    python -m places search --dir ./places --near 34.8,-83.9 "blairs"

``--dir`` defaults to ``PLACES_DIR``.
"""

import argparse
import os
import sys

from . import refresh, store


def main(argv=None):
    parser = argparse.ArgumentParser(prog="python -m places")
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("refresh", "build", "search"):
        command = commands.add_parser(name)
        command.add_argument("--dir", default=store.places_dir(), help="the index's folder (PLACES_DIR)")
    commands.choices["build"].add_argument("--zip", action="append", required=True,
                                           help="a NASR CSV zip (APT, NAV and FIX, or the all-areas one)")
    commands.choices["search"].add_argument("--near", help="lat,lon")
    commands.choices["search"].add_argument("text")
    args = parser.parse_args(argv)
    if not args.dir:
        parser.error("give --dir or set PLACES_DIR")

    if args.command == "refresh":
        print(refresh.refresh(args.dir))
    elif args.command == "build":
        os.makedirs(args.dir, exist_ok=True)
        current = refresh.build_from(args.zip, args.dir)
        print(f"built {current['file']}")
    else:
        os.environ["PLACES_DIR"] = args.dir
        near = tuple(float(part) for part in args.near.split(",")) if args.near else None
        status, places = store.search(args.text, near, 25)
        if not status["available"]:
            print("no index under", args.dir, file=sys.stderr)
            return 1
        for place in places:
            print(f"{place['ident'] or '':6} {place['name'] or '':40} {place['kindWords']:24} "
                  f"{place['city'] or ''}, {place['state'] or ''}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
