# AI module (`model/`)

Dataset loading, validation and landmark preprocessing for the SPAS hand-landmark dataset used to train the
PJM static-sign classifier. Model training and evaluation are **not** part of this
module yet.

Structural validation checks the shape and completeness of the data. It does **not**
confirm that the gesture labels are linguistically correct PJM signs.

## Dataset

- 16 letter classes (found: `A B C E I L M N O P R S T U W Y`), 12 subjects,
  5 repetitions per letter and subject = **960 samples**, 60 per letter.
- One file per sample, named `<LETTER>_<subject 1-12>_<repetition 1-5>`. Example: `C_6_2` is
  the second repetition of letter C by subject 6 (format from the readme supplied with SPAS).
- Each file is a pickle of a `RawData` object (class `RawData` in module `RawData`, supplied
  with SPAS) with `nodesX_world`, `nodesY_world`, `nodesZ_world` (21 values each),
  `nodesCount=21`, `hasData=True`.

### Location and configuration

The dataset is **not committed to git**: all of `model/data/SPAS/` is in `.gitignore`, including
the helper files supplied with the dataset (`RawData.py`, `LoadDataAndDisplayHand.py`, the
file-name readme), because their license is not confirmed either. Get them from the team.
Place the sample files in:

```
model/data/SPAS/dataset/
```

or point to another folder, in order of priority:

1. a path passed explicitly (`validate_dataset(path)`, `load_dataset(path)`, CLI argument),
2. the `SLLAPP_DATASET_DIR` environment variable,
3. the default above (`model.config.DEFAULT_DATASET_DIR`).

### Provenance and license

> **Needs confirmation before the dataset (or files derived from it) is redistributed.**
>
> - Source / authors: _TODO - to be filled in by the project team_
> - License: _TODO - to be filled in by the project team_
> - Redistribution allowed: _TODO - unknown; do not commit or publish the data until confirmed_

The repository's MIT license (`LICENSE`) covers the project code only, not the dataset.

## Landmark order

Every list holds 21 landmarks in the same order (MediaPipe Hands numbering). Index `i`
of `nodesX_world`, `nodesY_world` and `nodesZ_world` describes the same landmark.

| i | name | i | name | i | name |
|---|------|---|------|---|------|
| 0 | WRIST | 7 | INDEX_FINGER_DIP | 14 | RING_FINGER_PIP |
| 1 | THUMB_CMC | 8 | INDEX_FINGER_TIP | 15 | RING_FINGER_DIP |
| 2 | THUMB_MCP | 9 | MIDDLE_FINGER_MCP | 16 | RING_FINGER_TIP |
| 3 | THUMB_IP | 10 | MIDDLE_FINGER_PIP | 17 | PINKY_MCP |
| 4 | THUMB_TIP | 11 | MIDDLE_FINGER_DIP | 18 | PINKY_PIP |
| 5 | INDEX_FINGER_MCP | 12 | MIDDLE_FINGER_TIP | 19 | PINKY_DIP |
| 6 | INDEX_FINGER_PIP | 13 | RING_FINGER_MCP | 20 | PINKY_TIP |

The same order is available as `model.LANDMARK_NAMES`.

Coordinates: despite the `_world` suffix, X and Y are normalised to the image
(the display script supplied with SPAS, `LoadDataAndDisplayHand.py`, multiplies them by image
width/height; observed range roughly 0.02-0.97) and Z is a relative depth (observed roughly
-0.26 to 0.06). They are not metric world coordinates (a comment in the supplied `RawData.py`
says the same).

## Usage

Run all commands from the repository root. Only the Python standard library is needed.

```python
from model import load_dataset, validate_dataset

report = validate_dataset()        # full report, does not raise on bad data
print(report.format())

samples = load_dataset()           # list[Sample]; raises DatasetValidationError if invalid
samples[0].label, samples[0].subject, samples[0].repetition, samples[0].landmarks  # 21 x (x, y, z)
```

### Preprocessing (training and inference)

`model/preprocessing.py` holds the preprocessing that every hand goes through. Training and
inference call the same pipeline, `preprocess_landmarks`, through two thin adapters:

```python
from model import load_dataset, preprocess_samples, preprocess_hand

X = preprocess_samples(load_dataset())   # training: list of 21 x (x, y, z), one per sample
x = preprocess_hand(message["hand"])     # inference: the `hand` of a `hand_sample` message
```

Add new steps (for example scaling) to `preprocess_landmarks`, never to one adapter only.

Current pipeline:

1. **Wrist centring** (`center_on_wrist`): subtracts landmark 0 (`WRIST`) from all 21 landmarks,
   so the wrist becomes `(0, 0, 0)`. This removes the hand's position in the camera frame. It is
   a translation only: shape, size and orientation (rotation, left/right) are unchanged.

Input: exactly 21 landmarks in the order above, each as `(x, y, z)` or `{"x", "y", "z"}`.
SPAS samples and MediaPipe image landmarks (`coordinateSpace: "MEDIAPIPE_IMAGE"`) use the same
coordinate space. A wrong landmark count, a missing coordinate or a value that is not a finite
number raises `LandmarkValidationError` (a `ValueError`) that names the landmark and axis, for
example `landmark 3: y must be finite, got nan`.

### Run the validation

```
python -m model                       # default dataset location
python -m model path/to/dataset       # explicit folder
python -m model --strict-order        # treat landmark-order warnings as errors
```

Exit code `0` = valid, `1` = problems found (each one listed with its file name).

### Run the tests

```
python -m unittest discover -s model/tests -t . -v
```

The tests use small synthetic datasets in a temporary folder. One extra test runs against
the real dataset and is skipped when it is not present.

## What is validated

- file names follow `<LETTER>_<subject>_<repetition>`; every file can be read;
- every sample has `hasData=True`, `nodesCount=21`, and 21 values in each of X, Y, Z (equal lengths);
- all values are numeric and finite (no `NaN`, `Inf`, `None`, strings);
- 16 letter classes, 12 subjects (1-12), 5 repetitions (1-5);
- 960 samples in total, 60 per letter, 5 repetitions per letter and subject;
- every letter x subject x repetition combination exists exactly once;
- landmark order (heuristic): bone lengths of each sample are compared with the median over the
  dataset; swapped or shuffled landmarks are flagged. This is a sanity check, not proof of correct
  order, so by default it only produces a warning (`--strict-order` makes it an error).

Invalid or missing data is never skipped silently: the validator reports every problem, and
`load_dataset` raises `DatasetValidationError` (the full report is in `.report`).

## Security note

The sample files are pickles. They are read with a restricted unpickler that accepts only the
`RawData` class (see `model/dataset.py`); any other object in a file is rejected and reported.
Do not load pickles from untrusted sources with `pickle.load`.
