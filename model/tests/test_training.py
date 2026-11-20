import os
import random
import tempfile
import unittest
from unittest import mock

try:
    import numpy as np
    import sklearn
    from sklearn.model_selection import cross_validate

    from model import training
    from model.calibration import recommend_threshold
    from model.config import (CALIBRATION_METHOD, FINAL_CLASSIFIER, FINAL_VARIANT, MAX_ACCEPTED_ERROR,
                              THRESHOLDS)
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
class FinalTrainingTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.samples = synthetic_samples(subjects=range(1, 9))
        cls.final = training.train_final(cls.samples, comparison_variants=["V0", "V3"],
                                         comparison_classifiers=["knn", "logreg"], outer_splits=4)
        cls.tmp = tempfile.TemporaryDirectory()
        cls.model_path, cls.checksum_path, cls.report_path = training.write_final_run(cls.final, cls.tmp.name)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_final_variant_and_classifier_are_fixed(self):
        m = self.final.model
        self.assertEqual((m.variant.id, m.classifier, m.calibration),
                         (FINAL_VARIANT, FINAL_CLASSIFIER, CALIBRATION_METHOD))
        # the comparison is context only: the final model does not depend on its winner
        with mock.patch.object(training, "best_result", side_effect=AssertionError("no selection")):
            again = training.train_final(self.samples, comparison_variants=[], comparison_classifiers=[])
        self.assertEqual((again.model.variant.id, again.model.classifier), (FINAL_VARIANT, FINAL_CLASSIFIER))
        self.assertEqual(again.comparison, [])

    def test_comparison_covers_every_candidate(self):
        self.assertEqual({(r.variant, r.classifier) for r in self.final.comparison},
                         {(v, c) for v in ("V0", "V3") for c in ("knn", "logreg")})
        self.assertEqual(len(self.final.held_out.folds), 4)

    def test_final_model_is_trained_on_all_samples(self):
        X, y, _ = samples_to_arrays(self.samples)
        self.assertEqual(self.final.n_samples, len(y))
        self.assertEqual(self.final.n_people, 8)
        self.assertEqual(self.final.model.classes, sorted(set(y)))
        base = training.base_pipeline(self.final.model.pipeline)
        expected = LandmarkPreprocessor("wrist_middle_mcp").transform(X).mean(axis=0)
        np.testing.assert_allclose(base.named_steps["standardize"].mean_, expected)

    def test_recommended_threshold_follows_the_rule(self):
        rows = self.final.thresholds
        t = self.final.model.recommended_threshold
        self.assertEqual(t, recommend_threshold(rows, MAX_ACCEPTED_ERROR))
        self.assertEqual([r.threshold for r in rows], list(THRESHOLDS))

    def test_saved_files_and_report(self):
        loaded = load_model(self.model_path, expected_variant=FINAL_VARIANT)
        X, _, _ = samples_to_arrays(self.samples)
        np.testing.assert_allclose(loaded.pipeline.predict_proba(X), self.final.model.pipeline.predict_proba(X))
        self.assertEqual(loaded.recommended_threshold, self.final.model.recommended_threshold)
        checksum = training.file_sha256(self.model_path)
        self.assertEqual(self.checksum_path.read_text(encoding="utf-8"), "%s  model.joblib\n" % checksum)
        report = self.report_path.read_text(encoding="utf-8")
        for text in ("Variant: %s" % FINAL_VARIANT, "Classifier: %s" % FINAL_CLASSIFIER,
                     "method=%r" % CALIBRATION_METHOD, "## Confidence thresholds", "errors among accepted",
                     "## Poses that are not letters", "letter_blend", "finger_mix", checksum,
                     "scikit-learn %s" % sklearn.__version__):
            self.assertIn(text, report)
        t = self.final.model.recommended_threshold
        if t is not None:
            self.assertIn("reach the recommended threshold %.2f" % t, report)


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
    def test_folds_divide_people_75_25(self):
        _, y, groups = samples_to_arrays(load_dataset())
        folds = make_folds(groups)
        self.assertEqual(len(folds), 4)
        val_people = []
        for train, val in folds:
            train_p, val_p = set(groups[train]), set(groups[val])
            self.assertEqual((len(train_p), len(val_p)), (9, 3))
            self.assertEqual((len(train), len(val)), (720, 240))
            self.assertFalse(train_p & val_p)
            val_people += sorted(val_p)
        self.assertEqual(sorted(val_people), list(range(1, 13)))
        self.assertEqual(sorted(i for _, val in folds for i in val), list(range(len(y))))

    def test_nested_inner_folds_divide_outer_training_people_6_3(self):
        _, _, groups = samples_to_arrays(load_dataset())
        for train, test in make_folds(groups):
            inner_groups = groups[train]
            for itrain, ival in make_folds(inner_groups, 3):
                train_p, val_p = set(inner_groups[itrain]), set(inner_groups[ival])
                self.assertEqual((len(train_p), len(val_p)), (6, 3))
                self.assertFalse((train_p | val_p) & set(groups[test]))


