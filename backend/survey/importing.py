"""The old book's pages taken in as drafts (docs/LZ_SURVEYS.md §6).

``tools/import_survey_book.py`` reads the book on the owner's machine into a bundle: a zip of ``manifest.json`` (format
``ezpz-survey-import``) and the photos. ``read_bundle`` checks it as input from anyone: a zip that inflates past what an import takes,
a manifest that is not the importer's, a page with a value the survey has no field for, are refused in words; nothing is extracted to
disk, and only the photos the manifest names are read, each bounded. ``place_of`` puts the LZ's target at the book's grid and says
when its lat/long is elsewhere (§2: DONOVAN's are 527 m apart).

Each draft is then measured in the background, one at a time (``Measurer``): the model's boundary at the target, as *Analyze* finds
it, then the snapshot. What happens is kept on the survey's import record (``LzSurveyImport.state``) for the book's page to show. The
survey's own content is written only if no one saved it meanwhile (its revision is the one read): an editor who drew a boundary
while the model looked for one keeps theirs.
"""

from __future__ import annotations

import collections
import io
import json
import logging
import math
import re
import threading
import uuid as uuid_lib
import zipfile
import zlib
from datetime import datetime

from pygeodesy import mgrs as pg_mgrs

import terrain_tiles
from models import LzSurvey, LzSurveyImport, db
from survey import compare, fields, measure
from survey.assets import MAX_UPLOAD_BYTES

log = logging.getLogger(__name__)

FORMAT = "ezpz-survey-import"
VERSION = 1
# Cloudflare takes no larger request on its free plan; the 44-page book's bundle is 14 MB.
MAX_BUNDLE_BYTES = 100 * 1024 * 1024
MAX_ENTRIES = 2000
MAX_MANIFEST_BYTES = 8 * 1024 * 1024
# What the photos of one import may inflate to, all told: a zip that claims or reads past it is refused.
MAX_INFLATED_BYTES = 400 * 1024 * 1024
MAX_SURVEYS = 500
MAX_PHOTOS = 4
MAX_NOTES = 60
MAX_NOTE = 300
MAX_NAME = 100
MAX_HLZ_ID = 40
MAX_SOURCE = 200
MAX_TAKEN_ON = 40
PHOTO_KINDS = ("photo_north", "photo_approach", "photo_other")
# The book's grid and lat/long may differ by this much before the page is flagged (§2).
GRID_AGREES_M = 50.0
EARTH_RADIUS_M = 6371008.8
_PHOTO_NAME = re.compile(r"^photos/[A-Za-z0-9._-]{1,120}$")

# The background measuring's states. Active ones are waiting for, or in, the measurer.
ACTIVE = ("queued", "finding", "measuring")
STATES = ACTIVE + ("done", "no_target", "no_boundary", "failed", "changed")
# Who the survey says last changed it when the background measuring writes its boundary or snapshot: not the person who imported it,
# who did not save it then (an editor's banner says who saved while they edited).
WRITER = "EZ/PZ (import)"


class BundleError(ValueError):
    """Why a bundle is refused, in words for the person importing, with a ``code`` for the API's answer."""

    def __init__(self, message, code="invalid_bundle"):
        super().__init__(message)
        self.code = code


class Damaged(Exception):
    """A file in the bundle that cannot be read whole: cut short, failing its checksum, locked by a password, or packed in a way the
    importer never packs one."""


# What zipfile raises for a file it cannot read: a checksum or a cut (BadZipFile, zlib.error, EOFError), a password (RuntimeError), a
# method it does not read (NotImplementedError).
_UNREADABLE = (zipfile.BadZipFile, zlib.error, EOFError, NotImplementedError, RuntimeError, ValueError, OSError)
# The importer packs with deflate; a file stored as it is reads too.
_PACKED = (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED)
_NOT_READ = object()


# -- The bundle -------------------------------------------------------------------------------------------------------

def _one_line(value, limit, upper=False):
    """A name, an HLZ ID or a date as the editor takes one (on one line, at most ``limit`` characters, counted as it is kept, so once
    upper-cased: "ß" is two), as (text, None); or (None, why) for one it would refuse."""
    if value is None:
        return "", None
    if not isinstance(value, str):
        return None, "is not text"
    value = value.strip()
    if upper:
        value = value.upper()
    if any(ord(c) < 32 for c in value):
        return None, "is not on one line"
    if len(value) > limit:
        return None, f"is longer than {limit} characters"
    return value, None


