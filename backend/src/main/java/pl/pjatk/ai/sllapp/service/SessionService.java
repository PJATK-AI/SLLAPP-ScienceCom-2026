package pl.pjatk.ai.sllapp.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import pl.pjatk.ai.sllapp.config.session.SessionProperties;
import pl.pjatk.ai.sllapp.game.GameRules;
import pl.pjatk.ai.sllapp.game.WordPicker;
import pl.pjatk.ai.sllapp.model.GameSession;
import pl.pjatk.ai.sllapp.model.SelectedHand;
import pl.pjatk.ai.sllapp.moderation.NicknamePolicy;
import pl.pjatk.ai.sllapp.repository.GameResultRepository;

@Service
public class SessionService {
    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    private final ConcurrentHashMap<UUID, GameSession> sessions = new ConcurrentHashMap<>();
    private final Clock clock;
    private final SessionProperties properties;
    private final GameRules gameRules;
    private final NicknamePolicy nicknames;
    private final Supplier<WordPicker> wordPickers;
    private final GameResultRepository results;

    public SessionService(Clock clock, SessionProperties properties, GameRules gameRules, NicknamePolicy nicknames,
                          @Qualifier("sessionWordPickerFactory") Supplier<WordPicker> wordPickers,
                          GameResultRepository results) {
        this.clock = clock;
        this.properties = properties;
        this.gameRules = gameRules;
        this.nicknames = nicknames;
        this.wordPickers = wordPickers;
        this.results = results;
    }

    public GameSession create(String nickname, SelectedHand selectedHand) {
        return create(nickname, selectedHand, false);
    }

    public GameSession create(String nickname, SelectedHand selectedHand, boolean confirmExistingNickname) {
        String validated = nicknames.validate(nickname);
        if (selectedHand == null) throw new IllegalArgumentException("Selected hand is required.");
        var storedNickname = results.findNickname(validated);
        if (storedNickname.isPresent() && !confirmExistingNickname) throw new NicknameExistsException();
        var now = clock.instant();
        var session = new GameSession(UUID.randomUUID(), storedNickname.orElse(validated), selectedHand,
                now, now.plus(properties.creationTtl()));
        sessions.put(session.sessionId(), session);
        return session;
    }

    public Optional<GameSession> find(UUID id) {
        var session = sessions.get(id);
        if (session == null) return Optional.empty();
        synchronized (session) {
            return isActive(session, clock.instant()) ? Optional.of(session) : Optional.empty();
        }
    }

    public boolean isActive(GameSession session, Instant now) {
        return contains(session) && now.isBefore(session.expiresAt());
    }

    public boolean contains(GameSession session) {
        return sessions.get(session.sessionId()) == session;
    }

    public boolean start(GameSession session, Instant now) {
        return isActive(session, now) && session.start(now, properties.gameDuration(), properties.finishedTtl(),
                gameRules, wordPickers);
    }

    public List<GameSession> all() {
        return List.copyOf(sessions.values());
    }

    /** Called under the session monitor after reconciling the game deadline. */
    public void removeExpired(GameSession session, Instant now) {
        if (!now.isBefore(session.expiresAt()) && !session.hasPendingResult()) {
            sessions.remove(session.sessionId(), session);
        }
    }

    /** A completed write settles the round even when the existing record wins. */
    public void saveResult(GameSession session) {
        if (!session.hasPendingResult()) return;
        try {
            results.save(session.result());
            session.markResultSaved();
        } catch (DataAccessException exception) {
            log.warn("Could not save the result for session {}. The next sweep will retry.",
                    session.sessionId(), exception);
        }
    }

    public static final class NicknameExistsException extends RuntimeException {
    }
}