@unittest.skipUnless(HAVE_SKLEARN, "numpy/scikit-learn not installed")
class NestedCVTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.X, cls.y, cls.groups = samples_to_arrays(synthetic_samples(subjects=range(1, 9)))

    def run_nested(self):
        calls = []
        real = training.select_best

        def spy(X, y, groups, *args, **kwargs):
            calls.append((len(X), set(np.unique(groups))))
            return real(X, y, groups, *args, **kwargs)

        with mock.patch.object(training, "select_best", side_effect=spy):
            results = training.nested_evaluate(self.X, self.y, self.groups, ["V0", "V2"], ["knn"],
                                               outer_splits=4, inner_splits=3)
        return results, calls

    def test_selection_never_sees_test_people(self):
        results, calls = self.run_nested()
        self.assertEqual(len(results), len(calls), 4)
        for r, (n_rows, people) in zip(results, calls):
            self.assertFalse(people & set(r.test_people))
            self.assertEqual(people | set(r.test_people), set(range(1, 9)))
            self.assertEqual(n_rows, int(np.isin(self.groups, list(people)).sum()))

    def test_every_person_is_tested_once(self):
        results, _ = self.run_nested()
        tested = sorted(p for r in results for p in r.test_people)
        self.assertEqual(tested, list(range(1, 9)))

    def test_choice_is_one_of_the_candidates(self):
        results, _ = self.run_nested()
        for r in results:
            self.assertIn((r.variant, r.classifier), {("V0", "knn"), ("V2", "knn")})
            self.assertTrue(0.0 <= r.test_accuracy <= 1.0)

    def test_test_labels_do_not_affect_choice(self):
        outer = make_folds(self.groups, 4)
        train, test = outer[0]
        y_scrambled = self.y.copy()
        y_scrambled[test] = np.roll(y_scrambled[test], 7)
        a = training.nested_evaluate(self.X, self.y, self.groups, ["V0", "V2"], ["knn"], 4, 3)[0]
        b = training.nested_evaluate(self.X, y_scrambled, self.groups, ["V0", "V2"], ["knn"], 4, 3)[0]
        self.assertEqual((a.variant, a.classifier, a.inner_accuracy),
                         (b.variant, b.classifier, b.inner_accuracy))


@unittest.skipUnless(HAVE_SKLEARN, "numpy/scikit-learn not installed")
class FoldTests(unittest.TestCase):
    GROUPS = np.repeat(np.arange(1, 13), 5) if HAVE_SKLEARN else None

    def test_folds_are_reproducible_and_seeded(self):
        a = make_folds(self.GROUPS, 4, seed=42)
        b = make_folds(self.GROUPS, 4, seed=42)
        c = make_folds(self.GROUPS, 4, seed=7)
        val_people = lambda folds: [sorted(set(self.GROUPS[v])) for _, v in folds]
        self.assertEqual(val_people(a), val_people(b))
        self.assertNotEqual(val_people(a), val_people(c))

    def test_people_are_shuffled(self):
        in_order = [[1, 2, 3], [4, 5, 6], [7, 8, 9], [10, 11, 12]]
        self.assertNotEqual([sorted(set(self.GROUPS[v])) for _, v in make_folds(self.GROUPS)],
                            in_order)

    def test_invalid_split_count_is_rejected(self):
        for n in (1, 13):
            with self.subTest(n=n), self.assertRaises(ValueError):
                make_folds(self.GROUPS, n)


if __name__ == "__main__":
    unittest.main()
