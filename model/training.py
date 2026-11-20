import argparse
import hashlib
import pickle
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Callable, Dict, Iterable, List, Mapping, Optional, Sequence, Tuple

import joblib
import numpy as np
import sklearn
from sklearn.base import BaseEstimator, TransformerMixin
from sklearn.calibration import CalibratedClassifierCV
from sklearn.ensemble import RandomForestClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score, f1_score
from sklearn.model_selection import KFold, cross_validate
from sklearn.neighbors import KNeighborsClassifier
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler
from sklearn.svm import SVC

from .calibration import (CALIBRATION_METHODS, NON_LETTER_KINDS, HeldOutResult, build_calibrated,
                          held_out_evaluate, recommend_threshold)
from .config import (CALIBRATION_METHOD, CALIBRATION_SPLITS, CV_SPLITS, DEFAULT_ARTIFACTS_DIR, DEFAULT_CLASSIFIERS,
                     DEFAULT_VARIANT, FINAL_CLASSIFIER, FINAL_VARIANT, INNER_CV_SPLITS, MAX_ACCEPTED_ERROR,
                     N_LANDMARKS, RANDOM_SEED, THRESHOLDS)
from .preprocessing import (SCALE_METHODS, LandmarkValidationError, preprocess_landmarks,
                            validate_landmarks)

N_FEATURES = N_LANDMARKS * 3
#: Format 2: plain metadata + the pickled pipeline as bytes, so that the scikit-learn version is
#: checked before any scikit-learn object is unpickled.
MODEL_FORMAT = 2


@dataclass(frozen=True)
class Variant:
    id: str
    scale_method: str
    standardize: bool
    description: str


VARIANTS: Dict[str, Variant] = {v.id: v for v in (
    Variant("V0", "none", False, "baseline: wrist-centred only"),
    Variant("V1", "none", True, "StandardScaler only"),
    Variant("V2", "wrist_middle_mcp", False, "scale wrist->middle MCP only"),
    Variant("V3", "wrist_middle_mcp", True, "scale wrist->middle MCP + StandardScaler"),
    Variant("V4", "palm", True, "scale mean wrist->MCPs + StandardScaler"),
    Variant("V5", "max_extent", True, "scale max wrist distance + StandardScaler"),
)}


def get_variant(variant_id: str) -> Variant:
    try:
        return VARIANTS[variant_id]
    except KeyError:
        raise ValueError("unknown variant %r (expected one of %s)"
                         % (variant_id, ", ".join(VARIANTS))) from None


CLASSIFIERS: Dict[str, Callable[[int], BaseEstimator]] = {
    "logreg": lambda seed: LogisticRegression(max_iter=5000, random_state=seed),
    "svm": lambda seed: SVC(kernel="rbf", C=10.0, gamma="scale", random_state=seed),
    "knn": lambda seed: KNeighborsClassifier(n_neighbors=5),
    "random_forest": lambda seed: RandomForestClassifier(n_estimators=300, random_state=seed),
}


def make_classifier(name: str, seed: int = RANDOM_SEED) -> BaseEstimator:
    try:
        return CLASSIFIERS[name](seed)
    except KeyError:
        raise ValueError("unknown classifier %r (expected one of %s)"
                         % (name, ", ".join(CLASSIFIERS))) from None


# features

def landmarks_to_row(landmarks: Any) -> List[float]:
    return [v for point in validate_landmarks(landmarks) for v in point]


def samples_to_arrays(samples: Iterable[Any]):
    rows, labels, groups = [], [], []
    for s in samples:
        try:
            rows.append(landmarks_to_row(s.landmarks))
        except LandmarkValidationError as exc:
            raise LandmarkValidationError("%s: %s" % (getattr(s, "name", "?"), exc)) from exc
        labels.append(s.label)
        groups.append(s.subject)
    return np.asarray(rows, dtype=float), np.asarray(labels), np.asarray(groups)


