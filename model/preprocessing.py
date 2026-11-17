
import math
from collections.abc import Mapping
from typing import Any, Iterable, List, Sequence, Tuple

from .config import N_LANDMARKS

AXES = ("x", "y", "z")
WRIST = 0

Point = Tuple[float, float, float]
Landmarks = Tuple[Point, ...]


class LandmarkValidationError(ValueError):
    """Raised when landmarks are missing, malformed or not finite."""


def _coordinate(value: Any, index: int, axis: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise LandmarkValidationError(
            "landmark %d: %s must be a number, got %r" % (index, axis, value))
    if not math.isfinite(value):
        raise LandmarkValidationError(
            "landmark %d: %s must be finite, got %r" % (index, axis, value))
    return float(value)


def _point(raw: Any, index: int) -> Point:
    if isinstance(raw, Mapping):
        missing = [a for a in AXES if a not in raw]
        if missing:
            raise LandmarkValidationError(
                "landmark %d: missing coordinate(s) %s" % (index, ", ".join(missing)))
        values = [raw[a] for a in AXES]
    elif isinstance(raw, Sequence) and not isinstance(raw, (str, bytes)):
        if len(raw) != 3:
            raise LandmarkValidationError(
                "landmark %d: expected 3 coordinates (x, y, z), got %d" % (index, len(raw)))
        values = list(raw)
    else:
        raise LandmarkValidationError(
            "landmark %d: expected (x, y, z) or {x, y, z}, got %r" % (index, raw))
    x, y, z = (_coordinate(v, index, a) for v, a in zip(values, AXES))
    return x, y, z


def validate_landmarks(landmarks: Any) -> Landmarks:

    if landmarks is None:
        raise LandmarkValidationError("landmarks are missing")
    if isinstance(landmarks, (str, bytes, Mapping)) or not isinstance(landmarks, Sequence):
        raise LandmarkValidationError(
            "landmarks must be a sequence of %d points, got %s" % (N_LANDMARKS, type(landmarks).__name__))
    if len(landmarks) != N_LANDMARKS:
        raise LandmarkValidationError(
            "expected exactly %d landmarks, got %d" % (N_LANDMARKS, len(landmarks)))
    return tuple(_point(p, i) for i, p in enumerate(landmarks))


def center_on_wrist(landmarks: Any) -> Landmarks:

    points = validate_landmarks(landmarks)
    wx, wy, wz = points[WRIST]
    return tuple((x - wx, y - wy, z - wz) for x, y, z in points)


def preprocess_landmarks(landmarks: Any) -> Landmarks:

    return center_on_wrist(landmarks)


# --- adapters -----------------------------------------------------------------

def preprocess_sample(sample: Any) -> Landmarks:

    return preprocess_landmarks(sample.landmarks)


def preprocess_samples(samples: Iterable[Any]) -> List[Landmarks]:
    result = []
    for sample in samples:
        try:
            result.append(preprocess_sample(sample))
        except LandmarkValidationError as exc:
            raise LandmarkValidationError("%s: %s" % (getattr(sample, "name", "?"), exc)) from exc
    return result


def preprocess_hand(hand: Any) -> Landmarks:
    if not isinstance(hand, Mapping) or "landmarks" not in hand:
        raise LandmarkValidationError("hand must be an object with a 'landmarks' list")
    return preprocess_landmarks(hand["landmarks"])
