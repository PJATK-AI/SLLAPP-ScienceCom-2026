import argparse
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Callable, Dict, Iterable, List, Mapping, Optional, Sequence, Tuple

import joblib
import numpy as np
import sklearn
from sklearn.base import BaseEstimator, TransformerMixin
from sklearn.ensemble import RandomForestClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score, f1_score
from sklearn.model_selection import KFold, cross_validate
from sklearn.neighbors import KNeighborsClassifier
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler
from sklearn.svm import SVC

from .config import (CV_SPLITS, DEFAULT_CLASSIFIERS, DEFAULT_VARIANT, INNER_CV_SPLITS, N_LANDMARKS,
                     RANDOM_SEED)
from .preprocessing import (SCALE_METHODS, LandmarkValidationError, preprocess_landmarks,
                            validate_landmarks)

N_FEATURES = N_LANDMARKS * 3
MODEL_FORMAT = 1


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
             n_splits: int = CV_SPLITS, seed: int = RANDOM_SEED) -> List[CVResult]:
    folds = make_folds(groups, n_splits, seed)
    results = []
    for vid in variants:
        variant = get_variant(vid)
        for clf in classifiers:
            scores = cross_validate(build_pipeline(variant, clf, seed), X, y, cv=folds,
                                    scoring=("accuracy", "f1_macro"))
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
    results = evaluate(X, y, groups, variants, classifiers, n_splits, seed)
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


def _check_pipeline_matches(pipeline: Pipeline, variant: Variant) -> None:
    steps = dict(pipeline.steps)
    pre = steps.get("preprocess")
    if not isinstance(pre, LandmarkPreprocessor) or pre.scale_method != variant.scale_method:
        raise VariantMismatchError("pipeline preprocessing does not match variant %s" % variant.id)
    if ("standardize" in steps) != variant.standardize:
        raise VariantMismatchError("pipeline scaler does not match variant %s" % variant.id)


class TrainedModel:
    def __init__(self, pipeline: Pipeline, variant: Variant, classifier: str):
        _check_pipeline_matches(pipeline, variant)
        self.pipeline = pipeline
        self.variant = variant
        self.classifier = classifier

    @property
    def classes(self) -> List[str]:
        return [str(c) for c in self.pipeline.classes_]

    def predict_landmarks(self, landmarks: Any) -> str:
        return str(self.pipeline.predict(np.asarray([landmarks_to_row(landmarks)]))[0])

    def predict_hand(self, hand: Any) -> str:
        if not isinstance(hand, Mapping) or "landmarks" not in hand:
            raise LandmarkValidationError("hand must be an object with a 'landmarks' list")
        return self.predict_landmarks(hand["landmarks"])

    def save(self, path) -> Path:
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        joblib.dump({"format": MODEL_FORMAT, "variant": asdict(self.variant),
                     "classifier": self.classifier, "sklearn_version": sklearn.__version__,
                     "pipeline": self.pipeline}, path)
        return path


def train_model(samples: Iterable[Any], variant_id: str = DEFAULT_VARIANT,
                classifier: str = "logreg", seed: int = RANDOM_SEED) -> TrainedModel:
    variant = get_variant(variant_id)
    X, y, _ = samples_to_arrays(samples)
    return TrainedModel(build_pipeline(variant, classifier, seed).fit(X, y), variant, classifier)


def load_model(path, expected_variant: Optional[str] = None) -> TrainedModel:
    data = joblib.load(path)
    if not isinstance(data, dict) or data.get("format") != MODEL_FORMAT:
        raise ValueError("%s is not a model file of format %d" % (path, MODEL_FORMAT))
    stored = Variant(**data["variant"])
    if expected_variant is not None and stored.id != expected_variant:
        raise VariantMismatchError("model %s was trained with variant %s, expected %s"
                                   % (path, stored.id, expected_variant))
    known = VARIANTS.get(stored.id)
    if known is not None and known != stored:
        raise VariantMismatchError("variant %s in %s differs from the current definition"
                                   % (stored.id, path))
    return TrainedModel(data["pipeline"], stored, data["classifier"])


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
    parser.add_argument("--save", metavar="PATH",
                        help="train VARIANT/CLASSIFIER on all samples and save to PATH (no CV)")
    parser.add_argument("--variant", default=DEFAULT_VARIANT, choices=list(VARIANTS))
    parser.add_argument("--classifier", default="logreg", choices=list(CLASSIFIERS))
    args = parser.parse_args(argv)

    from .dataset import load_dataset
    samples = load_dataset(args.dataset)

    if args.save:
        model = train_model(samples, args.variant, args.classifier, args.seed)
        print("saved %s (%s, %s)" % (model.save(args.save), model.variant.id, model.classifier))
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