class LandmarkPreprocessor(BaseEstimator, TransformerMixin):
    def __init__(self, scale_method: str = "none"):
        self.scale_method = scale_method

    def fit(self, X, y=None):
        if self.scale_method not in SCALE_METHODS:
            raise ValueError("unknown scale method %r" % (self.scale_method,))
        self.n_features_in_ = N_FEATURES
        return self

    def transform(self, X):
        X = np.asarray(X, dtype=float)
        if X.ndim != 2 or X.shape[1] != N_FEATURES:
            raise ValueError("expected shape (n, %d), got %r" % (N_FEATURES, X.shape))
        out = np.empty_like(X)
        for i, row in enumerate(X):
            points = row.reshape(N_LANDMARKS, 3).tolist()
            try:
                out[i] = np.ravel(preprocess_landmarks(points, self.scale_method))
            except LandmarkValidationError as exc:
                raise LandmarkValidationError("row %d: %s" % (i, exc)) from exc
        return out


def build_pipeline(variant: Variant, classifier: str = "logreg",
                   seed: int = RANDOM_SEED) -> Pipeline:
    steps = [("preprocess", LandmarkPreprocessor(variant.scale_method))]
    if variant.standardize:
        steps.append(("standardize", StandardScaler()))
    steps.append(("classifier", make_classifier(classifier, seed)))
    return Pipeline(steps)


# cross-validated comparison

@dataclass(frozen=True)
class CVResult:
    variant: str
    classifier: str
    accuracy_mean: float
    accuracy_std: float
    f1_macro_mean: float
    f1_macro_std: float


def make_folds(groups, n_splits: int = CV_SPLITS, seed: int = RANDOM_SEED):
    groups = np.asarray(groups)
    subjects = np.unique(groups)
    if not 2 <= n_splits <= len(subjects):
        raise ValueError("n_splits must be between 2 and the number of people (%d), got %d"
                         % (len(subjects), n_splits))
    folds = []
    for train_s, val_s in KFold(n_splits=n_splits, shuffle=True, random_state=seed).split(subjects):
        val = np.isin(groups, subjects[val_s])
        folds.append((np.flatnonzero(~val), np.flatnonzero(val)))
    return folds


def evaluate(X, y, groups, variants: Sequence[str] = tuple(VARIANTS),
             classifiers: Sequence[str] = DEFAULT_CLASSIFIERS,
             n_splits: int = CV_SPLITS, seed: int = RANDOM_SEED,
             n_jobs: Optional[int] = None) -> List[CVResult]:
    folds = make_folds(groups, n_splits, seed)
    results = []
    for vid in variants:
        variant = get_variant(vid)
        for clf in classifiers:
            scores = cross_validate(build_pipeline(variant, clf, seed), X, y, cv=folds,
                                    scoring=("accuracy", "f1_macro"), n_jobs=n_jobs)
            acc, f1 = scores["test_accuracy"], scores["test_f1_macro"]
            results.append(CVResult(vid, clf, float(acc.mean()), float(acc.std()),
                                    float(f1.mean()), float(f1.std())))
    return results


def format_results(results: Sequence[CVResult]) -> str:
    lines = ["| variant | description | classifier | accuracy | macro-F1 |",
             "|---|---|---|---|---|"]
    for r in results:
        lines.append("| %s | %s | %s | %.3f +/- %.3f | %.3f +/- %.3f |" % (
            r.variant, VARIANTS[r.variant].description, r.classifier,
            r.accuracy_mean, r.accuracy_std, r.f1_macro_mean, r.f1_macro_std))
    return "\n".join(lines)


# nested cross-validation

@dataclass(frozen=True)
class NestedFoldResult:
    fold: int
    test_people: Tuple[int, ...]
    variant: str
    classifier: str
    inner_accuracy: float
    test_accuracy: float
    test_f1_macro: float


def select_best(X, y, groups, variants: Sequence[str], classifiers: Sequence[str],
                n_splits: int = INNER_CV_SPLITS, seed: int = RANDOM_SEED) -> CVResult:
    return best_result(evaluate(X, y, groups, variants, classifiers, n_splits, seed))


def best_result(results: Sequence[CVResult]) -> CVResult:
    return max(results, key=lambda r: r.accuracy_mean)


def nested_evaluate(X, y, groups, variants: Sequence[str] = tuple(VARIANTS),
                    classifiers: Sequence[str] = DEFAULT_CLASSIFIERS,
                    outer_splits: int = CV_SPLITS, inner_splits: int = INNER_CV_SPLITS,
                    seed: int = RANDOM_SEED) -> List[NestedFoldResult]:
    X, y, groups = np.asarray(X), np.asarray(y), np.asarray(groups)
    results = []
    for i, (train, test) in enumerate(make_folds(groups, outer_splits, seed), start=1):
        best = select_best(X[train], y[train], groups[train], variants, classifiers,
                           inner_splits, seed)
        model = build_pipeline(get_variant(best.variant), best.classifier, seed)
        pred = model.fit(X[train], y[train]).predict(X[test])
        results.append(NestedFoldResult(
            i, tuple(int(p) for p in np.unique(groups[test])), best.variant, best.classifier,
            best.accuracy_mean, float(accuracy_score(y[test], pred)),
            float(f1_score(y[test], pred, average="macro"))))
    return results


