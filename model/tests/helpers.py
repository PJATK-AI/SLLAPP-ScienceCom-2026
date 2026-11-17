import itertools
import os
import pickle
import sys
import types

LETTERS = list("ABCEILMNOPRSTUWY")


_TEMPLATE = [(0.30 + 0.02 * i, 0.50 + 0.015 * ((i * 7) % 5), -0.01 * i) for i in range(21)]


class RawData:
    pass


RawData.__module__ = "RawData"
RawData.__qualname__ = "RawData"


def make_raw(points=None, **overrides):
    points = points or _TEMPLATE
    obj = RawData()
    obj.nodesX_world = [p[0] for p in points]
    obj.nodesY_world = [p[1] for p in points]
    obj.nodesZ_world = [p[2] for p in points]
    obj.hasData = True
    obj.nodesCount = 21
    for k, v in overrides.items():
        setattr(obj, k, v)
    return obj


def dump(obj, path):
    previous = sys.modules.get("RawData")
    sys.modules["RawData"] = types.SimpleNamespace(RawData=RawData)
    try:
        with open(path, "wb") as fh:
            pickle.dump(obj, fh, protocol=4)
    finally:
        if previous is None:
            del sys.modules["RawData"]
        else:
            sys.modules["RawData"] = previous


def write_dataset(directory, letters=LETTERS, subjects=range(1, 13), reps=range(1, 6)):
    for i, (l, s, r) in enumerate(itertools.product(letters, subjects, reps)):
        scale, shift = 1.0 + 0.001 * (i % 50), 0.0005 * (i % 40)
        pts = [(x * scale + shift, y * scale + shift, z * scale) for x, y, z in _TEMPLATE]
        dump(make_raw(pts), os.path.join(directory, "%s_%d_%d" % (l, s, r)))
