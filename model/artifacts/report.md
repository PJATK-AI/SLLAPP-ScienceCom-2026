# Model training report

- Data: 960 samples, 12 people, 16 letters
- Folds: KFold over people, 4 folds for the held-out results, 3 calibration folds over the training people, seed 42
- scikit-learn 1.9.1, numpy 2.3.3

## Final model

- Variant: V3 (scale wrist->middle MCP + StandardScaler), fixed in `model/config.py` (`FINAL_VARIANT`)
- Classifier: logreg, fixed in `model/config.py` (`FINAL_CLASSIFIER`)
- Calibration: `CalibratedClassifierCV(method='temperature', ensemble=False)` on 3 folds by people
- Trained on all 960 samples. The calibrator is fitted on predictions for the people held out of each calibration fold.
- Recommended confidence threshold for the backend: 0.80
- Model file SHA-256: `7f95cc51f27d28340a3ecfa36ff8c1e69940a1de83d5ec0e8cf73690c69c29af`

## Held-out people

Each of the 4 folds trains and calibrates the final pipeline on the training people only and scores the held-out people:

| fold | training people | held-out people |
|---|---|---|
| 1 | 2, 3, 4, 5, 6, 7, 8, 9, 12 | 1, 10, 11 |
| 2 | 1, 2, 4, 5, 7, 8, 10, 11, 12 | 3, 6, 9 |
| 3 | 1, 3, 4, 6, 7, 8, 9, 10, 11 | 2, 5, 12 |
| 4 | 1, 2, 3, 5, 6, 9, 10, 11, 12 | 4, 7, 8 |

Accuracy 0.965, macro-F1 0.965, log loss 0.106, expected calibration error 0.020 (10 bins).

## Confidence thresholds

A sample is accepted when the probability of its most probable letter is at least the threshold. Letter columns: all 960 samples, each scored by the fold that held its person out. Non-letter columns: share of the poses below whose most probable letter reaches the threshold.

| threshold | accepted letter samples | errors among accepted | letter_blend reaching it | finger_mix reaching it | upside_down reaching it | noise reaching it |
|---|---|---|---|---|---|---|
| 0.00 | 100.0% (960/960) | 3.5% (34) | 100.0% | 100.0% | 100.0% | 100.0% |
| 0.30 | 100.0% (960/960) | 3.5% (34) | 99.2% | 99.7% | 99.9% | 100.0% |
| 0.40 | 100.0% (960/960) | 3.5% (34) | 96.0% | 97.9% | 99.9% | 99.1% |
| 0.50 | 99.8% (958/960) | 3.3% (32) | 87.4% | 93.2% | 99.9% | 97.1% |
| 0.60 | 98.2% (943/960) | 2.2% (21) | 73.3% | 84.4% | 99.6% | 90.5% |
| 0.70 | 96.8% (929/960) | 1.6% (15) | 58.5% | 74.2% | 99.0% | 85.9% |
| 0.80 (recommended) | 94.5% (907/960) | 0.6% (5) | 44.7% | 65.0% | 98.5% | 79.6% |
| 0.85 | 92.6% (889/960) | 0.3% (3) | 38.8% | 59.3% | 98.2% | 76.0% |
| 0.90 | 90.3% (867/960) | 0.2% (2) | 29.6% | 53.2% | 97.1% | 71.0% |
| 0.95 | 85.2% (818/960) | 0.2% (2) | 20.4% | 43.3% | 96.4% | 64.1% |

Rule: the recommended threshold is the lowest one in the table with at most 1.0% errors among the accepted held-out letter samples.

## Poses that are not letters

Generated from the samples of the held-out people of each fold, so the model never saw these people.

| kind | poses | reaching 0.80 | description |
|---|---|---|---|
| letter_blend | 1440 | 644 (44.7%) | average of two samples of two different letters by the same person |
| finger_mix | 720 | 468 (65.0%) | each finger taken from a sample of a different letter (5 letters) by the same person |
| upside_down | 960 | 946 (98.5%) | a letter sample rotated by 180 degrees around the wrist in the image plane |
| noise | 960 | 764 (79.6%) | a letter sample with every landmark except the wrist moved by random noise (std 0.25 x wrist->middle MCP distance) |

