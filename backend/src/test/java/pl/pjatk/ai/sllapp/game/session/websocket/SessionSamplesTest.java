package pl.pjatk.ai.sllapp.game.session.websocket;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import pl.pjatk.ai.sllapp.SllappApplication;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionSamplesTest {
    @TempDir
    Path directory;
    private ConfigurableApplicationContext app;
    private HttpClient client;
    private int port;

    @BeforeAll
    void start() {
        app = new SpringApplicationBuilder(SllappApplication.class).run(
                "--server.port=0", "--sllapp.storage.directory=" + directory,
                "--sllapp.websocket.diagnostic-mode=true");
        port = ((WebServerApplicationContext) app).getWebServer().getPort();
        client = HttpClient.newHttpClient();
    }

    @AfterAll
    void stop() {
        if (app != null) app.close();
        if (client != null) client.close();
    }

    @Test
    void validatesSamplesAndAcknowledgesOnlyFreshValidMessages() throws Exception {
        try (var socket = new WebSocketTestClient(client, port, null)) {
            socket.send(SampleFixtures.noHand(0));
            assertThat(socket.next().path("code").asString()).isEqualTo("SESSION_REQUIRED");
            socket.send("{\"type\":\"create_session\",\"nickname\":\"Ola\",\"selectedHand\":\"RIGHT\"}");
            assertThat(socket.next().path("type").asString()).isEqualTo("game_state");
            socket.send(SampleFixtures.hand(0));
            assertAck(socket, 0);
            socket.send(SampleFixtures.noHand(2));
            assertAck(socket, 2);
            socket.send(SampleFixtures.hand(2));
            socket.send(SampleFixtures.noHand(1));
            socket.assertSilent();
            socket.send(SampleFixtures.hand(3).replace("\"x\":0.5", "\"x\":null"));
            assertThat(socket.next().path("code").asString()).isEqualTo("INVALID_SAMPLE");
            socket.send(SampleFixtures.noHand(3).replace("null", "1"));
            assertThat(socket.next().path("code").asString()).isEqualTo("INVALID_SAMPLE");
            socket.send(SampleFixtures.noHand(3));
            assertAck(socket, 3);
        }
    }

    @Test
    void acceptsMaximumSafeSequenceAfterInvalidValues() throws Exception {
        try (var socket = new WebSocketTestClient(client, port, "http://127.0.0.1:5173")) {
            socket.send("{\"type\":\"create_session\",\"nickname\":\"Łucja\",\"selectedHand\":\"RIGHT\"}");
            socket.next();
            for (String seq : new String[]{"null", "\"1\"", "-1", "0.5", "9007199254740992"}) {
                socket.send("{\"type\":\"no_hand\",\"seq\":" + seq + ",\"positionId\":null}");
                assertThat(socket.next().path("code").asString()).isEqualTo("INVALID_SAMPLE");
            }
            socket.send(SampleFixtures.hand(0).replace("\"z\":-0.1", "\"z\":1e999"));
            assertThat(socket.next().path("code").asString()).isEqualTo("INVALID_SAMPLE");
            socket.send(SampleFixtures.noHand(9_007_199_254_740_991L));
            assertAck(socket, 9_007_199_254_740_991L);
            socket.send(SampleFixtures.hand(0));
            socket.assertSilent();
        }
    }

    @Test
    void restartsSequenceAfterReplacementAndAfterDisconnect() throws Exception {
        String id;
        try (var first = new WebSocketTestClient(client, port, null);
             var second = new WebSocketTestClient(client, port, null)) {
            first.send("{\"type\":\"create_session\",\"nickname\":\"Ola\",\"selectedHand\":\"LEFT\"}");
            id = first.next().path("sessionId").asString();
            first.send(SampleFixtures.noHand(100));
            assertAck(first, 100);
            second.send("{\"type\":\"resume_session\",\"sessionId\":\"" + id + "\"}");
            second.next();
            assertThat(first.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1000);
            second.send(SampleFixtures.noHand(0));
            assertAck(second, 0);
            second.socket.sendClose(1000, "done").get(5, TimeUnit.SECONDS);
            second.closed.get(5, TimeUnit.SECONDS);
        }
        try (var third = new WebSocketTestClient(client, port, null)) {
            third.send("{\"type\":\"resume_session\",\"sessionId\":\"" + id + "\"}");
            assertThat(third.next().path("type").asString()).isEqualTo("game_state");
            third.send(SampleFixtures.noHand(0));
            assertAck(third, 0);
        }
    }

    private void assertAck(WebSocketTestClient socket, long seq) throws Exception {
        var ack = socket.next();
        assertThat(ack.path("type").asString()).isEqualTo("sample_ack");
        assertThat(ack.path("seq").asLong()).isEqualTo(seq);
    }
}
