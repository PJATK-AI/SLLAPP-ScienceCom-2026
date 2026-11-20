package pl.pjatk.ai.sllapp.ai;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import pl.pjatk.ai.sllapp.ai.PredictionResult.Failure;
import pl.pjatk.ai.sllapp.ai.PredictionResult.FailureKind;
import pl.pjatk.ai.sllapp.ai.PredictionResult.Prediction;
import pl.pjatk.ai.sllapp.config.ai.AiProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.node.MissingNode;
import static pl.pjatk.ai.sllapp.ai.PredictionResult.FailureKind.CONTRACT_ERROR;
import static pl.pjatk.ai.sllapp.ai.PredictionResult.FailureKind.DISABLED;
import static pl.pjatk.ai.sllapp.ai.PredictionResult.FailureKind.INVALID_REQUEST;
import static pl.pjatk.ai.sllapp.ai.PredictionResult.FailureKind.OVERLOADED;
import static pl.pjatk.ai.sllapp.ai.PredictionResult.FailureKind.PAYLOAD_TOO_LARGE;
import static pl.pjatk.ai.sllapp.ai.PredictionResult.FailureKind.REJECTED_SAMPLE;
import static pl.pjatk.ai.sllapp.ai.PredictionResult.FailureKind.SERVICE_ERROR;
import static pl.pjatk.ai.sllapp.ai.PredictionResult.FailureKind.TIMEOUT;
import static pl.pjatk.ai.sllapp.ai.PredictionResult.FailureKind.TRANSPORT_ERROR;
import static pl.pjatk.ai.sllapp.ai.PredictionResult.FailureKind.UNEXPECTED_STATUS;

