package pl.pjatk.ai.sllapp.repository;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import pl.pjatk.ai.sllapp.SllappApplication;
import pl.pjatk.ai.sllapp.model.GameResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GameResultRepositoryTest {

    @TempDir
    Path tempDirectory;

    @Test
    void retainsRankingAfterRestartAndIgnoresDuplicateSession() {
        Path dataDirectory = tempDirectory.resolve("ranking data");
        Instant finishedAt = Instant.parse("2026-11-15T12:00:00Z");
        GameResult first = new GameResult("first", "Łucja", 100, finishedAt);
        GameResult second = new GameResult("second", "Ola", 100, finishedAt.plusNanos(1));
        GameResult third = new GameResult("third", "Jan", 50, finishedAt.minusSeconds(1));

        try (var app = start(dataDirectory)) {
            var results = app.getBean(GameResultRepository.class);
            assertThat(results.save(second)).isTrue();
            assertThat(results.save(third)).isTrue();
            assertThat(results.save(first)).isTrue();
        }

        assertThat(dataDirectory.resolve("sllapp.db")).isRegularFile();

        try (var app = start(dataDirectory)) {
            var results = app.getBean(GameResultRepository.class);
            assertThat(results.save(new GameResult("first", "Other", 200, finishedAt))).isFalse();
            assertThat(results.findTopResults(10)).containsExactly(first, second, third);
            assertThat(results.findTopResults(2)).containsExactly(first, second);
        }
    }

    @Test
    void updatesOnlyForHigherScoresAndKeepsTheStoredNickname() {
        Instant time = Instant.parse("2026-11-15T12:00:00Z");
        var first = new GameResult("first", "Óla", 100, time);
        var zero = new GameResult("zero", "Jan", 0, time);
        try (var app = start(tempDirectory)) {
            var results = app.getBean(GameResultRepository.class);
            assertThat(results.save(first)).isTrue();
            assertThat(results.save(zero)).isTrue();
            assertThat(results.save(new GameResult("lower", " óla ", 70, time.plusSeconds(1)))).isFalse();
            assertThat(results.save(new GameResult("equal", "O\u0301LA", 100, time.plusSeconds(2)))).isFalse();
            assertThat(results.findTopResults(10)).containsExactly(first, zero);
            assertThat(results.findNickname(" o\u0301LA ")).contains("Óla");

            var higherTime = time.plusSeconds(3);
            assertThat(results.save(new GameResult("higher", "óla", 130, higherTime))).isTrue();
            assertThat(results.save(first)).isFalse();
            assertThat(results.findTopResults(10)).containsExactly(
                    new GameResult("higher", "Óla", 130, higherTime), zero);
        }
    }

    @Test
    void migratesLegacyResultsByScoreFinishTimeAndSessionId() throws Exception {
        String url = createLegacyDatabase();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO game_results VALUES
                    ('lower', ' Ola ', 70, '2026-11-15T11:59:59.000000000Z'),
                    ('late', 'ola', 100, '2026-11-15T12:00:00.000000001Z'),
                    ('b', 'OLA', 100, '2026-11-15T12:00:00.000000000Z'),
                    ('a', 'Ola', 100, '2026-11-15T12:00:00.000000000Z'),
                    ('other', 'Łucja', 40, '2026-11-15T12:00:00.000000000Z')
                    """);
        }
        var time = Instant.parse("2026-11-15T12:00:00Z");
        for (int start = 0; start < 2; start++) {
            try (var app = start(tempDirectory)) {
                var results = app.getBean(GameResultRepository.class);
                assertThat(results.findTopResults(10)).containsExactly(
                        new GameResult("a", "Ola", 100, time), new GameResult("other", "Łucja", 40, time));
                assertThat(results.findNickname("ola")).contains("Ola");
            }
        }
    }

    @Test
    void rollsBackSchemaAndResultsIfMigrationFails() throws Exception {
        String url = createLegacyDatabase();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("PRAGMA ignore_check_constraints = ON");
            statement.executeUpdate("""
                    INSERT INTO game_results VALUES ('invalid', 'Ola', -1, '2026-11-15T12:00:00.000000000Z')
                    """);
        }
        assertThatThrownBy(() -> start(tempDirectory)).hasStackTraceContaining("SQLITE_CONSTRAINT_CHECK");
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("PRAGMA user_version")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isZero();
            }
            try (var rows = statement.executeQuery("SELECT score FROM game_results")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(-1);
            }
            try (var rows = statement.executeQuery("""
                    SELECT name FROM sqlite_schema
                    WHERE name IN ('game_results_v0', 'game_results_ranking_idx')
                    """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("game_results_ranking_idx");
                assertThat(rows.next()).isFalse();
            }
        }
    }

    @Test
    void keepsOnlyTheHighestOfConcurrentResults() throws Exception {
        var time = Instant.parse("2026-11-15T12:00:00Z");
        try (var app = start(tempDirectory); var executor = Executors.newFixedThreadPool(2)) {
            var results = app.getBean(GameResultRepository.class);
            var begin = new CountDownLatch(1);
            var lower = executor.submit(() -> {
                begin.await();
                return results.save(new GameResult("lower", "Ola", 100, time));
            });
            var higher = executor.submit(() -> {
                begin.await();
                return results.save(new GameResult("higher", "Ola", 130, time.plusNanos(1)));
            });
            begin.countDown();
            lower.get(5, TimeUnit.SECONDS);
            assertThat(higher.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(results.findTopResults(10)).containsExactly(
                    new GameResult("higher", "Ola", 130, time.plusNanos(1)));
        }
    }

    private String createLegacyDatabase() throws Exception {
        String url = "jdbc:sqlite:" + tempDirectory.resolve("sllapp.db").toUri();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE game_results (
                        session_id TEXT NOT NULL PRIMARY KEY,
                        nickname TEXT NOT NULL,
                        score INTEGER NOT NULL CHECK (score >= 0),
                        finished_at TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE INDEX game_results_ranking_idx ON game_results (score DESC, finished_at ASC, session_id ASC)
                    """);
        }
        return url;
    }

    private ConfigurableApplicationContext start(Path dataDirectory) {
        return new SpringApplicationBuilder(SllappApplication.class)
                .web(WebApplicationType.NONE)
                .run("--sllapp.storage.directory=" + dataDirectory);
    }
}
