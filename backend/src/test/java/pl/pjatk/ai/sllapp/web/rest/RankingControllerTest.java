package pl.pjatk.ai.sllapp.web.rest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import pl.pjatk.ai.sllapp.SllappApplication;
import pl.pjatk.ai.sllapp.model.GameResult;
import pl.pjatk.ai.sllapp.repository.GameResultRepository;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

class RankingControllerTest {
    @TempDir
    Path directory;

    @Test
    void servesRankingAndValidatesLimitsOverHttp() throws Exception {
        try (var app = new SpringApplicationBuilder(SllappApplication.class)
                .run("--server.port=0", "--sllapp.storage.directory=" + directory);
             var client = HttpClient.newHttpClient()) {
            int port = ((WebServerApplicationContext) app).getWebServer().getPort();
            var mapper = new ObjectMapper();
            var empty = get(client, port, "");
            assertThat(empty.statusCode()).isEqualTo(200);
            assertThat(empty.body()).isEqualTo("[]");
            assertThat(empty.headers().firstValue("content-type").orElseThrow()).startsWith("application/json");
            var repository = app.getBean(GameResultRepository.class);
            var time = Instant.parse("2026-11-16T12:00:00Z");
            repository.save(new GameResult("b", "Later id", 1000, time));
            repository.save(new GameResult("late", "Later time", 1000, time.plusNanos(1)));
            repository.save(new GameResult("low", "Earlier low score", 999, time.minusSeconds(1)));
            repository.save(new GameResult("a", "Łucja", 1000, time));
            for (int i = 0; i < 110; i++) {
                repository.save(new GameResult("extra-" + i, "Player " + i, i, time));
            }
            repository.save(new GameResult("repeat", " player 3 ", 500, time.plusSeconds(1)));
            var defaultResponse = get(client, port, "");
            assertThat(defaultResponse.statusCode()).isEqualTo(200);
            var rows = mapper.readTree(defaultResponse.body());
            assertThat(rows.size()).isEqualTo(10);
            assertThat(rows.get(0).size()).isEqualTo(3);
            assertThat(rows.get(0).get("nickname").asString()).isEqualTo("Łucja");
            assertThat(rows.get(1).get("nickname").asString()).isEqualTo("Later id");
            assertThat(rows.get(2).get("nickname").asString()).isEqualTo("Later time");
            assertThat(rows.get(3).get("score").asInt()).isEqualTo(999);
            assertThat(rows.get(4).get("nickname").asString()).isEqualTo("Player 3");
            assertThat(rows.get(4).get("score").asInt()).isEqualTo(500);
            for (int limit : new int[]{1, 2, 100}) {
                var response = get(client, port, "?limit=" + limit);
                assertThat(response.statusCode()).isEqualTo(200);
                var limited = mapper.readTree(response.body());
                assertThat(limited.size()).isEqualTo(limit);
                for (int i = 0; i < limit; i++) {
                    assertThat(limited.get(i).get("rank").asInt()).isEqualTo(i + 1);
                }
            }
            for (String invalid : new String[]{"", "0", "-1", "101", "abc", "1.5", "2147483648"}) {
                var response = get(client, port, "?limit=" + invalid);
                assertThat(response.statusCode()).as("limit=%s", invalid).isEqualTo(400);
                assertThat(response.headers().firstValue("content-type").orElseThrow())
                        .startsWith("application/problem+json");
                var problem = mapper.readTree(response.body());
                assertThat(problem.get("status").asInt()).isEqualTo(400);
                assertThat(problem.get("title").asString()).isEqualTo("Bad Request");
                assertThat(problem.get("detail").asString()).contains("1 and 100");
                assertThat(problem.get("instance").asString()).isEqualTo("/api/ranking");
            }
        }
    }

    private HttpResponse<String> get(HttpClient client, int port, String query) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ranking" + query))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
