"""Loading and running the SAM model.

It used to load when the terrain route module was imported, so building the app
(or a test app) pulled in torch and a gigabyte of weights, and a missing weights
file stopped the whole API from starting. These pin the replacement: load on
first use, once, and never run two predictions at the same time.
"""

import subprocess
import sys
import threading
import time
import types
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import requests  # noqa: E402

from app.services.terrain import field_detection  # noqa: E402

BACKEND = Path(__file__).resolve().parents[1]


class FakeSamModule:
    """Stands in for the ultralytics module: counts how often a model is built."""

    def __init__(self, *, fail_first=False, delay=0.0):
        self.created = 0
        self.fail_first = fail_first
        self.delay = delay
        self.SAM = self._sam

    def _sam(self, weights):
        time.sleep(self.delay)
        self.created += 1
        if self.fail_first and self.created == 1:
            raise OSError("weights not found")
        return types.SimpleNamespace(weights=weights)

    def install(self):
        return patch.dict(sys.modules, {"ultralytics": self})


class ModelLoadingTests(unittest.TestCase):
    def setUp(self):
        field_detection._model = None
        self.addCleanup(setattr, field_detection, "_model", None)

    def test_importing_the_routes_does_not_load_the_model_library(self):
        """The regression: this import used to bring in torch and the weights."""
        code = ("import sys, app.routes.terrain, app.services.terrain.field_detection;"
                "print('ultralytics' in sys.modules)")
        result = subprocess.run([sys.executable, "-c", code], cwd=BACKEND,
                                capture_output=True, text=True, timeout=120)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "False")

    def test_the_model_is_built_once_and_reused(self):
        fake = FakeSamModule()
        with fake.install():
            first = field_detection.get_model()
            second = field_detection.get_model()
        self.assertIs(first, second)
        self.assertEqual(fake.created, 1)
        self.assertEqual(first.weights, "sam_b.pt")

    def test_callers_arriving_together_share_one_load(self):
        fake = FakeSamModule(delay=0.2)
        results = []
        with fake.install():
            threads = [threading.Thread(target=lambda: results.append(field_detection.get_model()))
                       for _ in range(6)]
            for thread in threads:
                thread.start()
            for thread in threads:
                thread.join(10)
        self.assertEqual(fake.created, 1)
        self.assertEqual(len({id(model) for model in results}), 1)
        self.assertEqual(len(results), 6)

    def test_a_failed_load_is_tried_again_rather_than_remembered(self):
        fake = FakeSamModule(fail_first=True)
        with fake.install():
            with self.assertRaises(OSError):
                field_detection.get_model()
            self.assertIsNotNone(field_detection.get_model())
        self.assertEqual(fake.created, 2)

    def test_preloading_loads_in_the_background(self):
        fake = FakeSamModule(delay=0.1)
        with fake.install():
            thread = field_detection.preload_async()
            thread.join(10)
            self.assertFalse(thread.is_alive())
            self.assertIsNotNone(field_detection._model)
        self.assertEqual(fake.created, 1)

    def test_a_failed_preload_does_not_take_the_app_down(self):
        fake = FakeSamModule(fail_first=True)
        with fake.install():
            field_detection.preload_async().join(10)
            self.assertIsNone(field_detection._model)
            self.assertIsNotNone(field_detection.get_model())     # the first request recovers


class PredictionTests(unittest.TestCase):
    def test_two_predictions_never_run_at_once(self):
        """SAM keeps state between calls and holds ~1 GB per run."""
        running, peak = [0], [0]
        guard = threading.Lock()

        class Model:
            def predict(self, image, **kwargs):
                with guard:
                    running[0] += 1
                    peak[0] = max(peak[0], running[0])
                time.sleep(0.05)
                with guard:
                    running[0] -= 1
                return ["result"]

        with patch.object(field_detection, "get_model", return_value=Model()):
            threads = [threading.Thread(target=field_detection.predict, args=(object(), 1, 2))
                       for _ in range(5)]
            for thread in threads:
                thread.start()
            for thread in threads:
                thread.join(10)
        self.assertEqual(peak[0], 1)

    def test_the_prompt_is_one_positive_point_at_inference_size_512(self):
        calls = []

        class Model:
            def predict(self, image, **kwargs):
                calls.append(kwargs)
                return ["result"]

        with patch.object(field_detection, "get_model", return_value=Model()):
            self.assertEqual(field_detection.predict("image", 10, 20), ["result"])
        self.assertEqual(calls, [{"points": [[10, 20]], "labels": [1], "conf": 0.4, "imgsz": 512}])


class ElevationLabelTests(unittest.TestCase):
    class Answer:
        def __init__(self, body):
            self.body = body

        def json(self):
            return self.body

    def label(self, body=None, error=None):
        with patch.object(requests, "get", side_effect=error,
                          return_value=self.Answer(body)):
            return field_detection.elevation_label(34.78, -84.08)

    def test_metres_become_whole_feet_as_text(self):
        self.assertEqual(self.label({"results": [{"elevation": 500}]}), "1640")

    def test_no_result_reads_as_zero(self):
        self.assertEqual(self.label({"results": []}), "0")
        self.assertEqual(self.label({"results": [{"elevation": None}]}), "0")

    def test_a_failed_lookup_is_to_be_determined(self):
        self.assertEqual(self.label(error=requests.exceptions.ConnectionError()), "TBD")
        self.assertEqual(self.label("not a dict"), "TBD")


if __name__ == "__main__":
    unittest.main()
