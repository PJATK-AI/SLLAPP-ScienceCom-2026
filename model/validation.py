import argparse
import itertools
import math
import os
import statistics
from collections import Counter
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Sequence, Tuple

from .config import (
    HAND_BONES,
    N_LANDMARKS,
    N_LETTERS,
    N_REPETITIONS,
    N_SAMPLES,
    N_SUBJECTS,
    SAMPLES_PER_LETTER,
    get_dataset_dir,
)
from .dataset import Issue, Sample, collect_samples, parse_sample_name

#: Robust z-score of a bone length above which a sample is flagged as a possible ordering problem.
ORDER_Z_THRESHOLD = 15.0
MIN_SAMPLES_FOR_ORDER_CHECK = 10


@dataclass
class CheckResult:
    title: str
    ok: bool
    details: List[str] = field(default_factory=list)


@dataclass
class ValidationReport:
    dataset_dir: str
    n_files: int
    samples: Dict[str, Sample]
    checks: List[CheckResult]
    warnings: List[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return all(c.ok for c in self.checks)

    @property
    def issues(self) -> List[str]:
        return [d for c in self.checks if not c.ok for d in (c.details or [c.title])]

    def format(self, max_details: int = 15) -> str:
        lines = ["Dataset: %s" % self.dataset_dir,
                 "Files: %d | valid samples: %d" % (self.n_files, len(self.samples)), ""]
        for c in self.checks:
            lines.append("[%s] %s" % ("OK  " if c.ok else "FAIL", c.title))
            if not c.ok:
                for d in c.details[:max_details]:
                    lines.append("       - %s" % d)
                if len(c.details) > max_details:
                    lines.append("       ... and %d more" % (len(c.details) - max_details))
        for w in self.warnings:
            lines.append("[WARN] %s" % w)
        passed = sum(c.ok for c in self.checks)
        lines += ["", "Summary: %d/%d checks passed, %d warning(s)" % (passed, len(self.checks), len(self.warnings))]
        return "\n".join(lines)


class DatasetValidationError(ValueError):
    def __init__(self, report: ValidationReport):
        self.report = report
        super().__init__("Dataset validation failed:\n" + report.format())


def find_order_anomalies(samples: Dict[str, Sample]) -> List[Tuple[str, float, str]]:
    ratios: Dict[str, Optional[List[float]]] = {}
    for name, s in samples.items():
        scale = math.dist(s.landmarks[0], s.landmarks[9])
        ratios[name] = None if scale == 0 else [
            math.dist(s.landmarks[a], s.landmarks[b]) / scale for a, b in HAND_BONES]
    valid = [r for r in ratios.values() if r is not None]
    if not valid:
        return [(n, math.inf, "zero wrist-MCP distance") for n in samples]
    nb = len(HAND_BONES)
    med = [statistics.median(r[i] for r in valid) for i in range(nb)]
    mad = [(statistics.median(abs(r[i] - med[i]) for r in valid) * 1.4826) or 1e-9 for i in range(nb)]
    flagged = []
    for name in samples:
        r = ratios[name]
        if r is None:
            flagged.append((name, math.inf, "zero wrist-MCP distance"))
            continue
        z = [abs(r[i] - med[i]) / mad[i] for i in range(nb)]
        worst = max(range(nb), key=z.__getitem__)
        if z[worst] > ORDER_Z_THRESHOLD:
            flagged.append((name, z[worst], "bone %s" % (HAND_BONES[worst],)))
    return sorted(flagged, key=lambda t: -t[1])


def validate_dataset(path=None, *, strict_order: bool = False) -> ValidationReport:
    dataset_dir = get_dataset_dir(path)
    if not os.path.isdir(dataset_dir):
        check = CheckResult("Dataset directory exists", False, ["not a directory: %s" % dataset_dir])
        return ValidationReport(str(dataset_dir), 0, {}, [check])

    n_files = len(os.listdir(dataset_dir))
    samples, parsed_names, issues = collect_samples(dataset_dir)

    def by(check: str) -> List[str]:
        return [str(i) for i in issues if i.check == check]

    checks: List[CheckResult] = []

    def add(title: str, details: Sequence[str]) -> None:
        checks.append(CheckResult(title, not details, list(details)))

    add("Dataset directory exists", [])
    add("File names follow <LETTER>_<subject>_<repetition>", by("file-name"))
    add("Every file can be read by the restricted loader", by("readable"))
    add("Every sample has %d finite numeric landmarks with matching X, Y, Z arrays" % N_LANDMARKS,
        by("landmarks"))

    keys = Counter(parse_sample_name(n) for n in parsed_names)
    letters = sorted({k[0] for k in keys})
    subjects = sorted({k[1] for k in keys})
    reps = sorted({k[2] for k in keys})

    add("%d letter classes (found %d: %s)" % (N_LETTERS, len(letters), "".join(letters)),
        [] if len(letters) == N_LETTERS else ["expected %d classes, found %d" % (N_LETTERS, len(letters))])
    add("%d subjects numbered 1-%d (found %s)" % (N_SUBJECTS, N_SUBJECTS, subjects),
        [] if subjects == list(range(1, N_SUBJECTS + 1)) else ["found subjects: %s" % subjects])
    add("%d repetitions numbered 1-%d (found %s)" % (N_REPETITIONS, N_REPETITIONS, reps),
        [] if reps == list(range(1, N_REPETITIONS + 1)) else ["found repetitions: %s" % reps])
    add("%d samples in total (files: %d, valid samples: %d)" % (N_SAMPLES, n_files, len(samples)),
        [] if n_files == len(samples) == N_SAMPLES else
        ["expected %d, found %d files and %d valid samples" % (N_SAMPLES, n_files, len(samples))])

    per_letter = Counter()
    for (letter, _, _), c in keys.items():
        per_letter[letter] += c
    add("%d samples per letter class" % SAMPLES_PER_LETTER,
        ["%s: %d" % (l, per_letter[l]) for l in letters if per_letter[l] != SAMPLES_PER_LETTER])

    per_pair = Counter()
    for (letter, subject, _), c in keys.items():
        per_pair[(letter, subject)] += c
    add("%d repetitions for each letter and subject" % N_REPETITIONS,
        ["%s, subject %d: %d" % (l, s, per_pair.get((l, s), 0))
         for l in letters for s in range(1, N_SUBJECTS + 1) if per_pair.get((l, s), 0) != N_REPETITIONS])

    expected = set(itertools.product(letters, range(1, N_SUBJECTS + 1), range(1, N_REPETITIONS + 1)))
    details = (["missing: %s_%d_%d" % k for k in sorted(expected - set(keys))]
               + ["unexpected: %s_%d_%d" % k for k in sorted(set(keys) - expected)]
               + ["duplicated: %s_%d_%d" % k for k, c in sorted(keys.items()) if c > 1])
    add("Every letter x subject x repetition combination exists exactly once (%d x %d x %d)"
        % (len(letters), N_SUBJECTS, N_REPETITIONS), details)

    warnings: List[str] = []
    if len(samples) >= MIN_SAMPLES_FOR_ORDER_CHECK:
        flagged = find_order_anomalies(samples)
        lines = ["%s: z=%.1f, %s" % f for f in flagged]
        if not flagged:
            add("Landmark order consistent with hand topology (bone-length heuristic)", [])
        elif strict_order:
            add("Landmark order consistent with hand topology (bone-length heuristic)", lines)
        else:
            warnings.append("%d sample(s) with unusual bone lengths (possible landmark-order problem): %s"
                            % (len(flagged), "; ".join(lines[:15])))
    else:
        warnings.append("Landmark-order heuristic skipped: fewer than %d valid samples"
                        % MIN_SAMPLES_FOR_ORDER_CHECK)

    return ValidationReport(str(dataset_dir), n_files, samples, checks, warnings)


def main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser(prog="python -m model", description="Dataset validation: structure, completeness and landmark sanity.")
    ap.add_argument("dataset", nargs="?", default=None,
                    help="dataset folder (default: $SLLAPP_DATASET_DIR or model/data/SPAS/dataset)")
    ap.add_argument("--strict-order", action="store_true",
                    help="treat landmark-order warnings as errors")
    args = ap.parse_args(argv)
    report = validate_dataset(args.dataset, strict_order=args.strict_order)
    print(report.format())
    return 0 if report.ok else 1

