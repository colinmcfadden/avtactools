"""The API version: what it is, which clients it works with, and whether a change to the spec breaks them.

The API the clients call is described in `contracts/openapi.yaml`, and its `info.version` is the **API
version**, apart from the product's (web and backend) and each app's own. Its major is the promise to
installed apps: within one major nothing they read or send stops working. So:

- a **breaking** change to the spec (a route, field or status gone; a type changed; a value that may now
  be null; a new required input; an enum widened in a response, which a strict client cannot read) needs
  the major bumped;
- an **additive** one (a new route, an optional field, a new status) wants the minor bumped;
- and every client must support a major the server serves, or that client is broken.

Where each version lives:

- the spec's `info.version`, and `API_VERSION` in `backend/api_version.py`, which must agree; that file's
  `API_MAJORS_SERVED` lists the majors the server still answers (the current one, and any kept for apps
  not yet updated);
- each client's own build: `ezpz.apiMajors` in `android/gradle.properties`, `apiMajors` in
  `frontend/src/apiSupport.json` (iOS joins when it starts).

    python contracts/scripts/api_version.py check [--base REF]   # what CI runs; REF is the branch merged into
    python contracts/scripts/api_version.py bump  [--base REF]   # writes the bump the diff needs

`check` fails on a breaking change whose major was not bumped, on versions that disagree, and on a client
that supports no major the server serves. `bump` works out the version the diff calls for and writes it to
the spec and `backend/api_version.py` (and, on a new major, makes it the only one served); it changes no
client. Neither commits anything: a new major means installed apps will be told to update, which is a
decision to make, not one to have made for you.

Standard library and PyYAML (pinned in `backend/requirements.txt`).
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
SPEC = ROOT / "contracts" / "openapi.yaml"
SPEC_IN_GIT = "contracts/openapi.yaml"
BACKEND = ROOT / "backend" / "api_version.py"
ANDROID = ROOT / "android" / "gradle.properties"
WEB = ROOT / "frontend" / "src" / "apiSupport.json"

METHODS = ("get", "put", "post", "delete", "patch", "head", "options")
SEMVER = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")


# ---------------------------------------------------------------------------------------------------------
# Versions


def parse_version(text) -> tuple[int, int, int]:
    match = SEMVER.match(str(text).strip())
    if not match:
        raise ValueError(f"{text!r} is not a version (X.Y.Z)")
    return tuple(int(part) for part in match.groups())


def text_of(version: tuple[int, int, int]) -> str:
    return ".".join(str(part) for part in version)


def read_backend(path: Path = BACKEND) -> tuple[str, list[int]]:
    """`API_VERSION` and `API_MAJORS_SERVED`, read as text: the backend is not imported (it needs Flask)."""
    source = path.read_text(encoding="utf-8")
    version = re.search(r'^API_VERSION\s*=\s*"([^"]*)"', source, re.M)
    served = re.search(r"^API_MAJORS_SERVED\s*=\s*\(([^)]*)\)", source, re.M)
    if not version or not served:
        raise ValueError(f"{path}: API_VERSION and API_MAJORS_SERVED must be set as plain literals")
    return version.group(1), [int(n) for n in re.findall(r"\d+", served.group(1))]


def majors_list(text: str, where: str) -> list[int]:
    majors = [int(n) for n in re.split(r"[\s,]+", text.strip()) if n]
    if not majors:
        raise ValueError(f"{where}: no API majors listed")
    return majors


def read_clients(android: Path = ANDROID, web: Path = WEB) -> dict[str, list[int]]:
    """The API majors each client build says it supports. A client whose file is missing is left out."""
    clients = {}
    if android.exists():
        match = re.search(r"^ezpz\.apiMajors\s*=\s*(.+)$", android.read_text(encoding="utf-8"), re.M)
        if not match:
            raise ValueError(f"{android}: no ezpz.apiMajors")
        clients["android"] = majors_list(match.group(1), str(android))
    if web.exists():
        majors = json.loads(web.read_text(encoding="utf-8")).get("apiMajors")
        if not isinstance(majors, list) or not majors or not all(isinstance(n, int) for n in majors):
            raise ValueError(f"{web}: apiMajors must be a non-empty list of whole numbers")
        clients["web"] = majors
    return clients


# ---------------------------------------------------------------------------------------------------------
# The diff


@dataclass
class Report:
    breaking: list[str] = field(default_factory=list)
    additive: list[str] = field(default_factory=list)

    def breaks(self, where: str, what: str):
        self.breaking.append(f"{where}: {what}")

    def adds(self, where: str, what: str):
        self.additive.append(f"{where}: {what}")


class Schemas:
    """Resolves `$ref`s and folds an `allOf` into one schema, so two specs compare by meaning, not by layout."""

    def __init__(self, spec: dict):
        self.spec = spec

    def ref(self, pointer: str):
        node = self.spec
        for part in pointer.lstrip("#/").split("/"):
            node = node[part.replace("~1", "/").replace("~0", "~")]
        return node

    def resolve(self, node):
        seen = set()
        while isinstance(node, dict) and "$ref" in node:
            pointer = node["$ref"]
            if pointer in seen:
                break
            seen.add(pointer)
            node = self.ref(pointer)
        if isinstance(node, dict) and "allOf" in node:
            merged = {key: value for key, value in node.items() if key != "allOf"}
            for part in node["allOf"]:
                part = self.resolve(part)
                for key, value in part.items():
                    if key == "properties":
                        merged["properties"] = {**part["properties"], **merged.get("properties", {})}
                    elif key == "required":
                        merged["required"] = sorted(set(merged.get("required", [])) | set(value))
                    else:
                        merged.setdefault(key, value)
            node = merged
        return node if isinstance(node, dict) else {}


def type_of(schema: dict):
    if "type" in schema:
        return schema["type"]
    if "properties" in schema:
        return "object"
    if "items" in schema:
        return "array"
    return None


def compare_schema(old_spec: Schemas, new_spec: Schemas, old, new, where: str, direction: str, report: Report, seen=None):
    """`direction` is `response` (the server writes, a client reads) or `request` (a client writes, the server reads)."""
    seen = set() if seen is None else seen
    key = (id(old), id(new))
    if key in seen:
        return
    seen.add(key)
    old, new = old_spec.resolve(old), new_spec.resolve(new)
    reading = direction == "response"

    old_type, new_type = type_of(old), type_of(new)
    if old_type and new_type and old_type != new_type:
        report.breaks(where, f"type changed from {old_type} to {new_type}")
        return
    if old_type is None and new_type is not None and not reading:
        report.breaks(where, f"now has to be {new_type}")

    old_null, new_null = bool(old.get("nullable")), bool(new.get("nullable"))
    if reading and new_null and not old_null:
        report.breaks(where, "may now be null")
    if not reading and old_null and not new_null:
        report.breaks(where, "may no longer be null")

    if old.get("format") != new.get("format") and (old.get("format") or new.get("format")):
        report.breaks(where, f"format changed from {old.get('format')} to {new.get('format')}")
    if old.get("pattern") != new.get("pattern") and (old.get("pattern") or new.get("pattern")):
        report.breaks(where, f"pattern changed from {old.get('pattern')!r} to {new.get('pattern')!r}")

    old_enum, new_enum = old.get("enum"), new.get("enum")
    if old_enum is not None or new_enum is not None:
        before, after = set(map(str, old_enum or [])), set(map(str, new_enum or []))
        if old_enum is None:
            if reading:
                report.adds(where, f"now one of {sorted(after)}")
            else:
                report.breaks(where, f"now has to be one of {sorted(after)}")
        elif new_enum is None:
            if reading:
                report.breaks(where, f"no longer limited to {sorted(before)}")
            else:
                report.adds(where, f"no longer limited to {sorted(before)}")
        else:
            gone, came = sorted(before - after), sorted(after - before)
            if reading:
                if came:
                    report.breaks(where, f"may now be {came} (a client that knows only {sorted(before)} cannot read it)")
                if gone:
                    report.adds(where, f"no longer sent as {gone}")
            else:
                if gone:
                    report.breaks(where, f"no longer accepts {gone}")
                if came:
                    report.adds(where, f"now also accepts {came}")

    # Limits: in a request, a tighter limit refuses what an old client sends; in a response, a looser one sends what it never expected.
    for name, tighter_is_larger in (("minimum", True), ("minLength", True), ("minItems", True),
                                    ("maximum", False), ("maxLength", False), ("maxItems", False)):
        before, after = old.get(name), new.get(name)
        if before == after:
            continue
        if before is None:
            tighter = True
        elif after is None:
            tighter = False
        else:
            tighter = (after > before) if tighter_is_larger else (after < before)
        if tighter == (not reading):
            report.breaks(where, f"{name} changed from {before} to {after}")
        else:
            report.adds(where, f"{name} changed from {before} to {after}")

    old_props, new_props = old.get("properties") or {}, new.get("properties") or {}
    old_required, new_required = set(old.get("required") or []), set(new.get("required") or [])
    for name in sorted(set(old_props) | set(new_props)):
        here = f"{where}.{name}"
        if name not in new_props:
            if reading:
                report.breaks(here, "removed")
            elif new.get("additionalProperties") is False:
                report.breaks(here, "no longer accepted")
            else:
                report.adds(here, "no longer read")
            continue
        if name not in old_props:
            if not reading and name in new_required:
                report.breaks(here, "added, and required")
            else:
                report.adds(here, "added")
            continue
        if reading and name in old_required and name not in new_required:
            report.breaks(here, "no longer always sent")
        if not reading and name in new_required and name not in old_required:
            report.breaks(here, "now required")
        compare_schema(old_spec, new_spec, old_props[name], new_props[name], here, direction, report, seen)

    if "items" in old or "items" in new:
        compare_schema(old_spec, new_spec, old.get("items") or {}, new.get("items") or {}, f"{where}[]", direction, report, seen)

    old_extra, new_extra = old.get("additionalProperties"), new.get("additionalProperties")
    if isinstance(old_extra, dict) and isinstance(new_extra, dict):
        compare_schema(old_spec, new_spec, old_extra, new_extra, f"{where}{{*}}", direction, report, seen)


def parameters_of(spec: Schemas, path_item: dict, operation: dict) -> dict:
    params = {}
    for raw in (path_item.get("parameters") or []) + (operation.get("parameters") or []):
        param = spec.resolve(raw)
        params[(param.get("in"), str(param.get("name")).lower() if param.get("in") == "header" else param.get("name"))] = param
    return params


def compare_operation(old_spec: Schemas, new_spec: Schemas, old_item, new_item, old_op, new_op, where: str, report: Report):
    old_secure = bool(old_op.get("security", old_spec.spec.get("security")))
    new_secure = bool(new_op.get("security", new_spec.spec.get("security")))
    if new_secure and not old_secure:
        report.breaks(where, "now needs a token")

    old_params, new_params = parameters_of(old_spec, old_item, old_op), parameters_of(new_spec, new_item, new_op)
    for key in sorted(set(old_params) | set(new_params), key=str):
        place, name = key
        here = f"{where} {place} parameter {name}"
        before, after = old_params.get(key), new_params.get(key)
        if after is None:
            if before.get("required"):
                report.breaks(here, "removed")
            else:
                report.adds(here, "no longer read")
        elif before is None:
            if after.get("required"):
                report.breaks(here, "added, and required")
            else:
                report.adds(here, "added")
        else:
            if after.get("required") and not before.get("required"):
                report.breaks(here, "now required")
            compare_schema(old_spec, new_spec, before.get("schema") or {}, after.get("schema") or {}, here, "request", report)

    old_body, new_body = old_spec.resolve(old_op.get("requestBody") or {}), new_spec.resolve(new_op.get("requestBody") or {})
    if new_body and new_body.get("required") and not (old_body and old_body.get("required")):
        report.breaks(f"{where} body", "now required")
    old_media, new_media = old_body.get("content") or {}, new_body.get("content") or {}
    for media in sorted(set(old_media) | set(new_media)):
        here = f"{where} body ({media})"
        if media not in new_media:
            report.breaks(here, "no longer accepted")
        elif media not in old_media:
            report.adds(here, "now accepted")
        else:
            compare_schema(old_spec, new_spec, old_media[media].get("schema") or {}, new_media[media].get("schema") or {},
                           f"{where} body", "request", report)

    old_responses, new_responses = old_op.get("responses") or {}, new_op.get("responses") or {}
    for status in sorted(set(old_responses) | set(new_responses), key=str):
        here = f"{where} → {status}"
        if status not in new_responses:
            report.breaks(here, "no longer answered")
            continue
        if status not in old_responses:
            report.adds(here, "now answered")
            continue
        before, after = old_spec.resolve(old_responses[status]), new_spec.resolve(new_responses[status])
        old_content, new_content = before.get("content") or {}, after.get("content") or {}
        for media in sorted(set(old_content) | set(new_content)):
            if media not in new_content:
                report.breaks(f"{here} ({media})", "no longer sent")
            elif media not in old_content:
                report.adds(f"{here} ({media})", "now sent")
            else:
                compare_schema(old_spec, new_spec, old_content[media].get("schema") or {}, new_content[media].get("schema") or {},
                               f"{here} body", "response", report)
        for header in sorted(set(before.get("headers") or {}) - set(after.get("headers") or {})):
            report.breaks(f"{here} header {header}", "no longer sent")


def diff(old: dict, new: dict) -> Report:
    """What changed for the clients between two specs, classified."""
    report = Report()
    old_spec, new_spec = Schemas(old), Schemas(new)
    old_paths, new_paths = old.get("paths") or {}, new.get("paths") or {}
    for path in sorted(set(old_paths) | set(new_paths)):
        old_item, new_item = old_paths.get(path) or {}, new_paths.get(path) or {}
        for method in METHODS:
            old_op, new_op = old_item.get(method), new_item.get(method)
            where = f"{method.upper()} {path}"
            if old_op is None and new_op is None:
                continue
            if new_op is None:
                report.breaks(where, "removed")
            elif old_op is None:
                report.adds(where, "added")
            else:
                compare_operation(old_spec, new_spec, old_item, new_item, old_op, new_op, where, report)
    return report


def required_bump(base: tuple[int, int, int], report: Report) -> tuple[int, int, int]:
    """The least version the diff calls for, from the base's."""
    major, minor, patch = base
    if report.breaking:
        return (major + 1, 0, 0)
    if report.additive:
        return (major, minor + 1, 0)
    return base