def _notes(value, warnings, label):
    if value in (None, []):
        return []
    if not isinstance(value, list):
        warnings.append(f"The importer's {label} for the page could not be read.")
        return []
    out = [note.strip()[:MAX_NOTE] for note in value[:MAX_NOTES] if isinstance(note, str)]
    return [note for note in out if note]


def _heading(value):
    """A photo's heading, 0 to 360 degrees; None for none, ``_NOT_READ`` for one that is not a heading."""
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return _NOT_READ
    try:
        number = float(value)
    except OverflowError:
        return _NOT_READ
    return number % 360.0 if math.isfinite(number) and 0 <= number <= 360 else _NOT_READ


def _field(value, warnings):
    """The page's values held to the editor's own rules, one by one: a value the editor would refuse is left out and said, and the rest
    of the page imports."""
    if value in (None, {}):
        return {}
    if not isinstance(value, dict):
        warnings.append("The page's values could not be read: none was imported.")
        return {}
    out = {}
    for key, text in value.items():
        try:
            out.update(fields.clean_field({key: text}))
        except ValueError as problem:
            warnings.append(f"A value was not imported: {problem}.")
    return out


def _photos(value, names, warnings):
    """The photos a page names, each checked; one that cannot be used is left out and said."""
    if value in (None, []):
        return []
    if not isinstance(value, list):
        warnings.append("The page's photos could not be read: none was imported.")
        return []
    if len(value) > MAX_PHOTOS:
        warnings.append(f"The page names {len(value)} photos: the first {MAX_PHOTOS} were imported.")
    photos = []
    for photo in value[:MAX_PHOTOS]:
        if not isinstance(photo, dict) or photo.get("kind") not in PHOTO_KINDS:
            warnings.append("A photo of a kind the survey does not keep was left out.")
            continue
        words = photo_words(photo["kind"])
        file = photo.get("file")
        if not isinstance(file, str) or not _PHOTO_NAME.match(file) or file not in names:
            warnings.append(f"The {words} is not in the bundle and was left out.")
            continue
        heading = _heading(photo.get("heading_deg"))
        if heading is _NOT_READ:
            warnings.append(f"The {words}'s heading is not 0 to 360 degrees: it was left blank.")
            heading = None
        taken_on, why = _one_line(photo.get("taken_on"), MAX_TAKEN_ON)
        if why:
            warnings.append(f"The {words}'s date {why}: it was left blank.")
            taken_on = ""
        photos.append({"kind": photo["kind"], "file": file, "heading_deg": heading, "taken_on": taken_on})
    return photos


def _page(entry, index, names):
    """One page of the manifest as a survey to make. What the editor would refuse of it is left out and said in its warnings, so one
    odd value never stops the rest of the book; only an entry that is not a page at all refuses the bundle."""
    if not isinstance(entry, dict):
        raise BundleError(f"Entry {index + 1} of the bundle is not a page.")
    number = entry.get("page")
    if isinstance(number, bool) or not isinstance(number, int) or not 1 <= number <= 9999:
        raise BundleError(f"Entry {index + 1} of the bundle has no page number.")
    warnings = []
    notes = {"unread": _notes(entry.get("unread"), warnings, "list of what it did not find"),
             "blank": _notes(entry.get("blank"), warnings, "list of what was blank")}
    warnings.extend(_notes(entry.get("warnings"), warnings, "warnings"))
    field = _field(entry.get("field"), warnings)
    name, why = _one_line(entry.get("name"), MAX_NAME, upper=True)
    if why:
        warnings.append(f"The page's name {why}: the survey is called PAGE {number}.")
    hlz_id, why = _one_line(entry.get("hlzId"), MAX_HLZ_ID, upper=True)
    if why:
        warnings.append(f"The page's HLZ ID {why} and was left out.")
    photos = _photos(entry.get("photos"), names, warnings)
    return {"page": number, "hlzId": hlz_id or "", "name": name or f"PAGE {number}", "field": field, "photos": photos,
            "notes": {**notes, "warnings": warnings}}