def format_nested_results(results: Sequence[NestedFoldResult]) -> str:
    lines = ["| outer fold | test people | chosen variant | chosen classifier | inner val accuracy "
             "| test accuracy | test macro-F1 |", "|---|---|---|---|---|---|---|"]
    for r in results:
        lines.append("| %d | %s | %s | %s | %.3f | %.3f | %.3f |" % (
            r.fold, ", ".join(map(str, r.test_people)), r.variant, r.classifier,
            r.inner_accuracy, r.test_accuracy, r.test_f1_macro))
    acc = np.array([r.test_accuracy for r in results])
    f1 = np.array([r.test_f1_macro for r in results])
    lines.append("\nNested CV estimate (test people never used for selection): "
                 "accuracy %.3f +/- %.3f, macro-F1 %.3f +/- %.3f"
                 % (acc.mean(), acc.std(), f1.mean(), f1.std()))
    return "\n".join(lines)


#  trained model: save, load, inference

class VariantMismatchError(ValueError):
    pass


class ModelVersionError(ValueError):
    pass


def base_pipeline(estimator):
    # the uncalibrated Pipeline inside a CalibratedClassifierCV (fitted on all rows when fitted)
    if isinstance(estimator, CalibratedClassifierCV):
        fitted = getattr(estimator, "calibrated_classifiers_", None)
        return fitted[0].estimator if fitted else estimator.estimator
    return estimator


def _check_pipeline_matches(pipeline, variant: Variant) -> None:
    pipeline = base_pipeline(pipeline)
    if not isinstance(pipeline, Pipeline):
        raise VariantMismatchError("model is not a pipeline of variant %s" % variant.id)
    steps = dict(pipeline.steps)
    pre = steps.get("preprocess")
    if not isinstance(pre, LandmarkPreprocessor) or pre.scale_method != variant.scale_method:
        raise VariantMismatchError("pipeline preprocessing does not match variant %s" % variant.id)
    if ("standardize" in steps) != variant.standardize:
        raise VariantMismatchError("pipeline scaler does not match variant %s" % variant.id)


class TrainedModel:
    def __init__(self, pipeline, variant: Variant, classifier: str, calibration: Optional[str] = None,
                 recommended_threshold: Optional[float] = None):
        _check_pipeline_matches(pipeline, variant)
        if isinstance(pipeline, CalibratedClassifierCV):
            if pipeline.method != calibration:
                raise ValueError("model is calibrated with %r, not %r" % (pipeline.method, calibration))
        elif calibration is not None:
            raise ValueError("model is not calibrated, but calibration %r is given" % calibration)
        self.pipeline = pipeline
        self.variant = variant
        self.classifier = classifier
        self.calibration = calibration
        self.recommended_threshold = recommended_threshold

    @property
    def classes(self) -> List[str]:
        return [str(c) for c in self.pipeline.classes_]

    def predict_landmarks(self, landmarks: Any) -> str:
        return str(self.pipeline.predict(np.asarray([landmarks_to_row(landmarks)]))[0])

    def predict_hand(self, hand: Any) -> str:
        if not isinstance(hand, Mapping) or "landmarks" not in hand:
            raise LandmarkValidationError("hand must be an object with a 'landmarks' list")
        return self.predict_landmarks(hand["landmarks"])

    def metadata(self) -> Dict[str, Any]:
        return {"format": MODEL_FORMAT, "sklearn_version": sklearn.__version__,
                "numpy_version": np.__version__, "variant": asdict(self.variant),
                "classifier": self.classifier, "calibration": self.calibration,
                "recommended_threshold": self.recommended_threshold, "letters": self.classes}

    def save(self, path) -> Path:
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        data = self.metadata()
        data["pipeline"] = pickle.dumps(self.pipeline, protocol=pickle.HIGHEST_PROTOCOL)
        joblib.dump(data, path)
        return path


