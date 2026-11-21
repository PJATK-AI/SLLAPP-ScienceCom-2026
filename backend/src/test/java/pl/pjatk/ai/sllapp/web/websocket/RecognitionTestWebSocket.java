package pl.pjatk.ai.sllapp.web.websocket;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import pl.pjatk.ai.sllapp.ai.RecognitionTestPredictor;
import pl.pjatk.ai.sllapp.config.web.SessionWebSocketProperties;
import pl.pjatk.ai.sllapp.web.websocket.message.ErrorMessage;
import pl.pjatk.ai.sllapp.web.websocket.message.SampleAckMessage;
import pl.pjatk.ai.sllapp.web.websocket.message.SampleMessage;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

@Component
@Profile("recognition-test")
class RecognitionTestWebSocket extends AbstractWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(RecognitionTestWebSocket.class);
    private static final String CONNECTION_KEY = RecognitionTestWebSocket.class.getName();
    private final RecognitionTestPredictor predictor;
    private final SessionWebSocketProperties limits;
    private final SampleMessageValidator validator;
    private final ObjectMapper mapper;
    private final ObjectReader reader;

    RecognitionTestWebSocket(RecognitionTestPredictor predictor, SessionWebSocketProperties limits,
                             SampleMessageValidator validator, ObjectMapper mapper) {
        this.predictor = predictor;
        this.limits = limits;
        this.validator = validator;
        this.mapper = mapper;
        reader = mapper.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession socket) {
        socket.getAttributes().put(CONNECTION_KEY, new Connection(UUID.randomUUID(), new SessionConnection(socket, mapper)));
    }

    @Override
    protected void handleTextMessage(WebSocketSession socket, TextMessage message) throws IOException {
        var connection = (Connection) socket.getAttributes().get(CONNECTION_KEY);
        var output = connection.output();
        int bytes = message.getPayload().getBytes(StandardCharsets.UTF_8).length;
        if (bytes > limits.transportMaxMessageBytes()) {
            socket.close(CloseStatus.TOO_BIG_TO_PROCESS);
            return;
        }
        if (bytes > limits.maxMessageBytes()) {
            output.send(ErrorMessage.of("MESSAGE_TOO_LARGE", "Message exceeds the application size limit."));
            return;
        }
        SampleMessage sample;
        try {
            JsonNode node = reader.readValue(message.getPayload());
            if (node == null || !node.isObject()) throw new IllegalArgumentException("Expected a JSON sample object.");
            sample = validator.validate(node, null);
        } catch (JacksonException error) {
            output.send(ErrorMessage.of("INVALID_SAMPLE", "Expected one JSON sample object."));
            return;
        } catch (IllegalArgumentException error) {
            output.send(ErrorMessage.of("INVALID_SAMPLE", error.getMessage()));
            return;
        }
        if (!output.acceptSample(sample)) {
            output.send(new Skipped("sample_skipped", sample.seq(), "SEQUENCE_NOT_INCREASING"));
            return;
        }
        output.send(SampleAckMessage.of(sample.seq()));
        if (sample.hand() == null) {
            output.send(new Skipped("sample_skipped", sample.seq(), "NO_HAND"));
            return;
        }
        var pending = predictor.predict(connection.id(), sample.hand());
        if (pending.isEmpty()) {
            output.send(new Skipped("sample_skipped", sample.seq(), "AI_BUSY"));
            return;
        }
        pending.orElseThrow().thenAccept(report -> {
            try {
                output.send(new Result("prediction_result", sample.seq(), report));
            } catch (IOException | RuntimeException error) {
                try {
                    output.socket.close(CloseStatus.SERVER_ERROR);
                } catch (IOException closeError) {
                    error.addSuppressed(closeError);
                }
                log.debug("Could not send recognition diagnostics", error);
            }
        });
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession socket, BinaryMessage message) throws IOException {
        var connection = (Connection) socket.getAttributes().get(CONNECTION_KEY);
        connection.output().send(ErrorMessage.of("INVALID_SAMPLE", "Expected a JSON text message."));
    }

    private record Connection(UUID id, SessionConnection output) {}

    private record Skipped(String type, long seq, String reason) {}

    private record Result(String type, long seq, RecognitionTestPredictor.Report report) {}
}
