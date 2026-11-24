package pl.pjatk.ai.sllapp.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import pl.pjatk.ai.sllapp.config.moderation.ModerationProperties;
import pl.pjatk.ai.sllapp.config.session.SessionProperties;
import pl.pjatk.ai.sllapp.game.GameRules;
import pl.pjatk.ai.sllapp.model.SelectedHand;
import pl.pjatk.ai.sllapp.moderation.BlockedWordList;
import pl.pjatk.ai.sllapp.moderation.NicknamePolicy;
import pl.pjatk.ai.sllapp.repository.GameResultRepository;
import pl.pjatk.ai.sllapp.support.MutableClock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;

class SessionServiceTest {
    private final SessionProperties properties = new SessionProperties(
            Duration.ofMinutes(5), Duration.ofMinutes(1), Duration.ofMinutes(5));
    private final NicknamePolicy nicknames = new NicknamePolicy(new BlockedWordList(
            new ModerationProperties("classpath:moderation/test-blocked-words.txt"), new DefaultResourceLoader()));

    @Test
    void expiresAtTheExactDeadlineAndRemovesOnlyExpiredSessions() {
        var clock = new MutableClock();
        var sessions = sessions(clock);
        var first = sessions.create("Ola", SelectedHand.LEFT);
        clock.advance(Duration.ofSeconds(299));
        assertThat(sessions.find(first.sessionId())).contains(first);
        synchronized (first) {
            sessions.removeExpired(first, clock.instant());
        }
        assertThat(sessions.all()).containsExactly(first);
        var second = sessions.create("Jan", SelectedHand.RIGHT);
        clock.advance(Duration.ofSeconds(1));
        assertThat(sessions.find(first.sessionId())).isEmpty();
        assertThat(sessions.find(second.sessionId())).contains(second);
        for (var session : sessions.all()) {
            synchronized (session) {
                sessions.removeExpired(session, clock.instant());
            }
        }
        assertThat(sessions.all()).containsExactly(second);
    }

    @Test
    void createsDistinctSessionsWithFixedExpiry() {
        var now = Instant.parse("2026-11-16T12:00:00Z");
        var sessions = sessions(Clock.fixed(now, ZoneOffset.UTC));
        var session = sessions.create(" Ola ", SelectedHand.RIGHT);
        assertThat(session.nickname()).isEqualTo("Ola");
        assertThat(session.createdAt()).isEqualTo(now);
        assertThat(session.expiresAt()).isEqualTo(now.plusSeconds(300));
        assertThat(sessions.find(session.sessionId())).contains(session);
        assertThat(sessions.create("Ola", SelectedHand.RIGHT).sessionId()).isNotEqualTo(session.sessionId());
        assertThat(sessions.find(UUID.randomUUID())).isEmpty();
    }

    @Test
    void rejectsNonpositiveSessionDurations() {
        var valid = Duration.ofSeconds(1);
        for (var invalid : new Duration[]{Duration.ZERO, Duration.ofNanos(-1)}) {
            assertThatIllegalArgumentException().isThrownBy(() -> new SessionProperties(invalid, valid, valid));
            assertThatIllegalArgumentException().isThrownBy(() -> new SessionProperties(valid, invalid, valid));
            assertThatIllegalArgumentException().isThrownBy(() -> new SessionProperties(valid, valid, invalid));
        }
    }

    private SessionService sessions(Clock clock) {
        return new SessionService(clock, properties, GameRules.defaults(), nicknames,
                () -> length -> "A".repeat(length), mock(GameResultRepository.class));
    }
}