2822 of 4080 non-letter poses (69.2%) reach the recommended threshold 0.80.

The threshold alone does not reject poses that are not letters. The preprocessing does not normalize rotation, and the classifier can give a high confidence to poses far from the training data (see `upside_down` and `noise`). The backend should also require the same letter in several consecutive samples. These poses are synthetic: real non-letter poses at the booth are not measured.

## Uncalibrated variants and classifiers (context for the fixed choice)

4 folds by people; the best score here is optimistic. Use `python -m model.training --nested` for the nested estimate.

| variant | description | classifier | accuracy | macro-F1 |
|---|---|---|---|---|
| V0 | baseline: wrist-centred only | logreg | 0.894 +/- 0.062 | 0.884 +/- 0.071 |
| V0 | baseline: wrist-centred only | svm | 0.964 +/- 0.031 | 0.963 +/- 0.032 |
| V0 | baseline: wrist-centred only | knn | 0.860 +/- 0.032 | 0.854 +/- 0.032 |
| V0 | baseline: wrist-centred only | random_forest | 0.847 +/- 0.029 | 0.839 +/- 0.036 |
| V1 | StandardScaler only | logreg | 0.960 +/- 0.032 | 0.960 +/- 0.034 |
| V1 | StandardScaler only | svm | 0.942 +/- 0.021 | 0.942 +/- 0.021 |
| V1 | StandardScaler only | knn | 0.805 +/- 0.033 | 0.790 +/- 0.043 |
| V1 | StandardScaler only | random_forest | 0.847 +/- 0.029 | 0.839 +/- 0.036 |
| V2 | scale wrist->middle MCP only | logreg | 0.967 +/- 0.044 | 0.967 +/- 0.044 |
| V2 | scale wrist->middle MCP only | svm | 0.983 +/- 0.014 | 0.983 +/- 0.014 |
| V2 | scale wrist->middle MCP only | knn | 0.907 +/- 0.035 | 0.901 +/- 0.038 |
| V2 | scale wrist->middle MCP only | random_forest | 0.877 +/- 0.057 | 0.870 +/- 0.069 |
| V3 | scale wrist->middle MCP + StandardScaler | logreg | 0.965 +/- 0.023 | 0.964 +/- 0.023 |
| V3 | scale wrist->middle MCP + StandardScaler | svm | 0.979 +/- 0.014 | 0.979 +/- 0.014 |
| V3 | scale wrist->middle MCP + StandardScaler | knn | 0.848 +/- 0.020 | 0.840 +/- 0.020 |
| V3 | scale wrist->middle MCP + StandardScaler | random_forest | 0.877 +/- 0.057 | 0.870 +/- 0.069 |
| V4 | scale mean wrist->MCPs + StandardScaler | logreg | 0.965 +/- 0.023 | 0.964 +/- 0.023 |
| V4 | scale mean wrist->MCPs + StandardScaler | svm | 0.982 +/- 0.010 | 0.982 +/- 0.011 |
| V4 | scale mean wrist->MCPs + StandardScaler | knn | 0.867 +/- 0.023 | 0.860 +/- 0.025 |
| V4 | scale mean wrist->MCPs + StandardScaler | random_forest | 0.883 +/- 0.066 | 0.876 +/- 0.077 |
| V5 | scale max wrist distance + StandardScaler | logreg | 0.969 +/- 0.033 | 0.964 +/- 0.040 |
| V5 | scale max wrist distance + StandardScaler | svm | 0.978 +/- 0.015 | 0.978 +/- 0.015 |
| V5 | scale max wrist distance + StandardScaler | knn | 0.895 +/- 0.007 | 0.891 +/- 0.007 |
| V5 | scale max wrist distance + StandardScaler | random_forest | 0.919 +/- 0.041 | 0.917 +/- 0.043 |
