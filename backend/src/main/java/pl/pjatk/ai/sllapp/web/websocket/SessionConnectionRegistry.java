package pl.pjatk.ai.sllapp.web.websocket;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import pl.pjatk.ai.sllapp.model.GameSession;
import pl.pjatk.ai.sllapp.service.SessionService;
import pl.pjatk.ai.sllapp.web.websocket.message.GameStateMessage;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SessionConnectionRegistry {
    static final CloseStatus SESSION_EXPIRED = CloseStatus.POLICY_VIOLATION.withReason("Session expired");

    private static final Logger log = LoggerFactory.getLogger(SessionConnectionRegistry.class);
    private final SessionService sessions;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, SessionConnection> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, SessionConnection> owners = new ConcurrentHashMap<>();

    SessionConnectionRegistry(SessionService sessions, ObjectMapper mapper) {
        this.sessions = sessions;
        this.mapper = mapper;
    }

    void register(WebSocketSession socket) {
        connections.put(socket.getId(), new SessionConnection(socket, mapper));
    }

    SessionConnection find(String socketId) {
        return connections.get(socketId);
    }

    SessionConnection findUsable(String socketId) {
        var connection = find(socketId);
        return connection != null && isUsable(connection) ? connection : null;
    }

    void remove(String socketId) {
        var connection = connections.remove(socketId);
        var game = connection == null ? null : connection.game;
        if (game != null) {
            synchronized (game) {
                owners.remove(game.sessionId(), connection);
            }
        }
    }

    void closeAfterTransportError(String socketId) {
        var connection = find(socketId);
        if (connection != null) {
            closeSafely(connection, CloseStatus.SERVER_ERROR);
        }
        remove(socketId);
    }

    // Callers that act on ownership hold the game session monitor until the operation finishes.
    boolean isOwner(GameSession game, SessionConnection connection) {
        return owners.get(game.sessionId()) == connection;
    }

    void bind(SessionConnection connection, GameSession game) throws IOException {
        SessionConnection previous = null;
        try {
            synchronized (game) {
                if (!sessions.isActive(game)) {
                    connection.socket.close(SESSION_EXPIRED);
                    return;
                }
                connection.game = game;
                if (connections.get(connection.socket.getId()) != connection || !connection.socket.isOpen()) return;
                previous = owners.put(game.sessionId(), connection);
                connection.send(GameStateMessage.from(game));
            }
        } finally {
            if (previous != null) closeSafely(previous, CloseStatus.NORMAL.withReason("Connection replaced"));
        }
    }

    private boolean isUsable(SessionConnection connection) {
        var game = connection.game;
        if (game == null) return true;
        synchronized (game) {
            if (!isOwner(game, connection)) return false;
            if (sessions.isActive(game)) return true;
            closeSafely(connection, SESSION_EXPIRED);
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
            if (connection != null) closeSafely(connection, SESSION_EXPIRED);
        }
    }

    private void closeSafely(SessionConnection connection, CloseStatus status) {
        try {
            connection.socket.close(status);
        } catch (IOException exception) {
            log.warn("Could not close WebSocket {}", connection.socket.getId(), exception);
        }
    }
}
