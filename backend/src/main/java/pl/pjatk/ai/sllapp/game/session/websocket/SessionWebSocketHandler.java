package pl.pjatk.ai.sllapp.game.session.websocket;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import pl.pjatk.ai.sllapp.game.session.GameSession;
import pl.pjatk.ai.sllapp.game.session.SelectedHand;
import pl.pjatk.ai.sllapp.game.session.SessionRegistry;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SessionWebSocketHandler extends AbstractWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(SessionWebSocketHandler.class);
    private final ObjectMapper mapper;
    private final ObjectReader reader;
    private final SessionWebSocketProperties properties;
    private final SessionRegistry sessions;
    private final SampleMessageValidator samples;
    private final ConcurrentHashMap<String, SessionConnection> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, SessionConnection> owners = new ConcurrentHashMap<>();

    SessionWebSocketHandler(ObjectMapper mapper, SessionWebSocketProperties properties, SessionRegistry sessions,
                            SampleMessageValidator samples) {
        this.mapper = mapper;
        this.reader = mapper.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.properties = properties;
        this.sessions = sessions;
        this.samples = samples;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        connections.put(session.getId(), new SessionConnection(session));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        var connection = connections.remove(session.getId());
        if (connection != null && connection.game != null) {
            synchronized (connection.game) {
                owners.remove(connection.game.sessionId(), connection);
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        var connection = connections.get(session.getId());
        if (connection == null || !isUsable(connection)) return;
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
            case "hand_sample", "no_hand" -> sample(connection, input);
            default -> error(session, "UNKNOWN_TYPE", "Unknown message type.");
        }
    }

    private void sample(SessionConnection connection, JsonNode input) throws IOException {
        var game = connection.game;
        if (game == null) {
            error(connection.socket, "SESSION_REQUIRED", "Create or resume a session first.");
            return;
        }
        synchronized (game) {
            if (owners.get(game.sessionId()) != connection) return;
            if (!sessions.isActive(game)) {
                connection.socket.close(CloseStatus.POLICY_VIOLATION.withReason("Session expired"));
                return;
            }
            SampleMessage sample;
            try {
                sample = samples.validate(input);
            } catch (IllegalArgumentException exception) {
                error(connection.socket, "INVALID_SAMPLE", exception.getMessage());
                return;
            }
            if (sample.seq() <= connection.lastSeq) return;
            connection.lastSeq = sample.seq();
            connection.lastSample = sample;
            if (properties.diagnosticMode()) send(connection.socket, new SampleAckMessage("sample_ack", sample.seq()));
        }
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
        bind(connection, game);
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
        bind(connection, game.orElseThrow());
    }

    private boolean alreadyBound(SessionConnection connection) throws IOException {
        if (connection.game == null) return false;
        error(connection.socket, "SESSION_ALREADY_BOUND", "This connection already has a session.");
        return true;
    }

    private void bind(SessionConnection connection, GameSession game) throws IOException {
        SessionConnection previous = null;
        try {
            synchronized (game) {
                if (!sessions.isActive(game)) {
                    connection.socket.close(CloseStatus.POLICY_VIOLATION.withReason("Session expired"));
                    return;
                }
                connection.game = game;
                if (connections.get(connection.socket.getId()) != connection || !connection.socket.isOpen()) return;
                previous = owners.put(game.sessionId(), connection);
                send(connection.socket, GameStateMessage.from(game));
            }
        } finally {
            if (previous != null) closeSafely(previous, CloseStatus.NORMAL.withReason("Connection replaced"));
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws IOException {
        var connection = connections.get(session.getId());
        if (connection != null && isUsable(connection)) {
            error(session, "INVALID_MESSAGE", "Expected a text JSON message.");
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        var connection = connections.get(session.getId());
        if (connection != null) closeSafely(connection, CloseStatus.SERVER_ERROR);
        afterConnectionClosed(session, CloseStatus.SERVER_ERROR);
    }

    private boolean isUsable(SessionConnection connection) {
        var game = connection.game;
        if (game == null) return true;
        synchronized (game) {
            if (owners.get(game.sessionId()) != connection) return false;
            if (sessions.isActive(game)) return true;
            closeSafely(connection, CloseStatus.POLICY_VIOLATION.withReason("Session expired"));
            return false;
        }
    }

    @Scheduled(fixedDelay = 1000)
    void expireSessions() {
        for (var game : sessions.removeExpired()) {
            SessionConnection connection;
            synchronized (game) {
                connection = owners.remove(game.sessionId());
            }
            if (connection != null) closeSafely(connection, CloseStatus.POLICY_VIOLATION.withReason("Session expired"));
        }
    }

    private void closeSafely(SessionConnection connection, CloseStatus status) {
        try {
            connection.socket.close(status);
        } catch (IOException exception) {
            log.warn("Could not close WebSocket {}", connection.socket.getId(), exception);
        }
    }

    private void error(WebSocketSession session, String code, String message) throws IOException {
        var connection = connections.get(session.getId());
        if (connection != null) send(connection.socket, new ErrorMessage("error", code, message));
    }

    private void send(WebSocketSession session, Object message) throws IOException {
        if (session.isOpen()) session.sendMessage(new TextMessage(mapper.writeValueAsString(message)));
    }

    private record ErrorMessage(String type, String code, String message) {}
    private record SampleAckMessage(String type, long seq) {}
}
