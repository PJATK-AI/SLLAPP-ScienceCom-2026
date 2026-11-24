package pl.pjatk.ai.sllapp.repository;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pl.pjatk.ai.sllapp.model.GameResult;
import pl.pjatk.ai.sllapp.moderation.NicknamePolicy;

@Repository
public class GameResultRepository {

    private static final DateTimeFormatter STORAGE_TIMESTAMP = new DateTimeFormatterBuilder()
            .appendInstant(9)
            .toFormatter();

    private final JdbcTemplate jdbc;

    public GameResultRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean save(GameResult result) {
        return jdbc.update("""
                INSERT INTO game_results (session_id, nickname, nickname_key, score, finished_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (session_id) DO NOTHING
                ON CONFLICT (nickname_key) DO UPDATE SET
                    session_id = excluded.session_id,
                    score = excluded.score,
                    finished_at = excluded.finished_at
                WHERE excluded.score > game_results.score
                """, result.sessionId(), result.nickname(), NicknamePolicy.rankingKey(result.nickname()), result.score(),
                STORAGE_TIMESTAMP.format(result.finishedAt())) == 1;
    }

    public Optional<String> findNickname(String nickname) {
        return jdbc.query("SELECT nickname FROM game_results WHERE nickname_key = ?",
                rows -> rows.next() ? Optional.of(rows.getString("nickname")) : Optional.empty(),
                NicknamePolicy.rankingKey(nickname));
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
