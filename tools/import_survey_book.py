#!/usr/bin/env python3
"""Read the unit's old HLZ survey book (a PDF, one LZ a page) into a bundle EZ/PZ's survey books import (docs/LZ_SURVEYS.md §6).

Run once, on a machine with poppler (``brew install poppler``; on Linux ``apt install poppler-utils``)::

    python3 tools/import_survey_book.py "LZ-SURVEYS-OCT-2023.pdf" book-bundle.zip

Then, in EZ/PZ, open the book in LZ survey books and choose *Import*. Nothing here talks to EZ/PZ or anything else, and nothing but
the standard library and poppler's ``pdftohtml`` is needed: no PDF library joins the backend.

Each page of the old book is a form: the labelled values down its left column (Grid, Lat/Long, Max A/C, Best Approach and Exit,
Elevation, Surface Condition, Slope, Maximum Weight, PPR, Hazards, Remarks, Warning), the survey profile's box (an obstacle's height
at a distance), the two photos down its right (north, then down the best approach) and its foot (Survey Date, Last Survey by).
``pdftohtml -xml`` gives every piece of text and every picture with its place on the page, so each line is rebuilt from the pieces
on it ("OCT 202" and "3", "5" and a raised "th"), and each value is the text after its label up to the next label. What the page
does not say is left blank and named in the bundle (``unread``), for the importing editor to look at: nothing is made up.

The bundle is a zip: ``manifest.json`` (format ``ezpz-survey-import``) and the photos as poppler took them out of the PDF.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

FORMAT = "ezpz-survey-import"
VERSION = 1

# The left column's labels, in the order the form has them, and the field each fills (backend/survey/fields.py's keys).
LABELS = (
    ("grid", re.compile(r"^Grid\s*:", re.I)),
    ("latLong", re.compile(r"^Lat\s*/\s*Long\s*:", re.I)),
    ("maxAc", re.compile(r"^Max\s*A/C\s*:", re.I)),
    ("size", re.compile(r"^Size\s*:", re.I)),
    ("approachDeg", re.compile(r"^Best\s+Approach\s*:", re.I)),
    ("exitDeg", re.compile(r"^Best\s+Exit\s*:", re.I)),
    ("elevationFt", re.compile(r"^Elevation\b\s*:?", re.I)),
    ("surface", re.compile(r"^Surface\s+Condition\s*:", re.I)),
    ("slope", re.compile(r"^Slope\s*:", re.I)),
    ("maxWeight", re.compile(r"^Maximum\s+Weight\s*(?:\(if applicable\))?\s*:?", re.I)),
    ("ppr", re.compile(r"^PPR\s+Required\?\s*(?:\(\s*If so,\s*contact information\s*\))?\s*", re.I)),
    ("hazards", re.compile(r"^Hazards\s*:", re.I)),
    ("remarks", re.compile(r"^Remarks\s*:", re.I)),
    ("warning", re.compile(r"^Warning\s*:", re.I)),
)
LABEL_WORDS = {
    "grid": "Grid", "latLong": "Lat/Long", "maxAc": "Max A/C", "size": "Size", "approachDeg": "Best Approach",
    "exitDeg": "Best Exit", "elevationFt": "Elevation", "surface": "Surface Condition", "slope": "Slope",
    "maxWeight": "Maximum Weight", "ppr": "PPR", "hazards": "Hazards", "remarks": "Remarks", "warning": "Warning",
}
# Values a page may leave blank without anything being missed: the book has these blank on many pages.
MAY_BE_BLANK = {"size", "maxWeight", "remarks", "warning", "hazards", "maxAcNight"}
PROFILE_TITLE = re.compile(r"HLZ\s+Survey\s+Profile", re.I)
# Two photos of a page are its biggest pictures; the form's own small images (arrows, a helicopter) are far smaller.
MIN_PHOTO_AREA = 40_000


class BookError(RuntimeError):
    """A book that cannot be read, said in words for the person running this."""


# -- Lines from pieces --------------------------------------------------------------------------------------------

def _pieces(page):
    """Every piece of text on a page: its words, left, top, right, bottom and font size."""
    sizes = {}
    for spec in page.iter("fontspec"):
        try:
            sizes[spec.get("id")] = float(spec.get("size") or 0)
        except ValueError:
            pass
    out = []
    for node in page.iter("text"):
        words = "".join(node.itertext())
        if not words.strip():
            continue
        try:
            left, top = float(node.get("left")), float(node.get("top"))
            width, height = float(node.get("width")), float(node.get("height"))
        except (TypeError, ValueError):
            continue
        out.append({"words": words, "left": left, "top": top, "right": left + width, "bottom": top + height,
                    "size": sizes.get(node.get("font"), height)})
    return out


def _join(pieces):
    """The pieces of one line, left to right, as its words: a gap of a few points is a space, none is none (a superscript "th"
    after "5" is "5th"; "OCT 202" before "3" in another font is "OCT 2023")."""
    text = ""
    last_right = None
    for piece in sorted(pieces, key=lambda p: p["left"]):
        words = piece["words"]
        if last_right is not None and piece["left"] - last_right > 2.5 and not text.endswith(" ") and not words.startswith(" "):
            text += " "
        text += words
        last_right = piece["right"]
    return re.sub(r"\s+", " ", text).strip()


def _height(piece):
    return piece["bottom"] - piece["top"]


def _raised_onto(line, lines):
    """The line a line of raised pieces belongs to: a superscript ("th" after a "5", on one page a point above it) starts where a
    taller piece below it ends, and reaches down to that piece's top."""
    first = min(line["pieces"], key=lambda p: p["left"])
    tallest = max(_height(p) for p in line["pieces"])
    for other in lines:
        if other is line:
            continue
        for base in other["pieces"]:
            if (abs(first["left"] - base["right"]) <= 2.5 and first["top"] < base["top"] and first["bottom"] >= base["top"] - 2
                    and tallest < 0.85 * _height(base)):
                return other
    return None


