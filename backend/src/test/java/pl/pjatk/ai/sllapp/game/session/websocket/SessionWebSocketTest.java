package pl.pjatk.ai.sllapp.game.session.websocket;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocketHandshakeException;
import java.nio.file.Path;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionWebSocketTest {
    @TempDir
    Path directory;
    private ConfigurableApplicationContext app;
    private HttpClient client;
    private int port;

    @BeforeAll
    void start() {
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
