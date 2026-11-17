package pl.pjatk.ai.sllapp.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import pl.pjatk.ai.sllapp.moderation.NicknamePolicy;
import pl.pjatk.ai.sllapp.config.session.SessionProperties;
import pl.pjatk.ai.sllapp.model.GameSession;
import pl.pjatk.ai.sllapp.model.SelectedHand;

@Service
public class SessionService {
    private final ConcurrentHashMap<UUID, GameSession> sessions = new ConcurrentHashMap<>();
    private final Clock clock;
    private final SessionProperties properties;
    private final NicknamePolicy nicknames;

    public SessionService(Clock clock, SessionProperties properties, NicknamePolicy nicknames) {
        this.clock = clock;
        this.properties = properties;
        this.nicknames = nicknames;
    }

    public GameSession create(String nickname, SelectedHand selectedHand) {
        String validated = nicknames.validate(nickname);
        if (selectedHand == null) throw new IllegalArgumentException("Selected hand is required.");
        var now = clock.instant();
        var session = new GameSession(UUID.randomUUID(), validated, selectedHand, now, now.plus(properties.creationTtl()));
        sessions.put(session.sessionId(), session);
        return session;
    }

    public Optional<GameSession> find(UUID id) {
        return Optional.ofNullable(sessions.get(id)).filter(this::isActive);
    }

    public boolean isActive(GameSession session) {
        return clock.instant().isBefore(session.expiresAt()) && sessions.get(session.sessionId()) == session;
    }

    public List<GameSession> removeExpired() {
        var expired = new ArrayList<GameSession>();
        for (var session : sessions.values()) {
            synchronized (session) {
                if (!clock.instant().isBefore(session.expiresAt()) && sessions.remove(session.sessionId(), session)) {
                    expired.add(session);
                }
            }
        }
        return List.copyOf(expired);
    }
}
