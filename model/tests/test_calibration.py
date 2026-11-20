import os
import re
import tempfile
import unittest
from pathlib import Path
from unittest import mock

try:
    import joblib
    import numpy as np
    import sklearn
    from sklearn.calibration import CalibratedClassifierCV

    from model import calibration, training
    from model.calibration import (NON_LETTER_KINDS, ThresholdRow, build_calibrated, expected_calibration_error,
                                   held_out_evaluate, non_letter_poses, recommend_threshold)
    from model.service import ModelLoadError, load_service
    from model.training import (VARIANTS, ModelVersionError, build_pipeline, get_variant, load_model,
                                read_model_metadata, samples_to_arrays, train_model)
    HAVE_SKLEARN = True
except ImportError:  # pragma: no cover - training dependencies are optional
    HAVE_SKLEARN = False

from model.tests.test_training import synthetic_samples

REQUIREMENTS = Path(__file__).resolve().parents[1] / "requirements.txt"


@unittest.skipUnless(HAVE_SKLEARN, "numpy/scikit-learn not installed")
class CalibrationFoldTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.samples = synthetic_samples(subjects=range(1, 7))
        cls.X, cls.y, cls.groups = samples_to_arrays(cls.samples)

    def test_calibration_folds_divide_people(self):
        model = build_calibrated(build_pipeline(get_variant("V3")), self.groups, n_splits=3)
        self.assertIsInstance(model, CalibratedClassifierCV)
        self.assertFalse(model.ensemble)
        held_out = []
        for train, val in model.cv:
            self.assertFalse(set(self.groups[train]) & set(self.groups[val]))
            self.assertEqual(len(train) + len(val), len(self.groups))
            held_out += sorted(set(self.groups[val]))
        self.assertEqual(sorted(held_out), list(range(1, 7)))

    def test_calibrator_sees_only_predictions_for_held_out_people(self):
        model = build_calibrated(build_pipeline(get_variant("V3")), self.groups, n_splits=3)
        fitted_on = []
        real_fit = training.Pipeline.fit

        def spy(pipe, X, y=None, **kw):
            fitted_on.append(len(X))
            return real_fit(pipe, X, y, **kw)

        with mock.patch.object(training.Pipeline, "fit", spy):
            model.fit(self.X, self.y)
        # one fit per calibration fold (training people only), then one fit on all rows
        self.assertEqual(fitted_on, [len(train) for train, _ in model.cv] + [len(self.y)])
        self.assertEqual(len(model.calibrated_classifiers_), 1)

    def test_held_out_calibration_never_sees_test_people(self):
        calls = []
        real = calibration.build_calibrated

        def spy(pipeline, groups, *args, **kwargs):
            calls.append(set(np.unique(groups)))
            return real(pipeline, groups, *args, **kwargs)

        with mock.patch.object(calibration, "build_calibrated", side_effect=spy):
            result = held_out_evaluate(self.X, self.y, self.groups, "V3", "logreg", outer_splits=3,
                                       calibration_splits=2)
        self.assertEqual(len(calls), 3)
        for people, (train_people, test_people) in zip(calls, result.folds):
            self.assertEqual(people, set(train_people))
            self.assertFalse(people & set(test_people))
        self.assertFalse(np.isnan(result.proba).any())

    def test_unknown_method_is_rejected(self):
        with self.assertRaises(ValueError):
            build_calibrated(build_pipeline(get_variant("V3")), self.groups, method="platt")


@unittest.skipUnless(HAVE_SKLEARN, "numpy/scikit-learn not installed")
class ProbabilityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.samples = synthetic_samples(subjects=range(1, 7))
        cls.X, cls.y, cls.groups = samples_to_arrays(cls.samples)
        cls.models = {(vid, clf): train_model(cls.samples, vid, clf, calibration="temperature")
                      for vid, clf in (("V3", "logreg"), ("V2", "svm"))}

    def test_probabilities_for_every_letter_sum_to_one(self):
        poses = non_letter_poses(self.X, self.y, self.groups)
        for key, model in self.models.items():
            for name, rows in [("letters", self.X)] + list(poses.items()):
                with self.subTest(model=key, rows=name):
                    proba = model.pipeline.predict_proba(rows)
                    self.assertEqual(proba.shape, (len(rows), 3))
                    np.testing.assert_allclose(proba.sum(axis=1), 1.0, atol=1e-9)
                    self.assertTrue(((proba >= 0) & (proba <= 1)).all())

    def test_svm_gets_probabilities_without_svc_probability(self):
        model = self.models[("V2", "svm")]
        svc = training.base_pipeline(model.pipeline).named_steps["classifier"]
        self.assertIn(svc.probability, (False, "deprecated"))
        self.assertFalse(hasattr(svc, "predict_proba"))
        self.assertTrue(hasattr(model.pipeline, "predict_proba"))