def train_model(samples: Iterable[Any], variant_id: str = DEFAULT_VARIANT,
                classifier: str = FINAL_CLASSIFIER, seed: int = RANDOM_SEED,
                calibration: Optional[str] = None, calibration_splits: int = CALIBRATION_SPLITS,
                recommended_threshold: Optional[float] = None) -> TrainedModel:
    variant = get_variant(variant_id)
    X, y, groups = samples_to_arrays(samples)
    estimator = build_pipeline(variant, classifier, seed)
    if calibration is not None:
        estimator = build_calibrated(estimator, groups, calibration, calibration_splits, seed)
    return TrainedModel(estimator.fit(X, y), variant, classifier, calibration, recommended_threshold)


def read_model_metadata(path) -> Dict[str, Any]:
    data = joblib.load(path)
    if not isinstance(data, dict) or data.get("format") != MODEL_FORMAT:
        raise ValueError("%s is not a model file of format %d" % (path, MODEL_FORMAT))
    return data


def load_model(path, expected_variant: Optional[str] = None) -> TrainedModel:
    data = read_model_metadata(path)
    trained_with = data.get("sklearn_version")
    if trained_with != sklearn.__version__:
        raise ModelVersionError(
            "model %s was trained with scikit-learn %s, but scikit-learn %s is installed; install the "
            "pinned versions with: pip install -r model/requirements.txt"
            % (path, trained_with, sklearn.__version__))
    stored = Variant(**data["variant"])
    if expected_variant is not None and stored.id != expected_variant:
        raise VariantMismatchError("model %s was trained with variant %s, expected %s"
                                   % (path, stored.id, expected_variant))
    known = VARIANTS.get(stored.id)
    if known is not None and known != stored:
        raise VariantMismatchError("variant %s in %s differs from the current definition"
                                   % (stored.id, path))
    model = TrainedModel(pickle.loads(data["pipeline"]), stored, data["classifier"], data["calibration"],
                         data["recommended_threshold"])
    if model.classes != list(data["letters"]):
        raise ValueError("letters of the pipeline in %s differ from the stored letters" % path)
    return model