# ---------------------------------------------------------------------------------------------------------
# The commands


def load_spec(text: str) -> dict:
    return yaml.safe_load(text)


def spec_at(ref: str) -> dict | None:
    """The spec as it is at `ref`, or None where it did not exist yet (or `ref` is unknown)."""
    try:
        text = subprocess.run(["git", "show", f"{ref}:{SPEC_IN_GIT}"], cwd=ROOT, check=True,
                              capture_output=True, text=True).stdout
    except (subprocess.CalledProcessError, FileNotFoundError):
        return None
    return load_spec(text)


def problems(spec: dict, base: dict | None, backend: tuple[str, list[int]], clients: dict[str, list[int]]) -> tuple[list[str], list[str], Report | None]:
    """What `check` reports: errors (it fails), notes (it does not), and the diff, if there was a base."""
    errors, notes = [], []
    try:
        version = parse_version(spec["info"]["version"])
    except (KeyError, ValueError) as e:
        return [f"contracts/openapi.yaml info.version: {e}"], notes, None

    backend_version, served = backend
    if backend_version != text_of(version):
        errors.append(f"backend/api_version.py says API_VERSION = {backend_version!r}, the spec says {text_of(version)!r}: they must agree")
    if version[0] not in served:
        errors.append(f"backend/api_version.py serves majors {served}, which leaves out the current one ({version[0]})")
    if any(n > version[0] for n in served):
        errors.append(f"backend/api_version.py serves majors {served}, one above the current one ({version[0]})")

    for client, majors in sorted(clients.items()):
        if not set(majors) & set(served):
            errors.append(f"{client} supports API majors {majors}, and the server serves {served}: that build cannot talk to this server")
        elif version[0] not in majors:
            notes.append(f"{client} supports API majors {majors}: it still works through an older major the server keeps "
                         f"({sorted(set(majors) & set(served))}), not the current one ({version[0]})")

    report = None
    if base is not None:
        report = diff(base, spec)
        try:
            base_version = parse_version(base["info"]["version"])
        except (KeyError, ValueError):
            base_version = None
        if base_version is not None:
            needed = required_bump(base_version, report)
            if version < base_version:
                errors.append(f"the API version went down, from {text_of(base_version)} to {text_of(version)}")
            elif report.breaking and version[0] <= base_version[0]:
                errors.append(
                    f"the spec has {len(report.breaking)} breaking change(s) for installed apps, and the API major is still {version[0]}. "
                    f"Keep the old behaviour, or make it API {text_of(needed)} (`python contracts/scripts/api_version.py bump`), which "
                    "tells every app that does not support that major to update."
                )
            elif report.additive and version == base_version:
                notes.append(f"the spec gained {len(report.additive)} addition(s) and the API version did not move: "
                             f"{text_of(needed)} would say so (`python contracts/scripts/api_version.py bump`)")
    return errors, notes, report


