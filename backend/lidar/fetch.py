"""Fetching 3DEP LiDAR tiles from USGS.

A survey area is thousands of files and tens of gigabytes, over a public server
that is not always fast. So the download is resumable by construction: each file
is verified against the server's Content-Length and skipped when already
complete, which makes re-running the command the way to recover from any
interruption rather than something to avoid.

Downloads land in a temporary name and are moved into place only once complete,
so a partial file is never mistaken for a finished one.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import re
import shutil
import sys
import urllib.request
from dataclasses import dataclass
from pathlib import Path

# Polite against a public USGS endpoint: enough to saturate a home connection
# without behaving like a scraper.
DEFAULT_WORKERS = 6
CHUNK = 1 << 20

# Vegetation is what these tiles are for, and it grows. A survey flown years
# before the mission is worse than no vegetation data, because it looks
# authoritative while describing trees that are now metres taller.
STALE_PROJECT = re.compile(r"_((?:19|20)\d{2})[_/]|/(?:legacy)/", re.IGNORECASE)


@dataclass
class Outcome:
    url: str
    path: Path
    status: str          # downloaded | skipped | failed
    bytes: int = 0
    error: str = ""


def project_of(url: str) -> str:
    match = re.search(r"/Projects/(?:legacy/)?([^/]+)/", url)
    return match.group(1) if match else "unknown"


def remote_size(url: str, timeout=30) -> int | None:
    request = urllib.request.Request(url, method="HEAD")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            length = response.headers.get("Content-Length")
            return int(length) if length else None
    except Exception:  # noqa: BLE001 — treated as "unknown size"
        return None


def fetch_one(url: str, directory: Path, *, timeout=120) -> Outcome:
    destination = directory / url.rsplit("/", 1)[-1]
    expected = remote_size(url)

    if destination.exists():
        # Only a size match proves completeness; a truncated file from an
        # interrupted run is otherwise indistinguishable from a finished one.
        if expected is None or destination.stat().st_size == expected:
            return Outcome(url, destination, "skipped", destination.stat().st_size)
        destination.unlink()

    partial = destination.with_suffix(destination.suffix + ".part")
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response, \
                open(partial, "wb") as handle:
            shutil.copyfileobj(response, handle, CHUNK)
        size = partial.stat().st_size
        if expected is not None and size != expected:
            partial.unlink(missing_ok=True)
            return Outcome(url, destination, "failed", 0,
                           f"size mismatch: got {size}, expected {expected}")
        partial.replace(destination)
        return Outcome(url, destination, "downloaded", size)
    except Exception as error:  # noqa: BLE001 — reported per file, run continues
        partial.unlink(missing_ok=True)
        return Outcome(url, destination, "failed", 0, str(error))


def read_urls(list_file: Path, *, exclude=()) -> list[str]:
    urls, seen = [], set()
    for line in list_file.read_text().splitlines():
        url = line.strip()
        if not url or url.startswith("#") or url in seen:
            continue
        if any(term.lower() in url.lower() for term in exclude):
            continue
        seen.add(url)
        urls.append(url)
    return urls


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("list_file", type=Path, help="file of URLs, one per line")
    parser.add_argument("destination", type=Path, help="directory to download into")
    parser.add_argument("--exclude", nargs="*", default=[],
                        help="skip URLs containing any of these substrings, "
                             "e.g. a superseded survey")
    parser.add_argument("--workers", type=int, default=DEFAULT_WORKERS)
    parser.add_argument("--dry-run", action="store_true",
                        help="report what would be fetched, and its size")
    args = parser.parse_args(argv)

    urls = read_urls(args.list_file, exclude=args.exclude)
    by_project: dict[str, int] = {}
    for url in urls:
        by_project[project_of(url)] = by_project.get(project_of(url), 0) + 1

    print(f"{len(urls)} files across {len(by_project)} project(s)")
    for project, count in sorted(by_project.items()):
        print(f"  {count:>5}  {project}")

    if args.dry_run:
        sample = urls[: min(5, len(urls))]
        sizes = [s for s in (remote_size(u) for u in sample) if s]
        if sizes:
            average = sum(sizes) / len(sizes)
            print(f"\naverage tile: {average / 1048576:.0f} MB")
            print(f"estimated   : {average * len(urls) / 1024 ** 3:.0f} GB")
        return 0

    args.destination.mkdir(parents=True, exist_ok=True)
    done = failures = skipped = 0
    total_bytes = 0

    with concurrent.futures.ThreadPoolExecutor(args.workers) as pool:
        futures = {pool.submit(fetch_one, url, args.destination): url for url in urls}
        for index, future in enumerate(
                concurrent.futures.as_completed(futures), start=1):
            outcome = future.result()
            total_bytes += outcome.bytes
            if outcome.status == "failed":
                failures += 1
                print(f"  FAILED {outcome.path.name}: {outcome.error}", flush=True)
            elif outcome.status == "skipped":
                skipped += 1
            else:
                done += 1
            if index % 25 == 0 or index == len(urls):
                print(f"[{index}/{len(urls)}] {done} new, {skipped} present, "
                      f"{failures} failed, {total_bytes / 1024 ** 3:.1f} GB",
                      flush=True)

    print(f"\n{done} downloaded, {skipped} already present, {failures} failed")
    if failures:
        print("Re-run the same command to retry only the failures.")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
