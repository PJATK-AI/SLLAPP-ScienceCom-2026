package pl.pjatk.ai.sllapp.ai;

import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import pl.pjatk.ai.sllapp.config.ai.AiProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@Profile("recognition-test")
public final class RecognitionTestPredictor {
    private static final int RESPONSE_LIMIT = 8192;

    // Keep one call across the local panels so a trace always belongs to its own sample.
    private final AtomicReference<Trace> active = new AtomicReference<>();
    private final AiPredictionClient client;

    RecognitionTestPredictor(AiProperties properties, ObjectMapper mapper) {
        client = new AiPredictionClient(properties, mapper, builder -> builder.requestInterceptor(this::observe));
    }

    public Optional<CompletionStage<Report>> predict(UUID connectionId, JsonNode hand) {
        var trace = new Trace();
        if (!active.compareAndSet(null, trace)) return Optional.empty();
        try {
            return Optional.of(client.predict(connectionId, hand).handle((result, error) -> {
                try {
                    return trace.report(result, error);
                } finally {
                    active.compareAndSet(trace, null);
                }
            }));
        } catch (RuntimeException error) {
            active.compareAndSet(trace, null);
            throw error;
        }
    }

    private ClientHttpResponse observe(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        var trace = active.get();
        trace.method = request.getMethod().name();
        trace.url = request.getURI().toString();
        trace.requestBody = new String(body, StandardCharsets.UTF_8);
        trace.requestedAt = Instant.now().toEpochMilli();
        var response = execution.execute(request, body);
        return new TracedResponse(response, trace);
    }

    @PreDestroy
    void close() {
        client.close();
    }

    public record Report(long elapsedMs, HttpTrace http, PredictionResult result, String error) {}

    public record HttpTrace(String method, String url, String requestBody, long requestedAt,
                            Integer status, String responseBody, boolean responseTruncated, Long receivedAt) {}

    private static final class Trace {
        final long started = System.nanoTime();
        final ByteArrayOutputStream responseBytes = new ByteArrayOutputStream();
        String method;
        String url;
        String requestBody;
        long requestedAt;
        Integer status;
        Long receivedAt;
        boolean responseTruncated;

        void append(byte[] bytes, int offset, int length) {
            int accepted = Math.min(length, RESPONSE_LIMIT - responseBytes.size());
            responseBytes.write(bytes, offset, accepted);
            responseTruncated |= accepted < length;
        }

        void append(int value) {
            if (responseBytes.size() < RESPONSE_LIMIT) responseBytes.write(value);
            else responseTruncated = true;
        }

        Report report(PredictionResult result, Throwable error) {
            var http = method == null ? null : new HttpTrace(method, url, requestBody, requestedAt, status,
                    status == null ? null : responseBytes.toString(StandardCharsets.UTF_8), responseTruncated,
                    receivedAt);
            return new Report(Duration.ofNanos(System.nanoTime() - started).toMillis(), http,
                    result, error == null ? null : error.getClass().getSimpleName());
        }
    }

    private record TracedResponse(ClientHttpResponse delegate, Trace trace) implements ClientHttpResponse {
        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            var status = delegate.getStatusCode();
            trace.status = status.value();
            trace.receivedAt = Instant.now().toEpochMilli();
            return status;
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public InputStream getBody() throws IOException {
            return new FilterInputStream(delegate.getBody()) {
                @Override
                public int read() throws IOException {
                    int value = in.read();
                    if (value != -1) trace.append(value);
                    return value;
                }

                @Override
                public int read(byte[] bytes, int offset, int length) throws IOException {
                    int count = in.read(bytes, offset, length);
                    if (count > 0) trace.append(bytes, offset, count);
                    return count;
                }
            };
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
