"""The API version and its breaking-change check (contracts/scripts/api_version.py).

The check is what stops a change to contracts/openapi.yaml that would break an installed app from landing
without the API major moving, so its classification is held here case by case, and run once over the real
spec with a break made in it.
"""

import copy
import importlib.util
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BACKEND_DIR = ROOT / "backend"
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

import api_version  # noqa: E402

_spec = importlib.util.spec_from_file_location("api_version_script", ROOT / "contracts" / "scripts" / "api_version.py")
script = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = script  # dataclasses look their module up while the file runs
_spec.loader.exec_module(script)

REAL = script.load_spec((ROOT / "contracts" / "openapi.yaml").read_text(encoding="utf-8"))


def spec(response=None, request=None, *, params=None, version="1.0.0", security=None):
    """A one-route spec: `POST /api/thing`, answering 200 with `response` and reading `request`."""
    operation = {"responses": {"200": {"description": "ok", "content": {"application/json": {"schema": response or {"type": "object"}}}}}}
    if request is not None:
        operation["requestBody"] = {"required": True, "content": {"application/json": {"schema": request}}}
    if params is not None:
        operation["parameters"] = params
    if security is not None:
        operation["security"] = security
    return {"openapi": "3.0.3", "info": {"version": version}, "paths": {"/api/thing": {"post": operation}}}


def obj(required=(), **props):
    return {"type": "object", "required": list(required), "properties": props}


S = {"type": "string"}
I = {"type": "integer"}


class ResponseChangesTests(unittest.TestCase):
    """What a client reads: anything it relied on going away, or a value it cannot hold, breaks it."""

    def classify(self, before, after):
        return script.diff(spec(before), spec(after))

    def test_a_field_removed_breaks(self):
        report = self.classify(obj(["a", "b"], a=S, b=S), obj(["a"], a=S))
        self.assertTrue(any("body.b: removed" in b for b in report.breaking), report.breaking)

    def test_a_field_added_is_additive(self):
        report = self.classify(obj(["a"], a=S), obj(["a"], a=S, b=S))
        self.assertEqual(report.breaking, [])
        self.assertTrue(any("body.b: added" in a for a in report.additive))

    def test_a_type_changed_breaks(self):
        self.assertTrue(self.classify(obj(["a"], a=S), obj(["a"], a=I)).breaking)

    def test_a_field_that_may_now_be_null_breaks(self):
        report = self.classify(obj(["a"], a=S), obj(["a"], a={"type": "string", "nullable": True}))
        self.assertTrue(any("may now be null" in b for b in report.breaking))

    def test_a_field_no_longer_always_sent_breaks(self):
        self.assertTrue(self.classify(obj(["a"], a=S), obj([], a=S)).breaking)

    def test_an_enum_widened_breaks_and_narrowed_does_not(self):
        widened = self.classify(obj(["k"], k={"type": "string", "enum": ["lz", "route"]}),
                                obj(["k"], k={"type": "string", "enum": ["lz", "route", "brief"]}))
        self.assertTrue(any("brief" in b for b in widened.breaking))
        narrowed = self.classify(obj(["k"], k={"type": "string", "enum": ["lz", "route"]}),
                                 obj(["k"], k={"type": "string", "enum": ["lz"]}))
        self.assertEqual(narrowed.breaking, [])

    def test_a_change_inside_a_list_and_through_a_ref_is_found(self):
        before, after = spec(), spec()
        for s, item in ((before, obj(["x"], x=S)), (after, obj([], x=S))):
            s["components"] = {"schemas": {"Item": item}}
            s["paths"]["/api/thing"]["post"]["responses"]["200"]["content"]["application/json"]["schema"] = {
                "type": "array", "items": {"allOf": [{"$ref": "#/components/schemas/Item"}]}}
        report = script.diff(before, after)
        self.assertTrue(any("body[].x: no longer always sent" in b for b in report.breaking), report.breaking)

    def test_a_status_no_longer_answered_breaks(self):
        before = spec()
        before["paths"]["/api/thing"]["post"]["responses"]["409"] = {"description": "conflict"}
        self.assertTrue(script.diff(before, spec()).breaking)
        self.assertEqual(script.diff(spec(), before).breaking, [])


