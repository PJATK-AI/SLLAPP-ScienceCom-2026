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
