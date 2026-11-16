"""Dataset location and expected dataset structure."""
import os
from pathlib import Path
from typing import Optional, Tuple, Union

MODEL_DIR = Path(__file__).resolve().parent

#: Default location of the SPAS sample files (the dataset itself is not committed to git).
DEFAULT_DATASET_DIR = MODEL_DIR / "data" / "SPAS" / "dataset"

#: Environment variable that overrides the default dataset location.
DATASET_ENV_VAR = "SLLAPP_DATASET_DIR"

# --- expected dataset structure ---------------------------------------------
N_LETTERS = 16
N_SUBJECTS = 12
N_REPETITIONS = 5
N_LANDMARKS = 21
SAMPLES_PER_LETTER = N_SUBJECTS * N_REPETITIONS          # 60
N_SAMPLES = N_LETTERS * SAMPLES_PER_LETTER               # 960

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
    """Resolve the dataset folder: explicit ``path`` > ``$SLLAPP_DATASET_DIR`` > default."""
    if path is not None:
        return Path(path)
    env = os.environ.get(DATASET_ENV_VAR)
    if env:
        return Path(env)
    return DEFAULT_DATASET_DIR
