<a id="readme-top"></a>

<div align="center">
  <h1>SLLAPP · ScienceCom 2026</h1>
  <p>Sign Language Learning Application</p>
  <p>A Java game engine, AI letter recognition, WebSocket sessions, a ranking REST API, SQLite result storage and Vue hand tracking.</p>
  <p>
    <img src="https://img.shields.io/badge/Java-21-ED8B00?style=flat-square" alt="Java 21">
    <img src="https://img.shields.io/badge/Vue-3-4FC08D?style=flat-square" alt="Vue 3">
    <img src="https://img.shields.io/badge/SQLite-003B57?style=flat-square" alt="SQLite">
  </p>
  <p>
    <a href="#quick-start">Quick start</a> ·
    <a href="#prediction-service">Start the model</a> ·
    <a href="#vue-round-diagnostics">Vue diagnostics</a> ·
    <a href="#local-tools">Local tools</a>
  </p>
  <p>
    <a href="docs/api.md">API reference</a> ·
    <a href="docs/backend-architecture.md">Backend architecture</a> ·
    <a href="https://github.com/PJATK-AI/SLLAPP-ScienceCom-2026/issues">Issues</a>
  </p>
</div>

## Quick start

### Play with Docker Compose

With Docker running, run this from the repository root:

```sh
docker compose up
```

Open [the game](http://127.0.0.1:8088) and allow camera access. Compose builds
and starts the game, backend and prediction service. The first build needs
internet access for base images, dependencies and the MediaPipe hand tracker.
The classifier comes from `model/artifacts/model.joblib` in the checkout.

After changing code, run `docker compose up --build`. To stop the stack, run
`docker compose down` from another terminal. Rankings are stored in the named
`ranking` volume and survive `down`. [Compose setup and troubleshooting](docs/local-development.md#docker-compose)

### Run from source

**Requirements** · JDK 21 · Node.js 24.15+ · pnpm 12.21.0

Run each block from the repository root in its own terminal.
[PowerShell quick start](docs/local-development.md#windows-quick-start)

#### Backend · terminal 1

```sh
cd backend
sh mvnw spring-boot:run
```

#### Frontend · terminal 2

```sh
cd frontend
pnpm setup:local
pnpm dev
```

Run `pnpm setup:local` on first setup. It installs the locked dependencies,
creates `apps/web/.env.local` if needed and prepares the camera assets.
Use `pnpm dev` for subsequent starts.

Open [the browser app](http://127.0.0.1:5173), enter a nickname and select a
hand. Click **Utwórz sesję**, then **Uruchom kamerę**. To play, first [enable
prediction on the regular backend](docs/local-development.md#enable-prediction-on-the-regular-backend).
Click **Rozpocznij rundę**, then sign the highlighted letters using the hand
reference beside the camera. The final score and ranking appear when the round ends.

## Prediction service

The repository includes the trained model at `model/artifacts/model.joblib`.
With the Python 3.13 environment active, run from the repository root in a
separate terminal. [Python and model setup](docs/local-development.md#run-recognition-with-the-python-service)

```sh
python -m model.service
```

Check [`/health`](http://127.0.0.1:8000/health). For an artifact in a different
directory, pass `--model path/to/model.joblib`. For the Vue game, start the
[regular backend with AI enabled](docs/local-development.md#enable-prediction-on-the-regular-backend).

During a round, the backend completes the active letter after three matching
predictions with confidence at least `0.80` and no more than `350ms` between
their accepted samples by default. Two consecutive `no_hand` messages unlock
a repeated letter. [Recognition rules and settings](docs/api.md#letter-recognition)

## Local tools

### Vue round diagnostics

Start here to test the game in the browser. The **Diagnostyka** panel shows
WebSocket messages, sample acknowledgements and live predictions during a round.

Run this from the repository root. Use `pnpm setup:local` first if the frontend
hasn't been set up yet.

```sh
cd frontend
pnpm dev:diagnostics
```

- **Check WebSocket transport.** Enable diagnostics on the regular backend
  and create a session. With the camera off, click **Wyślij testową dłoń**
  and **Wyślij brak dłoni**. Look for a `sample_ack` with the same `seq`
  as each sample.
- **Play a round with live predictions.** Run the Python service and enable
  prediction on the backend. Start the camera and a round, then sign the
  highlighted letter. Check the predicted letter, confidence and game
  decision in the panel.

[Setup commands and expected results](docs/local-development.md#vue-round-diagnostics)

### Backend test panels

Run these commands from `backend/`. On Windows, use `.\mvnw.cmd` in place of
`sh mvnw`. For either recognition panel mode, first run
`pnpm setup:recognition` from `frontend/`.

| Panel | Start | What to check |
| --- | --- | --- |
| [Game engine tester](docs/local-development.md#game-engine-tester) | `sh mvnw -Pgame-test` | Simulate letter completions and check the timer, letter progression and score. |
| [Camera and WebSocket diagnostics](docs/local-development.md#recognition-diagnostics) | `sh mvnw -Precognition-test` | Check hand tracking and inspect the WebSocket JSON with AI disabled. |
| [Recognition diagnostics with Python](docs/local-development.md#run-recognition-with-the-python-service) | `sh mvnw -Precognition-ai` | Inspect Java's HTTP requests to Python, responses, predicted letters and confidence. Start Python first. |

### Automated checks

From `frontend/`, run `pnpm test` to run Vitest across the workspace. The
tests cover the Vue game, WebSocket sessions, ranking and hand tracking.

[Full CI commands for the frontend, backend and Python](docs/local-development.md#automated-checks)

## Documentation and contributing

| Document | Contents |
| --- | --- |
| [Local development](docs/local-development.md) | Startup commands, ports, profiles and troubleshooting |
| [API reference](docs/api.md) | WebSocket protocol and ranking endpoint |
| [Frontend](docs/frontend.md) | Camera tracking, sample handling and workspace structure |
| [Backend architecture](docs/backend-architecture.md) | Game engine, session lifecycle, prediction client and storage |
| [AI model](model/README.md) | Model artifact, prediction service API, dataset and training |
| [Project workflow](docs/WORKFLOW.md) | Issues, branches, commits and pull requests |

## License

[MIT License](LICENSE) · Copyright © 2026 PJATK{AI}.

The [fingerspelling chart](docs/frontend.md) is available under CC BY-SA 3.0.

<p align="right"><a href="#readme-top">Back to top ↑</a></p>
