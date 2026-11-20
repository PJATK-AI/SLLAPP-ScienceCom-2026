package pl.pjatk.ai.sllapp.ai;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import pl.pjatk.ai.sllapp.ai.PredictionResult.Failure;
import pl.pjatk.ai.sllapp.ai.PredictionResult.FailureKind;
import pl.pjatk.ai.sllapp.ai.PredictionResult.Prediction;
import pl.pjatk.ai.sllapp.config.ai.AiProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(OutputCaptureExtension.class)
class AiPredictionClientTest {
    private static final String BASE_URL = "http://127.0.0.1:8000";
    private static final String PREDICT_URL = BASE_URL + "/predict";
    private static final UUID SESSION_ID = UUID.fromString("6a386307-3c19-4e3b-90c7-ae405835f825");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String POINT = "{\"x\":0.4,\"y\":0.5,\"z\":-0.1}";
    static final JsonNode HAND = MAPPER.readTree("""
            {"coordinateSpace":"MEDIAPIPE_IMAGE","handedness":"LEFT","score":0.99,
             "extra":{"source":"Łódź"}, "landmarks":[%s]}
            """.formatted((POINT + ",").repeat(20) + POINT));

    private MockRestServiceServer server;
    private AiPredictionClient client;

    @BeforeEach
    void setUp() {
        client = new AiPredictionClient(new AiProperties(true, URI.create(BASE_URL), Duration.ofMillis(250)),
                MAPPER, builder -> server = MockRestServiceServer.bindTo(builder).build());
    }

    @AfterEach
    void tearDown() {
        client.close();
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, 0.75, 1})
    void sendsTheExactHandAndDecodesInclusiveConfidence(double confidence) throws Exception {
        server.expect(requestTo(PREDICT_URL)).andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(header("Accept", "application/json"))
                .andExpect(header("Content-Length", String.valueOf(MAPPER.writeValueAsBytes(HAND).length)))
                .andExpect(content().json(MAPPER.writeValueAsString(HAND), JsonCompareMode.STRICT))
                .andRespond(withSuccess("{\"letter\":\"Y\",\"confidence\":" + confidence + "}", MediaType.APPLICATION_JSON));
        assertThat(predict()).isEqualTo(new Prediction('Y', confidence));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "[]", "null", "{\"letter\":\"A\"}", "{\"confidence\":0.5}",
            "{\"letter\":\"D\",\"confidence\":0.5}", "{\"letter\":\"a\",\"confidence\":0.5}",
            "{\"letter\":\"AB\",\"confidence\":0.5}", "{\"letter\":1,\"confidence\":0.5}",
            "{\"letter\":\"A\",\"confidence\":\"0.5\"}", "{\"letter\":\"A\",\"confidence\":0.5} {}",
            "{\"letter\":\"A\",\"confidence\":null}", "{\"letter\":\"A\",\"confidence\":-0.1}",
            "{\"letter\":\"A\",\"confidence\":1.01}", "{\"letter\":\"A\",\"confidence\":1e999}",
            "{\"letter\":\"A\",\"confidence\":NaN}", "{\"letter\":\"A\",\"confidence\":Infinity}",
            "{\"letter\":\"A\",\"confidence\":-1e-400}", "{\"letter\":\"A\",\"confidence\":1.00000000000000000001}"})
    void rejectsInvalidSuccessBodies(String body) throws Exception {
        server.expect(requestTo(PREDICT_URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThat(predict()).isEqualTo(new Failure(FailureKind.CONTRACT_ERROR, 200, null));
        assertThat(client.failureCount(FailureKind.CONTRACT_ERROR)).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"400, INVALID_REQUEST", "413, PAYLOAD_TOO_LARGE", "422, REJECTED_SAMPLE", "500, SERVICE_ERROR",
            "503, SERVICE_ERROR", "404, UNEXPECTED_STATUS", "405, UNEXPECTED_STATUS", "201, UNEXPECTED_STATUS"})
    void classifiesHttpFailuresAndCountsAndLogsOnce(int status, FailureKind kind, CapturedOutput output) throws Exception {
        server.expect(requestTo(PREDICT_URL)).andRespond(withStatus(HttpStatusCode.valueOf(status))
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"sample details\"}"));
        assertThat(predict()).isEqualTo(new Failure(kind, status, "sample details"));
        assertThat(client.failureCount(kind)).isEqualTo(1);
        String logs = output.getAll();
        assertThat(logs).containsOnlyOnce("sessionId=" + SESSION_ID + " category=" + kind + " status=" + status + " count=1");
        assertThat(logs).doesNotContain("sample details", "Łódź");
    }

    @Test
    void keepsTheStatusMappingWhenErrorDetailsAreMalformed() throws Exception {
        server.expect(requestTo(PREDICT_URL)).andRespond(withStatus(HttpStatusCode.valueOf(422)).body("not JSON"));
        assertThat(predict()).isEqualTo(new Failure(FailureKind.REJECTED_SAMPLE, 422, null));
    }

    @Test
    void returnsTypedTimeoutAndConnectionError() throws Exception {
        server.expect(requestTo(PREDICT_URL)).andRespond(withException(new SocketTimeoutException("slow response")));
        server.expect(requestTo(PREDICT_URL)).andRespond(withException(new ConnectException("unavailable")));
        assertThat(predict()).isEqualTo(new Failure(FailureKind.TIMEOUT, null, null));
        assertThat(predict()).isEqualTo(new Failure(FailureKind.TRANSPORT_ERROR, null, null));
        assertThat(client.failureCount(FailureKind.TIMEOUT)).isEqualTo(1);
        assertThat(client.failureCount(FailureKind.TRANSPORT_ERROR)).isEqualTo(1);
    }

    @Test
    void disabledClientSkipsPredictionAndHealthRequests() throws Exception {
        var disabledServer = new AtomicReference<MockRestServiceServer>();
        try (var disabled = new AiPredictionClient(new AiProperties(false, URI.create(BASE_URL), Duration.ofMillis(250)),
                MAPPER, builder -> disabledServer.set(MockRestServiceServer.bindTo(builder).build()))) {
            disabled.probeHealth();
            assertThat(disabled.predict(SESSION_ID, HAND).toCompletableFuture().get(1, TimeUnit.SECONDS))
                    .isEqualTo(new Failure(FailureKind.DISABLED, null, null));
            assertThat(disabled.failureCount(FailureKind.DISABLED)).isZero();
            disabledServer.get().verify();
        }
    }

    private PredictionResult predict() throws Exception {
        return client.predict(SESSION_ID, HAND).toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
}
