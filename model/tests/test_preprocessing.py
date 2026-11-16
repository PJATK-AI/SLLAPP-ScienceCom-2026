import math
import unittest
from unittest import mock

from model import (DEFAULT_DATASET_DIR, LandmarkValidationError, Sample, center_on_wrist,
                   load_dataset, preprocess_hand, preprocess_landmarks, preprocess_sample,
                   preprocess_samples)
from model import preprocessing
from model.tests.helpers import _TEMPLATE

HAND = [(0.40 + 0.01 * i, 0.70 - 0.02 * i, -0.005 * i) for i in range(21)]


def as_message_hand(points):
    return {"coordinateSpace": "MEDIAPIPE_IMAGE", "handedness": "RIGHT",
            "landmarks": [{"x": x, "y": y, "z": z} for x, y, z in points]}


def rotate_z(points, angle):
    c, s = math.cos(angle), math.sin(angle)
    return [(c * x - s * y, s * x + c * y, z) for x, y, z in points]


class CenterOnWristTests(unittest.TestCase):
    def assertPointsAlmostEqual(self, a, b, places=12):
        self.assertEqual(len(a), len(b))
        for p, q in zip(a, b):
            for u, v in zip(p, q):
                self.assertAlmostEqual(u, v, places=places)

    def test_wrist_becomes_origin(self):
        self.assertEqual(center_on_wrist(HAND)[0], (0.0, 0.0, 0.0))

    def test_subtracts_wrist_from_every_landmark(self):
        wx, wy, wz = HAND[0]
        expected = [(x - wx, y - wy, z - wz) for x, y, z in HAND]
        self.assertPointsAlmostEqual(center_on_wrist(HAND), expected)

    def test_returns_21_float_triples(self):
        result = center_on_wrist([(1, 2, 3)] * 21)
        self.assertEqual(len(result), 21)
        self.assertTrue(all(len(p) == 3 and all(type(v) is float for v in p) for p in result))

    def test_position_in_frame_is_removed(self):
        moved = [(x + 0.3, y - 0.2, z + 0.05) for x, y, z in HAND]
        self.assertPointsAlmostEqual(center_on_wrist(moved), center_on_wrist(HAND))

    def test_orientation_and_shape_are_preserved(self):
        centred = center_on_wrist(HAND)

        for i in range(21):
            for j in range(21):
                before = tuple(b - a for a, b in zip(HAND[i], HAND[j]))
                after = tuple(b - a for a, b in zip(centred[i], centred[j]))
                for u, v in zip(before, after):
                    self.assertAlmostEqual(u, v, places=12)

        angle = 0.7
        self.assertPointsAlmostEqual(center_on_wrist(rotate_z(HAND, angle)),
                                     rotate_z(center_on_wrist(HAND), angle))
        self.assertNotAlmostEqual(center_on_wrist(rotate_z(HAND, angle))[8][0], centred[8][0])

    def test_input_is_not_modified(self):
        points = [list(p) for p in HAND]
        center_on_wrist(points)
        self.assertEqual(points, [list(p) for p in HAND])

    def test_accepts_mappings(self):
        self.assertPointsAlmostEqual(center_on_wrist(as_message_hand(HAND)["landmarks"]),
                                     center_on_wrist(HAND))


class ValidationErrorTests(unittest.TestCase):
    def assertRejected(self, landmarks, fragment):
        with self.assertRaises(LandmarkValidationError) as ctx:
            center_on_wrist(landmarks)
        self.assertIn(fragment, str(ctx.exception))

    def test_wrong_number_of_landmarks(self):
        self.assertRejected(HAND[:20], "exactly 21 landmarks, got 20")
        self.assertRejected(HAND + [HAND[0]], "exactly 21 landmarks, got 22")
        self.assertRejected([], "exactly 21 landmarks, got 0")

    def test_missing_landmarks(self):
        self.assertRejected(None, "missing")
        self.assertRejected("not a list", "sequence of 21 points")

    def test_missing_coordinate_in_sequence(self):
        points = list(HAND)
        points[5] = (0.1, 0.2)
        self.assertRejected(points, "landmark 5: expected 3 coordinates")

    def test_missing_coordinate_in_mapping(self):
        points = as_message_hand(HAND)["landmarks"]
        del points[7]["z"]
        self.assertRejected(points, "landmark 7: missing coordinate(s) z")

    def test_non_finite_and_non_numeric_values(self):
        for bad, fragment in ((float("nan"), "be finite"), (float("inf"), "be finite"),
                              (-float("inf"), "be finite"), (None, "be a number"),
                              ("0.5", "be a number"), (True, "be a number")):
            with self.subTest(bad=bad):
                points = list(HAND)
                points[3] = (0.1, bad, 0.0)
                self.assertRejected(points, "landmark 3: y must " + fragment)

    def test_malformed_point(self):
        points = list(HAND)
        points[2] = 0.5
        self.assertRejected(points, "landmark 2")

    def test_error_is_a_value_error(self):
        self.assertTrue(issubclass(LandmarkValidationError, ValueError))


class SharedPipelineTests(unittest.TestCase):
    def test_training_and_inference_give_identical_output(self):
        sample = Sample("A_1_1", "A", 1, 1, tuple(HAND))
        self.assertEqual(preprocess_sample(sample), preprocess_hand(as_message_hand(HAND)))

    def test_both_adapters_call_the_shared_pipeline(self):
        sample = Sample("A_1_1", "A", 1, 1, tuple(HAND))
        with mock.patch.object(preprocessing, "center_on_wrist",
                               wraps=preprocessing.center_on_wrist) as spy:
            preprocess_sample(sample)
            preprocess_hand(as_message_hand(HAND))
        self.assertEqual(spy.call_count, 2)

    def test_pipeline_is_wrist_centring(self):
        self.assertEqual(preprocess_landmarks(HAND), center_on_wrist(HAND))

    def test_preprocess_samples_names_failing_sample(self):
        bad = Sample("B_2_3", "B", 2, 3, tuple(HAND[:20]))
        good = Sample("A_1_1", "A", 1, 1, tuple(_TEMPLATE))
        self.assertEqual(len(preprocess_samples([good])), 1)
        with self.assertRaises(LandmarkValidationError) as ctx:
            preprocess_samples([good, bad])
        self.assertIn("B_2_3", str(ctx.exception))

    def test_hand_without_landmarks_is_rejected(self):
        for hand in (None, {}, {"handedness": "LEFT"}):
            with self.subTest(hand=hand), self.assertRaises(LandmarkValidationError):
                preprocess_hand(hand)


@unittest.skipUnless(DEFAULT_DATASET_DIR.is_dir(), "SPAS dataset not present locally")
class RealDatasetPreprocessingTests(unittest.TestCase):
    def test_every_sample_is_centred(self):
        samples = load_dataset()
        centred = preprocess_samples(samples)
        self.assertEqual(len(centred), 960)
        for s, c in zip(samples, centred):
            self.assertEqual(c[0], (0.0, 0.0, 0.0), s.name)
            self.assertAlmostEqual(c[8][0], s.landmarks[8][0] - s.landmarks[0][0], places=12)


if __name__ == "__main__":
    unittest.main()
