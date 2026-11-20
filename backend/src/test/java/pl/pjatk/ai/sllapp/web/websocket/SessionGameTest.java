package pl.pjatk.ai.sllapp.web.websocket;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import pl.pjatk.ai.sllapp.SllappApplication;
import pl.pjatk.ai.sllapp.game.WordPicker;
import pl.pjatk.ai.sllapp.model.GameResult;
import pl.pjatk.ai.sllapp.repository.GameResultRepository;
import pl.pjatk.ai.sllapp.service.SessionService;
import pl.pjatk.ai.sllapp.support.MutableClock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.assertThat;

class SessionGameTest {
    private static final String START = "{\"type\":\"start_game\"}";
    private static final Duration GAME_DURATION = Duration.ofSeconds(20);
    private final MutableClock clock = new MutableClock();
    private ConfigurableApplicationContext app;
    private HttpClient client;
    private int port;

    @BeforeEach
    void start(@TempDir Path directory) {
        Supplier<WordPicker> pickers = () -> length -> length == 3 ? "AAB" : "B".repeat(length);
        app = new SpringApplicationBuilder(SllappApplication.class).initializers(context -> {
            context.getBeanFactory().registerSingleton("testClock", clock);
            context.getBeanFactory().registerSingleton("sessionWordPickerFactory", pickers);
        }).run("--server.port=0", "--sllapp.storage.directory=" + directory,
                "--sllapp.session.creation-ttl=10s", "--sllapp.session.game-duration=20s",
                "--sllapp.session.finished-ttl=5s", "--sllapp.websocket.diagnostic-mode=true");
        port = ((WebServerApplicationContext) app).getWebServer().getPort();
        client = HttpClient.newHttpClient();
    }

