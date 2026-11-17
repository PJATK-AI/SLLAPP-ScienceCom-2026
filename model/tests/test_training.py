import os
import random
import tempfile
import unittest

try:
    import numpy as np
    from sklearn.model_selection import cross_validate

    from model import training
    from model.training import (VARIANTS, LandmarkPreprocessor, TrainedModel, VariantMismatchError,
                                build_pipeline, evaluate, get_variant, load_model, make_folds,
                                samples_to_arrays, train_model)
    HAVE_SKLEARN = True
except ImportError:  # pragma: no cover - training dependencies are optional
    HAVE_SKLEARN = False

from model import DEFAULT_DATASET_DIR, Sample, load_dataset, preprocess_landmarks
from model.tests.helpers import _TEMPLATE

LETTERS = "ABC"


def synthetic_samples(subjects=range(1, 7), reps=range(1, 4), seed=0):
    rng = random.Random(seed)
    samples = []
    for li, letter in enumerate(LETTERS):
        base = [(x + 0.04 * li * (i % 4 == 0), y - 0.03 * li * (i % 3 == 0), z)
                for i, (x, y, z) in enumerate(_TEMPLATE)]
        for s in subjects:
            for r in reps:
                f, dx, dy = rng.uniform(0.5, 2.0), rng.uniform(-0.2, 0.2), rng.uniform(-0.2, 0.2)
                pts = tuple((x * f + dx + rng.gauss(0, 0.002), y * f + dy + rng.gauss(0, 0.002),
                             z * f) for x, y, z in base)
                samples.append(Sample("%s_%d_%d" % (letter, s, r), letter, s, r, pts))
    return samples


@unittest.skipUnless(HAVE_SKLEARN, "numpy/scikit-learn not installed")
class PipelineTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.samples = synthetic_samples()
        cls.X, cls.y, cls.groups = samples_to_arrays(cls.samples)

    def test_variants_are_built_as_described(self):
        for vid, variant in VARIANTS.items():
            with self.subTest(variant=vid):
                steps = dict(build_pipeline(variant).steps)
                self.assertEqual(steps["preprocess"].scale_method, variant.scale_method)
                self.assertEqual("standardize" in steps, variant.standardize)

    def test_preprocessor_uses_shared_pipeline(self):
        out = LandmarkPreprocessor("palm").fit(self.X).transform(self.X[:2])
        expected = np.ravel(preprocess_landmarks(self.samples[1].landmarks, "palm"))
        np.testing.assert_allclose(out[1], expected)

    def test_scaler_fitted_on_training_rows_only(self):
        train, _ = make_folds(self.groups, 3)[0]
        pipe = build_pipeline(get_variant("V3")).fit(self.X[train], self.y[train])
        expected = LandmarkPreprocessor("wrist_middle_mcp").transform(self.X[train]).mean(axis=0)
        np.testing.assert_allclose(pipe.named_steps["standardize"].mean_, expected)

    def test_scaler_refitted_in_each_fold(self):
        folds = make_folds(self.groups, 3)
        scores = cross_validate(build_pipeline(get_variant("V3")), self.X, self.y, cv=folds,
                                return_estimator=True)
        means = []
        for est, (train, test) in zip(scores["estimator"], folds):
            self.assertFalse(set(self.groups[train]) & set(self.groups[test]))
            pre = LandmarkPreprocessor("wrist_middle_mcp").transform(self.X[train])
            np.testing.assert_allclose(est.named_steps["standardize"].mean_, pre.mean(axis=0))
            means.append(est.named_steps["standardize"].mean_)
        self.assertFalse(np.allclose(means[0], means[1]))

    def test_evaluate_reports_every_combination(self):
        results = evaluate(self.X, self.y, self.groups, ["V0", "V3"], ["logreg", "knn"], n_splits=3)
        self.assertEqual([(r.variant, r.classifier) for r in results],
                         [("V0", "logreg"), ("V0", "knn"), ("V3", "logreg"), ("V3", "knn")])
        for r in results:
            self.assertTrue(0.0 <= r.accuracy_mean <= 1.0 and 0.0 <= r.f1_macro_mean <= 1.0)

    def test_scale_normalization_helps_on_size_varying_hands(self):
        results = {r.variant: r for r in evaluate(self.X, self.y, self.groups, ["V0", "V2"],
                                                  ["knn"], n_splits=3)}
        self.assertGreater(results["V2"].accuracy_mean, results["V0"].accuracy_mean)


@unittest.skipUnless(HAVE_SKLEARN, "numpy/scikit-learn not installed")
class ModelFileTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.samples = synthetic_samples()
        cls.model = train_model(cls.samples, "V3", "logreg")
        cls.tmp = tempfile.TemporaryDirectory()
        cls.path = cls.model.save(os.path.join(cls.tmp.name, "m.joblib"))

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_inference_matches_training_pipeline(self):
        loaded = load_model(self.path, expected_variant="V3")
        X, _, _ = samples_to_arrays(self.samples)
        np.testing.assert_array_equal(loaded.pipeline.predict(X), self.model.pipeline.predict(X))
        s = self.samples[0]
        hand = {"landmarks": [{"x": x, "y": y, "z": z} for x, y, z in s.landmarks]}
        self.assertEqual(loaded.predict_hand(hand), self.model.pipeline.predict(X[:1])[0])
        self.assertEqual(loaded.variant, VARIANTS["V3"])

    def test_wrong_expected_variant_is_rejected(self):
        with self.assertRaises(VariantMismatchError):
            load_model(self.path, expected_variant="V0")

    def test_pipeline_not_matching_variant_is_rejected(self):
        with self.assertRaises(VariantMismatchError):
            TrainedModel(self.model.pipeline, VARIANTS["V0"], "logreg")

    def test_changed_variant_definition_is_rejected(self):
        original = VARIANTS["V3"]
        try:
            VARIANTS["V3"] = training.Variant("V3", "palm", True, original.description)
            with self.assertRaises(VariantMismatchError):
                load_model(self.path)
        finally:
            VARIANTS["V3"] = original


@unittest.skipUnless(HAVE_SKLEARN and DEFAULT_DATASET_DIR.is_dir(), "SPAS dataset not present")
class RealDatasetTrainingTests(unittest.TestCase):
    def test_subject_grouped_folds_cover_all_samples(self):
        _, y, groups = samples_to_arrays(load_dataset())
        folds = make_folds(groups)
        self.assertEqual(sorted(i for _, test in folds for i in test), list(range(len(y))))


if __name__ == "__main__":
    unittest.main()
