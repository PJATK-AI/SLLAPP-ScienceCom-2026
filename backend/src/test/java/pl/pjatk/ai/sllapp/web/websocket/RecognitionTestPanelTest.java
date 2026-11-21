package pl.pjatk.ai.sllapp.web.websocket;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import pl.pjatk.ai.sllapp.SllappApplication;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecognitionTestPanelTest {
    private static final String PANEL = "/recognition-test";
    private static final String SOCKET = PANEL + "/ws";
    @TempDir Path directory;

    @Test
    void hidesThePanelAndSocketWithoutTheProfile() throws Exception {
        try (var app = start(false, null); var http = HttpClient.newHttpClient()) {
            int port = port(app);
            assertThat(get(http, port, PANEL).statusCode()).isEqualTo(404);
            assertThat(get(http, port, PANEL + "/app.js").statusCode()).isEqualTo(404);
            assertThatThrownBy(() -> new WebSocketTestClient(http, port, null, SOCKET))
                    .hasCauseInstanceOf(WebSocketHandshakeException.class);
        }
    }

    @Test
    void servesThePanelAndAcceptsSamplesWithAiDisabled() throws Exception {
        writeAsset("packages/hand-landmarker/dist/index.js", "export const tracker = true;");
        writeAsset("packages/hand-landmarker/node_modules/@mediapipe/tasks-vision/vision_bundle.mjs", "export {};");
        writeAsset("apps/web/public/mediapipe/wasm/test.wasm", "wasm");
        try (var app = start(true, null); var http = HttpClient.newHttpClient()) {
            int port = port(app);
            var page = get(http, port, PANEL);
            assertThat(page.statusCode()).isEqualTo(200);
            assertThat(page.body()).contains("Podgląd kamery", "Ruch WebSocket i HTTP");
            assertThat(get(http, port, PANEL + "/app.js").headers().firstValue("Content-Type"))
                    .hasValueSatisfying(value -> assertThat(value).contains("javascript"));
            assertThat(get(http, port, PANEL + "/styles.css").statusCode()).isEqualTo(200);
            assertThat(get(http, port, PANEL + "/tracker/index.js").body()).isEqualTo("export const tracker = true;");
            assertThat(get(http, port, PANEL + "/vision/vision_bundle.mjs").headers().firstValue("Content-Type"))
                    .hasValueSatisfying(value -> assertThat(value).contains("javascript"));
            assertThat(get(http, port, PANEL + "/mediapipe/wasm/test.wasm").headers().firstValue("Content-Type"))
                    .hasValue("application/wasm");
            try (var socket = new WebSocketTestClient(http, port, "http://127.0.0.1:" + port, SOCKET)) {
                socket.send(SampleFixtures.hand(0));
                assertAck(socket, 0);
                var result = socket.next();
                assertThat(result.path("type").asString()).isEqualTo("prediction_result");
                assertThat(result.path("seq").asLong()).isZero();
                assertThat(result.path("report").path("http").isNull()).isTrue();
                assertThat(result.path("report").path("result").path("kind").asString()).isEqualTo("DISABLED");

                socket.send(SampleFixtures.noHand(1));
                assertAck(socket, 1);
                assertThat(socket.next().path("reason").asString()).isEqualTo("NO_HAND");
                socket.send(SampleFixtures.hand(1));
                assertThat(socket.next().path("reason").asString()).isEqualTo("SEQUENCE_NOT_INCREASING");
                socket.send("{\"type\":\"start_game\"}");
                assertThat(socket.next().path("code").asString()).isEqualTo("INVALID_SAMPLE");
                assertThat(get(http, port, "/api/ranking").body()).isEqualTo("[]");
            }
        }
    }

    @Test
    void tracesTheActualAiExchangeAndRecoversFromFailures() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var callCount = new AtomicInteger();
        var request = new AtomicReference<String>();
        var stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/health", exchange -> reply(exchange, 503, "{}"));
        stub.createContext("/predict", exchange -> {
            try (exchange) {
                request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                switch (callCount.getAndIncrement()) {
                    case 0 -> {
                        entered.countDown();
                        try {
                            if (!release.await(4, TimeUnit.SECONDS)) return;
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        reply(exchange, 200, "{\"letter\":\"A\",\"confidence\":0.91}");
                    }
                    case 1 -> reply(exchange, 503, "{\"error\":\"model unavailable\"}");
                    case 2 -> reply(exchange, 200, "invalid response");
                    case 3 -> reply(exchange, 200, "x".repeat(9000));
                    default -> reply(exchange, 200, "{\"letter\":\"B\",\"confidence\":0.93}");
                }
            }
        });
        stub.start();
        try (var app = start(true, "http://127.0.0.1:" + stub.getAddress().getPort());
             var http = HttpClient.newHttpClient();
             var socket = new WebSocketTestClient(http, port(app), null, SOCKET)) {
            socket.send(SampleFixtures.hand(0));
            assertAck(socket, 0);
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            try {
                socket.send(SampleFixtures.hand(1));
                assertAck(socket, 1);
                assertThat(socket.next().path("reason").asString()).isEqualTo("AI_BUSY");
            } finally {
                release.countDown();
            }
            var report = socket.next().path("report");
            var trace = report.path("http");
            assertThat(trace.path("method").asString()).isEqualTo("POST");
            assertThat(trace.path("url").asString()).endsWith("/predict");
            assertThat(trace.path("requestBody").asString()).isEqualTo(request.get());
            assertThat(new ObjectMapper().readTree(request.get())).isEqualTo(
                    new ObjectMapper().readTree(SampleFixtures.hand(0)).path("hand"));
            assertThat(trace.path("status").asInt()).isEqualTo(200);
            assertThat(trace.path("responseBody").asString()).isEqualTo("{\"letter\":\"A\",\"confidence\":0.91}");
            assertThat(report.path("result").path("letter").asString()).isEqualTo("A");
            assertThat(report.path("result").path("confidence").asDouble()).isEqualTo(0.91);
            assertThat(report.path("elapsedMs").asLong()).isNotNegative();

            socket.send(SampleFixtures.hand(2));
            assertAck(socket, 2);
            var failure = socket.next().path("report");
            assertThat(failure.path("http").path("status").asInt()).isEqualTo(503);
            assertThat(failure.path("http").path("responseBody").asString()).isEqualTo("{\"error\":\"model unavailable\"}");
            assertThat(failure.path("result").path("kind").asString()).isEqualTo("SERVICE_ERROR");

            socket.send(SampleFixtures.hand(3));
            assertAck(socket, 3);
            var invalid = socket.next().path("report");
            assertThat(invalid.path("http").path("responseBody").asString()).isEqualTo("invalid response");
            assertThat(invalid.path("result").path("kind").asString()).isEqualTo("CONTRACT_ERROR");

            socket.send(SampleFixtures.hand(4));
            assertAck(socket, 4);
            var large = socket.next().path("report").path("http");
            assertThat(large.path("responseTruncated").asBoolean()).isTrue();
            assertThat(large.path("responseBody").asString()).hasSize(8192);

            socket.send(SampleFixtures.hand(5));
            assertAck(socket, 5);
            assertThat(socket.next().path("report").path("result").path("letter").asString()).isEqualTo("B");
        } finally {
            release.countDown();
            stub.stop(0);
        }
    }

    @Test
    void keepsTheSocketUsableWhenAiCannotBeReached() throws Exception {
        var unavailable = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int aiPort = unavailable.getAddress().getPort();
        unavailable.start();
        unavailable.stop(0);
        try (var app = start(true, "http://127.0.0.1:" + aiPort);
             var http = HttpClient.newHttpClient();
             var socket = new WebSocketTestClient(http, port(app), null, SOCKET)) {
            socket.send(SampleFixtures.hand(0));
            assertAck(socket, 0);
            var report = socket.next().path("report");
            assertThat(report.path("http").path("url").asString()).endsWith("/predict");
            assertThat(report.path("http").path("status").isNull()).isTrue();
            assertThat(report.path("result").path("kind").asString()).isEqualTo("TRANSPORT_ERROR");
            socket.send(SampleFixtures.noHand(1));
            assertAck(socket, 1);
            assertThat(socket.next().path("reason").asString()).isEqualTo("NO_HAND");
        }
    }

    private ConfigurableApplicationContext start(boolean enabledProfile, String aiUrl) {
        var builder = new SpringApplicationBuilder(SllappApplication.class);
        if (enabledProfile) builder.profiles("recognition-test");
        return builder.run("--server.port=0", "--sllapp.storage.directory=" + directory.resolve("data"),
                "--sllapp.recognition-test.frontend-directory=" + directory.resolve("frontend"),
                "--sllapp.ai.enabled=" + (aiUrl != null), "--sllapp.ai.base-url=" + (aiUrl == null ? "" : aiUrl),
                "--sllapp.ai.timeout=2s");
    }

    private void writeAsset(String relative, String content) throws IOException {
        var path = directory.resolve("frontend").resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static void assertAck(WebSocketTestClient socket, long seq) throws Exception {
        var ack = socket.next();
        assertThat(ack.path("type").asString()).isEqualTo("sample_ack");
        assertThat(ack.path("seq").asLong()).isEqualTo(seq);
    }

    private static int port(ConfigurableApplicationContext app) {
        return ((WebServerApplicationContext) app).getWebServer().getPort();
    }

    private static HttpResponse<String> get(HttpClient client, int port, String path)
            throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void reply(HttpExchange exchange, int status, String payload) throws IOException {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var response = exchange.getResponseBody()) {
            response.write(bytes);
        }
    }
}
