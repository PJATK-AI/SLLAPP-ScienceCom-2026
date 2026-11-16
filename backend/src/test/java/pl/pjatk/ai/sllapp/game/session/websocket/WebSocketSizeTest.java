package pl.pjatk.ai.sllapp.game.session.websocket;

import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
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
class WebSocketSizeTest {
    @TempDir
    Path directory;
    private ConfigurableApplicationContext app;
    private HttpClient client;
    private int port;

    @BeforeAll
    void start() {
        app = new SpringApplicationBuilder(SllappApplication.class).run(
                "--server.port=0", "--sllapp.storage.directory=" + directory,
                "--sllapp.websocket.max-message-bytes=512", "--sllapp.websocket.transport-max-message-bytes=2048",
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
    void enforcesApplicationLimitInUtf8BytesWithoutConsumingSequence() throws Exception {
        try (var socket = connect()) {
            socket.send(paddedSample(0, 512, "Ł"));
            assertThat(socket.next().path("type").asString()).isEqualTo("sample_ack");
            socket.send(paddedSample(1, 513, "Ł"));
            assertThat(socket.next().path("code").asString()).isEqualTo("MESSAGE_TOO_LARGE");
            socket.send(SampleFixtures.noHand(1));
            assertThat(socket.next().path("seq").asLong()).isEqualTo(1);
        }
    }

    @Test
    void validatesCompleteFragmentedMessages() throws Exception {
        try (var socket = connect()) {
            for (int size : new int[]{512, 513}) {
                String message = paddedSample(0, size, "Ł");
                int split = message.length() / 2;
                socket.socket.sendText(message.substring(0, split), false).get(5, TimeUnit.SECONDS);
                socket.assertSilent();
                socket.socket.sendText(message.substring(split), true).get(5, TimeUnit.SECONDS);
                var response = socket.next();
                if (size == 512) assertThat(response.path("type").asString()).isEqualTo("sample_ack");
                else assertThat(response.path("code").asString()).isEqualTo("MESSAGE_TOO_LARGE");
            }
            socket.send(SampleFixtures.noHand(1));
            assertThat(socket.next().path("seq").asLong()).isEqualTo(1);
        }
    }

    @Test
    void keepsConnectionAtHardLimitAndClosesOversizedFragmentsAndBinary() throws Exception {
        try (var socket = connect()) {
            socket.send(paddedSample(0, 2048, "Ł"));
            assertThat(socket.next().path("code").asString()).isEqualTo("MESSAGE_TOO_LARGE");
            String message = paddedSample(0, 2049, "a");
            socket.socket.sendText(message.substring(0, 1000), false).get(5, TimeUnit.SECONDS);
            socket.socket.sendText(message.substring(1000), true).get(5, TimeUnit.SECONDS);
            assertThat(socket.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1009);
        }
        try (var socket = connect()) {
            socket.socket.sendBinary(ByteBuffer.wrap(new byte[2049]), true).get(5, TimeUnit.SECONDS);
            assertThat(socket.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1009);
        }
    }

    @Test
    void closesAboveHardLimitForAsciiAndUnicode() throws Exception {
        for (String character : new String[]{"a", "Ł", "😀"}) {
            try (var socket = connect()) {
                socket.send(paddedSample(0, 2049, character));
                assertThat(socket.closed.get(5, TimeUnit.SECONDS)).as(character).isEqualTo(1009);
            }
        }
    }

    private WebSocketTestClient connect() throws Exception {
        var socket = new WebSocketTestClient(client, port, null);
        socket.send("{\"type\":\"create_session\",\"nickname\":\"Ola\",\"selectedHand\":\"LEFT\"}");
        socket.next();
        return socket;
    }

    private String paddedSample(long seq, int size, String character) {
        String prefix = "{\"type\":\"no_hand\",\"seq\":" + seq + ",\"positionId\":null,\"padding\":\"";
        int remaining = size - prefix.length() - 2;
        int bytes = character.getBytes(StandardCharsets.UTF_8).length;
        String message = prefix + character.repeat(remaining / bytes) + "a".repeat(remaining % bytes) + "\"}";
        assertThat(message.getBytes(StandardCharsets.UTF_8)).hasSize(size);
        return message;
    }
}
