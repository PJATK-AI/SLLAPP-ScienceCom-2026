package pl.pjatk.ai.sllapp.web.websocket;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
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
public class SessionConnectionRegistry {
    static final CloseStatus SESSION_EXPIRED = CloseStatus.POLICY_VIOLATION.withReason("Session expired");

    private static final Logger log = LoggerFactory.getLogger(SessionConnectionRegistry.class);
    private final SessionService sessions;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ConcurrentHashMap<String, SessionConnection> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, SessionConnection> owners = new ConcurrentHashMap<>();

    SessionConnectionRegistry(SessionService sessions, ObjectMapper mapper, Clock clock) {
        this.sessions = sessions;
        this.mapper = mapper;
        this.clock = clock;
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
        return owners.get(game.sessionId()) == connection && connections.get(connection.socket.getId()) == connection;
    }

    boolean checkActive(GameSession game, SessionConnection connection, Instant now) {
        if (!isOwner(game, connection)) return false;
        if (finishIfDue(game, now) && !isOwner(game, connection)) return false;
        if (sessions.isActive(game, now)) return true;
        closeSafely(connection, SESSION_EXPIRED);
        return false;
    }

    void bind(SessionConnection connection, GameSession game) throws IOException {
        SessionConnection previous = null;
        try {
            synchronized (game) {
                var now = clock.instant();
                finishIfDue(game, now);
                if (!sessions.isActive(game, now)) {
                    connection.socket.close(SESSION_EXPIRED);
                    return;
                }
                connection.game = game;
                if (connections.get(connection.socket.getId()) != connection || !connection.socket.isOpen()) return;
                previous = owners.put(game.sessionId(), connection);
                connection.send(GameStateMessage.from(game, now));
            }
        } finally {
            if (previous != null) closeSafely(previous, CloseStatus.NORMAL.withReason("Connection replaced"));
        }
    }

    /** Applies a server-confirmed completion to the session's current position. */
    public boolean completePosition(GameSession game, long positionId) {
        synchronized (game) {
            if (!sessions.contains(game)) return false;
            var now = clock.instant();
            finishIfDue(game, now);
            if (!game.completePosition(positionId, now)) return false;
            publishState(game, now);
            return true;
        }
    }

    private void publishState(GameSession game, Instant now) {
        var connection = owners.get(game.sessionId());
        if (connection == null || !isOwner(game, connection)) return;
        try {
            connection.send(GameStateMessage.from(game, now));
        } catch (IOException | RuntimeException exception) {
            log.warn("Could not send game state to WebSocket {}", connection.socket.getId(), exception);
            closeAfterTransportError(connection.socket.getId());
        }
    }

    private boolean isUsable(SessionConnection connection) {
        var game = connection.game;
        if (game == null) return true;
        synchronized (game) {
            return checkActive(game, connection, clock.instant());
        }
    }

    @Scheduled(fixedDelay = 1000)
    void expireSessions() {
        for (var game : sessions.all()) {
            SessionConnection connection;
            synchronized (game) {
                var now = clock.instant();
                if (!finishIfDue(game, now)) sessions.saveResult(game);
                if (now.isBefore(game.expiresAt())) continue;
                sessions.removeExpired(game, now);
                connection = owners.remove(game.sessionId());
            }
            if (connection != null) closeSafely(connection, SESSION_EXPIRED);
        }
    }

    private boolean finishIfDue(GameSession game, Instant now) {
        if (!game.finishIfDue(now)) return false;
        sessions.saveResult(game);
        publishState(game, now);
        return true;
    }

    private void closeSafely(SessionConnection connection, CloseStatus status) {
        try {
            connection.socket.close(status);
        } catch (IOException | RuntimeException exception) {
            log.warn("Could not close WebSocket {}", connection.socket.getId(), exception);
        }
    }
}
