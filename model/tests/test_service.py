import hashlib
import http.client
import io
import json
import os
import random
import tempfile
import threading
import unittest
from contextlib import redirect_stderr
from unittest import mock

try:
    from model import service
    from model.service import ModelLoadError, PredictionService, create_server, load_service
    from model.training import train_model
    HAVE_SKLEARN = True
except ImportError:  # pragma: no cover - service dependencies are optional
    HAVE_SKLEARN = False

from model import Sample
from model.config import LETTERS
from model.tests.helpers import _TEMPLATE


def letter_points(index):
    return [(x + 0.03 * index * (i % 4 == 0), y - 0.02 * index * (i % 3 == 0), z)
            for i, (x, y, z) in enumerate(_TEMPLATE)]


def synthetic_samples(letters=LETTERS, subjects=range(1, 4), reps=range(1, 3), seed=0):
    rng = random.Random(seed)
    samples = []
    for li, letter in enumerate(letters):
        for s in subjects:
            for r in reps:
                f, dx, dy = rng.uniform(0.8, 1.2), rng.uniform(-0.1, 0.1), rng.uniform(-0.1, 0.1)
                pts = tuple((x * f + dx + rng.gauss(0, 0.001), y * f + dy + rng.gauss(0, 0.001), z * f)
                            for x, y, z in letter_points(li))
                samples.append(Sample("%s_%d_%d" % (letter, s, r), letter, s, r, pts))
    return samples


def hand(letter_index=0, handedness="RIGHT", **overrides):
    payload = {"coordinateSpace": "MEDIAPIPE_IMAGE", "handedness": handedness, "score": 0.9,
               "landmarks": [{"x": x, "y": y, "z": z} for x, y, z in letter_points(letter_index)]}
    payload.update(overrides)
    return payload


