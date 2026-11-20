from dataclasses import dataclass
from typing import Dict, List, Optional, Sequence, Tuple

import numpy as np
from sklearn.calibration import CalibratedClassifierCV
from sklearn.metrics import accuracy_score, f1_score, log_loss

from .config import (CALIBRATION_METHOD, CALIBRATION_SPLITS, CV_SPLITS, MAX_ACCEPTED_ERROR, N_LANDMARKS,
                     RANDOM_SEED, THRESHOLDS)

CALIBRATION_METHODS = ("temperature", "sigmoid", "isotonic")

#: Landmark indices of each finger (MediaPipe order), used to build mixed-finger poses.
FINGERS: Tuple[Tuple[int, ...], ...] = ((1, 2, 3, 4), (5, 6, 7, 8), (9, 10, 11, 12), (13, 14, 15, 16),
                                        (17, 18, 19, 20))

#: Hand poses that are not letters, generated from the samples of held-out people.
NON_LETTER_KINDS: Dict[str, str] = {
    "letter_blend": "average of two samples of two different letters by the same person",
    "finger_mix": "each finger taken from a sample of a different letter (5 letters) by the same person",
    "upside_down": "a letter sample rotated by 180 degrees around the wrist in the image plane",
    "noise": "a letter sample with every landmark except the wrist moved by random noise "
             "(std 0.25 x wrist->middle MCP distance)",
}


def build_calibrated(pipeline, groups, method: str = CALIBRATION_METHOD,
                     n_splits: int = CALIBRATION_SPLITS, seed: int = RANDOM_SEED) -> CalibratedClassifierCV:
    # groups: person id of each row that fit() will get, in the same order. With ensemble=False the
    # calibrator is fitted on predictions for people held out of each fold, then the pipeline is
    # refitted on all rows.
    from .training import make_folds

    if method not in CALIBRATION_METHODS:
        raise ValueError("unknown calibration method %r (expected one of %s)"
                         % (method, ", ".join(CALIBRATION_METHODS)))
    return CalibratedClassifierCV(pipeline, method=method, cv=make_folds(groups, n_splits, seed),
                                  ensemble=False)


# non-letter hand poses

def _points(row) -> np.ndarray:
    return np.asarray(row, dtype=float).reshape(N_LANDMARKS, 3)


def letter_blends(X, y, groups, rng: np.random.Generator) -> np.ndarray:
    rows = []
    for person in np.unique(groups):
        mine = np.flatnonzero(groups == person)
        letters = np.unique(y[mine])
        for i, a in enumerate(letters):
            for b in letters[i + 1:]:
                ia = rng.choice(mine[y[mine] == a])
                ib = rng.choice(mine[y[mine] == b])
                rows.append(0.5 * X[ia] + 0.5 * X[ib])
    return np.asarray(rows).reshape(-1, N_LANDMARKS * 3)


def finger_mixes(X, y, groups, rng: np.random.Generator, per_person: int = 60) -> np.ndarray:
    rows = []
    for person in np.unique(groups):
        mine = np.flatnonzero(groups == person)
        letters = np.unique(y[mine])
        for _ in range(per_person):
            # 5 different letters (letters repeat only when the data has fewer than 5)
            chosen = rng.choice(letters, size=len(FINGERS), replace=len(letters) < len(FINGERS))
            base = _points(X[rng.choice(mine[y[mine] == chosen[0]])])
            out = base.copy()
            for finger, letter in zip(FINGERS, chosen):
                donor = _points(X[rng.choice(mine[y[mine] == letter])])
                out[list(finger)] = base[0] + donor[list(finger)] - donor[0]
            rows.append(out.ravel())
    return np.asarray(rows).reshape(-1, N_LANDMARKS * 3)


def upside_down(X) -> np.ndarray:
    out = []
    for row in X:
        p = _points(row)
        q = p.copy()
        q[:, :2] = 2 * p[0, :2] - p[:, :2]
        out.append(q.ravel())
    return np.asarray(out).reshape(-1, N_LANDMARKS * 3)


def noisy(X, rng: np.random.Generator, scale: float = 0.25) -> np.ndarray:
    out = []
    for row in X:
        p = _points(row)
        size = np.linalg.norm(p[9] - p[0])
        q = p + rng.normal(0.0, scale * size, p.shape)
        q[0] = p[0]
        out.append(q.ravel())
    return np.asarray(out).reshape(-1, N_LANDMARKS * 3)


def non_letter_poses(X, y, groups, seed: int = RANDOM_SEED) -> Dict[str, np.ndarray]:
    rng = np.random.default_rng(seed)
    X, y, groups = np.asarray(X, dtype=float), np.asarray(y), np.asarray(groups)
    return {"letter_blend": letter_blends(X, y, groups, rng),
            "finger_mix": finger_mixes(X, y, groups, rng),
            "upside_down": upside_down(X),
            "noise": noisy(X, rng)}


