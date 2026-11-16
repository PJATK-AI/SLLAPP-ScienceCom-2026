<a id="readme-top"></a>

<div align="center">
  <h1>SLLAPP · ScienceCom 2026</h1>
  <p>Sign Language Learning Application</p>
  <p>A Spring Boot ranking API, SQLite result storage and Vue hand-tracking packages.</p>
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

The backend stores game results and serves an ordered ranking over REST. The
frontend workspace contains a Vue application scaffold, a MediaPipe hand tracker
and its Vue wrapper. The web application displays an interactive counter.

| Component | Technologies |
| --- | --- |
| Backend | Java 21, Spring Boot, Spring JDBC, SQLite |
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

In a second terminal, from the repository root, run:

```sh
cd frontend
pnpm install --frozen-lockfile
pnpm dev
```

Open the local URL printed by Vite.

## Usage

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
| `backend/` | Spring Boot application, ranking API and SQLite result storage |
| `frontend/apps/web/` | Vue application scaffold |
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

On Linux or macOS, use `sh mvnw -B verify`. The tests cover SQLite persistence,
duplicate-session handling, ranking order and limits, and HTTP responses for valid
and invalid ranking requests. Storage tests use temporary databases.

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
| [API reference](docs/api.md) | Ranking requests, responses and validation errors |
| [Backend architecture](docs/backend-architecture.md) | Packages, result storage, configuration and test coverage |
| [Project workflow](docs/WORKFLOW.md) | Issues, branches, commits, validation and pull requests |

Follow the project workflow when contributing. Changes reach `master` through
pull requests; the project uses merge commits.

## License

Distributed under the [MIT License](LICENSE). Copyright © 2026 PJATK{AI}.

<p align="right"><a href="#readme-top">Back to top ↑</a></p>