def file_sha256(path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


# final training

@dataclass
class FinalRun:
    model: "TrainedModel"
    comparison: List[CVResult]
    held_out: HeldOutResult
    n_samples: int
    n_people: int
    outer_splits: int
    calibration_splits: int
    seed: int
    max_error: float

    @property
    def thresholds(self):
        return self.held_out.thresholds(THRESHOLDS)


def train_final(samples: Iterable[Any], variant_id: str = FINAL_VARIANT, classifier: str = FINAL_CLASSIFIER,
                calibration: str = CALIBRATION_METHOD, comparison_variants: Sequence[str] = tuple(VARIANTS),
                comparison_classifiers: Sequence[str] = DEFAULT_CLASSIFIERS,
                outer_splits: int = CV_SPLITS, calibration_splits: int = CALIBRATION_SPLITS,
                seed: int = RANDOM_SEED, max_error: float = MAX_ACCEPTED_ERROR,
                n_jobs: Optional[int] = None) -> FinalRun:
    samples = list(samples)
    X, y, groups = samples_to_arrays(samples)
    comparison = (evaluate(X, y, groups, comparison_variants, comparison_classifiers, outer_splits, seed, n_jobs)
                  if comparison_variants and comparison_classifiers else [])
    held_out = held_out_evaluate(X, y, groups, variant_id, classifier, calibration, outer_splits,
                                 calibration_splits, seed)
    threshold = recommend_threshold(held_out.thresholds(THRESHOLDS), max_error)
    model = train_model(samples, variant_id, classifier, seed, calibration, calibration_splits, threshold)
    return FinalRun(model, comparison, held_out, len(y), len(np.unique(groups)), outer_splits,
                    calibration_splits, seed, max_error)


def _pct(value: Optional[float]) -> str:
    return "-" if value is None else "%.1f%%" % (100 * value)


def format_threshold_table(run: FinalRun) -> str:
    kinds = list(NON_LETTER_KINDS)
    lines = ["| threshold | accepted letter samples | errors among accepted | "
             + " | ".join("%s reaching it" % k for k in kinds) + " |",
             "|---|---|---|" + "---|" * len(kinds)]
    for row in run.thresholds:
        reached = run.held_out.non_letters_reaching(row.threshold)
        mark = " (recommended)" if row.threshold == run.model.recommended_threshold else ""
        lines.append("| %.2f%s | %s (%d/%d) | %s (%d) | %s |" % (
            row.threshold, mark, _pct(row.accepted_share), row.accepted, row.total, _pct(row.error_rate),
            row.errors, " | ".join(_pct(reached[k][0] / reached[k][1]) for k in kinds)))
    return "\n".join(lines)


def format_report(run: FinalRun, checksum: Optional[str] = None) -> str:
    m, h = run.model, run.held_out
    t = m.recommended_threshold
    lines = [
        "# Model training report",
        "",
        "- Data: %d samples, %d people, %d letters" % (run.n_samples, run.n_people, len(m.classes)),
        "- Folds: KFold over people, %d folds for the held-out results, %d calibration folds over the "
        "training people, seed %d" % (run.outer_splits, run.calibration_splits, run.seed),
        "- scikit-learn %s, numpy %s" % (sklearn.__version__, np.__version__),
        "",
        "## Final model",
        "",
        "- Variant: %s (%s), fixed in `model/config.py` (`FINAL_VARIANT`)" % (m.variant.id, m.variant.description),
        "- Classifier: %s, fixed in `model/config.py` (`FINAL_CLASSIFIER`)" % m.classifier,
        "- Calibration: `CalibratedClassifierCV(method=%r, ensemble=False)` on %d folds by people"
        % (m.calibration, run.calibration_splits),
        "- Trained on all %d samples. The calibrator is fitted on predictions for the people held out "
        "of each calibration fold." % run.n_samples,
        "- Recommended confidence threshold for the backend: %s"
        % ("%.2f" % t if t is not None else "none (no threshold reaches the error target)"),
    ]
    if checksum:
        lines.append("- Model file SHA-256: `%s`" % checksum)
    lines += [
        "",
        "## Held-out people",
        "",
        "Each of the %d folds trains and calibrates the final pipeline on the training people only and "
        "scores the held-out people:" % run.outer_splits,
        "",
        "| fold | training people | held-out people |",
        "|---|---|---|",
    ]
    lines += ["| %d | %s | %s |" % (i, ", ".join(map(str, tr)), ", ".join(map(str, te)))
              for i, (tr, te) in enumerate(h.folds, start=1)]
    lines += [
        "",
        "Accuracy %.3f, macro-F1 %.3f, log loss %.3f, expected calibration error %.3f (10 bins)."
        % (h.accuracy, h.f1_macro, h.log_loss, h.calibration_error),
        "",
        "## Confidence thresholds",
        "",
        "A sample is accepted when the probability of its most probable letter is at least the threshold. "
        "Letter columns: all %d samples, each scored by the fold that held its person out. Non-letter "
        "columns: share of the poses below whose most probable letter reaches the threshold." % len(h.y),
        "",
        format_threshold_table(run),
        "",
        "Rule: the recommended threshold is the lowest one in the table with at most %s errors among "
        "the accepted held-out letter samples." % _pct(run.max_error),
        "",
        "## Poses that are not letters",
        "",
        "Generated from the samples of the held-out people of each fold, so the model never saw these people.",
    ]
    if t is not None:
        reached = h.non_letters_reaching(t)
        lines += ["", "| kind | poses | reaching %.2f | description |" % t, "|---|---|---|---|"]
        for kind, text in NON_LETTER_KINDS.items():
            a, n = reached[kind]
            lines.append("| %s | %d | %d (%s) | %s |" % (kind, n, a, _pct(a / n), text))
        total_a = sum(a for a, _ in reached.values())
        total_n = sum(n for _, n in reached.values())
        lines += ["", "%d of %d non-letter poses (%s) reach the recommended threshold %.2f."
                  % (total_a, total_n, _pct(total_a / total_n), t)]
    lines += [
        "",
        "The threshold alone does not reject poses that are not letters. The preprocessing does not "
        "normalize rotation, and the classifier can give a high confidence to poses far from the training "
        "data (see `upside_down` and `noise`). The backend should also require the same letter in several "
        "consecutive samples. These poses are synthetic: real non-letter poses at the booth are not measured.",
    ]
    if run.comparison:
        lines += [
            "",
            "## Uncalibrated variants and classifiers (context for the fixed choice)",
            "",
            "4 folds by people; the best score here is optimistic. Use `python -m model.training --nested` "
            "for the nested estimate.",
            "",
            format_results(run.comparison),
        ]
    lines.append("")
    return "\n".join(lines)


def write_final_run(run: FinalRun, out_dir) -> Tuple[Path, Path, Path]:
    out_dir = Path(out_dir)
    model_path = run.model.save(out_dir / "model.joblib")
    checksum = file_sha256(model_path)
    checksum_path = out_dir / "model.joblib.sha256"
    checksum_path.write_text("%s  %s\n" % (checksum, model_path.name), encoding="utf-8")
    report_path = out_dir / "report.md"
    report_path.write_text(format_report(run, checksum), encoding="utf-8")
    return model_path, checksum_path, report_path


# CLI

def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        prog="python -m model.training",
        description="Compare preprocessing variants with cross-validation, or train and save one.")
    parser.add_argument("dataset", nargs="?", help="dataset folder (default: see model.config)")
    parser.add_argument("--variants", nargs="+", default=list(VARIANTS), choices=list(VARIANTS))
    parser.add_argument("--classifiers", nargs="+", default=list(DEFAULT_CLASSIFIERS),
                        choices=list(CLASSIFIERS))
    parser.add_argument("--splits", type=int, default=CV_SPLITS)
    parser.add_argument("--nested", action="store_true",
                        help="nested CV: choose the variant/classifier on the outer training people "
                             "only and score it on the outer test people")
    parser.add_argument("--inner-splits", type=int, default=INNER_CV_SPLITS)
    parser.add_argument("--seed", type=int, default=RANDOM_SEED)
    parser.add_argument("--train-final", metavar="DIR", nargs="?", const=str(DEFAULT_ARTIFACTS_DIR),
                        help="train the fixed final model (%s, %s, %s calibration) on all samples, "
                             "evaluate it on held-out people and write model.joblib, model.joblib.sha256 "
                             "and report.md to DIR (default: %%(const)s)"
                             % (FINAL_VARIANT, FINAL_CLASSIFIER, CALIBRATION_METHOD))
    parser.add_argument("--save", metavar="PATH",
                        help="train VARIANT/CLASSIFIER on all samples and save to PATH (no evaluation)")
    parser.add_argument("--variant", default=DEFAULT_VARIANT, choices=list(VARIANTS))
    parser.add_argument("--classifier", default=FINAL_CLASSIFIER, choices=list(CLASSIFIERS))
    parser.add_argument("--calibration", default=CALIBRATION_METHOD,
                        choices=list(CALIBRATION_METHODS) + ["none"],
                        help="probability calibration for --save (default: %(default)s)")
    args = parser.parse_args(argv)

    from .dataset import load_dataset
    samples = load_dataset(args.dataset)

    if args.train_final:
        run = train_final(samples, comparison_variants=args.variants, comparison_classifiers=args.classifiers,
                          outer_splits=args.splits, seed=args.seed, n_jobs=-1)
        paths = write_final_run(run, args.train_final)
        print(paths[2].read_text(encoding="utf-8"))
        print("saved %s" % ", ".join(map(str, paths)))
        return 0

    if args.save:
        calibration = None if args.calibration == "none" else args.calibration
        model = train_model(samples, args.variant, args.classifier, args.seed, calibration)
        print("saved %s (%s, %s, calibration %s)"
              % (model.save(args.save), model.variant.id, model.classifier, model.calibration))
        return 0

    X, y, groups = samples_to_arrays(samples)
    if args.nested:
        n_people = len(np.unique(groups))
        print("%d samples, %d people, nested CV: %d outer folds, %d inner folds, seed %d\n"
              % (len(y), n_people, args.splits, args.inner_splits, args.seed))
        print(format_nested_results(nested_evaluate(X, y, groups, args.variants, args.classifiers,
                                                    args.splits, args.inner_splits, args.seed)))
        return 0
    results = evaluate(X, y, groups, args.variants, args.classifiers, args.splits, args.seed)
    n_people = len(np.unique(groups))
    print("%d samples, %d people, %d folds divided by people "
          "(%d train / %d validation people per fold), seed %d\n"
          % (len(y), n_people, args.splits, n_people - n_people // args.splits,
             n_people // args.splits, args.seed))
    print(format_results(results))
    return 0


if __name__ == "__main__":
    # Run main() from the imported package module, not from ``__main__``: saved pipelines must
    # reference ``model.training.LandmarkPreprocessor`` so that load_model can find the class.
    from model.training import main as _main
    sys.exit(_main())
