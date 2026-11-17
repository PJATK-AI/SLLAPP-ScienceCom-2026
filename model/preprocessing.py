
import math
from collections.abc import Mapping
from typing import Any, Iterable, List, Sequence, Tuple

from .config import MIN_HAND_SCALE, N_LANDMARKS

AXES = ("x", "y", "z")
WRIST = 0
MIDDLE_FINGER_MCP = 9
MCP_JOINTS = (5, 9, 13, 17)  # index, middle, ring, pinky MCP

#: Hand-size measurements for scale normalization. All distances are 3D (x, y, z)
#: Euclidean distances, measured after wrist centring.
#:
#: - ``none``: no scale normalization (scale = 1).
#: - ``wrist_middle_mcp``: distance WRIST (0) -> MIDDLE_FINGER_MCP (9).
#: - ``palm``: mean distance WRIST (0) -> the four finger MCPs (5, 9, 13, 17).
#: - ``max_extent``: largest distance of any landmark from WRIST (0). Depends on the pose.
SCALE_METHODS = ("none", "wrist_middle_mcp", "palm", "max_extent")

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


def _norm(point: Point) -> float:
    return math.sqrt(sum(v * v for v in point))


def hand_scale(centred: Landmarks, method: str) -> float:
    """Hand size of wrist-centred landmarks, measured with ``method`` (see ``SCALE_METHODS``)."""
    if method == "none":
        return 1.0
    if method == "wrist_middle_mcp":
        return _norm(centred[MIDDLE_FINGER_MCP])
    if method == "palm":
        return sum(_norm(centred[i]) for i in MCP_JOINTS) / len(MCP_JOINTS)
    if method == "max_extent":
        return max(_norm(p) for p in centred)
    raise ValueError("unknown scale method %r (expected one of %s)"
                     % (method, ", ".join(SCALE_METHODS)))


def normalize_scale(centred: Landmarks, method: str) -> Landmarks:
    """Divide wrist-centred landmarks by the hand size, so the measurement becomes 1.

    Raises :class:`LandmarkValidationError` when the hand size is below
    ``MIN_HAND_SCALE`` (degenerate detection), instead of dividing by ~0.
    """
    scale = hand_scale(centred, method)
    if method == "none":
        return centred
    if not scale >= MIN_HAND_SCALE:
        raise LandmarkValidationError(
            "hand scale (%s) is %.3g, below the minimum %.3g: degenerate landmarks"
            % (method, scale, MIN_HAND_SCALE))
    return tuple((x / scale, y / scale, z / scale) for x, y, z in centred)


def preprocess_landmarks(landmarks: Any, scale_method: str = "none") -> Landmarks:
    """Shared pipeline: wrist centring, then per-sample scale normalization."""
    if scale_method not in SCALE_METHODS:
        raise ValueError("unknown scale method %r (expected one of %s)"
                         % (scale_method, ", ".join(SCALE_METHODS)))
    return normalize_scale(center_on_wrist(landmarks), scale_method)


# --- adapters -----------------------------------------------------------------

def preprocess_sample(sample: Any, scale_method: str = "none") -> Landmarks:

    return preprocess_landmarks(sample.landmarks, scale_method)


def preprocess_samples(samples: Iterable[Any], scale_method: str = "none") -> List[Landmarks]:
    result = []
    for sample in samples:
        try:
            result.append(preprocess_sample(sample, scale_method))
        except LandmarkValidationError as exc:
            raise LandmarkValidationError("%s: %s" % (getattr(sample, "name", "?"), exc)) from exc
    return result


def preprocess_hand(hand: Any, scale_method: str = "none") -> Landmarks:
    if not isinstance(hand, Mapping) or "landmarks" not in hand:
        raise LandmarkValidationError("hand must be an object with a 'landmarks' list")
    return preprocess_landmarks(hand["landmarks"], scale_method)