    @Test
    void scheduledSweepFinishesIdleGameAndPublishesTheSameResultOnResumeAndRanking() throws Exception {
        try (var first = socket(); var replacement = socket(); var tooLate = socket()) {
            var id = startGame(first);
            var game = app.getBean(SessionService.class).find(id).orElseThrow();
            var connections = app.getBean(SessionConnectionRegistry.class);
            assertThat(connections.completePosition(game, 1)).isTrue();
            assertThat(first.next().path("score").intValue()).isEqualTo(10);

            clock.advance(GAME_DURATION);
            var finished = first.next();
            assertFinished(finished, id, 10);
            assertThat(connections.completePosition(game, 2)).isFalse();
            assertThat(results().findTopResults(10)).containsExactly(new GameResult(id.toString(), "Ola", 10,
                    clock.instant()));
            var ranking = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ranking"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(ranking.statusCode()).isEqualTo(200);
            assertThat(new ObjectMapper().readTree(ranking.body())).isEqualTo(new ObjectMapper().readTree(
                    "[{\"rank\":1,\"nickname\":\"Ola\",\"score\":10}]"));

            resume(replacement, id.toString());
            assertThat(replacement.next()).isEqualTo(finished);
            assertThat(first.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1000);
            replacement.send(SampleFixtures.hand(0));
            assertError(replacement, "GAME_FINISHED");
            replacement.send(SampleFixtures.noHand(0));
            assertError(replacement, "GAME_FINISHED");
            replacement.send(START);
            assertError(replacement, "GAME_ALREADY_STARTED");
            assertThat(replacement.closed).isNotDone();

            clock.advance(Duration.ofMillis(4_999));
            connections.expireSessions();
            assertThat(replacement.closed).isNotDone();
            clock.advance(Duration.ofMillis(1));
            connections.expireSessions();
            assertThat(replacement.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
            resume(tooLate, id.toString());
            assertThat(tooLate.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
            assertThat(results().findTopResults(10)).hasSize(1);
        }
    }

    @Test
    void sweepFinishesADisconnectedGameUsingItsOriginalDeadline() throws Exception {
        UUID id;
        var deadline = clock.instant().plus(GAME_DURATION);
        try (var first = socket()) {
            id = startGame(first);
            first.socket.sendClose(1000, "done").get(5, TimeUnit.SECONDS);
            assertThat(first.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1000);
        }
        clock.advance(GAME_DURATION.plusSeconds(2));
        var connections = app.getBean(SessionConnectionRegistry.class);
        connections.expireSessions();
        connections.expireSessions();
        assertThat(results().findTopResults(10)).containsExactly(new GameResult(id.toString(), "Ola", 0, deadline));
        try (var replacement = socket()) {
            resume(replacement, id.toString());
            assertFinished(replacement.next(), id, 0);
        }
    }

    @Test
    void resumeAtTheDeadlinePublishesTheFinishToThePreviousOwnerOnlyOnce() throws Exception {
        try (var previous = socket(); var replacement = socket()) {
            var id = startGame(previous);
            clock.advance(GAME_DURATION);
            resume(replacement, id.toString());
            var finished = replacement.next();
            assertFinished(finished, id, 0);
            assertThat(previous.next()).isEqualTo(finished);
            assertThat(previous.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1000);
            previous.assertSilent();
            app.getBean(SessionConnectionRegistry.class).expireSessions();
            replacement.assertSilent();
            assertThat(results().findTopResults(10)).hasSize(1);
        }
    }

    @Test
    void completionAtTheDeadlineCannotScoreAndPersistenceRetriesAfterExpiry() throws Exception {
        var jdbc = app.getBean(JdbcTemplate.class);
        jdbc.execute("""
                CREATE TRIGGER reject_results BEFORE INSERT ON game_results
                BEGIN SELECT RAISE(ABORT, 'simulated write failure'); END
                """);
        try (var first = socket(); var expired = socket()) {
            var id = startGame(first);
            var sessions = app.getBean(SessionService.class);
            var game = sessions.find(id).orElseThrow();
            var connections = app.getBean(SessionConnectionRegistry.class);
            assertThat(connections.completePosition(game, 1)).isTrue();
            first.next();
            clock.advance(GAME_DURATION);
            var deadline = clock.instant();
            assertThat(connections.completePosition(game, 2)).isFalse();
            assertFinished(first.next(), id, 10);
            assertThat(results().findTopResults(10)).isEmpty();

            clock.advance(Duration.ofSeconds(5));
            connections.expireSessions();
            assertThat(first.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
            resume(expired, id.toString());
            assertThat(expired.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
            assertThat(sessions.find(id)).isEmpty();
            assertThat(sessions.all()).containsExactly(game);

            jdbc.execute("DROP TRIGGER reject_results");
            connections.expireSessions();
            connections.expireSessions();
            assertThat(sessions.all()).isEmpty();
            assertThat(results().findTopResults(10)).containsExactly(new GameResult(id.toString(), "Ola", 10, deadline));
        }
    }

    private GameResultRepository results() {
        return app.getBean(GameResultRepository.class);
    }

    private static UUID startGame(WebSocketTestClient socket) throws Exception {
        var id = UUID.fromString(create(socket).path("sessionId").asString());
        socket.send(START);
        assertThat(socket.next().path("status").asString()).isEqualTo("RUNNING");
        return id;
    }

    private static void assertFinished(JsonNode result, UUID id, int score) {
        assertThat(result.path("type").asString()).isEqualTo("game_state");
        assertThat(result.path("status").asString()).isEqualTo("FINISHED");
        assertThat(result.path("sessionId").asString()).isEqualTo(id.toString());
        assertThat(result.path("nickname").asString()).isEqualTo("Ola");
        assertThat(result.path("selectedHand").asString()).isEqualTo("RIGHT");
        assertThat(result.path("positionId").isNull()).isTrue();
        assertThat(result.path("remainingMs").longValue()).isZero();
        assertThat(result.path("score").intValue()).isEqualTo(score);
        assertThat(result.size()).isEqualTo(8);
    }

    @AfterEach
    void stop() {
        if (app != null) app.close();
        if (client != null) client.close();
    }

    @Test
    void startsOnceAndResumesAfterTheOriginalCreationDeadline() throws Exception {
        try (var first = socket(); var replacement = socket()) {
            first.send(START);
            assertError(first, "SESSION_REQUIRED");
            var created = create(first);
            assertThat(created.path("status").asString()).isEqualTo("CREATED");
            assertThat(created.has("positionId")).isTrue();
            assertThat(created.path("positionId").isNull()).isTrue();
            assertThat(created.size()).isEqualTo(6);

            clock.advance(Duration.ofMillis(9_999));
            first.send(START);
            var initial = first.next();
            assertThat(initial.path("type").asString()).isEqualTo("game_state");
            assertThat(initial.path("status").asString()).isEqualTo("RUNNING");
            assertThat(initial.path("sessionId")).isEqualTo(created.path("sessionId"));
            assertThat(initial.path("nickname").asString()).isEqualTo("Ola");
            assertThat(initial.path("selectedHand").asString()).isEqualTo("RIGHT");
            assertThat(initial.path("positionId").asLong()).isEqualTo(1);
            assertThat(initial.path("word").asString()).isEqualTo("AAB");
            assertThat(initial.path("letterIndex").intValue()).isZero();
            assertThat(initial.path("expectedLetter").asString()).isEqualTo("A");
            assertThat(initial.path("score").intValue()).isZero();
            assertThat(initial.path("requiresRelease").booleanValue()).isFalse();
            assertThat(initial.path("remainingMs").longValue()).isEqualTo(20_000);

            clock.advance(Duration.ofMillis(1_234));
            first.send(START);
            assertError(first, "GAME_ALREADY_STARTED");
            resume(replacement, created.path("sessionId").asString());
            var expected = (ObjectNode) initial.deepCopy();
            expected.put("remainingMs", 18_766);
            assertThat(replacement.next()).isEqualTo(expected);
            assertThat(first.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1000);
        }
    }

    @Test
    void validatesCurrentPositionBeforeAdvancingSequenceAndPublishesOnlyCompletions() throws Exception {
        try (var socket = socket()) {
            var id = UUID.fromString(create(socket).path("sessionId").asString());
            assertAck(socket, SampleFixtures.hand(0), 0);
            assertAck(socket, SampleFixtures.noHand(1), 1);
            socket.send(START);
            long position = socket.next().path("positionId").asLong();

            for (String invalid : new String[]{"null", "0", "1.0", "\"1\"", "9007199254740992", "2"}) {
                for (String sample : new String[]{SampleFixtures.hand(2), SampleFixtures.noHand(2)}) {
                    socket.send(sample.replace("\"positionId\":null", "\"positionId\":" + invalid));
                    assertError(socket, "INVALID_SAMPLE");
                }
            }
            socket.send(SampleFixtures.hand(2).replace("\"positionId\":null,", ""));
            assertError(socket, "INVALID_SAMPLE");
            socket.send(SampleFixtures.noHand(2).replace(",\"positionId\":null", ""));
            assertError(socket, "INVALID_SAMPLE");
            assertAck(socket, withPosition(SampleFixtures.hand(2), position), 2);
            assertAck(socket, withPosition(SampleFixtures.noHand(3), position), 3);
            socket.assertSilent();

            var game = app.getBean(SessionService.class).find(id).orElseThrow();
            var connections = app.getBean(SessionConnectionRegistry.class);
            assertThat(connections.completePosition(game, position)).isTrue();
            var next = socket.next();
            long nextPosition = next.path("positionId").asLong();
            assertThat(next.path("status").asString()).isEqualTo("RUNNING");
            assertThat(nextPosition).isEqualTo(position + 1);
            assertThat(next.path("score").intValue()).isEqualTo(10);
            assertThat(next.path("letterIndex").intValue()).isEqualTo(1);
            assertThat(next.path("expectedLetter").asString()).isEqualTo("A");
            assertThat(next.path("requiresRelease").booleanValue()).isTrue();

            assertThat(connections.completePosition(game, position)).isFalse();
            socket.send(withPosition(SampleFixtures.hand(4), position));
            assertError(socket, "INVALID_SAMPLE");
            socket.send(withPosition(SampleFixtures.noHand(4), position));
            assertError(socket, "INVALID_SAMPLE");
            assertAck(socket, withPosition(SampleFixtures.noHand(4), nextPosition), 4);
            socket.assertSilent();
        }
    }

    private static String withPosition(String sample, long position) {
        return sample.replace("\"positionId\":null", "\"positionId\":" + position);
    }

    private static void assertAck(WebSocketTestClient socket, String sample, long seq) throws Exception {
        socket.send(sample);
        var ack = socket.next();
        assertThat(ack.path("type").asString()).isEqualTo("sample_ack");
        assertThat(ack.path("seq").asLong()).isEqualTo(seq);
    }

    private WebSocketTestClient socket() throws Exception {
        return new WebSocketTestClient(client, port, null);
    }

    private static JsonNode create(WebSocketTestClient socket) throws Exception {
        socket.send("{\"type\":\"create_session\",\"nickname\":\"Ola\",\"selectedHand\":\"RIGHT\"}");
        return socket.next();
    }

    private static void resume(WebSocketTestClient socket, String id) throws Exception {
        socket.send("{\"type\":\"resume_session\",\"sessionId\":\"" + id + "\"}");
    }

    private static void assertError(WebSocketTestClient socket, String code) throws Exception {
        var message = socket.next();
        assertThat(message.path("type").asString()).isEqualTo("error");
        assertThat(message.path("code").asString()).isEqualTo(code);
    }
}