def print_report(report: Report | None):
    if report is None:
        return
    for title, items in (("Breaking", report.breaking), ("Additive", report.additive)):
        if items:
            print(f"{title} ({len(items)}):")
            for item in items:
                print(f"  - {item}")


def write_version(new: tuple[int, int, int], served: list[int], spec_path: Path = SPEC, backend_path: Path = BACKEND):
    """Writes `new` into the spec's `info.version` and the backend, keeping both files as they are otherwise."""
    spec_text = spec_path.read_text(encoding="utf-8")
    spec_text, count = re.subn(r"(?m)^(info:\n(?:[ \t]+.*\n)*?[ \t]+version:[ \t]*)\S+", rf"\g<1>{text_of(new)}", spec_text, count=1)
    if count != 1:
        raise ValueError(f"{spec_path}: could not find info.version")
    spec_path.write_text(spec_text, encoding="utf-8")

    source = backend_path.read_text(encoding="utf-8")
    source = re.sub(r'(?m)^API_VERSION\s*=\s*"[^"]*"', f'API_VERSION = "{text_of(new)}"', source, count=1)
    majors = ", ".join(str(n) for n in served) + ("," if len(served) == 1 else "")
    source = re.sub(r"(?m)^API_MAJORS_SERVED\s*=\s*\([^)]*\)", f"API_MAJORS_SERVED = ({majors})", source, count=1)
    backend_path.write_text(source, encoding="utf-8")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("command", choices=("check", "bump"))
    parser.add_argument("--base", help="the git ref the change is measured from (the branch it merges into)")
    args = parser.parse_args(argv)

    spec = load_spec(SPEC.read_text(encoding="utf-8"))
    base = spec_at(args.base) if args.base else None
    if args.base and base is None:
        print(f"note: no spec at {args.base}; nothing to compare with")
    backend = read_backend()
    clients = read_clients()

    if args.command == "bump":
        if base is None:
            print("bump needs --base, the ref to measure the change from")
            return 2
        report = diff(base, spec)
        print_report(report)
        current = parse_version(spec["info"]["version"])
        needed = max(required_bump(parse_version(base["info"]["version"]), report), current)
        served = backend[1]
        if needed[0] > max(served):
            served = [needed[0]]
        write_version(needed, served)
        print(f"API version {text_of(needed)}, majors served {served}")
        for client, majors in sorted(clients.items()):
            if not set(majors) & set(served):
                print(f"  {client} supports {majors}: installed {client} builds will be told to update; "
                      f"add {needed[0]} to its supported majors once it is ported")
        return 0

    errors, notes, report = problems(spec, base, backend, clients)
    print_report(report)
    for note in notes:
        print(f"note: {note}")
    for error in errors:
        print(f"error: {error}")
    if not errors:
        print(f"API {spec['info']['version']}, majors served {backend[1]}; clients: "
              + ", ".join(f"{c} {m}" for c, m in sorted(clients.items())))
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
