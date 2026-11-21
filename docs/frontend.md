# Frontend

The Vue app checks camera tracking and WebSocket transport in the `CREATED`
session state. It displays the camera preview and last sent sample. The
[recognition diagnostics panel](local-development.md#recognition-diagnostics)
shows the additional exchange with the Python prediction service.
[Start the Vue app](../README.md#quick-start)

## Camera and session panel

1. Open the app on localhost or HTTPS. Enter a nickname, select the physical
   hand and click **Utwórz sesję**. The server confirms the session with a
   `game_state` message and the panel displays its ID.
2. Click **Uruchom kamerę** and allow camera access. The preview draws the 21
   landmarks of the selected hand in the original image coordinates. The points
   disappear when that hand leaves the frame.
3. Watch **Ostatnia wysłana próbka (JSON)**. The selected hand produces
   `hand_sample`. An empty frame or a frame showing only the other hand
   produces `no_hand`. With the camera stopped, the two sample buttons send
   equivalent transport tests using synthetic data.
4. Use **Rozłącz** to stop sending while keeping the preview. **Wznów sesję**
   opens a new connection and restarts the sample sequence. **Zatrzymaj kamerę**
   releases the camera and re-enables the synthetic sample buttons. Leaving the
   view or losing the session also releases it.

The default sample rate is 10 Hz. Intermediate frames and frames sent while the
socket has buffered data are skipped. The panel keeps showing the most recent
sent frame. To see `sample_ack` confirmations, enable the backend's WebSocket
diagnostic mode. [WebSocket configuration](api.md#websocket-configuration)

## Configuration

Vite reads environment variables from `frontend/apps/web/.env.local`.
`pnpm setup:local` creates it from the [example file](../frontend/.env.example)
if it is missing. The example points at the local backend. Restart Vite after
editing the file. `pnpm dev` serves the app at `127.0.0.1:5173` and reports an
error if the port is busy.

| Variable | Value for local use |
| --- | --- |
| `VITE_WS_URL` | `ws://127.0.0.1:8080/ws` for the session panel |
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

`useGameSession({ url, timeoutMs })` owns one WebSocket connection. By default
it reads `VITE_WS_URL` and allows 10 seconds each for opening the connection and
receiving session confirmation. It exposes connection status, readiness, the
latest validated state, errors, closure information and sample diagnostics.

Use `createSession({ nickname, selectedHand })`, `disconnect()` and
`reconnect()` for the session lifecycle. The current browser client accepts
`CREATED` session states. `sendHandSample(hand)` and `sendNoHand()` add
`positionId: null` and a shared sequence starting at zero. A true return value
means the browser accepted the data for sending. A diagnostic `sample_ack`
confirms that the server accepted the sample.

The session ID is held in memory and is lost on reload. When the server closes
an expired session with code 1008, the panel clears its ID so a new session can
be created. Reconnection is manual. Vue scope disposal or an explicit
`dispose()` releases the socket, listeners and timers.
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
scaling. Both browser panels send the normalized points and the physical hand
label (`LEFT` or `RIGHT`). The recognition diagnostics panel adds
`coordinateSpace: "MEDIAPIPE_IMAGE"` for the Python prediction request.
[Prediction payload](../model/README.md#post-predict)

## Workspace

| Path | Purpose |
| --- | --- |
| [`apps/web/`](../frontend/apps/web/) | Vue camera and session test panel, WebSocket client and ranking API client |
| [`packages/hand-landmarker/`](../frontend/packages/hand-landmarker/) | MediaPipe tracker, hand selection and coordinate normalization |
| [`packages/hand-landmarker-vue/`](../frontend/packages/hand-landmarker-vue/) | Vue wrapper for the tracker |

The app resolves library exports from `src/` during Vite development. Production
builds use compiled `dist/` output. Turborepo builds workspace dependencies
first and caches build output. See [Automated checks](local-development.md#automated-checks)
for the workspace build, type checking and Vitest commands.
