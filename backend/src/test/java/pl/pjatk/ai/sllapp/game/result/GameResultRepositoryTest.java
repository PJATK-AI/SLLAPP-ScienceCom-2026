package pl.pjatk.ai.sllapp.game.result;

import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import pl.pjatk.ai.sllapp.SllappApplication;

import static org.assertj.core.api.Assertions.assertThat;

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

    private ConfigurableApplicationContext start(Path dataDirectory) {
        return new SpringApplicationBuilder(SllappApplication.class)
                .web(WebApplicationType.NONE)
                .run("--sllapp.storage.directory=" + dataDirectory);
    }
}
