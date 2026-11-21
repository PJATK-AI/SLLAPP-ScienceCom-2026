package pl.pjatk.ai.sllapp.web.websocket;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import pl.pjatk.ai.sllapp.ai.AiPredictionClient;
import pl.pjatk.ai.sllapp.ai.PredictionResult;
import pl.pjatk.ai.sllapp.config.ai.AiProperties;
import pl.pjatk.ai.sllapp.config.session.RecognitionProperties;
import pl.pjatk.ai.sllapp.model.GameSession;
import pl.pjatk.ai.sllapp.model.LetterRecognition.Attempt;
import pl.pjatk.ai.sllapp.service.SessionService;
import pl.pjatk.ai.sllapp.web.websocket.message.GameStateMessage;
import pl.pjatk.ai.sllapp.web.websocket.message.SampleMessage;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SessionConnectionRegistry {
    static final CloseStatus SESSION_EXPIRED = CloseStatus.POLICY_VIOLATION.withReason("Session expired");

    private static final Logger log = LoggerFactory.getLogger(SessionConnectionRegistry.class);
    private final SessionService sessions;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final AiPredictionClient ai;
    private final RecognitionProperties recognitionRules;
    private final Duration predictionBudget;
    private final LongSupplier nanoTime;
    private final ConcurrentHashMap<String, SessionConnection> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, SessionConnection> owners = new ConcurrentHashMap<>();

    @Autowired
    SessionConnectionRegistry(SessionService sessions, ObjectMapper mapper, Clock clock, AiPredictionClient ai,
                              RecognitionProperties recognitionRules, AiProperties aiProperties) {
        this(sessions, mapper, clock, ai, recognitionRules, aiProperties.timeout(), System::nanoTime);
    }

    SessionConnectionRegistry(SessionService sessions, ObjectMapper mapper, Clock clock, AiPredictionClient ai,
                              RecognitionProperties recognitionRules, Duration predictionBudget, LongSupplier nanoTime) {
        this.sessions = sessions;
        this.mapper = mapper;
        this.clock = clock;
        this.ai = ai;
        this.recognitionRules = recognitionRules;
        this.predictionBudget = predictionBudget;
        this.nanoTime = nanoTime;
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
        var connection = find(socketId);
        if (connection == null) return;
        GameSession game;
        synchronized (connection) {
            game = connection.game;
            if (game == null) {
                connections.remove(socketId, connection);
                return;
            }
        }
        synchronized (game) {
            connections.remove(socketId, connection);
            if (owners.remove(game.sessionId(), connection)) game.recognition().resetInput();
        }
    }

    void closeAfterTransportError(String socketId) {
        var connection = find(socketId);
        remove(socketId);
        if (connection != null) {
            closeSafely(connection, CloseStatus.SERVER_ERROR);
        }
    }

    // Callers that act on ownership hold the game session monitor until the operation finishes.
    boolean isOwner(GameSession game, SessionConnection connection) {
        return owners.get(game.sessionId()) == connection && connections.get(connection.socket.getId()) == connection;
    }

    boolean checkActive(GameSession game, SessionConnection connection, Instant now) {
        if (!isOwner(game, connection) || !connection.socket.isOpen()) return false;
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
                synchronized (connection) {
                    if (connections.get(connection.socket.getId()) != connection || !connection.socket.isOpen()) return;
                    connection.game = game;
                    previous = owners.put(game.sessionId(), connection);
                }
                game.recognition().resetInput();
                connection.send(GameStateMessage.from(game, now));
            }
        } finally {
            if (previous != null) closeSafely(previous, CloseStatus.NORMAL.withReason("Connection replaced"));
        }
    }

    long sampleTime() {
        return nanoTime.getAsLong();
    }

    // Called for a fresh RUNNING sample under the game session monitor.
    Attempt acceptGameSample(GameSession game, SampleMessage sample, Instant now, long sampleNanos) {
        var recognition = game.recognition();
        if (sample.hand() == null) {
            if (recognition.noHand(recognitionRules.releaseNoHandCount())) publishState(game, now);
            return null;
        }
        if (!game.selectedHand().name().equals(sample.hand().path("handedness").asString())) {
            recognition.resetInput();
            return null;
        }
        return recognition.selectedHand(sampleNanos);
    }

    void predict(GameSession game, SessionConnection connection, SampleMessage sample, Attempt attempt) {
        CompletionStage<PredictionResult> response;
        try {
            response = ai.predict(game.sessionId(), sample.hand());
        } catch (RuntimeException exception) {
            log.warn("Could not submit AI prediction for session {}", game.sessionId(), exception);
            applyPrediction(game, connection, sample.positionId(), attempt, null);
            return;
        }
        response.whenComplete((result, error) ->
                applyPrediction(game, connection, sample.positionId(), attempt, error == null ? result : null));
    }

    private void applyPrediction(GameSession game, SessionConnection connection, long positionId,
                                 Attempt attempt, PredictionResult result) {
        synchronized (game) {
            var recognition = game.recognition();
            if (!recognition.finishAttempt(attempt)) return;
            var now = clock.instant();
            if (!checkActive(game, connection, now)) return;
            var state = game.state(now);
            if (state == null || state.positionId() != positionId || recognition.requiresRelease()) return;

            var elapsed = Duration.ofNanos(nanoTime.getAsLong() - attempt.sampleNanos());
            if (elapsed.compareTo(predictionBudget) >= 0) {
                recognition.resetInput();
                return;
            }
            if (recognition.countPrediction(attempt, result, state.expectedLetter(), recognitionRules)) {
                completePosition(game, positionId, now);
            }
        }
    }

    /** Applies a server-confirmed completion to the session's current position. */
    public boolean completePosition(GameSession game, long positionId) {
        synchronized (game) {
            if (!sessions.contains(game)) return false;
            var now = clock.instant();
            finishIfDue(game, now);
            return completePosition(game, positionId, now);
        }
    }

    private boolean completePosition(GameSession game, long positionId, Instant now) {
        if (!game.completePosition(positionId, now)) return false;
        publishState(game, now);
        return true;
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
