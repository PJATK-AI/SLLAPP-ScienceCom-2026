package pl.pjatk.ai.sllapp.config.storage;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import pl.pjatk.ai.sllapp.moderation.NicknamePolicy;

final class SqliteSchema {
    private static final int SCHEMA_VERSION = 1;

    private SqliteSchema() {
    }

    static void initialize(DataSource dataSource) throws SQLException {
        try (var connection = dataSource.getConnection(); var transaction = connection.createStatement()) {
            transaction.execute("BEGIN IMMEDIATE");
            try {
                int version = version(connection);
                if (version == 0) {
                    initializeFromVersionZero(connection);
                } else if (version != SCHEMA_VERSION) {
                    throw new SQLException("Unsupported SQLite schema version: " + version);
                }
                transaction.execute("COMMIT");
            } catch (SQLException | RuntimeException exception) {
                try {
                    transaction.execute("ROLLBACK");
                } catch (SQLException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                throw exception;
            }
        }
    }

    private static int version(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("PRAGMA user_version")) {
            if (!rows.next()) throw new SQLException("SQLite did not return a schema version.");
            return rows.getInt(1);
        }
    }

    private static void initializeFromVersionZero(Connection connection) throws SQLException {
        boolean migrate;
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("""
                     SELECT 1 FROM sqlite_schema WHERE type = 'table' AND name = 'game_results'
                     """)) {
            migrate = rows.next();
        }

        try (var statement = connection.createStatement()) {
            if (migrate) {
                statement.execute("ALTER TABLE game_results RENAME TO game_results_v0");
                statement.execute("DROP INDEX IF EXISTS game_results_ranking_idx");
            }
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
            if (migrate) {
                copyBestResults(connection);
                statement.execute("DROP TABLE game_results_v0");
            }
            statement.execute("PRAGMA user_version = " + SCHEMA_VERSION);
        }
    }

    private static void copyBestResults(Connection connection) throws SQLException {
        try (var select = connection.createStatement();
             var rows = select.executeQuery("""
                     SELECT session_id, nickname, score, finished_at
                     FROM game_results_v0
                     ORDER BY score DESC, finished_at ASC, session_id ASC
                     """);
             var insert = connection.prepareStatement("""
                     INSERT INTO game_results (session_id, nickname, nickname_key, score, finished_at)
                     VALUES (?, ?, ?, ?, ?)
                     ON CONFLICT (nickname_key) DO NOTHING
                     """)) {
            while (rows.next()) {
                String nickname = rows.getString("nickname");
                insert.setString(1, rows.getString("session_id"));
                insert.setString(2, nickname);
                insert.setString(3, NicknamePolicy.rankingKey(nickname));
                insert.setInt(4, rows.getInt("score"));
                insert.setString(5, rows.getString("finished_at"));
                insert.executeUpdate();
            }
        }
    }
}