@unittest.skipUnless(HAVE_SKLEARN, "numpy/scikit-learn not installed")
class ServiceTestCase(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        samples = synthetic_samples()
        cls.model_path = train_model(samples, "V3", "logreg").save(os.path.join(cls.tmp.name, "model.joblib"))
        cls.svm_path = train_model(samples, "V3", "svm").save(os.path.join(cls.tmp.name, "svm.joblib"))
        cls.abc_path = train_model(synthetic_samples("ABC"), "V3", "logreg").save(
            os.path.join(cls.tmp.name, "abc.joblib"))

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()


class HttpTests(ServiceTestCase):
    @classmethod
    def setUpClass(cls):
        super().setUpClass()
        cls.service = load_service(cls.model_path, "V3")
        cls.server = create_server(cls.service, "127.0.0.1", 0)
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()
        super().tearDownClass()

    def request(self, method, path, body=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        try:
            if isinstance(body, (dict, list)):
                body = json.dumps(body)
            if isinstance(body, str):
                body = body.encode("utf-8")
            with redirect_stderr(io.StringIO()):
                conn.request(method, path, body=body, headers={"Content-Type": "application/json"})
                resp = conn.getresponse()
                data = resp.read()
            self.assertEqual(resp.getheader("Content-Type"), "application/json")
            return resp.status, json.loads(data)
        finally:
            conn.close()

    def test_valid_sample_returns_letter_and_confidence(self):
        for i in (0, 5, 15):
            with self.subTest(letter=LETTERS[i]):
                status, body = self.request("POST", "/predict", hand(i))
                self.assertEqual(status, 200)
                self.assertEqual(set(body), {"letter", "confidence"})
                self.assertEqual(body["letter"], LETTERS[i])

    def test_confidence_is_the_model_probability_in_0_1(self):
        pipeline = self.service.model.pipeline
        for i in range(len(LETTERS)):
            status, body = self.request("POST", "/predict", hand(i))
            self.assertEqual(status, 200)
            self.assertIn(body["letter"], LETTERS)
            self.assertGreaterEqual(body["confidence"], 0.0)
            self.assertLessEqual(body["confidence"], 1.0)
            row = [v for p in letter_points(i) for v in p]
            proba = pipeline.predict_proba([row])[0]
            self.assertAlmostEqual(body["confidence"], float(max(proba)))
            self.assertEqual(body["letter"], self.service.model.classes[int(proba.argmax())])

    def test_left_hand_is_not_mirrored(self):
        _, right = self.request("POST", "/predict", hand(3, "RIGHT"))
        _, left = self.request("POST", "/predict", hand(3, "LEFT"))
        self.assertEqual(left, right)

    def test_invalid_json_returns_400(self):
        cases = {"syntax": "{\"handedness\":", "empty": b"", "nan_literal": "{\"x\": NaN}",
                 "infinity_literal": "{\"x\": Infinity}", "not_utf8": b"\xff\xfe{"}
        for name, body in cases.items():
            with self.subTest(case=name):
                status, payload = self.request("POST", "/predict", body)
                self.assertEqual(status, 400)
                self.assertIn("not valid JSON", payload["error"])

    def test_invalid_sample_returns_422_with_message(self):
        landmarks = hand()["landmarks"]
        non_finite = hand()
        non_finite["landmarks"] = [dict(p) for p in landmarks]
        non_finite["landmarks"][4]["y"] = "OVERFLOW"
        non_finite = json.dumps(non_finite).replace('"OVERFLOW"', "1e999")  # valid JSON, parsed as inf
        cases = {
            "wrong_coordinate_space": (hand(coordinateSpace="WORLD"), "coordinateSpace"),
            "missing_coordinate_space": ({k: v for k, v in hand().items() if k != "coordinateSpace"},
                                         "coordinateSpace"),
            "unknown_handedness": (hand(handedness="Left"), "handedness"),
            "missing_handedness": ({k: v for k, v in hand().items() if k != "handedness"}, "handedness"),
            "20_landmarks": (hand(landmarks=landmarks[:20]), "exactly 21 landmarks, got 20"),
            "22_landmarks": (hand(landmarks=landmarks + landmarks[:1]), "exactly 21 landmarks, got 22"),
            "landmarks_not_list": (hand(landmarks={"x": 1}), "landmarks"),
            "point_not_object": (hand(landmarks=[[0, 0, 0]] * 21), "landmark 0"),
            "missing_axis": (hand(landmarks=[{"x": 0, "y": 0}] * 21), "landmark 0: missing coordinate(s) z"),
            "non_finite": (non_finite, "landmark 4: y must be finite"),
            "non_numeric": (hand(landmarks=[{"x": "1", "y": 0, "z": 0}] * 21), "must be a number"),
            "degenerate_hand": (hand(landmarks=[{"x": 0.5, "y": 0.5, "z": 0}] * 21), "degenerate"),
            "body_not_object": ([1, 2], "hand must be an object"),
        }
        for name, (body, message) in cases.items():
            with self.subTest(case=name):
                status, payload = self.request("POST", "/predict", body)
                self.assertEqual(status, 422)
                self.assertIn(message, payload["error"])

    def test_health_reports_model(self):
        status, body = self.request("GET", "/health")
        self.assertEqual(status, 200)
        with open(self.model_path, "rb") as fh:
            checksum = hashlib.sha256(fh.read()).hexdigest()
        self.assertEqual(body, {"status": "ok", "variant": "V3", "classifier": "logreg",
                                "letters": list(LETTERS), "checksum": "sha256:" + checksum})

    def test_unknown_path_and_wrong_method(self):
        self.assertEqual(self.request("GET", "/nope")[0], 404)
        self.assertEqual(self.request("GET", "/predict")[0], 405)
        self.assertEqual(self.request("POST", "/health", "{}")[0], 405)

    def test_body_too_large_returns_413(self):
        status, _ = self.request("POST", "/predict", b" " * (service.MAX_BODY_BYTES + 1))
        self.assertEqual(status, 413)


class StartupTests(ServiceTestCase):
    def test_missing_file(self):
        with self.assertRaisesRegex(ModelLoadError, "does not exist"):
            load_service(os.path.join(self.tmp.name, "missing.joblib"), "V3")

    def test_variant_mismatch(self):
        with self.assertRaisesRegex(ModelLoadError, "trained with variant V3, expected V4"):
            load_service(self.model_path, "V4")

    def test_model_without_probabilities(self):
        with self.assertRaisesRegex(ModelLoadError, "cannot give probabilities"):
            load_service(self.svm_path, "V3")

    def test_model_with_other_letters(self):
        with self.assertRaisesRegex(ModelLoadError, "letters ABC differ"):
            load_service(self.abc_path, "V3")

    def test_file_that_is_not_a_model(self):
        path = os.path.join(self.tmp.name, "junk.joblib")
        with open(path, "wb") as fh:
            fh.write(b"not a model")
        with self.assertRaises(ModelLoadError):
            load_service(path, "V3")

    def test_main_exits_with_error_and_does_not_serve(self):
        cases = {"missing": ["--model", os.path.join(self.tmp.name, "missing.joblib")],
                 "variant": ["--model", str(self.model_path), "--variant", "V0"],
                 "no_proba": ["--model", str(self.svm_path)]}
        for name, argv in cases.items():
            with self.subTest(case=name), mock.patch.object(service, "create_server") as create:
                err = io.StringIO()
                with redirect_stderr(err):
                    self.assertEqual(service.main(argv + ["--port", "0"]), 1)
                create.assert_not_called()
                self.assertIn("error:", err.getvalue())

    def test_settings_from_arguments_and_environment(self):
        env = {"SLLAPP_MODEL_PATH": "env.joblib", "SLLAPP_MODEL_VARIANT": "V4",
               "SLLAPP_PREDICT_HOST": "0.0.0.0", "SLLAPP_PREDICT_PORT": "9001"}
        with mock.patch.dict(os.environ, env):
            args = service.parse_args([])
            self.assertEqual((args.model, args.variant, args.host, args.port), ("env.joblib", "V4", "0.0.0.0", 9001))
            args = service.parse_args(["--model", "cli.joblib", "--port", "9002"])
            self.assertEqual((args.model, args.port), ("cli.joblib", 9002))
        with mock.patch.dict(os.environ, {}, clear=True):
            args = service.parse_args([])
            self.assertEqual((args.variant, args.host, args.port), ("V3", "127.0.0.1", 8000))
            self.assertTrue(args.model.endswith("model.joblib"))
        with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            service.parse_args(["--port", "70000"])


if __name__ == "__main__":
    unittest.main()