public final class AiPredictionClient implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(AiPredictionClient.class);
    private static final String SUPPORTED_LETTERS = "ABCEILMNOPRSTUWY";
    private static final int CONCURRENT_REQUESTS = 4;
    private static final int MAX_RESPONSE_BYTES = 8192;

    private final AiProperties properties;
    private final Duration readTimeout;
    private final ObjectMapper mapper;
    private final ObjectReader reader;
    private final HttpClient httpClient;
    private final RestClient client;
    private final ThreadPoolExecutor workers;
    private final ConcurrentHashMap<FailureKind, AtomicLong> failureCounts = new ConcurrentHashMap<>();

    public AiPredictionClient(AiProperties properties, ObjectMapper mapper) {
        this(properties, mapper, builder -> {});
    }

    AiPredictionClient(AiProperties properties, ObjectMapper mapper, Consumer<RestClient.Builder> customize) {
        this.properties = properties;
        this.readTimeout = Duration.ofMillis(Math.max(1, properties.timeout().toMillis()));
        this.mapper = mapper;
        this.reader = mapper.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).without(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS);
        this.httpClient = HttpClient.newBuilder().connectTimeout(readTimeout).version(HttpClient.Version.HTTP_1_1).build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        var builder = RestClient.builder().requestFactory(factory);
        if (properties.enabled()) builder.baseUrl(properties.baseUrl().toString());
        customize.accept(builder);
        this.client = builder.build();
        this.workers = new ThreadPoolExecutor(CONCURRENT_REQUESTS, CONCURRENT_REQUESTS, 0, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), Thread.ofPlatform().daemon().name("ai-prediction-", 1).factory());
    }

    /** Submits a validated hand. The caller owns sample selection and any use of the prediction. */
    public CompletionStage<PredictionResult> predict(UUID sessionId, JsonNode hand) {
        if (!properties.enabled()) return CompletableFuture.completedStage(failure(DISABLED));
        return submit(() -> requestPrediction(hand), failure -> failure).thenApply(result -> {
            if (result instanceof Failure failure) {
                long count = failureCounts.computeIfAbsent(failure.kind(), kind -> new AtomicLong()).incrementAndGet();
                log.warn("AI prediction failed: sessionId={} category={} status={} count={}",
                        sessionId, failure.kind(), failure.httpStatus(), count);
            }
            return result;
        }).minimalCompletionStage();
    }

    public long failureCount(FailureKind kind) {
        var count = failureCounts.get(kind);
        return count == null ? 0 : count.get();
    }

    @EventListener(ApplicationReadyEvent.class)
    void probeHealth() {
        if (!properties.enabled()) return;
        submit(this::requestHealth, Function.identity()).thenAccept(failure -> {
            if (failure != null) {
                log.warn("AI health check failed: category={} status={}", failure.kind(), failure.httpStatus());
            }
        });
    }

    private PredictionResult requestPrediction(JsonNode hand) {
        if (hand == null || !hand.isObject()) return failure(INVALID_REQUEST);
        byte[] payload;
        try {
            payload = mapper.writeValueAsBytes(hand);
        } catch (JacksonException exception) {
            return failure(INVALID_REQUEST);
        }
        var response = client.post().uri("/predict").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON).contentLength(payload.length).body(payload)
                .exchange((request, reply) -> readResponse(reply));
        if (response.status() != 200) return statusFailure(response);

        var letter = response.body().path("letter");
        var confidence = response.body().path("confidence");
        if (!letter.isString() || letter.asString().length() != 1 || !SUPPORTED_LETTERS.contains(letter.asString())
                || !confidence.isNumber()) {
            return new Failure(CONTRACT_ERROR, 200, null);
        }
        var value = confidence.decimalValue();
        if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) return new Failure(CONTRACT_ERROR, 200, null);
        return new Prediction(letter.asString().charAt(0), value.doubleValue());
    }

    private Failure requestHealth() {
        var response = client.get().uri("/health").accept(MediaType.APPLICATION_JSON)
                .exchange((request, reply) -> readResponse(reply));
        if (response.status() != 200) return statusFailure(response);
        var body = response.body();
        var variant = body.path("variant");
        var classifier = body.path("classifier");
        var letters = body.path("letters");
        var checksum = body.path("checksum");
        if (!body.path("status").isString() || !"ok".equals(body.path("status").asString())
                || !isMetadataName(variant) || !isMetadataName(classifier) || !hasSupportedLetters(letters)
                || !checksum.isString() || !checksum.asString().matches("sha256:[0-9a-fA-F]{64}")) {
            return new Failure(CONTRACT_ERROR, 200, null);
        }
        log.info("AI health check passed: variant={} classifier={} letters={} checksum={}",
                variant.asString(), classifier.asString(), letters, checksum.asString());
        return null;
    }

    private ServiceResponse readResponse(ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        byte[] bytes = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
        if (bytes.length > 0 && bytes.length <= MAX_RESPONSE_BYTES) {
            try {
                var json = (JsonNode) reader.readValue(bytes);
                if (json != null && json.isObject()) return new ServiceResponse(status, json);
            } catch (JacksonException exception) {
                // Keep the HTTP status when a failed response has no usable JSON details.
            }
        }
        return new ServiceResponse(status, MissingNode.getInstance());
    }

    private <T> CompletableFuture<T> submit(Supplier<T> call, Function<Failure, T> onFailure) {
        var result = new CompletableFuture<T>();
        try {
            workers.execute(() -> {
                long started = System.nanoTime();
                T value;
                try {
                    value = call.get();
                } catch (RuntimeException exception) {
                    value = onFailure.apply(transportFailure(exception, started));
                }
                result.complete(value);
            });
        } catch (RejectedExecutionException exception) {
            result.complete(onFailure.apply(failure(OVERLOADED)));
        }
        return result;
    }

    private Failure transportFailure(RuntimeException exception, long started) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) return failure(TIMEOUT);
            if (cause instanceof IOException && Duration.ofNanos(System.nanoTime() - started).compareTo(readTimeout) >= 0) {
                // The JDK request factory may close an already-started response body at its deadline.
                return failure(TIMEOUT);
            }
        }
        return failure(TRANSPORT_ERROR);
    }

    private static Failure statusFailure(ServiceResponse response) {
        int status = response.status();
        FailureKind kind = switch (status) {
            case 400 -> INVALID_REQUEST;
            case 413 -> PAYLOAD_TOO_LARGE;
            case 422 -> REJECTED_SAMPLE;
            default -> status >= 500 && status < 600 ? SERVICE_ERROR : UNEXPECTED_STATUS;
        };
        var error = response.body().path("error");
        return new Failure(kind, status, error.isString() ? error.asString() : null);
    }

    private static Failure failure(FailureKind kind) {
        return new Failure(kind, null, null);
    }

    private static boolean isMetadataName(JsonNode value) {
        return value.isString() && value.asString().matches("[A-Za-z0-9_-]+");
    }

    private static boolean hasSupportedLetters(JsonNode letters) {
        if (!letters.isArray() || letters.size() != SUPPORTED_LETTERS.length()) return false;
        for (int index = 0; index < letters.size(); index++) {
            var letter = letters.get(index);
            if (!letter.isString() || !String.valueOf(SUPPORTED_LETTERS.charAt(index)).equals(letter.asString())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void close() {
        workers.shutdownNow();
        httpClient.shutdownNow();
    }

    private record ServiceResponse(int status, JsonNode body) {}
}
