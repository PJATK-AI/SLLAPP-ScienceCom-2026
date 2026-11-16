# SLLAPP-ScienceCom-2026

Sign Language Learning Application for ScienceCom 2026

## Backend

The Spring Boot backend is in `backend/` and requires JDK 21. Run these commands from the repository root:

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

On Linux or macOS, run `sh mvnw spring-boot:run` from `backend/`.
The server listens on `http://127.0.0.1:8080`. No endpoints are defined yet.

The backend creates a local SQLite database for game results at `./data/sllapp.db`,
relative to its working directory. Set `DATA_DIR` to use a different directory.
Keep this directory between runs to retain the ranking.

## Frontend

The frontend in `frontend/` is a pnpm + Turborepo monorepo using Vue 3, TypeScript and Vite.
It requires Node.js 24.15 or newer and pnpm 12.

Add new libraries as folders in `packages/` and depend on them with `"workspace:*"`.
Turborepo builds dependencies first (`dependsOn: ["^build"]`) and caches results.

In development, Vite and TypeScript load libraries straight from their `src/` through the
`development` condition in each library's `exports`, so changes show up with HMR and no build step.
Production builds use the compiled `dist/` output. Give every new library the same `exports` layout.

```powershell
cd frontend
pnpm install
pnpm dev          # start the Vite dev server
pnpm build        # build all packages
pnpm test         # run Vitest unit tests in all packages
pnpm typecheck    # type-check all packages
pnpm format       # format code with Prettier (format:check only verifies)
pnpm format:check # verify code with Prettier
```

## AI module

The AI module in `model/` loads and validates the SPAS hand-landmark dataset. It uses only the Python
standard library (tested with Python 3.13). Run these commands from the repository root:

```powershell
python -m model                                     # validate the dataset
python -m unittest discover -s model/tests -t .     # run the tests
```

See [`model/README.md`](model/README.md) for the dataset location, the landmark order and the
provenance and license status of the dataset.