class Bundle:
    """A bundle opened and checked (``read_bundle``): its source and its pages, each with the photos it names. The photos are read one
    at a time (``photo``), while it is open, so an import holds one photo's bytes at a time, and the bundle counts every byte it
    inflates, so it cannot be made to inflate without end."""

    def __init__(self, archive):
        self._archive = archive
        self._infos = {info.filename: info for info in archive.infolist()}
        self.inflated = 0
        self.source = ""
        self.pages = []

    def __enter__(self):
        return self

    def __exit__(self, *_exc):
        self.close()

    def close(self):
        self._archive.close()

    def read(self, name, limit):
        """A file's bytes, or None when it is larger than ``limit``. Raises ``Damaged`` for one that cannot be read whole, and
        BundleError once the bundle has inflated past what an import takes."""
        info = self._infos.get(name)
        if info is None or info.flag_bits & 0x1 or info.compress_type not in _PACKED:
            raise Damaged(name)
        try:
            with self._archive.open(info) as member:
                data = member.read(limit + 1)
        except _UNREADABLE:
            raise Damaged(name) from None
        self.inflated += len(data)
        if self.inflated > MAX_INFLATED_BYTES:
            raise BundleError(TOO_LARGE, "bundle_too_large")
        return None if len(data) > limit else data

    def photo(self, photo):
        """A page's photo's bytes, or None when it is larger than an upload takes. Raises ``Damaged``."""
        return self.read(photo["file"], MAX_UPLOAD_BYTES)


TOO_LARGE = "The bundle holds more than an import takes (100 MB sent, 400 MB unpacked). Import the book in parts (--pages)."


def read_bundle(data):
    """The bundle opened and checked, to use as a context manager: its ``source`` and its ``pages``, each {page, hlzId, name, field,
    photos: [{kind, file, heading_deg, taken_on}], notes}, in page order. Raises BundleError."""
    if not isinstance(data, (bytes, bytearray)) or len(data) > MAX_BUNDLE_BYTES:
        raise BundleError(TOO_LARGE, "bundle_too_large")
    try:
        archive = zipfile.ZipFile(io.BytesIO(data))
    except _UNREADABLE:
        raise BundleError("That is not a bundle from tools/import_survey_book.py: it is not a zip.") from None
    bundle = Bundle(archive)
    try:
        _check(bundle, archive)
    except BaseException:
        bundle.close()
        raise
    return bundle


def _check(bundle, archive):
    infos = archive.infolist()
    if len(infos) > MAX_ENTRIES:
        raise BundleError("The bundle holds too many files to be one book.")
    if sum(info.file_size for info in infos) > MAX_INFLATED_BYTES + MAX_MANIFEST_BYTES:
        raise BundleError(TOO_LARGE, "bundle_too_large")
    names = {info.filename for info in infos}
    if "manifest.json" not in names:
        raise BundleError("That is not a bundle from tools/import_survey_book.py: it has no manifest.json.")
    try:
        raw = bundle.read("manifest.json", MAX_MANIFEST_BYTES)
    except Damaged:
        raise BundleError("The bundle is damaged: its manifest.json cannot be read. Write the bundle again with the importer.") from None
    if raw is None:
        raise BundleError("The bundle's manifest is too large to be one book.")
    try:
        manifest = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, ValueError, RecursionError):
        raise BundleError("The bundle's manifest is not JSON.") from None
    if not isinstance(manifest, dict) or manifest.get("format") != FORMAT:
        raise BundleError("That is not a bundle from tools/import_survey_book.py.")
    if manifest.get("version") != VERSION:
        raise BundleError("This bundle was written by another version of the importer. Run the importer in this release again.")
    surveys = manifest.get("surveys")
    if not isinstance(surveys, list) or not surveys:
        raise BundleError("The bundle has no pages.")
    if len(surveys) > MAX_SURVEYS:
        raise BundleError(f"The bundle has more than {MAX_SURVEYS} pages: import the book in parts (--pages).")
    source = manifest.get("source")
    source = "".join(c for c in source if ord(c) >= 32).strip()[:MAX_SOURCE] if isinstance(source, str) else ""
    bundle.source = source
    bundle.pages = sorted((_page(entry, index, names) for index, entry in enumerate(surveys)), key=lambda p: p["page"])


def photo_words(kind):
    return {"photo_north": "north photo", "photo_approach": "best approach photo"}.get(kind, "photo")


# -- Where the LZ is --------------------------------------------------------------------------------------------------

def latlon_of_grid(text):
    """The book's grid ("16S GD 6341 3199") as (lat, lon), the centre of the square it names, or None."""
    if not isinstance(text, str) or not text.strip():
        return None
    compact = re.sub(r"\s+", "", text).upper()
    if not re.match(r"^[0-9]{1,2}[C-HJ-NP-X][A-HJ-NP-Z]{2}[0-9]{2,10}$", compact):
        return None
    try:
        point = pg_mgrs.parseMGRS(compact).toLatLon()
    except Exception:  # noqa: BLE001 - PyGeodesy's MGRSError and the odd ValueError alike: the grid is not one
        return None
    lat, lon = float(point.lat), float(point.lon)
    return (lat, lon) if math.isfinite(lat) and math.isfinite(lon) else None


