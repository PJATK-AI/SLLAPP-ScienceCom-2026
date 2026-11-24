package pl.pjatk.ai.sllapp.web.websocket;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocketHandshakeException;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import pl.pjatk.ai.sllapp.SllappApplication;
import pl.pjatk.ai.sllapp.model.GameResult;
import pl.pjatk.ai.sllapp.repository.GameResultRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionWebSocketTest {
    private ConfigurableApplicationContext app;
    private HttpClient client;
    private int port;

    @BeforeAll
    void start(@TempDir Path directory) {
        app = new SpringApplicationBuilder(SllappApplication.class).run(
                "--server.port=0", "--sllapp.storage.directory=" + directory,
                "--sllapp.web.allowed-origins=http://localhost:5173");
        port = ((WebServerApplicationContext) app).getWebServer().getPort();
        client = HttpClient.newHttpClient();
    }

    @AfterAll
    void stop() {
        if (app != null) app.close();
        if (client != null) client.close();
    }

    @Test
    void rejectsInvalidMessagesWithoutClosingConnection() throws Exception {
        try (var socket = new WebSocketTestClient(client, port, "http://localhost:5173")) {
            socket.assertSilent();
            for (String input : new String[]{"{", "null", "[]", "{}", "{\"type\":1}", "{} {}"}) {
                socket.send(input);
                assertThat(socket.next().path("code").asString()).isEqualTo("INVALID_MESSAGE");
            }
            socket.binary();
            assertThat(socket.next().path("code").asString()).isEqualTo("INVALID_MESSAGE");
            socket.send("{\"type\":\"unknown\"}");
            assertThat(socket.next().path("code").asString()).isEqualTo("UNKNOWN_TYPE");
            assertThat(socket.closed).isNotDone();
        }
    }

    @Test
    void createsAndResumesSessionOnAnotherConnection() throws Exception {
        try (var first = new WebSocketTestClient(client, port, null);
             var second = new WebSocketTestClient(client, port, null)) {
            first.send("{\"type\":\"create_session\",\"nickname\":\" Ola \",\"selectedHand\":\"RIGHT\"}");
            var state = first.next();
            assertThat(state.path("type").asString()).isEqualTo("game_state");
            assertThat(state.path("status").asString()).isEqualTo("CREATED");
            assertThat(state.path("nickname").asString()).isEqualTo("Ola");
            assertThat(state.path("selectedHand").asString()).isEqualTo("RIGHT");
            assertThat(state.has("positionId")).isTrue();
            assertThat(state.path("positionId").isNull()).isTrue();
            java.util.UUID.fromString(state.path("sessionId").asString());
            first.send("{\"type\":\"create_session\",\"nickname\":\"Other\",\"selectedHand\":\"LEFT\"}");
            assertThat(first.next().path("code").asString()).isEqualTo("SESSION_ALREADY_BOUND");
            second.send("{\"type\":\"resume_session\",\"sessionId\":\"" + state.path("sessionId").asString() + "\"}");
            assertThat(second.next()).isEqualTo(state);
            assertThat(first.closed.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(1000);
            second.send("{\"type\":\"unknown\"}");
            assertThat(second.next().path("code").asString()).isEqualTo("UNKNOWN_TYPE");
        }
    }

    @Test
    void validatesCreationAndClosesForUnknownSession() throws Exception {
        try (var socket = new WebSocketTestClient(client, port, null)) {
            for (String fields : new String[]{"", ",\"nickname\":null", ",\"nickname\":\" \"",
                    ",\"nickname\":\"FUCK\"", ",\"nickname\":\"" + "a".repeat(21) + "\""}) {
                socket.send("{\"type\":\"create_session\",\"selectedHand\":\"LEFT\"" + fields + "}");
                assertThat(socket.next().path("code").asString()).isEqualTo("INVALID_SESSION");
            }
            socket.send("{\"type\":\"create_session\",\"nickname\":\"Ola\",\"selectedHand\":\"Left\"}");
            assertThat(socket.next().path("code").asString()).isEqualTo("INVALID_SESSION");
            socket.send("{\"type\":\"resume_session\",\"sessionId\":\"invalid\"}");
            assertThat(socket.next().path("code").asString()).isEqualTo("INVALID_SESSION");
            socket.send("{\"type\":\"resume_session\",\"sessionId\":\"" + java.util.UUID.randomUUID() + "\"}");
            assertThat(socket.closed.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(1008);
        }
    }

    @Test
    void requiresConfirmationEvenWhenTheNicknameIsOutsideTheDefaultRanking() throws Exception {
        var results = app.getBean(GameResultRepository.class);
        var time = Instant.parse("2026-11-15T12:00:00Z");
        for (int i = 0; i < 10; i++) {
            results.save(new GameResult("leader-" + i, "Leader " + i, 100, time));
        }
        var existing = new GameResult("existing", "Łucja", 0, time);
        results.save(existing);
        assertThat(results.findTopResults(10)).doesNotContain(existing);

        try (var socket = new WebSocketTestClient(client, port, null)) {
            String create = """
                    {"type":"create_session","nickname":" łUCJA ","selectedHand":"LEFT"%s}
                    """;
            socket.send(create.formatted(""));
            assertThat(socket.next().path("code").asString()).isEqualTo("NICKNAME_EXISTS");
            socket.send(create.formatted(",\"confirmExistingNickname\":false"));
            assertThat(socket.next().path("code").asString()).isEqualTo("NICKNAME_EXISTS");
            for (String invalid : new String[]{"null", "\"true\""}) {
                socket.send(create.formatted(",\"confirmExistingNickname\":" + invalid));
                assertThat(socket.next().path("code").asString()).isEqualTo("INVALID_SESSION");
            }
            socket.send("{\"type\":\"start_game\"}");
            assertThat(socket.next().path("code").asString()).isEqualTo("SESSION_REQUIRED");

            socket.send(create.formatted(",\"confirmExistingNickname\":true"));
            var state = socket.next();
            assertThat(state.path("status").asString()).isEqualTo("CREATED");
            assertThat(state.path("nickname").asString()).isEqualTo("Łucja");
            assertThat(state.path("selectedHand").asString()).isEqualTo("LEFT");
        }
    }

    @Test
    void disablesSampleAcknowledgementsByDefault() throws Exception {
        try (var socket = new WebSocketTestClient(client, port, null)) {
            socket.send("{\"type\":\"create_session\",\"nickname\":\"Ola\",\"selectedHand\":\"LEFT\"}");
            socket.next();
            socket.send(SampleFixtures.hand(0));
            socket.send(SampleFixtures.noHand(1));
            socket.assertSilent();
            socket.send("{\"type\":\"unknown\"}");
            assertThat(socket.next().path("code").asString()).isEqualTo("UNKNOWN_TYPE");
        }
    }

    @Test
    void appliesTheOriginListToRestPreflightRequests() throws Exception {
        for (String origin : new String[]{"http://localhost:5173", "http://127.0.0.1:5173", "http://untrusted.example"}) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ranking"))
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                    .header("Origin", origin).header("Access-Control-Request-Method", "GET").build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(origin.equals("http://localhost:5173") ? 200 : 403);
        }
        assertThatThrownBy(() -> new WebSocketTestClient(client, port, "http://127.0.0.1:5173"))
                .hasCauseInstanceOf(WebSocketHandshakeException.class);
    }

    @Test
    void checksOriginsForWebSocketAndRest() throws Exception {
        assertThatThrownBy(() -> new WebSocketTestClient(client, port, "http://untrusted.example"))
                .hasCauseInstanceOf(WebSocketHandshakeException.class);
        try (var socket = new WebSocketTestClient(client, port, null)) {
            socket.send("{\"type\":\"unknown\"}");
            assertThat(socket.next().path("type").asString()).isEqualTo("error");
        }
        for (String origin : new String[]{"http://localhost:5173", "http://untrusted.example"}) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ranking"))
                    .header("Origin", origin).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(origin.contains("localhost") ? 200 : 403);
            assertThat(response.headers().firstValue("access-control-allow-origin").orElse(""))
                    .isEqualTo(origin.contains("localhost") ? origin : "");
        }
    }
}
