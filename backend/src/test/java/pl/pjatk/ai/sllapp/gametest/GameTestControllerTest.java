package pl.pjatk.ai.sllapp.gametest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import pl.pjatk.ai.sllapp.SllappApplication;
import pl.pjatk.ai.sllapp.game.WordCatalog;
import pl.pjatk.ai.sllapp.support.MutableClock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

class GameTestControllerTest {
    @TempDir
    Path directory;

    private final ObjectMapper mapper = new ObjectMapper();
    private final MutableClock clock = new MutableClock();

    @Test
    void servesThePanelAndExercisesTheRealEngineWithTheProfileEnabled() throws Exception {
        try (var app = start("game-test"); var client = HttpClient.newHttpClient()) {
            String panel = panelUrl(app);

            assertThat(get(client, panel).body()).contains("Game preview", "Start game", "Complete letter");
            assertThat(get(client, panel + "/app.js").headers().firstValue("Content-Type"))
                    .hasValueSatisfying(value -> assertThat(value).startsWith("text/javascript"));
            assertThat(get(client, panel + "/styles.css").statusCode()).isEqualTo(200);
            assertThat(get(client, panel + "/api/defaults").statusCode()).isEqualTo(404);
            assertThat(get(client, panel + "/api/checks").statusCode()).isEqualTo(404);

            var initial = json(post(client, panel + "/api/game", null));
            String game = panel + "/api/game/" + initial.path("gameId").asString();
            var catalog = WordCatalog.loadBundled();
            String firstWord = initial.path("state").path("word").asString();
            assertThat(catalog.wordsOfLength(3)).contains(firstWord);
            assertThat(initial.path("expectedLetter").asString()).isEqualTo(firstWord.substring(0, 1));
            assertThat(initial.path("state").path("remainingMs").asLong()).isEqualTo(60_000);
            assertThat(post(client, game + "/clock", null).statusCode()).isEqualTo(404);

            clock.advance(Duration.ofSeconds(1));
            assertThat(json(get(client, game)).path("state").path("remainingMs").asLong()).isEqualTo(59_000);
            assertThat(complete(client, game, 1).path("scoreChange").asInt()).isEqualTo(10);
            assertThat(complete(client, game, 2).path("scoreChange").asInt()).isEqualTo(10);
            var lastLetter = complete(client, game, 3);
            assertThat(lastLetter.path("scoreChange").asInt()).isEqualTo(30);
            var current = lastLetter.path("game").path("state");
            assertThat(catalog.wordsOfLength(4)).contains(current.path("word").asString());
            assertThat(current.path("score").asInt()).isEqualTo(50);

            var retry = complete(client, game, 3);
            assertThat(retry.path("accepted").asBoolean()).isFalse();
            assertThat(retry.path("game").path("state")).isEqualTo(current);

            clock.advance(Duration.ofSeconds(58));
            var beforeDeadline = json(get(client, game)).path("state");
            assertThat(beforeDeadline.path("remainingMs").asLong()).isEqualTo(1_000);
            assertThat(beforeDeadline.path("finished").asBoolean()).isFalse();
            clock.advance(Duration.ofSeconds(1));
            var expiredAttempt = complete(client, game, current.path("positionId").asLong());
            assertThat(expiredAttempt.path("accepted").asBoolean()).isFalse();
            var expired = expiredAttempt.path("game").path("state");
            assertThat(expired.path("finished").asBoolean()).isTrue();
            assertThat(expired.path("remainingMs").asLong()).isZero();
            assertThat(expired.path("score").asInt()).isEqualTo(50);

            var restarted = json(post(client, panel + "/api/game", """
                    {"letterPoints":500,"wordBonus":500,"multiplierStep":500}
                    """));
            String restartedGame = panel + "/api/game/" + restarted.path("gameId").asString();
            assertThat(restarted.path("state").path("score").asInt()).isZero();
            assertThat(complete(client, restartedGame, 1).path("scoreChange").asInt()).isEqualTo(10);
            assertThat(get(client, game).statusCode()).isEqualTo(404);
        }
    }

    @Test
    void hidesThePageAssetsAndApiWhenTheProfileIsInactive() throws Exception {
        try (var app = start(); var client = HttpClient.newHttpClient()) {
            for (String path : new String[]{"", "/app.js", "/styles.css"}) {
                assertThat(get(client, panelUrl(app) + path).statusCode()).as(path).isEqualTo(404);
            }
            assertThat(post(client, panelUrl(app) + "/api/game", null).statusCode()).isEqualTo(404);
        }
    }

    private ConfigurableApplicationContext start(String... profiles) {
        return new SpringApplicationBuilder(SllappApplication.class)
                .profiles(profiles)
                .initializers(context -> context.getBeanFactory().registerSingleton("testClock", clock))
                .run("--server.port=0", "--sllapp.storage.directory=" + directory);
    }

    private static String panelUrl(ConfigurableApplicationContext app) {
        int port = ((WebServerApplicationContext) app).getWebServer().getPort();
        return "http://127.0.0.1:" + port + "/game-test";
    }

    private JsonNode complete(HttpClient client, String game, long positionId) throws IOException, InterruptedException {
        return json(post(client, game + "/complete", """
                {"positionId":%d}
                """.formatted(positionId)));
    }

    private JsonNode json(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return mapper.readTree(response.body());
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(HttpClient client, String url, String body)
            throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(url));
        if (body == null) {
            request.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
