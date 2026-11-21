# Local development

Use the [README quick start](../README.md#quick-start) for the browser app and
backend on macOS or Linux. Choose one of the test panels below to work on the
game engine or inspect AI predictions. Keep each server running in its own
terminal. Open a new terminal at the repository root before starting another
set of commands.

| Address | Process | Requirements |
| --- | --- | --- |
| [127.0.0.1:5173](http://127.0.0.1:5173) | Vue camera and session panel | Backend on port 8080, Node.js and pnpm |
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
if (-not (Test-Path apps/web/.env.local)) { Copy-Item .env.example apps/web/.env.local }
pnpm install --frozen-lockfile
pnpm prepare:camera
pnpm --filter @sllapp/web dev --host 127.0.0.1 --port 5173 --strictPort
```

On subsequent starts, run the last command from `frontend/`.

Open [the app](http://127.0.0.1:5173) and [the ranking API](http://127.0.0.1:8080/api/ranking)
to check both processes. See [the frontend camera walkthrough](frontend.md#camera-and-session-panel)
for a sample test.

## Game engine tester

From `backend/` on macOS or Linux:

```sh
sh mvnw spring-boot:test-run -Dspring-boot.run.profiles=game-test
```

In PowerShell:

```powershell
.\mvnw.cmd spring-boot:test-run "-Dspring-boot.run.profiles=game-test"
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

First, prepare the assets in [Recognition diagnostics](#recognition-diagnostics).
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

Run each block from the repository root.

**Backend** · JDK 21

```sh
cd backend
sh mvnw -B verify
```

In PowerShell, run `.\mvnw.cmd -B verify` after `cd backend`. To run only the
standalone game engine tests, pass
`'-Dtest=pl.pjatk.ai.sllapp.game.*Test'` and use the `test` goal.

**Frontend** · Node.js and pnpm

```sh
cd frontend
pnpm install --frozen-lockfile
pnpm format:check
pnpm turbo run build typecheck test
```

Run `pnpm prepare:camera` before a build intended to run camera tracking in
the browser. The CI checks do not require the tracker model or a camera.

**Python** · Python 3.13

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

The suite starts its own test HTTP server and uses synthetic data for the model
and service checks. [Python test details](../model/README.md#run-the-tests)

The same three suites run in the [GitHub Actions workflow](../.github/workflows/ci.yml)
on pull requests and pushes to `master`.

## Configuration and troubleshooting

| Symptom | Check |
| --- | --- |
| Spring Boot cannot bind to its port | Stop the process already using that port, or check whether the requested server is already running at the address above. |
| Vite reports a busy port | Free port 5173. To use another port, pass it to Vite and add the exact alternate origin to `sllapp.web.allowed-origins` before starting the backend. |
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

Send a sample in the [browser app](http://127.0.0.1:5173) and check the
`sample_ack` sequence in the panel.
