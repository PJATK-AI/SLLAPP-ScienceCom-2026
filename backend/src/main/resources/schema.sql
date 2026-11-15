CREATE TABLE IF NOT EXISTS game_results (
    session_id TEXT NOT NULL PRIMARY KEY,
    nickname TEXT NOT NULL,
    score INTEGER NOT NULL CHECK (score >= 0),
    finished_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS game_results_ranking_idx
    ON game_results (score DESC, finished_at ASC, session_id ASC);
