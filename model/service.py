import argparse
import json
import math
import os
import sys
from collections.abc import Mapping
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Dict, Optional, Sequence

import numpy as np

from .config import (COORDINATE_SPACE, DEFAULT_MODEL_PATH, DEFAULT_SERVICE_HOST, DEFAULT_SERVICE_PORT,
                     DEFAULT_VARIANT, HANDEDNESS_VALUES, LETTERS, MODEL_PATH_ENV_VAR, MODEL_VARIANT_ENV_VAR,
                     N_LANDMARKS, SERVICE_HOST_ENV_VAR, SERVICE_PORT_ENV_VAR)
from .preprocessing import LandmarkValidationError
from .training import TrainedModel, file_sha256, landmarks_to_row, load_model

MAX_BODY_BYTES = 64 * 1024


class ModelLoadError(RuntimeError):
    pass


class PredictionService:
    def __init__(self, model: TrainedModel, checksum: str):
        if not hasattr(model.pipeline, "predict_proba"):
            raise ModelLoadError("classifier %r of the model cannot give probabilities" % model.classifier)
        if tuple(model.classes) != LETTERS:
            raise ModelLoadError("model letters %s differ from the expected %s"
                                 % ("".join(model.classes), "".join(LETTERS)))
        self.model = model
        self.checksum = checksum

    def health(self) -> Dict[str, Any]:
        return {"status": "ok", "variant": self.model.variant.id, "classifier": self.model.classifier,
                "letters": list(self.model.classes), "checksum": "sha256:" + self.checksum}

    def predict(self, hand: Any) -> Dict[str, Any]:
        row = landmarks_to_row(validate_hand(hand))
        probabilities = self.model.pipeline.predict_proba(np.asarray([row]))[0]
        best = int(np.argmax(probabilities))
        confidence = float(probabilities[best])
        if not math.isfinite(confidence):
            raise RuntimeError("model returned a non-finite probability")
        return {"letter": self.model.classes[best], "confidence": min(max(confidence, 0.0), 1.0)}


def validate_hand(hand: Any):
    if not isinstance(hand, Mapping):
        raise LandmarkValidationError("hand must be an object")
    if hand.get("coordinateSpace") != COORDINATE_SPACE:
        raise LandmarkValidationError("hand.coordinateSpace must be %s, got %r"
                                      % (COORDINATE_SPACE, hand.get("coordinateSpace")))
    if hand.get("handedness") not in HANDEDNESS_VALUES:
        raise LandmarkValidationError("hand.handedness must be %s, got %r"
                                      % (" or ".join(HANDEDNESS_VALUES), hand.get("handedness")))
    landmarks = hand.get("landmarks")
    if not isinstance(landmarks, list):
        raise LandmarkValidationError("hand.landmarks must be a list of %d points" % N_LANDMARKS)
    if len(landmarks) != N_LANDMARKS:
        raise LandmarkValidationError("expected exactly %d landmarks, got %d" % (N_LANDMARKS, len(landmarks)))
    for i, point in enumerate(landmarks):
        if not isinstance(point, Mapping):
            raise LandmarkValidationError("landmark %d: expected an object with x, y, z" % i)
    return landmarks


def load_service(model_path, expected_variant: str) -> PredictionService:
    path = Path(model_path)
    if not path.is_file():
        raise ModelLoadError("model file %s does not exist" % path)
    try:
        model = load_model(path, expected_variant=expected_variant)
    except ValueError as exc:  # includes VariantMismatchError and ModelVersionError
        raise ModelLoadError(str(exc)) from exc
    except Exception as exc:
        raise ModelLoadError("cannot load model %s: %s" % (path, exc)) from exc
    return PredictionService(model, file_sha256(path))


def _reject_constant(name: str):
    raise ValueError("%s is not valid JSON" % name)


class PredictionHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "SLLAPPPredict/1"
    service: PredictionService

    def do_GET(self):
        if self.path == "/health":
            self._send(HTTPStatus.OK, self.service.health())
        elif self.path == "/predict":
            self._send(HTTPStatus.METHOD_NOT_ALLOWED, {"error": "use POST /predict"}, {"Allow": "POST"})
        else:
            self._send(HTTPStatus.NOT_FOUND, {"error": "not found"})

    def do_POST(self):
        if self.path != "/predict":
            self.close_connection = True  # the body is not read
            if self.path == "/health":
                self._send(HTTPStatus.METHOD_NOT_ALLOWED, {"error": "use GET /health"}, {"Allow": "GET"})
            else:
                self._send(HTTPStatus.NOT_FOUND, {"error": "not found"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = -1
        if length < 0:
            self.close_connection = True
            self._send(HTTPStatus.BAD_REQUEST, {"error": "invalid Content-Length"})
            return
        if length > MAX_BODY_BYTES:
            self.close_connection = True
            self._send(HTTPStatus.REQUEST_ENTITY_TOO_LARGE,
                       {"error": "request body is larger than %d bytes" % MAX_BODY_BYTES})
            return
        body = self.rfile.read(length)
        try:
            hand = json.loads(body.decode("utf-8"), parse_constant=_reject_constant)
        except (UnicodeDecodeError, ValueError) as exc:
            self._send(HTTPStatus.BAD_REQUEST, {"error": "body is not valid JSON: %s" % exc})
            return
        try:
            result = self.service.predict(hand)
        except LandmarkValidationError as exc:
            self._send(HTTPStatus.UNPROCESSABLE_ENTITY, {"error": str(exc)})
            return
        except Exception as exc:
            self.log_error("prediction failed: %r", exc)
            self._send(HTTPStatus.INTERNAL_SERVER_ERROR, {"error": "prediction failed"})
            return
        self._send(HTTPStatus.OK, result)

    def _send(self, status: HTTPStatus, payload: Dict[str, Any], headers: Optional[Dict[str, str]] = None):
        data = json.dumps(payload, allow_nan=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(data)

    def log_request(self, code="-", size="-"):
        # one request per hand sample: log only failures
        if not (isinstance(code, int) and code < 400):
            super().log_request(code, size)


def create_server(service: PredictionService, host: str = DEFAULT_SERVICE_HOST,
                  port: int = DEFAULT_SERVICE_PORT) -> ThreadingHTTPServer:
    handler = type("BoundPredictionHandler", (PredictionHandler,), {"service": service})
    server = ThreadingHTTPServer((host, port), handler)
    server.daemon_threads = True
    return server


def _port(value: str) -> int:
    try:
        port = int(value)
    except ValueError:
        raise argparse.ArgumentTypeError("port must be an integer, got %r" % value) from None
    if not 0 <= port <= 65535:
        raise argparse.ArgumentTypeError("port must be 0-65535, got %d" % port)
    return port


def parse_args(argv: Optional[Sequence[str]] = None) -> argparse.Namespace:
    env = os.environ
    parser = argparse.ArgumentParser(prog="python -m model.service",
                                     description="HTTP service that predicts the letter of one hand sample.")
    parser.add_argument("--model", default=env.get(MODEL_PATH_ENV_VAR) or str(DEFAULT_MODEL_PATH),
                        help="model file (env %s, default %s)" % (MODEL_PATH_ENV_VAR, DEFAULT_MODEL_PATH))
    parser.add_argument("--variant", default=env.get(MODEL_VARIANT_ENV_VAR) or DEFAULT_VARIANT,
                        help="expected model variant (env %s, default %s)" % (MODEL_VARIANT_ENV_VAR, DEFAULT_VARIANT))
    parser.add_argument("--host", default=env.get(SERVICE_HOST_ENV_VAR) or DEFAULT_SERVICE_HOST,
                        help="address to listen on (env %s, default %s)" % (SERVICE_HOST_ENV_VAR, DEFAULT_SERVICE_HOST))
    parser.add_argument("--port", type=_port, default=env.get(SERVICE_PORT_ENV_VAR) or DEFAULT_SERVICE_PORT,
                        help="port to listen on (env %s, default %d)" % (SERVICE_PORT_ENV_VAR, DEFAULT_SERVICE_PORT))
    return parser.parse_args(argv)


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = parse_args(argv)
    try:
        service = load_service(args.model, args.variant)
    except ModelLoadError as exc:
        print("error: %s" % exc, file=sys.stderr)
        return 1
    server = create_server(service, args.host, args.port)
    host, port = server.server_address[:2]
    info = service.health()
    print("serving %s/%s (%s) on http://%s:%d" % (info["variant"], info["classifier"], info["checksum"], host, port),
          flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
