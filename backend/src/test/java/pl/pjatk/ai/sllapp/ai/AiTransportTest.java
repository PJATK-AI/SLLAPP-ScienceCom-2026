package pl.pjatk.ai.sllapp.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pl.pjatk.ai.sllapp.ai.PredictionResult.Failure;
import pl.pjatk.ai.sllapp.ai.PredictionResult.FailureKind;
import pl.pjatk.ai.sllapp.ai.PredictionResult.Prediction;
import pl.pjatk.ai.sllapp.config.ai.AiProperties;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static pl.pjatk.ai.sllapp.ai.AiPredictionClientTest.HAND;

class AiTransportTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final byte[] PREDICTION = "{\"letter\":\"A\",\"confidence\":0.95}".getBytes(StandardCharsets.UTF_8);

    @Test
    void sendsAByteCountAndReusesTheHttpConnection() throws Exception {
        var received = new ConcurrentLinkedQueue<Received>();
        try (var server = new StubServer(exchange -> {
            var bytes = exchange.getRequestBody().readAllBytes();
            received.add(new Received(exchange.getRequestHeaders().getFirst("Content-Length"),
                    exchange.getRequestHeaders().getFirst("Transfer-Encoding"), bytes, exchange.getRemoteAddress()));
            sendPrediction(exchange);
        }); var client = server.client(Duration.ofSeconds(2))) {
            assertThat(predict(client).get(5, TimeUnit.SECONDS)).isEqualTo(new Prediction('A', 0.95));
            assertThat(predict(client).get(5, TimeUnit.SECONDS)).isEqualTo(new Prediction('A', 0.95));
            assertThat(received).hasSize(2);
            assertThat(received).allSatisfy(request -> {
                assertThat(request.contentLength()).isEqualTo(String.valueOf(request.body().length));
                assertThat(request.transferEncoding()).isNull();
                assertThat(request.body().length).isGreaterThan(new String(request.body(), StandardCharsets.UTF_8).length());
                assertThat(MAPPER.readTree(request.body())).isEqualTo(HAND);
            });
            assertThat(received).extracting(Received::socket).containsOnly(received.peek().socket());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void timesOutWhetherHeadersOrBodyStallAndRecovers(boolean sendHeaders) throws Exception {
        var first = new AtomicInteger();
        var blocked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var server = new StubServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (first.getAndIncrement() > 0) {
                sendPrediction(exchange);
                return;
            }
            if (sendHeaders) {
                exchange.sendResponseHeaders(200, PREDICTION.length);
                exchange.getResponseBody().write(PREDICTION, 0, 1);
                exchange.getResponseBody().flush();
            }
            blocked.countDown();
            await(release);
        }); var client = server.client(Duration.ofMillis(350))) {
            var pending = predict(client);
            assertThat(blocked.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(pending.get(3, TimeUnit.SECONDS)).isEqualTo(new Failure(FailureKind.TIMEOUT, null, null));
            assertThat(client.failureCount(FailureKind.TIMEOUT)).isEqualTo(1);
            assertThat(predict(client).get(3, TimeUnit.SECONDS)).isEqualTo(new Prediction('A', 0.95));
        } finally {
            release.countDown();
        }
    }

    @Test
    void returnsWithoutWaitingForIoAndRejectsWorkAtCapacity() throws Exception {
        var entered = new CountDownLatch(4);
        var release = new CountDownLatch(1);
        try (var server = new StubServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            entered.countDown();
            await(release);
            sendPrediction(exchange);
        }); var client = server.client(Duration.ofSeconds(5))) {
            var pending = new ArrayList<CompletableFuture<PredictionResult>>();
            for (int request = 0; request < 4; request++) pending.add(predict(client));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(pending).allSatisfy(call -> assertThat(call).isNotDone());
            assertThat(predict(client).get(1, TimeUnit.SECONDS)).isEqualTo(new Failure(FailureKind.OVERLOADED, null, null));
            assertThat(client.failureCount(FailureKind.OVERLOADED)).isEqualTo(1);
            release.countDown();
            for (var call : pending) assertThat(call.get(3, TimeUnit.SECONDS)).isEqualTo(new Prediction('A', 0.95));
        } finally {
            release.countDown();
        }
    }

    private static void sendPrediction(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, PREDICTION.length);
        exchange.getResponseBody().write(PREDICTION);
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(7, TimeUnit.SECONDS)) throw new IOException("Stub wait expired");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException(exception);
        }
    }

    private static CompletableFuture<PredictionResult> predict(AiPredictionClient client) {
        return client.predict(SESSION_ID, HAND).toCompletableFuture();
    }

    private record Received(String contentLength, String transferEncoding, byte[] body, InetSocketAddress socket) {}

    private static final class StubServer implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        StubServer(HttpHandler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/predict", exchange -> {
                try (exchange) {
                    handler.handle(exchange);
                }
            });
            server.setExecutor(executor);
            server.start();
        }

        AiPredictionClient client(Duration timeout) {
            var baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            return new AiPredictionClient(new AiProperties(true, baseUrl, timeout), MAPPER);
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