@unittest.skipUnless(HAVE_SKLEARN, "numpy/scikit-learn not installed")
class NonLetterPoseTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.X, cls.y, cls.groups = samples_to_arrays(synthetic_samples(subjects=range(1, 4)))
        cls.poses = non_letter_poses(cls.X, cls.y, cls.groups, seed=1)

    def test_every_kind_is_generated(self):
        self.assertEqual(set(self.poses), set(NON_LETTER_KINDS))
        for kind, rows in self.poses.items():
            with self.subTest(kind=kind):
                self.assertEqual(rows.shape[1], 63)
                self.assertTrue(np.isfinite(rows).all())
        self.assertEqual(len(self.poses["letter_blend"]), 3 * 3)  # 3 letter pairs x 3 people
        self.assertEqual(len(self.poses["upside_down"]), len(self.X))

    def test_blends_mix_two_different_letters(self):
        rng = np.random.default_rng(0)
        blends = calibration.letter_blends(self.X, self.y, self.groups, rng)
        # the first blend is person 1, letters A and B: the mean of one A and one B sample of that person
        person = np.flatnonzero(self.groups == 1)
        a = self.X[person][self.y[person] == "A"]
        b = self.X[person][self.y[person] == "B"]
        expected = {tuple(np.round(0.5 * ra + 0.5 * rb, 9)) for ra in a for rb in b}
        self.assertIn(tuple(np.round(blends[0], 9)), expected)

    def test_upside_down_keeps_the_wrist_and_flips_the_hand(self):
        p = self.X[0].reshape(21, 3)
        q = self.poses["upside_down"][0].reshape(21, 3)
        np.testing.assert_allclose(q[0], p[0])
        np.testing.assert_allclose(q[:, :2] - q[0, :2], -(p[:, :2] - p[0, :2]))
        np.testing.assert_allclose(q[:, 2], p[:, 2])

    def test_noise_keeps_the_wrist(self):
        np.testing.assert_allclose(self.poses["noise"][:, :3], self.X[:, :3])


class ThresholdTests(unittest.TestCase):
    def test_rows(self):
        row = ThresholdRow(0.8, accepted=50, total=100, errors=1)
        self.assertEqual((row.accepted_share, row.error_rate), (0.5, 0.02))
        self.assertIsNone(ThresholdRow(0.99, 0, 100, 0).error_rate)

    def test_recommendation_is_the_lowest_threshold_within_the_error_target(self):
        rows = [ThresholdRow(0.5, 100, 100, 5), ThresholdRow(0.7, 90, 100, 1), ThresholdRow(0.9, 80, 100, 0),
                ThresholdRow(0.95, 0, 100, 0)]
        self.assertEqual(recommend_threshold(rows, 0.02), 0.7)
        self.assertEqual(recommend_threshold(rows, 0.0), 0.9)
        self.assertIsNone(recommend_threshold(rows[:1], 0.01))

    @unittest.skipUnless(HAVE_SKLEARN, "numpy not installed")
    def test_calibration_error(self):
        self.assertAlmostEqual(expected_calibration_error([0.9] * 10, [True] * 9 + [False]), 0.0)
        self.assertAlmostEqual(expected_calibration_error([1.0] * 4, [True, True, False, False]), 0.5)


