# Local development

Use the [README quick start](../README.md#quick-start) for the browser app and
backend on macOS or Linux. Use [Vue round diagnostics](#vue-round-diagnostics)
to test gameplay in the browser, or use the standalone panels below to check
the game engine or inspect prediction requests. Start each server in its own
terminal from the repository root.

| Address | Process | Requirements |
| --- | --- | --- |
| [127.0.0.1:5173](http://127.0.0.1:5173) | Vue fingerspelling game | Backend on port 8080, Node.js and pnpm |
| [127.0.0.1:8080/api/ranking](http://127.0.0.1:8080/api/ranking) | Spring Boot backend | JDK 21 |
| [127.0.0.1:8081/game-test](http://127.0.0.1:8081/game-test) | Game engine tester | JDK 21 |
| [127.0.0.1:8082/recognition-test](http://127.0.0.1:8082/recognition-test) | Camera and AI diagnostics | JDK 21, prepared frontend assets, Python only for letter predictions |
| [127.0.0.1:8000/health](http://127.0.0.1:8000/health) | Python prediction service | Python 3.13 and a compatible model |

Check your versions with `java -version`, `node --version` and `pnpm --version`.
The project uses JDK 21, Node.js 24.15 or newer and pnpm 12.21.0. If pnpm is
missing, install the workspace version with `npm install --global pnpm@12.21.0`.

## Windows quick start

Use PowerShell. In terminal 1, from the repository root:

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

In terminal 2, from the repository root:

```powershell
cd frontend
pnpm setup:local
pnpm dev
```

Run `pnpm setup:local` on first setup. It installs dependencies, creates
`apps/web/.env.local` if needed and prepares the camera assets.
Use `pnpm dev` for subsequent starts.

Open [the app](http://127.0.0.1:5173) and [the ranking API](http://127.0.0.1:8080/api/ranking)
to check both processes. Then [enable prediction on the regular backend](#enable-prediction-on-the-regular-backend)
and follow [play a round](frontend.md#play-a-round).

## Vue round diagnostics

From `frontend/`, run `pnpm setup:local` on first setup. Then start the Vue app
in diagnostics mode:

```sh
pnpm dev:diagnostics
```

Choose the backend startup command for the check you want to run. The regular
backend uses port 8080 in both cases.

### Check WebSocket samples before a round

Start the regular backend with diagnostic acknowledgements enabled.
[Backend startup commands for macOS, Linux and Windows](#enable-websocket-acknowledgements)

1. Open [the Vue game](http://127.0.0.1:5173), enter a nickname, select a hand
   and click **Utwórz sesję**. Leave the camera off.
2. In **Diagnostyka**, click **Wyślij testową dłoń** and then **Wyślij brak dłoni**.
3. In **Historia WebSocket**, check that the browser sends `hand_sample` and
   `no_hand`. Each should have a matching `sample_ack` with the same `seq`.
   Expand a history entry to inspect its JSON.

### Check live predictions during a round

Start the [Python prediction service](#run-recognition-with-the-python-service)
and check its [health endpoint](http://127.0.0.1:8000/health). Then start the
regular backend with both WebSocket diagnostics and prediction enabled. From
`backend/` on macOS or Linux:

```sh
SLLAPP_WEBSOCKET_DIAGNOSTICMODE=true AI_PREDICTION_ENABLED=true AI_PREDICTION_BASE_URL=http://127.0.0.1:8000 sh mvnw spring-boot:run
```

In PowerShell:

```powershell
$env:SLLAPP_WEBSOCKET_DIAGNOSTICMODE = 'true'
$env:AI_PREDICTION_ENABLED = 'true'
$env:AI_PREDICTION_BASE_URL = 'http://127.0.0.1:8000'
.\mvnw.cmd spring-boot:run
```

1. Open [the Vue game](http://127.0.0.1:5173) and create a session. Click
   **Uruchom kamerę**, allow camera access and click **Rozpocznij rundę**.
2. Sign the highlighted letter with the selected hand. In **Ostatnia predykcja
   AI**, check the predicted letter, confidence (**Ufność**), HTTP status and
   game decision (**Decyzja gry**).
3. When the panel reports **Zaliczona litera**, check that the score increases
   and the round advances. Use the connection number, `seq` and `positionId`
   in **Historia WebSocket** to match a prediction to its sample.

[Frontend diagnostics panel details](frontend.md#round-diagnostics) ·
[Panel for inspecting raw Java-to-Python HTTP bodies](#run-recognition-with-the-python-service)

## Game engine tester

From `backend/` on macOS or Linux:

```sh
sh mvnw -Pgame-test
```

In PowerShell:

```powershell
.\mvnw.cmd -Pgame-test
```

Open [the game tester](http://127.0.0.1:8081/game-test). Click **Start game**
and **Complete letter** to exercise the timer, position and scoring using
simulated completions. Each test round starts with a 60-second timer.
[Game engine behavior](backend-architecture.md#standalone-game-engine)

## Recognition diagnostics

The diagnostic panel shows the selected hand and its skeleton, the exact JSON
sent and received over WebSocket, and the HTTP request and response observed by
the Java prediction client. It runs on its own backend port and doesn't need a
Vite server. Prepare its assets once from `frontend/`:

```sh
pnpm setup:recognition
```

To check tracking and transport with AI disabled, start the panel from
`backend/` on macOS or Linux:

```sh
sh mvnw -Precognition-test
```

On Windows:

```powershell
.\mvnw.cmd -Precognition-test
```

Open [the recognition panel](http://127.0.0.1:8082/recognition-test). Select a
hand, click **Uruchom kamerę**, allow camera access and click **Połącz WebSocket**.
The preview mirrors the camera image and skeleton together. Samples use the
tracker coordinates with the usual selected-hand normalization.
Show the selected hand to send `hand_sample`, then move it out of frame to
send `no_hand`. Expand a row to inspect its exact payload. The panel keeps the
latest 150 events and shows `DISABLED` for prediction attempts while AI is off.
[How the diagnostics endpoint works](backend-architecture.md#camera-recognition-diagnostics)

## Run recognition with the Python service

Use the repository's `model/artifacts/model.joblib` and check its SHA-256.
The checksum and training report are in the same directory. [Delivered model file](../model/README.md#delivered-model-file)

**One-time Python setup · repository root**

On macOS or Linux, check that `python3.13 --version` works before creating
the environment. If macOS reports `command not found` and you use Homebrew,
install Python 3.13 with `brew install python@3.13`.
[Homebrew Python 3.13 formula](https://formulae.brew.sh/formula/python@3.13)

```sh
python3.13 -m venv .venv
.venv/bin/python -m pip install -r model/requirements.txt
```

Windows PowerShell:

```powershell
py -3.13 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r model/requirements.txt
```

**Terminal 1 · Start Python from the repository root**

```sh
.venv/bin/python -m model.service
```

In PowerShell, use `.\.venv\Scripts\python.exe -m model.service`.

Open [`/health`](http://127.0.0.1:8000/health) to check the loaded model and
checksum. To use the model from a different path, add
`--model path/to/model.joblib` to the service command.

**Terminal 2 · Start diagnostics with AI enabled**

Prepare the assets as shown in [Recognition diagnostics](#recognition-diagnostics).
If the diagnostics panel is already running on port 8082, stop it before
starting the AI-enabled mode. From `backend/` on macOS or Linux:

```sh
sh mvnw -Precognition-ai
```

On Windows:

```powershell
.\mvnw.cmd -Precognition-ai
```

This mode uses Python at `http://127.0.0.1:8000` by default. Set
`AI_PREDICTION_BASE_URL` if the service runs at a different address.

In [the recognition panel](http://127.0.0.1:8082/recognition-test), start the
camera and connect. Show the selected hand and look for `POST /predict`, its
HTTP status, and the predicted letter and confidence. The panel also shows
client failures such as `TIMEOUT` and `TRANSPORT_ERROR`. The default timeout is
`250ms`. You can override it with `AI_PREDICTION_TIMEOUT`.
[Prediction client configuration](backend-architecture.md#ai-prediction-client)

### Enable prediction on the regular backend

The Java client starts with prediction disabled. To connect it to Python at
`127.0.0.1:8000`, run from `backend/` on macOS or Linux:

```sh
AI_PREDICTION_ENABLED=true AI_PREDICTION_BASE_URL=http://127.0.0.1:8000 sh mvnw spring-boot:run
```

In PowerShell:

```powershell
$env:AI_PREDICTION_ENABLED = 'true'
$env:AI_PREDICTION_BASE_URL = 'http://127.0.0.1:8000'
.\mvnw.cmd spring-boot:run
```

The client checks `/health` at startup and logs the model metadata. The
prediction timeout defaults to `250ms`. Set `AI_PREDICTION_TIMEOUT` to change it.
[Prediction client configuration](backend-architecture.md#ai-prediction-client)

## Automated checks

Start each suite from the repository root. The full checks also run in
[GitHub Actions](../.github/workflows/ci.yml) on pull requests and pushes to
`master`.

### Frontend

Vitest covers the Vue game and diagnostics, WebSocket session creation and
resumption, ranking requests, and hand selection and normalization. The
suite uses simulated inputs and can run without a camera, the tracker model
or a running backend.

```sh
cd frontend
pnpm install --frozen-lockfile
pnpm test
```

From `frontend/`, run the full frontend CI checks for formatting, builds,
type checking and tests:

```sh
pnpm format:check
pnpm turbo run build typecheck test
```

Run `pnpm prepare:camera` before a build intended to run camera tracking in
the browser.

### Backend

The Maven checks cover game rules and scoring, WebSocket sessions and
recognition, the prediction client, the ranking API and SQLite storage.

```sh
cd backend
sh mvnw -B verify
```

In PowerShell, run `.\mvnw.cmd -B verify` after `cd backend`. To run only the
standalone game engine tests, use the `test` goal with
`'-Dtest=pl.pjatk.ai.sllapp.game.*Test'`.

### Python

Create the virtual environment and install the pinned dependencies as shown
in [Run recognition with the Python service](#run-recognition-with-the-python-service).
Use the environment's interpreter from the repository root:

```sh
.venv/bin/python -m unittest discover -s model/tests -t . -v
```

On Windows:

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s model/tests -t . -v
```

The suite covers landmark preprocessing, dataset validation, training,
calibration and the prediction API. The API tests start their own HTTP
server and use synthetic samples. [Python test details](../model/README.md#run-the-tests)

## Configuration and troubleshooting

| Symptom | Check |
| --- | --- |
| Spring Boot cannot bind to its port | Stop the process already using that port, or check whether the requested server is already running at the address above. |
| Vite reports a busy port | Free port 5173. To use port 5174, run `pnpm dev -- --port 5174` and add `http://127.0.0.1:5174` to `sllapp.web.allowed-origins` before starting the backend. |
| Browser reports a missing URL or connection error | Check `VITE_WS_URL` and `VITE_API_BASE_URL` in `frontend/apps/web/.env.local`. Restart Vite after editing the file. |
| Camera permission was denied or the preview is blank | Use a loopback URL or HTTPS. Check the browser's camera permission and the operating system's camera settings. |
| Camera assets fail to load | Run `pnpm prepare:camera` for the Vue app or `pnpm setup:recognition` for the recognition panel, from `frontend/`. |
| Python exits before opening port 8000 | Follow the startup error. Check the model path, format and scikit-learn version using the [model documentation](../model/README.md#prediction-service). |
| The diagnostic result is `DISABLED` | Start the panel with `-Precognition-ai`. |
| The diagnostic result is `TRANSPORT_ERROR` or `TIMEOUT` | Check the service's `/health` at the configured address. Verify that the service responds within the configured timeout. |

The default backend database is `backend/data/sllapp.db` with the commands
above. Set `DATA_DIR` to choose another directory. The test profiles have
separate databases under `backend/target/game-test-data/` and
`backend/target/recognition-test-data/`.

See [frontend environment variables](frontend.md#configuration) and
[backend WebSocket settings](api.md#websocket-configuration) for further options.

### Enable WebSocket acknowledgements

From `backend/`, restart the regular backend with diagnostic acknowledgements
enabled. On macOS or Linux:

```sh
SLLAPP_WEBSOCKET_DIAGNOSTICMODE=true sh mvnw spring-boot:run
```

On Windows:

```powershell
$env:SLLAPP_WEBSOCKET_DIAGNOSTICMODE = 'true'
.\mvnw.cmd spring-boot:run
```

With `pnpm dev:diagnostics` running, send a sample in the
[Vue game](http://127.0.0.1:5173) and check the `sample_ack` sequence in
**Diagnostyka**.
