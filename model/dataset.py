import math
import os
import pickle
import re
from dataclasses import dataclass
from typing import Dict, List, Optional, Sequence, Tuple, Union

from .config import N_LANDMARKS, get_dataset_dir

SAMPLE_NAME_RE = re.compile(r"^([A-Z])_(\d+)_(\d+)$")
AXES = ("X", "Y", "Z")

Landmarks = Tuple[Tuple[float, float, float], ...]


@dataclass(frozen=True)
class Issue:
    check: str
    message: str
    file: Optional[str] = None

    def __str__(self) -> str:
        return "%s: %s" % (self.file, self.message) if self.file else self.message


@dataclass(frozen=True)
class Sample:
    name: str
    label: str
    subject: int
    repetition: int
    landmarks: Landmarks


def parse_sample_name(name: str) -> Optional[Tuple[str, int, int]]:
    m = SAMPLE_NAME_RE.match(name)
    if not m:
        return None
    return m.group(1), int(m.group(2)), int(m.group(3))


class _RawDataStub:
    pass


class _RestrictedUnpickler(pickle.Unpickler):
    def find_class(self, module, name):
        if (module, name) == ("RawData", "RawData"):
            return _RawDataStub
        raise pickle.UnpicklingError("forbidden object in file: %s.%s" % (module, name))


def read_raw_sample(path: Union[str, "os.PathLike[str]"]) -> dict:
    with open(path, "rb") as fh:
        obj = _RestrictedUnpickler(fh).load()
    if not isinstance(obj, _RawDataStub):
        raise ValueError("file does not contain a RawData object")
    return dict(vars(obj))


def check_raw_sample(raw: dict) -> List[str]:
    problems: List[str] = []
    if raw.get("hasData") is not True:
        problems.append("hasData=%r (expected True)" % (raw.get("hasData"),))
    if raw.get("nodesCount") != N_LANDMARKS:
        problems.append("nodesCount=%r (expected %d)" % (raw.get("nodesCount"), N_LANDMARKS))
    lengths = {}
    for ax in AXES:
        values = raw.get("nodes%s_world" % ax)
        if not isinstance(values, list):
            problems.append("%s: missing or not a list" % ax)
            continue
        lengths[ax] = len(values)
        if len(values) != N_LANDMARKS:
            problems.append("%s: %d landmarks (expected %d)" % (ax, len(values), N_LANDMARKS))
        bad = [v for v in values
               if isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v)]
        if bad:
            problems.append("%s: %d non-numeric or non-finite values, e.g. %r" % (ax, len(bad), bad[:3]))
    if len(set(lengths.values())) > 1:
        problems.append("X, Y, Z arrays differ in length: %s" % lengths)
    return problems


def collect_samples(dataset_dir) -> Tuple[Dict[str, Sample], List[str], List[Issue]]:
    samples: Dict[str, Sample] = {}
    parsed_names: List[str] = []
    issues: List[Issue] = []
    for entry in sorted(os.scandir(dataset_dir), key=lambda e: e.name):
        name = entry.name
        if not entry.is_file():
            issues.append(Issue("file-name", "not a regular file", name))
            continue
        parsed = parse_sample_name(name)
        if parsed is None:
            issues.append(Issue("file-name", "name does not match <LETTER>_<subject>_<repetition>", name))
            continue
        parsed_names.append(name)
        try:
            raw = read_raw_sample(entry.path)
        except Exception as exc:  # noqa: BLE001 - reported, never swallowed
            issues.append(Issue("readable", "cannot be read: %s: %s" % (type(exc).__name__, exc), name))
            continue
        problems = check_raw_sample(raw)
        if problems:
            for p in problems:
                issues.append(Issue("landmarks", p, name))
            continue
        landmarks = tuple(
            (float(x), float(y), float(z))
            for x, y, z in zip(raw["nodesX_world"], raw["nodesY_world"], raw["nodesZ_world"])
        )
        label, subject, repetition = parsed
        samples[name] = Sample(name, label, subject, repetition, landmarks)
    return samples, parsed_names, issues


def load_dataset(path=None, *, strict_order: bool = False) -> List[Sample]:
    from .validation import DatasetValidationError, validate_dataset

    report = validate_dataset(get_dataset_dir(path), strict_order=strict_order)
    if not report.ok:
        raise DatasetValidationError(report)
    return [report.samples[name] for name in sorted(report.samples)]
