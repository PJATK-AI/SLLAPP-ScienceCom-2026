# AI module (`model/`)

Dataset loading, validation, landmark preprocessing and the comparison of preprocessing
variants for the SPAS hand-landmark dataset used to train the PJM static-sign classifier.

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
>
> **Who confirms:** the owner of the AI area (`model/`), listed in the pinned issue *Project ownership*,
> confirms the source and the license with the dataset authors and fills in the lines above.
>
> **Until then, the model file must not be published.** The model is trained on SPAS, so it is a file
> derived from the dataset. Share it only inside the team, as described in
> [Delivered model file](#delivered-model-file). Do not attach it to a GitHub release, commit it or
> upload it to a public place.

The repository's MIT license (`LICENSE`) covers the project code only, not the dataset or the model file.

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

Run all commands from the repository root. Loading, validation and preprocessing need only the
Python standard library. Training (`model/training.py`) also needs the packages in
`model/requirements.txt`:

```
pip install -r model/requirements.txt
```

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
2. **Scale normalization** (`normalize_scale`, per sample): divides the centred landmarks by a
   hand-size measurement, so that measurement becomes 1. Choose it with `scale_method`
   (`preprocess_landmarks(points, scale_method="palm")`; the default `"none"` skips this step).
   All distances are 3D Euclidean (x, y, z) distances:

   | `scale_method` | hand size |
   |---|---|
   | `none` | no scale normalization |
   | `wrist_middle_mcp` | distance WRIST (0) -> MIDDLE_FINGER_MCP (9) |
   | `palm` | mean distance WRIST (0) -> the MCPs 5, 9, 13, 17 |
   | `max_extent` | largest distance of any landmark from WRIST (0); depends on the pose |

   A hand size below `MIN_HAND_SCALE` (`model/config.py`) means a degenerate detection and raises
   `LandmarkValidationError`; there is no division by ~0. Rotation is not normalized.

Input: exactly 21 landmarks in the order above, each as `(x, y, z)` or `{"x", "y", "z"}`.
SPAS samples and MediaPipe image landmarks (`coordinateSpace: "MEDIAPIPE_IMAGE"`) use the same
coordinate space. A wrong landmark count, a missing coordinate or a value that is not a finite
number raises `LandmarkValidationError` (a `ValueError`) that names the landmark and axis, for
example `landmark 3: y must be finite, got nan`.

### Preprocessing variants and model comparison

`model/training.py` builds an sklearn `Pipeline` for each variant: `LandmarkPreprocessor` (wrist
centring + scale normalization, stateless) -> `StandardScaler` (if enabled) -> classifier. The
pipeline input is the raw 21 landmarks flattened to 63 values. The scaler is a pipeline step, so it
is fitted on training data only, refitted inside each cross-validation fold, and saved with the model.

| variant | scale normalization | `StandardScaler` |
|---|---|---|
| `V0` | none | no |
| `V1` | none | yes |
| `V2` | `wrist_middle_mcp` | no |
| `V3` (final model, `FINAL_VARIANT`) | `wrist_middle_mcp` | yes |
| `V4` | `palm` | yes |
| `V5` | `max_extent` | yes |

Compare variants x classifiers (`logreg`, `svm`, `knn`, `random_forest`) on the same folds. The
dataset is divided by people: `KFold(n_splits=4, shuffle=True, random_state=42)` splits the 12
subject ids (`CV_SPLITS`, `RANDOM_SEED` in `model/config.py`), and each sample follows its subject.
Every fold trains on 9 people (720 samples) and validates on 3 people (240 samples), 75/25; every
person is in validation exactly once. The output is a table of mean +/- std validation accuracy and
macro-F1 over the 4 folds:

```
python -m model.training                                   # all variants, all classifiers
python -m model.training --variants V0 V3 --classifiers svm
```

The table above is good for choosing a variant, but the best score in it is optimistic: the same
validation people are used to pick the winner. For an unbiased estimate of accuracy on new people,
run nested cross-validation:

```
python -m model.training --nested
```

- Outer loop: the same 4 folds by people (9 train / 3 test people).
- Inner loop: on the 9 outer training people only, the variant x classifier comparison runs with
  `KFold` over those people (`INNER_CV_SPLITS = 3`: 6 train / 3 validation people) and the
  combination with the highest mean validation accuracy is chosen.
- The chosen pipeline is refitted on the 9 outer training people and scored once on the 3 test
  people, who are never used for selection, scaling or fitting.

The output lists the choice and test score of each outer fold and the mean +/- std over the 4 folds.
That mean estimates the whole procedure "compare the variants and keep the best", not one fixed variant.

### Final model

The final model is fixed explicitly in `model/config.py`. It is not chosen again at each training:

| setting | value |
|---|---|
| `FINAL_VARIANT` | `V3` (scale wrist->middle MCP + `StandardScaler`) |
| `FINAL_CLASSIFIER` | `logreg` (`LogisticRegression`) |
| `CALIBRATION_METHOD` | `temperature` (`CalibratedClassifierCV`, `ensemble=False`) |
| `CALIBRATION_SPLITS` | `3` folds by people |

The pipeline is `CalibratedClassifierCV(Pipeline(preprocess -> standardize -> classifier))`. The
calibration folds are `KFold` over people (the same `make_folds` as the comparison). The calibrator
is fitted on the predictions for the people held out of each fold. Then the pipeline is fitted on all
samples. `predict_proba` gives a probability for each of the 16 letters, and the probabilities sum
to 1. `SVC(probability=True)` is not used.

Why `V3` + `logreg` and not the most accurate SVM. On held-out people, after the same temperature
calibration (4 folds by people, calibration folds made from the training people only):

| candidate | accuracy | log loss | errors among accepted at 0.80 | letter samples accepted at 0.80 | letter blends reaching 0.80 |
|---|---|---|---|---|---|
| `V3` + `logreg` | 0.965 | 0.106 | 0.6% | 94.5% | 45% |
| `V2` + `svm` | 0.982 | 0.114 | 1.6% | 99.8% | 95% |
| `V3` + `svm` | 0.977 | 0.117 | 1.5% | 99.1% | 93% |

The calibrated SVMs give a confidence of about 0.9 or more to nearly every sample, including the
wrong ones and the letter blends, so no threshold removes their errors. With `logreg`, a threshold
removes most errors, so the backend can trust its confidence. The sigmoid method gave a much higher
calibration error (0.17-0.22 against 0.02-0.05) and was rejected.

### Train the final model

```
python -m model.training --train-final            # writes to model/artifacts/
python -m model.training --train-final path/to/dir
```

This command:

1. compares the 6 variants x 4 classifiers without calibration (4 folds by people), as context only;
2. evaluates the fixed calibrated pipeline on held-out people: each of the 4 folds by people trains
   and calibrates it on 9 people and scores the other 3 people;
3. computes, for each threshold in `THRESHOLDS`, the share of accepted held-out samples and the error
   rate among the accepted samples, and recommends the lowest threshold with at most
   `MAX_ACCEPTED_ERROR` (1%) errors;
4. scores poses that are not letters. They are built from the samples of the held-out people:
   blends of two different letters, hands with each finger from a different letter, upside-down
   hands and hands with random noise. The report states how many of them reach the recommended threshold;
5. trains the calibrated pipeline on all 960 samples.

It writes `model.joblib`, `model.joblib.sha256` and `report.md` (the fixed choice, the scikit-learn
version, the checksum and all tables). `model/artifacts/` is in `.gitignore`: do not commit model files.

Result of the delivered model (from `report.md`): accuracy 0.965 on held-out people, expected
calibration error 0.020. **Recommended threshold for the backend: 0.80.** At 0.80, 94.5% of the letter
samples are accepted, with 0.6% errors (5 of 907). Of the 4080 generated non-letter poses, 2822
(69.2%) reach 0.80: letter blends 44.7%, mixed fingers 65.0%, upside-down hands 98.5%, noise 79.6%.
A threshold alone does not reject poses that are not letters, because the preprocessing does not
normalize rotation. The backend should also require the same letter in several consecutive samples
(issue #43). The non-letter poses are synthetic. They do not replace a test with real people at the booth.

Train one variant on all samples and save it for inference (no evaluation; do not commit model files):

```
python -m model.training --save out/model.joblib --variant V3 --classifier logreg   # calibrated (temperature)
python -m model.training --save out/model.joblib --calibration none                 # uncalibrated
```

```python
from model.training import load_model

model = load_model("out/model.joblib", expected_variant="V3")
letter = model.predict_hand(message["hand"])   # same preprocessing and fitted scaler as training
```

The model file (format 2) stores the variant, the classifier, the calibration method, the recommended
threshold, the letters, and the scikit-learn and numpy versions. The pipeline is stored as pickled
bytes. `load_model` reads the stored versions first. It raises `ModelVersionError` before it unpickles
the pipeline if the installed scikit-learn differs:

```
model model/artifacts/model.joblib was trained with scikit-learn 1.9.1, but scikit-learn 1.8.0 is
installed; install the pinned versions with: pip install -r model/requirements.txt
```

It raises `VariantMismatchError` if the variant differs from `expected_variant` or from the current
definition of that variant. Format 1 files (before the calibration) are rejected. The training and
service dependencies are pinned to exact versions in `model/requirements.txt`. A test checks that
the installed scikit-learn is the pinned one. Model files are pickles (joblib): load only files that
you created or got from the team.

### Delivered model file

The model file is **not in git** and **not on GitHub**. It is shared only inside the team, until the
dataset license is confirmed (see [Provenance and license](#provenance-and-license)).

| item | value |
|---|---|
| file | `model.joblib` (35 KB) |
| model | `V3` + `logreg`, temperature calibration, recommended threshold 0.80 |
| trained with | `python -m model.training --train-final`, scikit-learn 1.9.1, numpy 2.3.3, Python 3.13 |
| SHA-256 | `7f95cc51f27d28340a3ecfa36ff8c1e69940a1de83d5ec0e8cf73690c69c29af` |

Where to get it: ask the owner of the AI area (pinned issue *Project ownership*). The owner puts the
file, with `model.joblib.sha256` and `report.md`, in the files of the team's Teams channel. Copy the
file to `model/artifacts/model.joblib`, or give its path to the service with `--model`. You can also
build it yourself from the dataset with `python -m model.training --train-final`. With the pinned
versions, a second training on the same computer made a byte-for-byte identical file. On a different
computer the checksum can differ. In that case use the delivered file.

Check the checksum before you use the file. The result must be the SHA-256 above:

```powershell
(Get-FileHash model\artifacts\model.joblib -Algorithm SHA256).Hash.ToLower()
```

```sh
sha256sum model/artifacts/model.joblib            # Linux
shasum -a 256 model/artifacts/model.joblib        # macOS
```

When the service is running, `GET /health` also returns the checksum of the loaded file
(`"checksum": "sha256:..."`).

### Prediction service

`model/service.py` is a small HTTP service that returns the predicted letter and its confidence
for one hand sample. The Java backend calls it for each sample and decides which predictions
count: the service only predicts and applies no threshold (the recommended threshold, 0.80, is
in [Train the final model](#train-the-final-model) and in `report.md`). It uses only the Python standard
library and the packages in `model/requirements.txt` (exact versions; a model file must be loaded
with the scikit-learn version that trained it).

```
pip install -r model/requirements.txt
python -m model.training --train-final              # or use the delivered model file (see above)
python -m model.service                             # model/artifacts/model.joblib on 127.0.0.1:8000
python -m model.service --model path/to/model.joblib --variant V3 --port 8001
```

Settings (a command-line argument wins over the environment variable):

| argument | environment variable | default |
|---|---|---|
| `--model` | `SLLAPP_MODEL_PATH` | `model/artifacts/model.joblib` |
| `--variant` | `SLLAPP_MODEL_VARIANT` | `V3` (`DEFAULT_VARIANT`) |
| `--host` | `SLLAPP_PREDICT_HOST` | `127.0.0.1` |
| `--port` | `SLLAPP_PREDICT_PORT` | `8000` |

The service loads the model once at startup. It prints an error and exits with code `1`, without
opening the port, if the file is missing or is not a model file of format 2, the file was trained
with a different scikit-learn version, the variant in the file differs from `--variant`, the
classifier cannot give probabilities (for example an uncalibrated `svm`, saved with
`--calibration none`) or the model letters are not `A B C E I L M N O P R S T U W Y`.

#### `POST /predict`

The body is the `hand` object of a `hand_sample` message (see `docs/api.md`) with
`coordinateSpace`:

```json
{"coordinateSpace": "MEDIAPIPE_IMAGE", "handedness": "RIGHT",
 "landmarks": [{"x": 0.50, "y": 0.70, "z": 0.00}, "... 21 points in MediaPipe order"]}
```

- `coordinateSpace` must be `MEDIAPIPE_IMAGE` and `handedness` must be `LEFT` or `RIGHT`.
- `landmarks` holds exactly 21 objects with finite numeric `x`, `y`, `z`. Extra fields (for
  example `score`) are ignored.
- The service does **not** mirror left-hand samples: the frontend applies `x = 1 - x` to the
  left hand before sending (`normalizeHandForModel`, issue #29). `handedness` is only validated.

| status | when | body |
|---|---|---|
| `200` | valid sample | `{"letter": "A", "confidence": 0.93}` |
| `400` | body is not valid JSON (also `NaN`/`Infinity` literals, bad UTF-8, empty body) | `{"error": "<message>"}` |
| `413` | body larger than 64 KB | `{"error": "<message>"}` |
| `422` | sample fails validation | `{"error": "<validation message>"}` |
| `500` | unexpected prediction error | `{"error": "prediction failed"}` |

`letter` is the most probable of the 16 letters and `confidence` (0-1) is its calibrated probability
from `predict_proba` of the loaded model. Example 422 messages:
`hand.coordinateSpace must be MEDIAPIPE_IMAGE, got 'WORLD'`, `expected exactly 21 landmarks, got 20`,
`landmark 4: y must be finite, got inf`.

#### `GET /health`

```json
{"status": "ok", "variant": "V3", "classifier": "logreg",
 "letters": ["A", "B", "C", "E", "I", "L", "M", "N", "O", "P", "R", "S", "T", "U", "W", "Y"],
 "checksum": "sha256:<hex digest of the model file>"}
```

Only failed requests are logged. The model file is a pickle: start the service only with model
files that you created or got from the team.

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