def latlon_of_text(text):
    """The book's lat/long as (lat, lon), or None: read as the editor reads one (survey/compare.py), in degrees and decimal minutes
    ("N 34 35.579 W084 07.402"), degrees, minutes and seconds, or decimal degrees, the hemisphere letters before or after."""
    return compare.parse_lat_long(text)


def distance_m(a, b):
    lat1, lon1, lat2, lon2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2
    return 2 * EARTH_RADIUS_M * math.asin(min(1.0, math.sqrt(h)))


def _metres_words(metres):
    return f"{metres / 1000:.1f} km" if metres >= 1000 else f"{int(round(metres))} m"


def place_of(field):
    """Where the LZ's target goes, and what to say about it: (lat, lon) or None, and a note or None. The grid is the LZ's (§2); its
    lat/long is checked against it and used only where there is no grid to read."""
    grid = latlon_of_grid(field.get("grid"))
    latlon = latlon_of_text(field.get("latLong"))
    if grid is not None:
        if latlon is not None:
            apart = distance_m(grid, latlon)
            if apart > GRID_AGREES_M:
                return grid, (f"The book's grid and lat/long are {_metres_words(apart)} apart: the target is at the grid. "
                              "Check which is right.")
        elif (field.get("latLong") or "").strip():
            return grid, "The book's lat/long could not be read, so it was not checked against the grid: check it."
        return grid, None
    if latlon is not None:
        if field.get("grid", "").strip():
            return latlon, "The book's grid could not be read: the target is at its lat/long."
        return latlon, "The book has no grid: the target is at its lat/long."
    return None, "Neither the book's grid nor its lat/long could be read: set the target on the map."


def _now_iso():
    return datetime.utcnow().replace(microsecond=0).isoformat() + "Z"


def diagram_at(lat, lon, name):
    """A new LZ/PZ diagram with its target at (lat, lon): the web's schema-2 document, as createLzDiagramFromTarget makes it and
    serializeLzDiagram saves it (frontend/src/feature/lzWorkspace/useLzWorkspace.js). No doghouses yet: the editor makes the standard
    two and turns the landing one to the best approach when it next saves (surveyDiagram.withApproach), and the page reads the
    approach from the field."""
    grid = measure.grid_text(lat, lon)
    now = _now_iso()
    return {
        "schemaVersion": 2,
        "id": str(uuid_lib.uuid4()),
        "savedId": None,
        "name": name,
        "dirty": False,
        "createdAt": now,
        "updatedAt": now,
        "status": "targeted",
        "target": {"lat": lat, "lon": lon, "mgrs": grid},
        "mapData": {"mgrs": grid},
        "flightData": {},
        "analysis": {"customLZ": None, "detectedLZ": None, "terrainData": None, "results": None, "gridElevation": "", "latLong": ""},
        "graphics": {"doghouses": [], "helicopters": [], "pzMarkers": [], "sectorsOfFire": [], "goArounds": [], "units": [],
                     "measurements": [], "exportBox": None},
        "view": {"mapStyle": "satellite", "showLZOutline": True, "showHeatmap": False},
    }


def with_boundary(diagram, ring):
    """The diagram with ``ring`` as its measured boundary, as surveyDiagram.withBoundary sets one the model found."""
    analysis = {**(diagram.get("analysis") or {}), "detectedLZ": ring, "customLZ": None}
    return {**diagram, "analysis": analysis, "status": "analyzed", "updatedAt": _now_iso()}


# -- Measuring in the background --------------------------------------------------------------------------------------

def _sam_boundary(lat, lon):
    # Imported here: the model loads with the terrain routes, which the app has already imported, and which tests never need.
    from routes.terrain_routes import ImageryUnavailable, find_lz_boundary
    try:
        return find_lz_boundary(lat, lon)
    except ImageryUnavailable:
        raise RuntimeError("The imagery the model looks at could not be fetched. Find the boundary in the editor later.") from None


def _settle(record, state, reason=None):
    record.state = state
    record.reason = (reason or None) and reason[:300]
    db.session.commit()


def _write(survey, seen_revision, values):
    """Writes ``values`` to the survey and moves its revision on, only if it is still at ``seen_revision``: an editor's save in
    between wins, and is not undone."""
    count = (LzSurvey.query.filter_by(id=survey.id, revision=seen_revision)
             .update({**values, "revision": seen_revision + 1}, synchronize_session=False))
    db.session.commit()
    db.session.expire(survey)
    return count == 1