class RequestChangesTests(unittest.TestCase):
    """What a client sends: anything the server now refuses from an old client breaks it."""

    def classify(self, before, after):
        return script.diff(spec(request=before), spec(request=after))

    def test_a_new_required_input_breaks_and_an_optional_one_does_not(self):
        self.assertTrue(self.classify(obj(["a"], a=S), obj(["a", "b"], a=S, b=S)).breaking)
        self.assertEqual(self.classify(obj(["a"], a=S), obj(["a"], a=S, b=S)).breaking, [])

    def test_an_input_made_required_breaks(self):
        self.assertTrue(self.classify(obj([], a=S), obj(["a"], a=S)).breaking)

    def test_an_accepted_value_removed_breaks_and_one_added_does_not(self):
        before = obj(["r"], r={"type": "string", "enum": ["editor", "viewer"]})
        self.assertTrue(self.classify(before, obj(["r"], r={"type": "string", "enum": ["editor"]})).breaking)
        self.assertEqual(self.classify(before, obj(["r"], r={"type": "string", "enum": ["editor", "viewer", "owner"]})).breaking, [])

    def test_a_tighter_limit_breaks_and_a_looser_one_does_not(self):
        self.assertTrue(self.classify(obj(["n"], n={"type": "string", "maxLength": 100}),
                                      obj(["n"], n={"type": "string", "maxLength": 50})).breaking)
        self.assertEqual(self.classify(obj(["n"], n={"type": "string", "maxLength": 100}),
                                       obj(["n"], n={"type": "string", "maxLength": 200})).breaking, [])

    def test_a_new_required_parameter_breaks(self):
        param = {"in": "query", "name": "since", "schema": I}
        self.assertEqual(script.diff(spec(params=[]), spec(params=[param])).breaking, [])
        self.assertTrue(script.diff(spec(params=[]), spec(params=[{**param, "required": True}])).breaking)

    def test_a_public_route_that_now_needs_a_token_breaks(self):
        self.assertTrue(script.diff(spec(security=[]), spec(security=[{"bearer": []}])).breaking)


class RoutesTests(unittest.TestCase):
    def test_a_route_removed_breaks_and_one_added_does_not(self):
        two = spec()
        two["paths"]["/api/other"] = copy.deepcopy(two["paths"]["/api/thing"])
        self.assertTrue(any("POST /api/other: removed" in b for b in script.diff(two, spec()).breaking))
        report = script.diff(spec(), two)
        self.assertEqual(report.breaking, [])
        self.assertIn("POST /api/other: added", report.additive)

    def test_the_real_spec_against_itself_changes_nothing(self):
        report = script.diff(REAL, copy.deepcopy(REAL))
        self.assertEqual((report.breaking, report.additive), ([], []))


