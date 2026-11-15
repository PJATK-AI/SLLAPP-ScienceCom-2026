package pl.pjatk.ai.sllapp.ranking;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ResultRepository {

    private static final DateTimeFormatter STORAGE_TIMESTAMP = new DateTimeFormatterBuilder()
            .appendInstant(9)
            .toFormatter();

    private final JdbcTemplate jdbc;

    public ResultRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean save(GameResult result) {
        return jdbc.update("""
                INSERT INTO game_results (session_id, nickname, score, finished_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (session_id) DO NOTHING
                """, result.sessionId(), result.nickname(), result.score(),
                STORAGE_TIMESTAMP.format(result.finishedAt())) == 1;
    }

    public List<GameResult> findTopResults(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("Ranking limit must be positive");
        }

        return jdbc.query("""
                SELECT session_id, nickname, score, finished_at
                FROM game_results
                ORDER BY score DESC, finished_at ASC, session_id ASC
                LIMIT ?
                """, (row, rowNumber) -> new GameResult(
                row.getString("session_id"), row.getString("nickname"), row.getInt("score"),
                Instant.parse(row.getString("finished_at"))), limit);
    }
}
