# Frontend

The Vue app runs a timed fingerspelling round over WebSocket. It uses the
camera to track the selected hand and loads the ranking after the server
reports the final score.
[Start the Vue app](../README.md#quick-start)

The game displays cropped excerpts from [“Alfabet palcowy” on Zintegrowana
Platforma Edukacyjna](https://zpe.gov.pl/a/w-swiecie-znakow/DNLn0RJB9#RuTDZ6bESDuoy).
The source image is stored unchanged and licensed under
[CC BY-SA 3.0](https://creativecommons.org/licenses/by-sa/3.0/).

## Play a round

1. Open the app on localhost or HTTPS. Enter a nickname, select the physical
   hand and click **Utwórz sesję**.
2. Click **Uruchom kamerę** and allow camera access. The preview draws the 21
   landmarks of the selected hand in the original image coordinates. The points
   disappear when that hand leaves the frame.
3. Click **Rozpocznij rundę** once tracking starts. Sign the highlighted
   letter. The reference beside the camera displays a crop of the current
   letter from the Polish fingerspelling chart. When the same letter follows,
   take your hand out of frame as prompted.
4. If the connection drops, click **Wznów sesję**. The server returns the
   current position, time and score, or the final result after the deadline.
5. When the round ends, the camera stops. The score stays visible while the
   ranking loads. Use **Ponów** to retry the ranking or **Nowa sesja** to play again.

Use the regular backend with [prediction enabled](local-development.md#enable-prediction-on-the-regular-backend)
to score gestures. The default sample rate is 10 Hz. The client skips intermediate
frames and frames produced while the socket has buffered data.

## Camera crop

The gear button on the right of the header opens **Ustawienia kamery**. It
shows the camera preview with a crop frame. Set **Przybliżenie** (1× to 3×)
and drag the frame, or select it and use the arrow keys. **Zapisz** stores the
crop in `localStorage` (`sllapp-camera-crop`) in this browser. **Pełny obraz**
removes the crop.

The game camera shows only the saved crop. The tracker gives MediaPipe only
this part of each frame, so hands outside it are not detected. The landmarks
are then normalized to the crop. The crop keeps the camera's aspect ratio, so
the points sent to the model are not stretched. A crop saved while the camera
runs applies from the next frame.

## Round diagnostics

Run `pnpm dev:diagnostics` from `frontend/` and enable WebSocket diagnostics
on the regular backend. **Diagnostyka** opens on the right, next to the game
and camera, with compact spacing in narrow browser panels. It shows the
latest prediction, confidence, HTTP status, time and ACK. Expand **Ostatnia
próbka** for the latest sample JSON. The scrollable 150-event history shows
sent and received WebSocket messages. Match a prediction to a sample by
connection number, `seq` and `positionId`. With the camera off before the
round, the two sample buttons test transport.
[Start the diagnostic mode](local-development.md#vue-round-diagnostics) ·
[Prediction message](api.md#round-prediction-diagnostics)

## Configuration

Vite reads environment variables from `frontend/apps/web/.env.local`.
`pnpm setup:local` creates it from the [example file](../frontend/.env.example)
if it is missing. The example points at the local backend. Restart Vite after
editing the file. `pnpm dev` serves the app at `127.0.0.1:5173` and reports an
error if the port is busy.

| Variable | Value for local use |
| --- | --- |
| `VITE_WS_URL` | `ws://127.0.0.1:8080/ws` for gameplay |
| `VITE_API_BASE_URL` | `http://127.0.0.1:8080/api` for the ranking API client |
| `VITE_HAND_SAMPLE_HZ` | `10` by default. Supply a positive finite rate. |
| `VITE_HAND_LANDMARKER_MODEL_URL` | `/mediapipe/hand_landmarker.task` by default |
| `VITE_HAND_LANDMARKER_WASM_PATH` | `/mediapipe/wasm` by default |

`pnpm prepare:camera` downloads the float16 Hand Landmarker model and copies
the installed MediaPipe WASM runtime into `apps/web/public/mediapipe/`.
Vite serves the files locally and includes them when building the app with
those assets present. The tracker model locates the hand. The letter classifier
in `model/artifacts/model.joblib` runs in the Python prediction service.
[Run the Python service](local-development.md#run-recognition-with-the-python-service)

## Session client

`useGameSession({ url, timeoutMs, diagnostics })` owns one WebSocket connection. By default
it reads `VITE_WS_URL` and allows 10 seconds each for opening the connection and
receiving confirmation of a session or a round start. It exposes connection
status, readiness, the latest validated state and errors.

The [Compose frontend](local-development.md#docker-compose) is built with
`VITE_WS_URL=/ws` and `VITE_API_BASE_URL=/api`. A WebSocket path beginning
with a single slash uses the page's host and port. It connects using `ws://`
from an HTTP page and `wss://` from an HTTPS page. For source development,
use an absolute URL such as `ws://127.0.0.1:8080/ws`.

`createSession({ nickname, selectedHand })` opens a session. `startGame()`
sends one request and waits for the server to confirm the round. `reconnect()`
opens a new socket and waits for the current server state. Both sample types
carry `positionId: null` in `CREATED` and the active ID in `RUNNING`. They share
a sequence starting at zero on each connection. A true return value means the
browser accepted the data for sending. A diagnostic `sample_ack` confirms that
the server accepted the sample.

The session ID is held in memory and is lost on reload. Code 1008 clears
an expired unfinished session. A displayed final score remains until the
player starts a new session. Vue scope disposal releases the socket, listeners
and timers.
[Backend WebSocket contract](api.md#websocket-transport)

## Landmark coordinates

`useHandTracker` from `@sllapp/hand-landmarker-vue` exposes the selected hand
through `hand.value`. Both landmark arrays use the same 21-point MediaPipe order.

```ts
const imagePoints = hand.value?.landmarks;
const wristPoints = hand.value?.wristRelativeLandmarks;
const modelHand = hand.value ? normalizeHandForModel(hand.value) : null;
```

Use the image points for the overlay. Their x and y are in image coordinates
and z is the depth reported by MediaPipe. The wrist-relative points subtract
landmark 0 on all three axes and preserve scale and orientation. The arrays
contain separate point objects. The tracker skips detections with missing or
invalid coordinates and reports `null` when no valid detection remains.

For the right-hand-trained letter model, `normalizeHandForModel` from the same
package reflects left-hand x with `x = 1 - x`. It keeps the handedness label,
score, landmark order, y and z. The model pipeline handles wrist centering and
scaling. Both browser panels send the normalized points, the physical hand
label (`LEFT` or `RIGHT`) and `coordinateSpace: "MEDIAPIPE_IMAGE"` for the
Python prediction request. An empty frame or a frame with only the other hand
produces `no_hand`.
[Prediction payload](../model/README.md#post-predict)

## Workspace

| Path | Purpose |
| --- | --- |
| [`apps/web/`](../frontend/apps/web/) | Vue game, WebSocket client and ranking API client |
| [`packages/hand-landmarker/`](../frontend/packages/hand-landmarker/) | MediaPipe tracker, hand selection and coordinate normalization |
| [`packages/hand-landmarker-vue/`](../frontend/packages/hand-landmarker-vue/) | Vue wrapper for the tracker |

The app resolves library exports from `src/` during Vite development. Production
builds use compiled `dist/` output. Turborepo builds workspace dependencies
first and caches build output. See [Automated checks](local-development.md#automated-checks)
for the workspace build, type checking and Vitest commands.