@unittest.skipUnless(HAVE_SKLEARN, "numpy/scikit-learn not installed")
class ModelFileMetadataTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.samples = synthetic_samples()
        cls.model = train_model(cls.samples, "V3", "logreg", calibration="temperature", recommended_threshold=0.8)
        cls.tmp = tempfile.TemporaryDirectory()
        cls.path = cls.model.save(os.path.join(cls.tmp.name, "model.joblib"))

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_file_records_variant_classifier_calibration_and_versions(self):
        data = read_model_metadata(self.path)
        self.assertEqual(data["variant"], {"id": "V3", "scale_method": "wrist_middle_mcp", "standardize": True,
                                           "description": VARIANTS["V3"].description})
        self.assertEqual((data["classifier"], data["calibration"], data["recommended_threshold"]),
                         ("logreg", "temperature", 0.8))
        self.assertEqual(data["sklearn_version"], sklearn.__version__)
        self.assertEqual(data["numpy_version"], np.__version__)
        self.assertEqual(data["letters"], ["A", "B", "C"])

    def test_loaded_model_keeps_the_stored_values(self):
        loaded = load_model(self.path, expected_variant="V3")
        self.assertEqual(loaded.variant, VARIANTS["V3"])
        self.assertEqual((loaded.classifier, loaded.calibration, loaded.recommended_threshold),
                         ("logreg", "temperature", 0.8))
        self.assertIsInstance(loaded.pipeline, CalibratedClassifierCV)

    def test_other_scikit_learn_version_fails_before_unpickling(self):
        with mock.patch.object(training.sklearn, "__version__", "0.0.1"), \
                mock.patch.object(training.pickle, "loads") as loads:
            with self.assertRaises(ModelVersionError) as ctx:
                load_model(self.path)
            loads.assert_not_called()
        message = str(ctx.exception)
        self.assertIn("trained with scikit-learn %s" % sklearn.__version__, message)
        self.assertIn("scikit-learn 0.0.1 is installed", message)
        self.assertIn("pip install -r model/requirements.txt", message)

    def test_service_refuses_other_scikit_learn_version(self):
        with mock.patch.object(training.sklearn, "__version__", "0.0.1"):
            with self.assertRaisesRegex(ModelLoadError, "trained with scikit-learn .* 0.0.1 is installed"):
                load_service(self.path, "V3")

    def test_file_without_version_is_rejected(self):
        data = read_model_metadata(self.path)
        del data["sklearn_version"]
        path = os.path.join(self.tmp.name, "no-version.joblib")
        joblib.dump(data, path)
        with self.assertRaisesRegex(ModelVersionError, "trained with scikit-learn None"):
            load_model(path)

    def test_old_format_is_rejected(self):
        path = os.path.join(self.tmp.name, "old.joblib")
        joblib.dump({"format": 1, "pipeline": self.model.pipeline}, path)
        with self.assertRaisesRegex(ValueError, "not a model file of format 2"):
            load_model(path)

    def test_calibration_must_match_the_pipeline(self):
        with self.assertRaises(ValueError):
            training.TrainedModel(self.model.pipeline, VARIANTS["V3"], "logreg", calibration=None)
        with self.assertRaises(ValueError):
            training.TrainedModel(self.model.pipeline, VARIANTS["V3"], "logreg", calibration="sigmoid")


class RequirementsTests(unittest.TestCase):
    def test_training_dependencies_are_pinned(self):
        lines = [l.strip() for l in REQUIREMENTS.read_text(encoding="utf-8").splitlines()
                 if l.strip() and not l.startswith("#")]
        names = set()
        for line in lines:
            with self.subTest(line=line):
                self.assertRegex(line, r"^[A-Za-z0-9_.-]+==\d+(\.\d+)+$")
            names.add(line.split("==")[0].lower())
        self.assertTrue({"scikit-learn", "numpy", "scipy", "joblib", "threadpoolctl"} <= names)

    @unittest.skipUnless(HAVE_SKLEARN, "scikit-learn not installed")
    def test_installed_scikit_learn_is_the_pinned_one(self):
        pinned = re.search(r"^scikit-learn==(\S+)$", REQUIREMENTS.read_text(encoding="utf-8"), re.M).group(1)
        self.assertEqual(sklearn.__version__, pinned,
                         "install the pinned versions: pip install -r model/requirements.txt")


if __name__ == "__main__":
    unittest.main()