def lines_of(pieces):
    """Pieces grouped into lines: those whose heights overlap, and a raised piece with the line it is raised from."""
    lines = []
    for piece in sorted(pieces, key=lambda p: (p["top"], p["left"])):
        for line in lines:
            if piece["top"] < line["bottom"] - 1 and piece["bottom"] > line["top"] + 1:
                line["pieces"].append(piece)
                line["top"], line["bottom"] = min(line["top"], piece["top"]), max(line["bottom"], piece["bottom"])
                break
        else:
            lines.append({"pieces": [piece], "top": piece["top"], "bottom": piece["bottom"]})
    for line in list(lines):
        onto = _raised_onto(line, lines)
        if onto is not None:
            onto["pieces"].extend(line["pieces"])
            onto["top"], onto["bottom"] = min(onto["top"], line["top"]), max(onto["bottom"], line["bottom"])
            lines.remove(line)
    for line in lines:
        line["left"] = min(p["left"] for p in line["pieces"])
        line["text"] = _join(line["pieces"])
    return sorted(lines, key=lambda l: (l["top"], l["left"]))


# -- One page ---------------------------------------------------------------------------------------------------------

def _value_below(pieces, label):
    """The words just below a header label ("HLZ Name:" over "DONOVAN")."""
    found = next((p for p in pieces if re.match(label, p["words"].strip(), re.I)), None)
    if found is None:
        return ""
    below = [p for p in pieces if found["bottom"] - 2 <= p["top"] <= found["bottom"] + 30
             and p["left"] < found["right"] + 40 and p["right"] > found["left"] - 40 and p is not found]
    return _join(below)


def _clean(text):
    return re.sub(r"\s+", " ", text or "").strip().lstrip(":").strip()


def _number(text):
    match = re.search(r"-?\d+(?:\.\d+)?", text or "")
    return match.group() if match else None


