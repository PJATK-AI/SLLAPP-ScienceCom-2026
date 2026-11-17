"""AI module: SPAS hand-landmark dataset loading and validation.

Entry points::

    from model import load_dataset, validate_dataset

    report = validate_dataset()      # full report, never raises on bad data
    samples = load_dataset()         # validated samples, raises DatasetValidationError

See ``model/README.md`` for the dataset location, landmark order and usage.
"""
from .config import (
    DATASET_ENV_VAR,
    DEFAULT_DATASET_DIR,
    LANDMARK_NAMES,
    get_dataset_dir,
)
from .dataset import Sample, load_dataset
from .validation import DatasetValidationError, ValidationReport, validate_dataset

__all__ = [
    "DATASET_ENV_VAR",
    "DEFAULT_DATASET_DIR",
    "LANDMARK_NAMES",
    "DatasetValidationError",
    "Sample",
    "ValidationReport",
    "get_dataset_dir",
    "load_dataset",
    "validate_dataset",
]
