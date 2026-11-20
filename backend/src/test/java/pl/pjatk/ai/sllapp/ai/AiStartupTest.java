package pl.pjatk.ai.sllapp.ai;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.MapPropertySource;
import pl.pjatk.ai.sllapp.SllappApplication;
import pl.pjatk.ai.sllapp.config.ai.AiProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@ExtendWith(OutputCaptureExtension.class)
class AiStartupTest {
    private static final String CHECKSUM = "sha256:" + "a1".repeat(32);
    private static final String METADATA = """
            {"status":"ok","variant":"V3","classifier":"logreg",
             "letters":["A","B","C","E","I","L","M","N","O","P","R","S","T","U","W","Y"],
             "checksum":"%s"}
            """.formatted(CHECKSUM);

    @Test
    void bindsDefaultsAndRejectsInvalidSettings() {
        var context = new ApplicationContextRunner().withUserConfiguration(TestProperties.class);
        context.run(app -> assertThat(app.getBean(AiProperties.class))
                .isEqualTo(new AiProperties(false, null, Duration.ofMillis(250))));
        context.withPropertyValues("sllapp.ai.enabled=true").run(app ->
                assertThat(app).hasFailed().getFailure().hasRootCauseMessage(
                        "Enabled AI prediction requires an absolute HTTP(S) base URL."));
        for (String url : new String[]{"/local", "ftp://localhost:8000", "http:/missing-host", "http://localhost:8000?query=1"}) {
            context.withPropertyValues("sllapp.ai.enabled=true", "sllapp.ai.base-url=" + url).run(app ->
                    assertThat(app).hasFailed().getFailure().hasRootCauseMessage(
                            "Enabled AI prediction requires an absolute HTTP(S) base URL."));
        }
        for (String timeout : new String[]{"0ms", "-1s"}) {
            context.withPropertyValues("sllapp.ai.timeout=" + timeout).run(app ->
                    assertThat(app).hasFailed().getFailure().hasRootCauseMessage("AI prediction timeout must be positive."));
        }
    }

    @Test
    void startupChecksAndLogsTheModelConfiguration(@TempDir Path directory, CapturedOutput output) throws Exception {
        var contacted = new AtomicInteger();
        var method = new AtomicReference<String>();
        var server = healthServer(METADATA, contacted, method);
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        var environment = Map.<String, Object>of("AI_PREDICTION_ENABLED", "true", "AI_PREDICTION_BASE_URL", url,
                "AI_PREDICTION_TIMEOUT", "1200ms");
        try (var app = backend().initializers(context -> context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("testEnvironment", environment))).run("--sllapp.storage.directory=" + directory)) {
            assertThat(app.getBean(AiProperties.class)).isEqualTo(new AiProperties(true, URI.create(url), Duration.ofMillis(1200)));
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(output.getAll())
                    .contains("AI health check passed: variant=V3 classifier=logreg", "letters=[\"A\",\"B\"", "checksum=" + CHECKSUM));
            assertThat(contacted).hasValue(1);
            assertThat(method).hasValue("GET");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unhealthyServiceLeavesBackendRunning(@TempDir Path directory, CapturedOutput output) throws Exception {
        int unusedPort;
        try (var reserved = new ServerSocket(0)) {
            unusedPort = reserved.getLocalPort();
        }
        try (var app = backend().run("--sllapp.storage.directory=" + directory, "--sllapp.ai.enabled=true",
                "--sllapp.ai.timeout=1s", "--sllapp.ai.base-url=http://127.0.0.1:" + unusedPort)) {
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(output.getAll())
                    .contains("AI health check failed: category=TRANSPORT_ERROR"));
            assertThat(app.isRunning()).isTrue();
            assertThat(app.getBean(AiPredictionClient.class).failureCount(PredictionResult.FailureKind.TRANSPORT_ERROR)).isZero();
        }
    }

    @Test
    void malformedHealthResponseLeavesBackendRunning(@TempDir Path directory, CapturedOutput output) throws Exception {
        var server = healthServer(METADATA.replace(CHECKSUM, "sha256:not-a-checksum"), new AtomicInteger(),
                new AtomicReference<>());
        try (var app = backend().run("--sllapp.storage.directory=" + directory, "--sllapp.ai.enabled=true",
                "--sllapp.ai.timeout=1s", "--sllapp.ai.base-url=http://127.0.0.1:" + server.getAddress().getPort())) {
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(output.getAll())
                    .contains("AI health check failed: category=CONTRACT_ERROR status=200"));
            assertThat(app.isRunning()).isTrue();
        } finally {
            server.stop(0);
        }
    }

    private static SpringApplicationBuilder backend() {
        return new SpringApplicationBuilder(SllappApplication.class).web(WebApplicationType.NONE);
    }

    private static HttpServer healthServer(String body, AtomicInteger contacted,
                                           AtomicReference<String> method) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            try (exchange) {
                contacted.incrementAndGet();
                method.set(exchange.getRequestMethod());
                var bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        return server;
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiProperties.class)
    static class TestProperties {}
}
