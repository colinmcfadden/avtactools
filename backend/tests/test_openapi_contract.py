"""Responses are held to contracts/openapi.yaml.

The apps are installed on devices for months, so a response that quietly stops
matching the contract breaks apps nobody can update. These tests make that a
failure here first.
"""

import json
import os
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

from flask import Flask

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from client_header import parse_client  # noqa: E402
from openapi_check import load_spec, validate  # noqa: E402
from routes.config_routes import config_bp  # noqa: E402

SPEC = load_spec()
CONFIG_SCHEMA = SPEC["components"]["schemas"]["Config"]


def refs(node):
    """Every $ref in a spec fragment."""
    if isinstance(node, dict):
        for key, value in node.items():
            if key == "$ref":
                yield value
            else:
                yield from refs(value)
    elif isinstance(node, list):
        for item in node:
            yield from refs(item)


class SpecTests(unittest.TestCase):
    def test_every_reference_resolves(self):
        for ref in refs(SPEC):
            node = SPEC
            for part in ref[2:].split("/"):
                self.assertIn(part, node, f"{ref} does not resolve")
                node = node[part]

    def test_every_documented_path_is_under_api(self):
        for path in SPEC["paths"]:
            self.assertTrue(path.startswith("/api/"), path)


class ConfigConformsTests(unittest.TestCase):
    def setUp(self):
        app = Flask(__name__)
        app.register_blueprint(config_bp)
        self.client = app.test_client()
        patcher = patch("routes.config_routes._default_mapbox_token", return_value="pk.test")
        patcher.start()
        self.addCleanup(patcher.stop)

    def config(self, **env):
        with patch.dict(os.environ, env, clear=False):
            return self.client.get("/api/config").get_json()

    def test_a_bare_deployment_conforms(self):
        self.assertEqual(validate(SPEC, CONFIG_SCHEMA, self.config()), [])

    def test_a_fully_configured_deployment_conforms(self):
        config = self.config(
            MIN_APP_VERSION_ANDROID="1.2.0", MIN_APP_VERSION_IOS="1.0.0",
            MAINTENANCE_MESSAGE="Maintenance until 1800Z", LIDAR_BUILDER_URL="http://builder:8090",
            MAPBOX_PUBLIC_TOKEN="pk.rotated",
        )
        self.assertEqual(validate(SPEC, CONFIG_SCHEMA, config), [])
        self.assertEqual(config["mapbox"]["publicToken"], "pk.rotated")

    def test_a_secret_token_would_break_the_contract_even_if_served(self):
        config = self.config()
        config["mapbox"]["publicToken"] = "sk.secret"
        self.assertTrue(validate(SPEC, CONFIG_SCHEMA, config))


class CheckerCatchesDriftTests(unittest.TestCase):
    """A contract test that cannot fail proves nothing."""

    def good(self):
        return {
            "configVersion": 1, "serverVersion": "1.7.7",
            "minAppVersion": {"android": None, "ios": "1.0.0"},
            "maintenance": {"active": False, "message": None},
            "services": {"lidarBuilds": True, "packs": False},
            "mapbox": {"publicToken": "pk.x"},
        }

    def problems(self, mutate):
        value = self.good()
        mutate(value)
        return validate(SPEC, CONFIG_SCHEMA, value)

    def test_the_baseline_is_valid(self):
        self.assertEqual(validate(SPEC, CONFIG_SCHEMA, self.good()), [])

    def test_a_removed_field_is_caught(self):
        self.assertTrue(self.problems(lambda v: v.pop("services")))
        self.assertTrue(self.problems(lambda v: v["maintenance"].pop("active")))

    def test_an_added_field_is_caught(self):
        # Additive changes are fine for the apps, but they must be written into
        # the spec first, deliberately.
        self.assertTrue(self.problems(lambda v: v.update(surprise=1)))
        self.assertTrue(self.problems(lambda v: v["services"].update(packsReady=True)))

    def test_a_retyped_field_is_caught(self):
        self.assertTrue(self.problems(lambda v: v.update(configVersion="1")))
        self.assertTrue(self.problems(lambda v: v["services"].update(packs="no")))
        self.assertTrue(self.problems(lambda v: v["maintenance"].update(active=1)))

    def test_a_json_true_is_not_an_integer(self):
        self.assertTrue(self.problems(lambda v: v.update(configVersion=True)))

    def test_a_null_where_none_is_allowed_is_not_caught_but_elsewhere_is(self):
        self.assertEqual(self.problems(lambda v: v["minAppVersion"].update(ios=None)), [])
        self.assertTrue(self.problems(lambda v: v.update(serverVersion=None)))

    def test_a_malformed_version_is_caught(self):
        self.assertTrue(self.problems(lambda v: v["minAppVersion"].update(android="latest")))

    def test_a_keyword_the_checker_cannot_check_fails_loudly(self):
        with self.assertRaises(NotImplementedError):
            validate(SPEC, {"type": "object", "oneOf": [{"type": "string"}]}, {})
        with self.assertRaises(NotImplementedError):
            validate(SPEC, {"allOf": [{"type": "string"}, {"maxLength": 3}]}, "x")

    def test_a_nullable_reference_is_null_or_the_referenced_schema(self):
        person = {"nullable": True, "allOf": [{"$ref": "#/components/schemas/Person"}]}
        self.assertEqual(validate(SPEC, person, None), [])
        self.assertEqual(validate(SPEC, person, {"id": 1, "name": "Colin"}), [])
        self.assertTrue(validate(SPEC, person, {"id": 1}))
        self.assertTrue(validate(SPEC, {"allOf": [{"$ref": "#/components/schemas/Person"}]}, None))


