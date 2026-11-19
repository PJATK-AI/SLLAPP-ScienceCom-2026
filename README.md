<a id="readme-top"></a>

<div align="center">
  <h1>SLLAPP · ScienceCom 2026</h1>
  <p>Sign Language Learning Application</p>
  <p>A Java game engine, WebSocket sessions, a ranking REST API, SQLite result storage and Vue hand tracking.</p>
  <p>
    <img src="https://img.shields.io/badge/Java-21-ED8B00?style=flat-square" alt="Java 21">
    <img src="https://img.shields.io/badge/Vue-3-4FC08D?style=flat-square" alt="Vue 3">
    <img src="https://img.shields.io/badge/SQLite-003B57?style=flat-square" alt="SQLite">
  </p>
  <p>
    <a href="docs/api.md">API reference</a> ·
    <a href="docs/backend-architecture.md">Backend architecture</a> ·
    <a href="https://github.com/PJATK-AI/SLLAPP-ScienceCom-2026/issues">Issues</a>
  </p>
</div>

<details>
  <summary>Table of contents</summary>

- [About the project](#about-the-project)
- [Getting started](#getting-started)
- [Usage](#usage)
- [Repository structure](#repository-structure)
- [Development and testing](#development-and-testing)
- [Documentation and contributing](#documentation-and-contributing)
- [License](#license)

</details>

## About the project

SLLAPP is the Sign Language Learning Application project for ScienceCom 2026,
maintained by PJATK AI.

The backend includes a standalone Java engine for the spelling game. It also
creates player sessions over WebSocket, validates hand samples, stores game
results and serves an ordered ranking over REST. The
frontend workspace contains a Vue WebSocket session test panel, a MediaPipe hand
tracker and its Vue wrapper. The panel creates and resumes sessions and sends
synthetic hand samples for transport testing.

| Component | Technologies |
| --- | --- |
| Backend | Java 21, Spring Boot, WebSocket, Spring JDBC, SQLite |
| Frontend | Vue 3, TypeScript, Vite, MediaPipe Hand Landmarker |
| Build and tests | Maven Wrapper, pnpm, Turborepo, JUnit, Vitest |
| CI | GitHub Actions |

## Getting started

### Prerequisites

| Tool | Required version |
| --- | --- |
| JDK | 21 |
| Node.js | 24.15 or newer |
| pnpm | 12.21.0, as declared in `frontend/package.json` |

The backend includes the Maven Wrapper. SQLite runs inside the backend process
and stores data in a local file.

### Start the backend

From the repository root, run:

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

On Linux or macOS, use `sh mvnw spring-boot:run` from `backend/`.
The server listens on **http://127.0.0.1:8080**.

### Start the frontend

The web application reads the backend REST API address from
`VITE_API_BASE_URL`. [`frontend/.env.example`](frontend/.env.example) shows the
value for the local backend. Vite loads environment files from
`frontend/apps/web/`, so put the variable in `frontend/apps/web/.env.local`:

```sh
cp frontend/.env.example frontend/apps/web/.env.local
```

When the variable is missing, API calls fail with a configuration error.

In a second terminal, from the repository root, run:

```sh
cd frontend
pnpm install --frozen-lockfile
pnpm prepare:camera
pnpm dev
```

Open the local URL printed by Vite.

## Usage

### Use the game engine

Java callers create a `GameEngine` with rules, a word picker, a start time and
a deadline. The bundled catalog contains Polish words of lengths 3–7. The
engine returns the current letter, position ID, remaining time and score,
and advances when the caller completes the current position before the
deadline. `GameRules.defaults()` sets the maximum word length to 7, letter
points to 10, the word bonus to 20 and the multiplier step to 1. To configure
these values per game, pass a custom `GameRules` instance. See the
[Java usage example and scoring rules](docs/backend-architecture.md#standalone-game-engine).

### Open the game engine tester

From `backend/` on Linux or macOS, start Spring Boot with the `game-test` profile.

```sh
sh mvnw spring-boot:test-run -Dspring-boot.run.profiles=game-test
```

On Windows PowerShell, use:

```powershell
.\mvnw.cmd spring-boot:test-run "-Dspring-boot.run.profiles=game-test"
```

Open the [game preview](http://127.0.0.1:8081/game-test) and select **Start
game** for a 60-second round with random words. Click **Complete letter**
to simulate a recognized sign. Watch the active letter, score, last award
and countdown update from the engine. **New game** starts a fresh round.

The tester uses the backend's engine, default rules and bundled catalog. The
profile loads the panel from the test classpath and uses a database under
`backend/target/game-test-data/`.

### Frontend WebSocket test panel

Set `VITE_WS_URL=ws://127.0.0.1:8080/ws` in `frontend/apps/web/.env.local`
(using `frontend/.env.example`) and restart Vite. The page provides a nickname
and hand selection, session creation, disconnect and manual resume controls.
An open socket is ready to send samples only after the server confirms the session.

The sample buttons send one synthetic 21-point hand or one `no_hand` message.
They test transport, not camera tracking or gesture recognition. The panel shows
session ID, connection status, the last sent sequence and optional `sample_ack`.
Enable `sllapp.websocket.diagnostic-mode=true` on the backend to see acknowledgements.
Sending also works when acknowledgements are disabled.

### Stream hand landmarks from the camera

Run `pnpm prepare:camera` from `frontend/` after installing dependencies and
before starting Vite or building the web app. It downloads version 1 of Google's
float16 Hand Landmarker and copies the WASM runtime from the installed MediaPipe
package to `apps/web/public/mediapipe/`. Vite serves these files locally and
includes them in the production build. Custom deployments can set
`VITE_HAND_LANDMARKER_MODEL_URL` to the model file and
`VITE_HAND_LANDMARKER_WASM_PATH` to the matching runtime directory.

1. Open the app on localhost or HTTPS. Enter a nickname, select the physical
   hand and create a session. After confirmation, click **Uruchom kamerę** and
   allow camera access. The preview marks the 21 detected landmarks on the selected
   hand using the original camera coordinates. The dots disappear when that hand
   leaves the frame.
2. Show the selected hand. Each eligible frame sends `hand_sample` with 21
   image landmarks. For the left hand the client reflects x once and keeps
   the `LEFT` label. An empty frame or a frame with only the other hand sends
   `no_hand`. Both messages share the `VITE_HAND_SAMPLE_HZ` limit (default 10
   per second). The client drops intermediate frames and skips a busy socket.
3. The JSON panel displays the complete last sample sent over the socket from
   the camera or either test button. Skipped frames leave the displayed sample
   in place. In diagnostic mode, check the last sent sequence and the corresponding
   `sample_ack` for each type. **Rozłącz** pauses sending and keeps the preview.
   After **Wznów sesję**, the first fresh eligible frame starts the new sequence.
4. Click **Zatrzymaj kamerę** to release the camera and enable the synthetic
   test buttons again. Leaving the view or losing the session also releases it.

If camera permission is denied, allow it in the browser and start again.
For a model load error, check that the model file and WASM directory are served
at the configured paths. After editing `.env.local`, restart Vite.

Reconnection retains the session ID in memory and resets the sample sequence
on the new confirmed connection. Reloading the page loses that ID. Closure code
1008 clears an expired or unavailable session and enables creating another one.
The client waits up to 10 seconds for connection opening and another 10 seconds
for session confirmation. It does not reconnect automatically.

### Read hand landmarks in Vue

`useHandTracker` from `@sllapp/hand-landmarker-vue` exposes the latest selected
hand through `hand.value`. When a hand is detected, both arrays contain 21
points in MediaPipe order.

```ts
const imagePoints = hand.value?.landmarks;
const wristPoints = hand.value?.wristRelativeLandmarks;
```

Use the original image points to draw an overlay. Their x and y coordinates are
normalized to the image, and z is the depth reported by MediaPipe. Wrist-relative
points subtract landmark 0 from each point on all three axes, so the wrist is
at (0, 0, 0). This translation preserves scale and orientation. Each array has
its own point objects. The tracker skips detections with missing or invalid
coordinates and reports `null` if no valid hand remains.

For a classifier trained on right-hand image landmarks, prepare a separate copy
with `normalizeHandForModel` from `@sllapp/hand-landmarker-vue`:

```ts
const modelHand = hand.value ? normalizeHandForModel(hand.value) : null;
```

The function reflects the left hand's horizontal image coordinates and preserves
the physical hand label. Keep using the original landmarks for the overlay.
The model pipeline handles wrist centering and scaling.

### Open a WebSocket session

With the backend running, use a browser console on the local Vite page:

```javascript
const socket = new WebSocket('ws://127.0.0.1:8080/ws');
socket.onmessage = (event) => console.log(JSON.parse(event.data));
socket.onopen = () => socket.send(JSON.stringify({
    type: 'create_session',
    nickname: 'Ola',
    selectedHand: 'RIGHT',
}));
```

The server responds with `game_state`, a `sessionId`, player data and
`positionId: null`. The connection accepts `hand_sample` and `no_hand` messages.
Sessions expire five minutes after creation by default; their data lives in
memory. A new socket can resume a session and replace its previous connection.
See the [WebSocket reference](docs/api.md#websocket-transport) for complete
messages, reconnect behavior, origin settings and optional acknowledgements.

### Read the ranking

With the backend running, request the top results:

```sh
curl "http://127.0.0.1:8080/api/ranking?limit=10"
```

The endpoint returns a JSON array of `rank`, `nickname` and `score`. It orders
results by score descending, then by earlier finish time, with the session
identifier as the final tie-breaker. An empty database returns `[]`.

The default limit is 10. Values outside 1–100 and invalid input return `400` with a
`ProblemDetail` body. See the [API reference](docs/api.md) for response examples.

### Keep saved results

The backend creates `data/sllapp.db` relative to its working directory. With the
startup commands above, that file is `backend/data/sllapp.db`. Set `DATA_DIR` to
choose another storage directory. Keep the database file between runs to retain
saved results.

## Repository structure

| Path | Contents |
| --- | --- |
| `backend/` | Java game engine, Spring Boot application, WebSocket sessions, ranking API and SQLite storage |
| `frontend/apps/web/` | Vue session test panel, WebSocket client and ranking API client |
| `frontend/packages/hand-landmarker/` | MediaPipe tracker API and hand selection |
| `frontend/packages/hand-landmarker-vue/` | Vue composable for the hand tracker |
| `docs/` | API reference, backend architecture and project workflow |
| `.github/workflows/ci.yml` | Backend and frontend checks |

## Development and testing

### Backend checks

From `backend/`:

```powershell
.\mvnw.cmd -B verify
```

On Linux or macOS, use `sh mvnw -B verify`. The tests cover the game catalog,
scoring and progression, WebSocket sessions and sample validation, SQLite
persistence and ranking HTTP responses. Integration tests use random server
ports and temporary databases.

To run only the standalone game tests from `backend/`, use
`sh mvnw -B '-Dtest=pl.pjatk.ai.sllapp.game.*Test' test`.

### Frontend commands

Run these commands from `frontend/`:

| Command | Action |
| --- | --- |
| `pnpm dev` | Start the development server |
| `pnpm build` | Build the application and workspace libraries |
| `pnpm typecheck` | Check TypeScript types across the workspace |
| `pnpm test` | Run Vitest tests |
| `pnpm format:check` | Check formatting with Prettier |
| `pnpm format` | Apply Prettier formatting |

Workspace libraries live in `packages/` and use `workspace:*` dependencies.
Turborepo builds dependencies first and caches build output. In development,
Vite and TypeScript resolve library exports to `src/` through the `development`
condition; production builds use compiled `dist/` output.

The [CI workflow](.github/workflows/ci.yml) runs backend verification and frontend
formatting, build, type checks and tests on pull requests and pushes to `master`.

## Documentation and contributing

| Document | Contents |
| --- | --- |
| [API reference](docs/api.md) | WebSocket messages, ranking requests, configuration and errors |
| [Backend architecture](docs/backend-architecture.md) | Game engine, packages, session lifecycle, result storage and tests |
| [Project workflow](docs/WORKFLOW.md) | Issues, branches, commits, validation and pull requests |

Follow the project workflow when contributing. Changes reach `master` through
pull requests; the project uses merge commits.

## License

Distributed under the [MIT License](LICENSE). Copyright © 2026 PJATK{AI}.

<p align="right"><a href="#readme-top">Back to top ↑</a></p>
