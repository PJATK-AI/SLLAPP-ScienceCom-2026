import math
import unittest

from model import (SCALE_METHODS, LandmarkValidationError, Sample, center_on_wrist, hand_scale,
                   preprocess_hand, preprocess_landmarks, preprocess_sample)
from model.config import MIN_HAND_SCALE

HAND = [(0.40 + 0.01 * i, 0.70 - 0.02 * i, -0.005 * i) for i in range(21)]
SCALED_METHODS = [m for m in SCALE_METHODS if m != "none"]


def transform(points, factor, shift=(0.0, 0.0, 0.0)):
    return [tuple(v * factor + d for v, d in zip(p, shift)) for p in points]


def dist(p):
    return math.sqrt(sum(v * v for v in p))


class ScaleNormalizationTests(unittest.TestCase):
    def assertPointsAlmostEqual(self, a, b, places=10):
        for p, q in zip(a, b):
            for u, v in zip(p, q):
                self.assertAlmostEqual(u, v, places=places)

    def test_scaled_and_shifted_copy_gives_same_output(self):
        for method in SCALED_METHODS:
            for factor in (0.3, 2.5):
                with self.subTest(method=method, factor=factor):
                    copy = transform(HAND, factor, shift=(0.1, -0.2, 0.03))
                    self.assertPointsAlmostEqual(preprocess_landmarks(copy, method),
                                                 preprocess_landmarks(HAND, method))

    def test_none_keeps_size(self):
        self.assertEqual(preprocess_landmarks(HAND, "none"), center_on_wrist(HAND))
        self.assertEqual(preprocess_landmarks(HAND), center_on_wrist(HAND))
        big = preprocess_landmarks(transform(HAND, 2.0), "none")
        self.assertAlmostEqual(dist(big[9]), 2 * dist(center_on_wrist(HAND)[9]))

    def test_documented_measurements_become_one(self):
        centred = center_on_wrist(HAND)
        self.assertAlmostEqual(dist(preprocess_landmarks(HAND, "wrist_middle_mcp")[9]), 1.0)
        palm = preprocess_landmarks(HAND, "palm")
        self.assertAlmostEqual(sum(dist(palm[i]) for i in (5, 9, 13, 17)) / 4, 1.0)
        extent = preprocess_landmarks(HAND, "max_extent")
        self.assertAlmostEqual(max(dist(p) for p in extent), 1.0)
        self.assertAlmostEqual(hand_scale(centred, "wrist_middle_mcp"), dist(centred[9]))

    def test_scale_is_3d(self):
        centred = center_on_wrist(HAND)
        self.assertNotEqual(centred[9][2], 0.0)
        self.assertAlmostEqual(hand_scale(centred, "wrist_middle_mcp"),
                               math.sqrt(sum(v * v for v in centred[9])))

    def test_wrist_stays_at_origin(self):
        for method in SCALE_METHODS:
            self.assertEqual(preprocess_landmarks(HAND, method)[0], (0.0, 0.0, 0.0))

    def test_degenerate_hand_is_rejected(self):
        collapsed = [HAND[0]] * 21
        for method in SCALED_METHODS:
            with self.subTest(method=method), self.assertRaises(LandmarkValidationError) as ctx:
                preprocess_landmarks(collapsed, method)
            self.assertIn("degenerate", str(ctx.exception))

    def test_tiny_but_valid_scale_is_accepted(self):
        tiny = transform(HAND, MIN_HAND_SCALE * 1e3)
        self.assertAlmostEqual(dist(preprocess_landmarks(tiny, "wrist_middle_mcp")[9]), 1.0)

    def test_unknown_method_is_rejected(self):
        with self.assertRaises(ValueError):
            preprocess_landmarks(HAND, "bogus")

    def test_training_and_inference_adapters_agree(self):
        sample = Sample("A_1_1", "A", 1, 1, tuple(HAND))
        hand = {"landmarks": [{"x": x, "y": y, "z": z} for x, y, z in HAND]}
        for method in SCALE_METHODS:
            self.assertEqual(preprocess_sample(sample, method), preprocess_hand(hand, method))


if __name__ == "__main__":
    unittest.main()