class PackSessionFixtureTests(unittest.TestCase):
    """contracts/fixtures/packs/session.json replays what the server sends a client with a mission pack open.

    The native apps' pack sync is held to that file, so its inputs must be what the spec says the server sends
    (and what the session sends back must be what the route takes). The web generator checks the required keys by
    hand; this holds every input to the spec itself, nested types and enums included.
    """

    @classmethod
    def setUpClass(cls):
        path = BACKEND_DIR.parent / "contracts" / "fixtures" / "packs" / "session.json"
        cls.scenarios = json.loads(path.read_text(encoding="utf-8"))["scenarios"]
        cls.steps = [step for scenario in cls.scenarios for step in scenario["steps"]]
        schemas = SPEC["components"]["schemas"]
        cls.pack, cls.event, cls.answer = schemas["Pack"], schemas["PackEvent"], schemas["PackOpsResult"]
        cls.batch = SPEC["paths"]["/api/packs/{uuid}/ops"]["post"]["requestBody"]["content"]["application/json"]["schema"]

    def conforms(self, schema, value, where):
        self.assertEqual(validate(SPEC, schema, value), [], where)

    def test_every_pack_a_scenario_opens_or_reloads_is_a_pack(self):
        for scenario in self.scenarios:
            self.conforms(self.pack, scenario["pack"], scenario["name"])
        reloads = [step for step in self.steps if step["do"] == "reload"]
        self.assertGreater(len(reloads), 3)
        for step in reloads:
            self.conforms(self.pack, step["pack"], "reload")

    def test_every_event_and_every_answer_is_what_the_server_sends(self):
        received = [step for step in self.steps if step["do"] == "receive"]
        answered = [step for step in self.steps if step["do"] == "batchAnswered"]
        self.assertGreater(len(received), 30)
        self.assertGreater(len(answered), 10)
        for step in received:
            for event in step["events"]:
                self.conforms(self.event, event, f"event {event['seq']} {event['type']}")
        for step in answered:
            self.conforms(self.answer, step["answer"], "answer")

    def test_what_a_refusal_says_the_pack_took_is_what_the_server_sends(self):
        # `taken` (what of a refused batch the pack's log already has) comes only on the ops route's 403 pack_read_only,
        # 413 and 423, as a list of the same results a 200 carries.
        taken = SPEC["components"]["schemas"]["PackError"]["properties"]["taken"]
        failures = [step["failure"] for step in self.steps if step["do"] == "batchFailed"]
        carrying = [failure for failure in failures if "taken" in failure]
        self.assertGreater(len([failure for failure in carrying if failure["taken"]]), 3)
        for failure in carrying:
            self.conforms(taken, failure["taken"], f"taken of a {failure['status']}")
            self.assertTrue(failure["status"] in (413, 423) or failure.get("code") == "pack_read_only", failure)

    def test_every_batch_the_session_sends_is_one_the_route_takes(self):
        batches = [step["result"]["batch"] for step in self.steps if step["do"] == "nextBatch" and step["result"]]
        self.assertGreater(len(batches), 20)
        for batch in batches:
            self.conforms(self.batch, batch, "batch")
            self.assertTrue(1 <= len(batch["ops"]) <= 200)


class ClientHeaderSpecMatchesCodeTests(unittest.TestCase):
    """The pattern documented for apps and the one the server enforces must agree.

    The spec states the canonical form (lower-case platform). The parser is also
    tolerant of the platform's case, which is deliberate and tested separately in
    test_client_header; everything else it accepts or rejects exactly as the spec does.
    """

    def test_the_parser_is_only_more_tolerant_about_platform_case(self):
        import re
        pattern = SPEC["components"]["parameters"]["ClientHeader"]["schema"]["pattern"]
        self.assertIsNone(re.match(pattern, "Android/1.4.0 (212)"))
        self.assertIsNotNone(parse_client("Android/1.4.0 (212)"))

    def test_spec_pattern_and_parser_accept_and_reject_the_same_values(self):
        import re
        pattern = SPEC["components"]["parameters"]["ClientHeader"]["schema"]["pattern"]
        samples = [
            "android/1.4.0 (212)", "ios/2.0.1 (9)", "web/1.7.7", "android/1.5.0-beta.2 (300)",
            "windows/1.0.0", "android/1.4", "android/1.4.0 (abc)", "", "<b>x</b>",
            "android/1.4.0 (1234567890)", "android/1.4.0 (212) extra",
        ]
        for raw in samples:
            with self.subTest(raw=raw):
                self.assertEqual(bool(re.match(pattern, raw)), parse_client(raw) is not None)


if __name__ == "__main__":
    unittest.main()
