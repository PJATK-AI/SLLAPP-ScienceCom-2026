import contextlib
import io
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from model import (DATASET_ENV_VAR, DEFAULT_DATASET_DIR, LANDMARK_NAMES, DatasetValidationError,
                   get_dataset_dir, load_dataset, validate_dataset)
from model.validation import main
from model.tests.helpers import _TEMPLATE, dump, make_raw, write_dataset


class SyntheticDatasetCase(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.dir = self._tmp.name
        write_dataset(self.dir)

    def path(self, name):
        return os.path.join(self.dir, name)

    def failed(self, report):
        return [c.title for c in report.checks if not c.ok]


class ValidDatasetTests(SyntheticDatasetCase):
    def test_complete_dataset_is_valid(self):
        report = validate_dataset(self.dir)
        self.assertTrue(report.ok, report.format())
        self.assertEqual(len(report.samples), 960)
        self.assertEqual(report.warnings, [])

    def test_load_dataset_returns_all_samples(self):
        samples = load_dataset(self.dir)
        self.assertEqual(len(samples), 960)
        s = samples[0]
        self.assertEqual(len(s.landmarks), 21)
        self.assertEqual(len(s.landmarks[0]), 3)
        self.assertEqual(len(LANDMARK_NAMES), 21)

    def test_loader_parses_labels(self):
        by_name = {s.name: s for s in load_dataset(self.dir)}
        s = by_name["C_6_2"]
        self.assertEqual((s.label, s.subject, s.repetition), ("C", 6, 2))


class InvalidDatasetTests(SyntheticDatasetCase):
    def test_missing_sample_is_reported(self):
        os.remove(self.path("A_1_1"))
        report = validate_dataset(self.dir)
        self.assertFalse(report.ok)
        self.assertTrue(any("missing: A_1_1" in d for d in report.issues), report.issues)
        self.assertTrue(any("60 samples per letter" in t for t in self.failed(report)))

    def test_nan_value_is_reported_with_file_name(self):
        raw = make_raw()
        raw.nodesX_world[4] = float("nan")
        dump(raw, self.path("B_2_3"))
        report = validate_dataset(self.dir)
        self.assertFalse(report.ok)
        self.assertTrue(any("B_2_3" in d and "non-finite" in d for d in report.issues), report.issues)
        self.assertNotIn("B_2_3", report.samples)

    def test_infinite_none_and_string_values_are_reported(self):
        for name, bad in (("A_1_1", float("inf")), ("A_1_2", None), ("A_1_3", "0.5")):
            raw = make_raw()
            raw.nodesY_world[0] = bad
            dump(raw, self.path(name))
        report = validate_dataset(self.dir)
        for name in ("A_1_1", "A_1_2", "A_1_3"):
            self.assertTrue(any(name in d for d in report.issues), name)

    def test_wrong_landmark_count_is_reported(self):
        raw = make_raw()
        raw.nodesZ_world.pop()
        dump(raw, self.path("E_3_4"))
        report = validate_dataset(self.dir)
        self.assertTrue(any("E_3_4" in d and "20 landmarks" in d for d in report.issues), report.issues)
        self.assertTrue(any("differ in length" in d for d in report.issues))

    def test_missing_attribute_is_reported(self):
        raw = make_raw()
        del raw.nodesZ_world
        dump(raw, self.path("I_4_4"))
        report = validate_dataset(self.dir)
        self.assertTrue(any("I_4_4" in d and "Z: missing" in d for d in report.issues), report.issues)

    def test_invalid_file_name_is_reported(self):
        with open(self.path("notes.txt"), "w") as fh:
            fh.write("x")
        report = validate_dataset(self.dir)
        self.assertFalse(report.ok)
        self.assertTrue(any("notes.txt" in d for d in report.issues))

    def test_extra_subject_is_reported(self):
        dump(make_raw(), self.path("A_13_1"))
        report = validate_dataset(self.dir)
        self.assertFalse(report.ok)
        self.assertTrue(any("12 subjects" in t for t in self.failed(report)))

    def test_missing_directory_is_reported(self):
        report = validate_dataset(os.path.join(self.dir, "does-not-exist"))
        self.assertFalse(report.ok)
        self.assertIn("Dataset directory exists", self.failed(report))

    def test_empty_directory_is_invalid(self):
        with tempfile.TemporaryDirectory() as empty:
            self.assertFalse(validate_dataset(empty).ok)


class RestrictedLoaderTests(SyntheticDatasetCase):
    def test_malicious_pickle_is_rejected_and_not_executed(self):
        marker = self.path("pwned.marker")

        class Evil:
            def __reduce__(self):
                return (open, (marker, "w"))

        import pickle
        with open(self.path("A_1_1"), "wb") as fh:
            pickle.dump(Evil(), fh, protocol=4)
        report = validate_dataset(self.dir)
        self.assertFalse(os.path.exists(marker), "payload must not be executed")
        self.assertTrue(any("A_1_1" in d and "forbidden object" in d for d in report.issues), report.issues)

    def test_corrupt_file_is_reported(self):
        with open(self.path("A_1_1"), "wb") as fh:
            fh.write(b"not a pickle")
        report = validate_dataset(self.dir)
        self.assertTrue(any("A_1_1" in d and "cannot be read" in d for d in report.issues))


class LoadDatasetErrorTests(SyntheticDatasetCase):
    def test_load_dataset_raises_with_report_instead_of_skipping(self):
        raw = make_raw()
        raw.nodesX_world[0] = float("nan")
        dump(raw, self.path("L_5_5"))
        with self.assertRaises(DatasetValidationError) as ctx:
            load_dataset(self.dir)
        self.assertFalse(ctx.exception.report.ok)
        self.assertIn("L_5_5", str(ctx.exception))


class LandmarkOrderTests(SyntheticDatasetCase):
    def _swap(self, name, i, j):
        pts = list(_TEMPLATE)
        pts[i], pts[j] = pts[j], pts[i]
        dump(make_raw(pts), self.path(name))

    def test_swapped_landmarks_give_warning(self):
        self._swap("M_7_3", 4, 8)
        report = validate_dataset(self.dir)
        self.assertTrue(report.ok)  # heuristic: warning only by default
        self.assertTrue(any("M_7_3" in w for w in report.warnings), report.warnings)

    def test_strict_order_turns_warning_into_failure(self):
        self._swap("M_7_3", 4, 8)
        report = validate_dataset(self.dir, strict_order=True)
        self.assertFalse(report.ok)
        self.assertTrue(any("M_7_3" in d for d in report.issues))


class ConfigAndCliTests(SyntheticDatasetCase):
    def test_default_location(self):
        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop(DATASET_ENV_VAR, None)
            self.assertEqual(get_dataset_dir(), DEFAULT_DATASET_DIR)

    def test_env_var_overrides_default_and_explicit_path_wins(self):
        with mock.patch.dict(os.environ, {DATASET_ENV_VAR: self.dir}):
            self.assertEqual(get_dataset_dir(), Path(self.dir))
            self.assertEqual(get_dataset_dir("/other"), Path("/other"))
            self.assertTrue(validate_dataset().ok)

    def _run(self, *args):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = main(list(args))
        return code, out.getvalue()

    def test_cli_exit_code_zero_for_valid_dataset(self):
        code, out = self._run(self.dir)
        self.assertEqual(code, 0)
        self.assertIn("12/12 checks passed", out)

    def test_cli_exit_code_one_for_invalid_dataset(self):
        os.remove(self.path("A_1_1"))
        code, out = self._run(self.dir)
        self.assertEqual(code, 1)
        self.assertIn("A_1_1", out)


@unittest.skipUnless(DEFAULT_DATASET_DIR.is_dir(), "SPAS dataset not present locally")
class RealDatasetTests(unittest.TestCase):
    def test_supplied_dataset_is_valid(self):
        report = validate_dataset()
        self.assertTrue(report.ok, report.format())
        self.assertEqual(len(report.samples), 960)
        self.assertEqual(report.warnings, [])


if __name__ == "__main__":
    unittest.main()
