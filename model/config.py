import os
from pathlib import Path
from typing import Optional, Tuple, Union

MODEL_DIR = Path(__file__).resolve().parent

#: Default location of the SPAS sample files (the dataset itself is not committed to git).
DEFAULT_DATASET_DIR = MODEL_DIR / "data" / "SPAS" / "dataset"

#: Environment variable that overrides the default dataset location.
DATASET_ENV_VAR = "SLLAPP_DATASET_DIR"

# expected dataset structure
N_LETTERS = 16
N_SUBJECTS = 12
N_REPETITIONS = 5
N_LANDMARKS = 21
SAMPLES_PER_LETTER = N_SUBJECTS * N_REPETITIONS          # 60
N_SAMPLES = N_LETTERS * SAMPLES_PER_LETTER               # 960

# --- preprocessing variants and training -------------------------------------
#: Smallest accepted hand size after wrist centring (SPAS x/y are image-normalised, so a real
#: wrist -> middle MCP distance is roughly 0.05-0.3). Smaller values mean a degenerate detection.
MIN_HAND_SCALE = 1e-6

#: Variant used when training a model for inference and when none is given on the CLI.
DEFAULT_VARIANT = "V3"

#: Classifiers compared for every variant (keys of ``model.training.CLASSIFIERS``).
DEFAULT_CLASSIFIERS: Tuple[str, ...] = ("logreg", "svm", "knn", "random_forest")

#: Cross-validation: KFold over the 12 people, so no person is in train and validation at once.
#: 4 splits = 9 people train / 3 people validation (75/25) in each fold.
CV_SPLITS = 4
#: Nested CV inner loop: KFold over the 9 outer training people = 6 train / 3 validation people.
INNER_CV_SPLITS = 3
RANDOM_SEED = 42

#: Where ``python -m model.training --train-final`` writes model.joblib and report.md (git-ignored).
DEFAULT_ARTIFACTS_DIR = MODEL_DIR / "artifacts"

# Landmark order, identical to MediaPipe Hands (index = position in the file's lists).
LANDMARK_NAMES: Tuple[str, ...] = (
    "WRIST",
    "THUMB_CMC", "THUMB_MCP", "THUMB_IP", "THUMB_TIP",
    "INDEX_FINGER_MCP", "INDEX_FINGER_PIP", "INDEX_FINGER_DIP", "INDEX_FINGER_TIP",
    "MIDDLE_FINGER_MCP", "MIDDLE_FINGER_PIP", "MIDDLE_FINGER_DIP", "MIDDLE_FINGER_TIP",
    "RING_FINGER_MCP", "RING_FINGER_PIP", "RING_FINGER_DIP", "RING_FINGER_TIP",
    "PINKY_MCP", "PINKY_PIP", "PINKY_DIP", "PINKY_TIP",
)

# Hand bones as pairs of landmark indices (used by the landmark-order sanity check).
HAND_BONES: Tuple[Tuple[int, int], ...] = (
    (0, 1), (1, 2), (2, 3), (3, 4),            # thumb
    (0, 5), (5, 6), (6, 7), (7, 8),            # index finger
    (9, 10), (10, 11), (11, 12),               # middle finger
    (13, 14), (14, 15), (15, 16),              # ring finger
    (0, 17), (17, 18), (18, 19), (19, 20),     # pinky
    (5, 9), (9, 13), (13, 17),                 # palm
)


def get_dataset_dir(path: Optional[Union[str, os.PathLike]] = None) -> Path:
    if path is not None:
        return Path(path)
    env = os.environ.get(DATASET_ENV_VAR)
    if env:
        return Path(env)
    return DEFAULT_DATASET_DIR
