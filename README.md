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
    <a href="#local-tools">Local tools</a>
  </p>
  <p>
    <a href="docs/api.md">API reference</a> ·
    <a href="docs/backend-architecture.md">Backend architecture</a> ·
    <a href="https://github.com/PJATK-AI/SLLAPP-ScienceCom-2026/issues">Issues</a>
  </p>
</div>

## Quick start

**Requirements** · JDK 21 · Node.js 24.15+ · pnpm 12.21.0

Run each block from the repository root in its own terminal.
[PowerShell quick start](docs/local-development.md#windows-quick-start)

### Backend · terminal 1

```sh
cd backend
sh mvnw spring-boot:run
```

### Frontend · terminal 2

```sh
cd frontend
pnpm setup:local
pnpm dev
```

Run `pnpm setup:local` on first setup. It installs the locked dependencies,
creates `apps/web/.env.local` if needed and prepares the camera assets.
Use `pnpm dev` for subsequent starts.

Open [the browser app](http://127.0.0.1:5173), enter a nickname and select a
hand. Click **Utwórz sesję**, then **Uruchom kamerę**. Watch the landmarks and
the last sample sent over WebSocket. To check the backend separately, open
[`/api/ranking`](http://127.0.0.1:8080/api/ranking). A fresh database returns `[]`.

## Prediction service

The repository includes the trained model at `model/artifacts/model.joblib`.
With the Python 3.13 environment active, run from the repository root in a
separate terminal. [Python and model setup](docs/local-development.md#run-recognition-with-the-python-service)

```sh
python -m model.service
```

Check [`/health`](http://127.0.0.1:8000/health). For an artifact in a different
directory, pass `--model path/to/model.joblib`. To see predictions in the
browser, [enable AI in the recognition panel](docs/local-development.md#run-recognition-with-the-python-service).

During a round, the backend completes the active letter after three matching
predictions with confidence at least `0.80` and no more than `350ms` between
their accepted samples by default. Two consecutive `no_hand` messages unlock
a repeated letter. [Recognition rules and settings](docs/api.md#letter-recognition)

## Local tools

Run the panel commands from `backend/`. On Windows, use `.\mvnw.cmd` in place
of `sh mvnw`.

| Panel | Start |
| --- | --- |
| [Game engine tester](docs/local-development.md#game-engine-tester) | `sh mvnw -Pgame-test` |
| [Camera and WebSocket diagnostics](docs/local-development.md#recognition-diagnostics) | `sh mvnw -Precognition-test` |
| [Recognition diagnostics with Python](docs/local-development.md#run-recognition-with-the-python-service) | `sh mvnw -Precognition-ai` |

For either recognition mode, run `pnpm setup:recognition` once from
`frontend/`. [Automated checks](docs/local-development.md#automated-checks)

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

<p align="right"><a href="#readme-top">Back to top ↑</a></p>