class CheckTests(unittest.TestCase):
    """What `check` fails on."""

    SERVED = ("1.0.0", [1])
    CLIENTS = {"android": [1], "web": [1]}

    def broken_real(self):
        """The real spec with a field an app reads taken out of /api/config."""
        broken = copy.deepcopy(REAL)
        config = broken["components"]["schemas"]["Config"]
        del config["properties"]["serverVersion"]
        config["required"].remove("serverVersion")
        return broken

    def test_the_repository_as_it_is_passes(self):
        errors, _, _ = script.problems(REAL, None, script.read_backend(), script.read_clients())
        self.assertEqual(errors, [])

    def test_a_break_without_a_new_major_fails(self):
        errors, _, report = script.problems(self.broken_real(), REAL, self.SERVED, self.CLIENTS)
        self.assertTrue(any("serverVersion: removed" in b for b in report.breaking), report.breaking)
        self.assertTrue(any("breaking change" in e and "2.0.0" in e for e in errors), errors)

    def test_a_break_with_a_new_major_passes_once_the_clients_support_it(self):
        broken = self.broken_real()
        broken["info"]["version"] = "2.0.0"
        errors, _, _ = script.problems(broken, REAL, ("2.0.0", [2]), self.CLIENTS)
        self.assertTrue(any("android supports API majors [1]" in e for e in errors), errors)
        errors, _, _ = script.problems(broken, REAL, ("2.0.0", [2]), {"android": [1, 2], "web": [2]})
        self.assertEqual(errors, [])

    def test_an_old_major_kept_for_installed_apps_keeps_them_working(self):
        broken = self.broken_real()
        broken["info"]["version"] = "2.0.0"
        errors, notes, _ = script.problems(broken, REAL, ("2.0.0", [1, 2]), self.CLIENTS)
        self.assertEqual(errors, [])
        self.assertTrue(any("android" in n for n in notes))

    def test_versions_that_disagree_fail(self):
        errors, _, _ = script.problems(REAL, None, ("1.1.0", [1]), self.CLIENTS)
        self.assertTrue(any("must agree" in e for e in errors))
        errors, _, _ = script.problems(REAL, None, ("1.0.0", [2]), self.CLIENTS)
        self.assertTrue(errors)

    def test_an_addition_without_a_new_minor_is_a_note_not_a_failure(self):
        grown = copy.deepcopy(REAL)
        grown["components"]["schemas"]["Config"]["properties"]["extra"] = {"type": "string"}
        errors, notes, _ = script.problems(grown, REAL, self.SERVED, self.CLIENTS)
        self.assertEqual(errors, [])
        self.assertTrue(any("1.1.0" in n for n in notes), notes)

    def test_the_version_going_down_fails(self):
        older = copy.deepcopy(REAL)
        older["info"]["version"] = "0.9.0"
        errors, _, _ = script.problems(older, REAL, ("0.9.0", [0]), {"android": [0]})
        self.assertTrue(any("went down" in e for e in errors))


class BumpTests(unittest.TestCase):
    def test_bump_writes_both_files_and_keeps_the_rest(self):
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            spec_path, backend_path = Path(tmp) / "openapi.yaml", Path(tmp) / "api_version.py"
            spec_path.write_text("openapi: 3.0.3\ninfo:\n  title: X\n  version: 1.4.2\n  description: keep\npaths: {}\n", encoding="utf-8")
            backend_path.write_text('"""doc"""\n\nAPI_VERSION = "1.4.2"\n\nAPI_MAJORS_SERVED = (1,)\n', encoding="utf-8")
            script.write_version((2, 0, 0), [2], spec_path, backend_path)
            self.assertEqual(spec_path.read_text(), "openapi: 3.0.3\ninfo:\n  title: X\n  version: 2.0.0\n  description: keep\npaths: {}\n")
            self.assertEqual(script.read_backend(backend_path), ("2.0.0", [2]))

    def test_the_bump_a_diff_calls_for(self):
        self.assertEqual(script.required_bump((1, 4, 2), script.Report(breaking=["x"])), (2, 0, 0))
        self.assertEqual(script.required_bump((1, 4, 2), script.Report(additive=["x"])), (1, 5, 0))
        self.assertEqual(script.required_bump((1, 4, 2), script.Report()), (1, 4, 2))


class BackendTests(unittest.TestCase):
    def test_the_backend_agrees_with_the_spec(self):
        self.assertEqual(api_version.API_VERSION, REAL["info"]["version"])
        self.assertIn(int(api_version.API_VERSION.split(".")[0]), api_version.API_MAJORS_SERVED)

    def test_the_text_reader_reads_what_python_does(self):
        self.assertEqual(script.read_backend(), (api_version.API_VERSION, list(api_version.API_MAJORS_SERVED)))


if __name__ == "__main__":
    unittest.main()