def measure_imported(survey_id, find_boundary):
    """Finds the boundary of one imported survey and measures it, as far as it can go, saying how it stands on its import record."""
    record = LzSurveyImport.query.filter_by(survey_id=survey_id).first()
    survey = db.session.get(LzSurvey, survey_id)
    if record is None or survey is None or record.state not in ACTIVE:
        return
    if survey.archived_at is not None:
        return _settle(record, "changed", "Archived before it was measured.")
    if survey.snapshot is not None:
        return _settle(record, "done")
    target = measure.target_of(survey.diagram)
    if target is None:
        return _settle(record, "no_target", "Set the LZ's target on the map, then measure it.")
    by = record.imported_by_name or ""
    with terrain_tiles.analysis_running():
        if measure.boundary_of(survey.diagram) is None:
            seen = survey.revision
            _settle(record, "finding")
            try:
                ring = find_boundary(*target)
            except RuntimeError as problem:
                return _settle(record, "failed", str(problem))
            if not ring or len(ring) < 3:
                return _settle(record, "no_boundary", "The model found no boundary at the grid: draw it on the map, then measure.")
            diagram = with_boundary(survey.diagram, [[float(lat), float(lon)] for lat, lon in ring])
            if not _write(survey, seen, {"diagram": diagram, "updated_by": record.imported_by, "updated_by_name": WRITER}):
                return _settle(record, "changed", "Saved by an editor while its boundary was being found: measure it in the editor.")
        seen = survey.revision
        diagram, field = survey.diagram, survey.field
        _settle(record, "measuring")
        try:
            snapshot = measure.measure(diagram, field, by)
        except measure.CannotMeasure as problem:
            return _settle(record, "failed", str(problem))
    if not _write(survey, seen, {"snapshot": snapshot, "updated_by": record.imported_by, "updated_by_name": WRITER}):
        return _settle(record, "changed", "Saved by an editor while it was being measured: measure it in the editor.")
    _settle(record, "done")


class Measurer:
    """One thread measuring imported surveys one at a time, in the order they were imported. Nothing is lost when the server
    restarts: the next look at the book (``resume``) queues again what was waiting. Nor when the database goes away mid-survey: that
    survey is let go, still waiting, for the next look to queue again, and the thread goes on (or a new one starts)."""

    def __init__(self, find_boundary=None):
        self.find_boundary = find_boundary or _sam_boundary
        # Tests: queued surveys wait for run_pending(), in the test's own thread, instead of a thread of their own.
        self.inline = False
        self._queue = collections.deque()
        self._known = set()
        self._lock = threading.Lock()
        self._thread = None

    def enqueue(self, app, survey_ids):
        """Queues what is not queued yet, and starts the thread if there is work and none is running."""
        with self._lock:
            for survey_id in survey_ids:
                if survey_id not in self._known:
                    self._known.add(survey_id)
                    self._queue.append(survey_id)
            if self.inline or not self._queue or (self._thread is not None and self._thread.is_alive()):
                return
            self._thread = threading.Thread(target=self._work, args=(app,), name="survey-import", daemon=True)
            self._thread.start()

    def knows(self, survey_id):
        with self._lock:
            return survey_id in self._known

    def _next(self):
        with self._lock:
            if not self._queue:
                self._thread = None
                return None
            return self._queue.popleft()

    def _one(self, app, survey_id):
        try:
            with app.app_context():
                try:
                    measure_imported(survey_id, self.find_boundary)
                except Exception:  # noqa: BLE001 - one survey's fault must not stop the rest
                    log.exception("surveys: measuring imported survey %s failed", survey_id)
                    db.session.rollback()
                    record = LzSurveyImport.query.filter_by(survey_id=survey_id).first()
                    if record is not None and record.state in ACTIVE:
                        _settle(record, "failed", "It could not be measured here: measure it in the editor.")
        except Exception:  # noqa: BLE001 - the database itself is away: the survey stays waiting, for the next look to queue
            log.exception("surveys: imported survey %s could not be settled", survey_id)
        finally:
            with self._lock:
                self._known.discard(survey_id)

    def _work(self, app):
        try:
            while True:
                survey_id = self._next()
                if survey_id is None:
                    return
                self._one(app, survey_id)
        finally:
            with self._lock:
                if self._thread is threading.current_thread():
                    self._thread = None

    def run_pending(self, app):
        """Measures what is queued, in the caller's thread (tests)."""
        while True:
            survey_id = self._next()
            if survey_id is None:
                return
            self._one(app, survey_id)

    def resume(self, app, records):
        """Queues again the records still waiting that nothing is measuring (after a restart, their thread is gone), and starts a
        thread for what is queued if none is running."""
        self.enqueue(app, [r.survey_id for r in records if r.state in ACTIVE and not self.knows(r.survey_id)])


MEASURER = Measurer()
