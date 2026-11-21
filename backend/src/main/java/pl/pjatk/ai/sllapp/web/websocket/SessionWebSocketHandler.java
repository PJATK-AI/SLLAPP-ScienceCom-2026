package pl.pjatk.ai.sllapp.web.websocket;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;
import pl.pjatk.ai.sllapp.config.web.SessionWebSocketProperties;
import pl.pjatk.ai.sllapp.model.GameSession;
import pl.pjatk.ai.sllapp.model.LetterRecognition.Attempt;
import pl.pjatk.ai.sllapp.model.SelectedHand;
import pl.pjatk.ai.sllapp.service.SessionService;
import pl.pjatk.ai.sllapp.web.websocket.message.ErrorMessage;
import pl.pjatk.ai.sllapp.web.websocket.message.GameStateMessage;
import pl.pjatk.ai.sllapp.web.websocket.message.SampleAckMessage;
import pl.pjatk.ai.sllapp.web.websocket.message.SampleMessage;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SessionWebSocketHandler extends AbstractWebSocketHandler {
    private final ObjectReader reader;
    private final SessionWebSocketProperties properties;
    private final SessionService sessions;
    private final SampleMessageValidator samples;
    private final SessionConnectionRegistry connections;
    private final Clock clock;

    SessionWebSocketHandler(ObjectMapper mapper, SessionWebSocketProperties properties, SessionService sessions,
                            SampleMessageValidator samples, SessionConnectionRegistry connections, Clock clock) {
        this.reader = mapper.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.properties = properties;
        this.sessions = sessions;
        this.samples = samples;
        this.connections = connections;
        this.clock = clock;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        var uri = session.getUri();
        boolean predictionDiagnostics = properties.diagnosticMode() && uri != null && "1".equals(
                UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst("diagnostics"));
        connections.register(session, predictionDiagnostics);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        connections.remove(session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        var connection = connections.findUsable(session.getId());
        if (connection == null) return;
        int bytes = message.getPayload().getBytes(StandardCharsets.UTF_8).length;
        if (bytes > properties.transportMaxMessageBytes()) {
            connection.socket.close(CloseStatus.TOO_BIG_TO_PROCESS.withReason("Message exceeds transport limit"));
            return;
        }
        if (bytes > properties.maxMessageBytes()) {
            error(session, "MESSAGE_TOO_LARGE", "Message exceeds the application size limit.");
            return;
        }
        JsonNode input;
        try {
            input = reader.readValue(message.getPayload());
        } catch (JacksonException exception) {
            error(session, "INVALID_MESSAGE", "Expected one JSON object with a string type.");
            return;
        }
        if (input == null || !input.isObject() || !input.path("type").isString()) {
            error(session, "INVALID_MESSAGE", "Expected one JSON object with a string type.");
            return;
        }
        switch (input.path("type").asString()) {
            case "create_session" -> create(connection, input);
            case "resume_session" -> resume(connection, input);
            case "start_game" -> start(connection);
            case "hand_sample", "no_hand" -> sample(connection, input);
            default -> error(session, "UNKNOWN_TYPE", "Unknown message type.");
        }
    }

    private void start(SessionConnection connection) throws IOException {
        var game = connection.game;
        if (game == null) {
            connection.send(ErrorMessage.of("SESSION_REQUIRED", "Create or resume a session first."));
            return;
        }
        synchronized (game) {
            var now = clock.instant();
            if (!connections.checkActive(game, connection, now)) return;
            if (!sessions.start(game, now)) {
                connection.send(ErrorMessage.of("GAME_ALREADY_STARTED", "This session already started a game."));
                return;
            }
            connection.send(GameStateMessage.from(game, now));
        }
    }

    private void sample(SessionConnection connection, JsonNode input) throws IOException {
        var game = connection.game;
        if (game == null) {
            error(connection.socket, "SESSION_REQUIRED", "Create or resume a session first.");
            return;
        }
        SampleMessage sample;
        Attempt attempt;
        synchronized (game) {
            var now = clock.instant();
            if (!connections.checkActive(game, connection, now)) return;
            if (game.result() != null) {
                connection.send(ErrorMessage.of("GAME_FINISHED", "This game has finished."));
                return;
            }
            var state = game.state(now);
            try {
                Long activePositionId = state == null ? null : state.positionId();
                sample = samples.validate(input, activePositionId);
            } catch (IllegalArgumentException exception) {
                error(connection.socket, "INVALID_SAMPLE", exception.getMessage());
                return;
            }
            if (!connection.acceptSample(sample)) return;
            long sampleNanos = connections.sampleTime();
            if (properties.diagnosticMode()) {
                try {
                    connection.send(SampleAckMessage.of(sample.seq()));
                } catch (IOException | RuntimeException exception) {
                    connections.closeAfterTransportError(connection.socket.getId());
                    throw exception;
                }
            }
            if (state == null) return;
            attempt = connections.acceptGameSample(game, sample, now, sampleNanos);
        }
        if (attempt != null) connections.predict(game, connection, sample, attempt);
    }

    private void create(SessionConnection connection, JsonNode input) throws IOException {
        if (alreadyBound(connection)) return;
        if (!input.path("nickname").isString() || !input.path("selectedHand").isString()) {
            error(connection.socket, "INVALID_SESSION", "Expected nickname and selectedHand strings.");
            return;
        }
        GameSession game;
        try {
            game = sessions.create(input.path("nickname").asString(),
                    SelectedHand.valueOf(input.path("selectedHand").asString()));
        } catch (IllegalArgumentException exception) {
            error(connection.socket, "INVALID_SESSION", "Nickname or selectedHand is invalid.");
            return;
        }
        connections.bind(connection, game);
    }

    private void resume(SessionConnection connection, JsonNode input) throws IOException {
        if (alreadyBound(connection)) return;
        UUID id;
        try {
            if (!input.path("sessionId").isString()) throw new IllegalArgumentException();
            String value = input.path("sessionId").asString();
            id = UUID.fromString(value);
            if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            error(connection.socket, "INVALID_SESSION", "Expected a UUID sessionId.");
            return;
        }
        var game = sessions.find(id);
        if (game.isEmpty()) {
            connection.socket.close(CloseStatus.POLICY_VIOLATION.withReason("Unknown or expired session"));
            return;
        }
        connections.bind(connection, game.orElseThrow());
    }

    private boolean alreadyBound(SessionConnection connection) throws IOException {
        if (connection.game == null) return false;
        error(connection.socket, "SESSION_ALREADY_BOUND", "This connection already has a session.");
        return true;
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws IOException {
        var connection = connections.findUsable(session.getId());
        if (connection != null) {
            error(session, "INVALID_MESSAGE", "Expected a text JSON message.");
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        connections.closeAfterTransportError(session.getId());
    }

    private void error(WebSocketSession session, String code, String message) throws IOException {
        var connection = connections.find(session.getId());
        if (connection != null) connection.send(ErrorMessage.of(code, message));
    }

}
