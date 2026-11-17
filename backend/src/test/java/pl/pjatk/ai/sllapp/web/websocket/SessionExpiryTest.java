package pl.pjatk.ai.sllapp.web.websocket;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import pl.pjatk.ai.sllapp.SllappApplication;
import pl.pjatk.ai.sllapp.service.SessionService;
import pl.pjatk.ai.sllapp.support.MutableClock;
import static org.assertj.core.api.Assertions.assertThat;

class SessionExpiryTest {
    @TempDir
    Path directory;
    private final MutableClock clock = new MutableClock();
    private ConfigurableApplicationContext app;
    private HttpClient client;
    private int port;

    @BeforeEach
    void start() {
        app = new SpringApplicationBuilder(SllappApplication.class)
                .initializers(context -> context.getBeanFactory().registerSingleton("testClock", clock))
                .run("--server.port=0", "--sllapp.storage.directory=" + directory,
                        "--sllapp.session.creation-ttl=2m", "--sllapp.websocket.diagnostic-mode=true");
        port = ((WebServerApplicationContext) app).getWebServer().getPort();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void stop() {
        if (app != null) app.close();
        if (client != null) client.close();
    }

    @Test
    void closesIdleSocketAndRemovesExpiredSession() throws Exception {
        try (var socket = new WebSocketTestClient(client, port, null)) {
            socket.send("{\"type\":\"create_session\",\"nickname\":\"Ola\",\"selectedHand\":\"RIGHT\"}");
            var id = UUID.fromString(socket.next().path("sessionId").asString());
            clock.advance(Duration.ofMinutes(2));
            assertThat(socket.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
            var sessions = app.getBean(SessionService.class);
            assertThat(sessions.find(id)).isEmpty();
            assertThat(sessions.removeExpired()).isEmpty();
            try (var next = new WebSocketTestClient(client, port, null)) {
                next.send("{\"type\":\"resume_session\",\"sessionId\":\"" + id + "\"}");
                assertThat(next.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
            }
        }
    }

    @Test
    void activityAndReplacementDoNotExtendExpiry() throws Exception {
        try (var first = new WebSocketTestClient(client, port, null);
             var second = new WebSocketTestClient(client, port, null)) {
            first.send("{\"type\":\"create_session\",\"nickname\":\"Ola\",\"selectedHand\":\"LEFT\"}");
            var id = first.next().path("sessionId").asString();
            clock.advance(Duration.ofSeconds(119));
            first.send(SampleFixtures.noHand(10));
            assertThat(first.next().path("type").asString()).isEqualTo("sample_ack");
            second.send("{\"type\":\"resume_session\",\"sessionId\":\"" + id + "\"}");
            assertThat(second.next().path("type").asString()).isEqualTo("game_state");
            assertThat(first.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1000);
            clock.advance(Duration.ofSeconds(1));
            second.send(SampleFixtures.noHand(0));
            assertThat(second.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
            second.assertSilent();
        }
    }
}