def read_page(page, number):
    """One page of the book as a survey: {page, hlzId, name, field, photos (pictures in the XML's order, top to bottom, with their
    kind and heading), unread, warnings}. ``page`` is pdftohtml's <page> element."""
    pieces = _pieces(page)
    width = float(page.get("width") or 918)
    images = []
    for node in page.iter("image"):
        try:
            box = {key: float(node.get(key)) for key in ("left", "top", "width", "height")}
        except (TypeError, ValueError):
            continue
        if box["width"] * box["height"] >= MIN_PHOTO_AREA:
            images.append({**box, "src": node.get("src")})
    images.sort(key=lambda b: b["top"])
    # The left column ends where the photos begin.
    split = min((b["left"] for b in images), default=width * 0.37) - 8

    name = _value_below(pieces, r"^HLZ\s+Name\s*:")
    hlz_id = _value_below(pieces, r"^HLZ\s+ID\s*:")
    lines = lines_of(pieces)
    footer = next((l for l in lines if re.search(r"Survey\s+Date\s*:", l["text"], re.I)), None)
    profile_top = next((l["top"] for l in lines if PROFILE_TITLE.search(l["text"])), None)
    reference = next((l["top"] for l in lines if re.match(r"^Reference\b", l["text"], re.I)), 0)

    # The labelled values: the left column's lines from "Reference" down to the survey profile's box.
    left = lines_of([p for p in pieces if p["right"] <= split + 4 and p["top"] >= reference
                     and (profile_top is None or p["top"] < profile_top - 4)
                     and (footer is None or p["top"] < footer["top"] - 4)])
    values, current = {}, None
    for line in left:
        for key, pattern in LABELS:
            match = pattern.match(line["text"])
            if match:
                current = key
                values[key] = [line["text"][match.end():]]
                break
        else:
            if current:
                values[current].append(line["text"])

    # ``unread``: a label the page does not have; ``blank``: one it has with nothing after it. Both are the importing editor's to look at.
    field, unread, blank, warnings = {}, [], [], []
    for key, _pattern in LABELS:
        if key not in values:
            if key not in MAY_BE_BLANK:
                unread.append(LABEL_WORDS[key])
            continue
        field[key] = _clean(" ".join(values[key]))

    max_ac = field.pop("maxAc", "")
    match = re.search(r"(\d+)\s*(?:day|d)\b\s*(?:/\s*(\d+)\s*(?:night|n)\b)?", max_ac, re.I)
    if match:
        field["maxAcDay"], field["maxAcNight"] = match.group(1), match.group(2) or ""
        if not match.group(2):
            warnings.append(f'Max A/C is "{max_ac}": no night value.')
    elif max_ac:
        field["maxAcDay"] = max_ac
        warnings.append(f'Max A/C is "{max_ac}", not a number by day and night: kept as written.')
    for key in ("approachDeg", "exitDeg"):
        if field.get(key):
            field[key] = re.sub(r"\s+", " ", field[key].replace("°", "").replace("º", "")).strip()
    if field.get("elevationFt"):
        feet = _number(field["elevationFt"])
        if feet is not None:
            field["elevationFt"] = feet
    for key in list(field):
        if not field[key] and key not in MAY_BE_BLANK:
            blank.append(LABEL_WORDS.get(key, key))
    if "maxAcDay" not in field and "maxAc" not in values:
        pass
    elif not field.get("maxAcDay"):
        blank.append("Max A/C")

    # The survey profile's box, read by where its numbers are: the obstacle's height up its right edge (beside "Feet"), the distance
    # along its foot (beside "Meters"). A mark after the distance ("75’ Meters", on some pages) is not taken for a height.
    if profile_top is not None:
        box = [p for p in pieces if p["top"] >= profile_top - 2 and p["right"] <= split + 40
               and (footer is None or p["top"] < footer["top"] - 4)]
        meters = next((p for p in box if re.search(r"meters?|metres?", p["words"], re.I)), None)
        foot = [p for p in box if meters is not None and p["top"] < meters["bottom"] and p["bottom"] > meters["top"]]
        distance = _number(_join(foot)) if foot else None
        right_edge = max((p["right"] for p in box), default=0) - 60
        # Its digits come in pieces ("8" and a raised "0’"): read it a line at a time, the first line with a number.
        edge_lines = lines_of([p for p in box if p["left"] >= right_edge and p not in foot])
        height = next((_number(line["text"]) for line in edge_lines if _number(line["text"])), None)
        field["profileObstacleFt"] = height or ""
        field["profileDistanceM"] = distance or ""
        if not height or not distance:
            blank.append("Survey profile")

    if footer is not None:
        match = re.search(r"Survey\s+Date\s*:\s*(.*?)\s*(?:Last\s+Survey\s+by\s*:\s*(.*))?$", footer["text"], re.I)
        if match:
            field["surveyedOn"] = _clean(match.group(1))
            by = _clean(match.group(2) or "")
            if "," in by:
                unit, person = by.split(",", 1)
                field["surveyUnit"], field["surveyedBy"] = unit.strip(), person.strip()
            elif by:
                field["surveyedBy"] = by
    for key, words in (("surveyedOn", "Survey Date"), ("surveyedBy", "Last Survey by")):
        if not field.get(key):
            unread.append(words)
    field.pop("maxAc", None)

    view = next((l["text"] for l in lines if re.match(r"^View\s+\d", l["text"], re.I)), "")
    view_heading = _number(view)
    photos = []
    for index, box in enumerate(images[:2]):
        kind = "photo_north" if index == 0 else "photo_approach"
        heading = 0.0 if kind == "photo_north" else (float(view_heading) if view_heading else None)
        if heading is None and field.get("approachDeg") and _number(field["approachDeg"]):
            heading = float(_number(field["approachDeg"]))
        photos.append({"src": box["src"], "kind": kind, "heading_deg": heading})
    if len(images) < 2:
        warnings.append(f"{len(images)} photo{'s' if len(images) != 1 else ''} on the page, where the book has two.")

    if not name:
        unread.append("HLZ Name")
    clean_name = _clean(name).upper()
    if clean_name and not re.match(r"^(LZ|PZ|HLZ)\b", clean_name):
        clean_name = f"LZ {clean_name}"
    return {
        "page": number,
        "hlzId": _clean(hlz_id).upper(),
        "name": clean_name,
        "field": field,
        "photos": photos,
        "unread": sorted(set(unread), key=unread.index),
        "blank": sorted(set(blank), key=blank.index),
        "warnings": warnings,
    }