# held-out evaluation

@dataclass(frozen=True)
class ThresholdRow:
    threshold: float
    accepted: int
    total: int
    errors: int

    @property
    def accepted_share(self) -> float:
        return self.accepted / self.total if self.total else 0.0

    @property
    def error_rate(self) -> Optional[float]:
        return self.errors / self.accepted if self.accepted else None


@dataclass
class HeldOutResult:
    classes: List[str]
    y: np.ndarray
    proba: np.ndarray
    non_letter_conf: Dict[str, np.ndarray]
    folds: List[Tuple[Tuple[int, ...], Tuple[int, ...]]]

    @property
    def predicted(self) -> np.ndarray:
        return np.asarray(self.classes)[self.proba.argmax(axis=1)]

    @property
    def confidence(self) -> np.ndarray:
        return self.proba.max(axis=1)

    @property
    def accuracy(self) -> float:
        return float(accuracy_score(self.y, self.predicted))

    @property
    def f1_macro(self) -> float:
        return float(f1_score(self.y, self.predicted, average="macro"))

    @property
    def log_loss(self) -> float:
        return float(log_loss(self.y, self.proba, labels=self.classes))

    @property
    def calibration_error(self) -> float:
        return expected_calibration_error(self.confidence, self.predicted == self.y)

    def thresholds(self, thresholds: Sequence[float] = THRESHOLDS) -> List[ThresholdRow]:
        conf, wrong = self.confidence, self.predicted != self.y
        return [ThresholdRow(t, int((conf >= t).sum()), len(conf), int((wrong & (conf >= t)).sum()))
                for t in thresholds]

    def non_letters_reaching(self, threshold: float) -> Dict[str, Tuple[int, int]]:
        return {kind: (int((conf >= threshold).sum()), len(conf))
                for kind, conf in self.non_letter_conf.items()}


def expected_calibration_error(confidence, correct, n_bins: int = 10) -> float:
    confidence, correct = np.asarray(confidence, dtype=float), np.asarray(correct, dtype=float)
    edges = np.linspace(0.0, 1.0, n_bins + 1)
    total = 0.0
    for lo, hi in zip(edges[:-1], edges[1:]):
        in_bin = (confidence > lo) & (confidence <= hi)
        if in_bin.any():
            total += in_bin.mean() * abs(correct[in_bin].mean() - confidence[in_bin].mean())
    return float(total)


def recommend_threshold(rows: Sequence[ThresholdRow], max_error: float = MAX_ACCEPTED_ERROR) -> Optional[float]:
    # lowest threshold whose error rate among accepted held-out samples is at most max_error
    for row in sorted(rows, key=lambda r: r.threshold):
        if row.accepted and row.error_rate <= max_error:
            return row.threshold
    return None


def held_out_evaluate(X, y, groups, variant_id: str, classifier: str, method: str = CALIBRATION_METHOD,
                      outer_splits: int = CV_SPLITS, calibration_splits: int = CALIBRATION_SPLITS,
                      seed: int = RANDOM_SEED) -> HeldOutResult:
    # Each outer fold: calibrated pipeline trained on the training people only (calibration folds
    # from those people too), scored on the held-out people and on non-letter poses built from them.
    from .training import build_pipeline, get_variant, make_folds

    X, y, groups = np.asarray(X, dtype=float), np.asarray(y), np.asarray(groups)
    variant = get_variant(variant_id)
    classes = [str(c) for c in np.unique(y)]
    proba = np.full((len(y), len(classes)), np.nan)
    non_letter: Dict[str, List[np.ndarray]] = {k: [] for k in NON_LETTER_KINDS}
    folds = []
    for i, (train, test) in enumerate(make_folds(groups, outer_splits, seed)):
        model = build_calibrated(build_pipeline(variant, classifier, seed), groups[train], method,
                                 calibration_splits, seed).fit(X[train], y[train])
        if [str(c) for c in model.classes_] != classes:
            raise ValueError("fold %d: training people do not cover every letter" % (i + 1))
        proba[test] = model.predict_proba(X[test])
        for kind, rows in non_letter_poses(X[test], y[test], groups[test], seed + i).items():
            non_letter[kind].append(model.predict_proba(rows).max(axis=1))
        folds.append((tuple(int(p) for p in np.unique(groups[train])),
                      tuple(int(p) for p in np.unique(groups[test]))))
    return HeldOutResult(classes, y, proba, {k: np.concatenate(v) for k, v in non_letter.items()}, folds)