# -- The book ----------------------------------------------------------------------------------------------------------

def _pdftohtml():
    found = shutil.which("pdftohtml")
    if not found:
        raise BookError("pdftohtml is not installed. Install poppler: brew install poppler (macOS) or apt install poppler-utils.")
    return found


def read_book(pdf, workdir, pages=None):
    """Every page of the book: (surveys, {photo name in the bundle: its path})."""
    binary = _pdftohtml()
    xml_stem = Path(workdir) / "book"
    command = [binary, "-xml", "-q", "-nodrm", str(pdf), str(xml_stem)]
    if pages:
        command[1:1] = ["-f", str(pages[0]), "-l", str(pages[1])]
    result = subprocess.run(command, capture_output=True, text=True, timeout=600)
    xml_path = xml_stem.with_suffix(".xml")
    if result.returncode != 0 or not xml_path.exists():
        raise BookError(f"pdftohtml could not read {pdf}: {result.stderr.strip() or result.returncode}")
    try:
        root = ET.parse(xml_path).getroot()
    except ET.ParseError as error:
        raise BookError(f"pdftohtml's output could not be read: {error}") from None
    surveys, files = [], {}
    for page in root.iter("page"):
        number = int(page.get("number") or 0)
        survey = read_page(page, number)
        kept = []
        for photo in survey["photos"]:
            source = Path(workdir) / (photo["src"] or "")
            if not photo["src"] or not source.is_file():
                survey["warnings"].append("A photo could not be taken out of the PDF.")
                continue
            name = f"photos/p{number:03d}-{'north' if photo['kind'] == 'photo_north' else 'approach'}{source.suffix.lower()}"
            files[name] = source
            kept.append({"file": name, "kind": photo["kind"], "heading_deg": photo["heading_deg"],
                         "taken_on": survey["field"].get("surveyedOn", "")})
        survey["photos"] = kept
        surveys.append(survey)
    if not surveys:
        raise BookError(f"{pdf} has no pages that could be read.")
    return surveys, files


def write_bundle(surveys, files, out, source_name):
    manifest = {"format": FORMAT, "version": VERSION, "source": source_name, "surveys": surveys}
    with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED) as bundle:
        bundle.writestr("manifest.json", json.dumps(manifest, indent=1, ensure_ascii=False))
        for name, path in files.items():
            bundle.write(path, name)


def main(argv=None):
    parser = argparse.ArgumentParser(description="Read an HLZ survey book PDF into a bundle for EZ/PZ's Import.")
    parser.add_argument("pdf", type=Path, help="the book, a PDF with one LZ a page")
    parser.add_argument("bundle", type=Path, help="the zip to write, e.g. book-bundle.zip")
    parser.add_argument("--pages", nargs=2, type=int, metavar=("FIRST", "LAST"), help="only these pages")
    args = parser.parse_args(argv)
    if not args.pdf.is_file():
        print(f"There is no file {args.pdf}.", file=sys.stderr)
        return 2
    with tempfile.TemporaryDirectory() as workdir:
        try:
            surveys, files = read_book(args.pdf, workdir, args.pages)
        except BookError as error:
            print(error, file=sys.stderr)
            return 1
        write_bundle(surveys, files, args.bundle, args.pdf.name)
    print(f"{len(surveys)} pages read into {args.bundle}.")
    for survey in surveys:
        notes = ([f"not on the page: {', '.join(survey['unread'])}"] if survey["unread"] else []) \
            + ([f"blank: {', '.join(survey['blank'])}"] if survey["blank"] else []) + survey["warnings"]
        if notes:
            print(f"  page {survey['page']} {survey['hlzId'] or '?'} {survey['name'] or '?'}: " + "; ".join(notes))
    return 0


if __name__ == "__main__":
    sys.exit(main())
