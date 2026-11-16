package pl.pjatk.ai.sllapp.game.session;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SessionRegistryTest {
    @Test
    void createsDistinctSessionsWithFixedExpiry() {
        var now = Instant.parse("2026-11-16T12:00:00Z");
        var properties = new SessionProperties(Duration.ofMinutes(5), List.of());
        var registry = new SessionRegistry(Clock.fixed(now, ZoneOffset.UTC), properties, new NicknamePolicy(properties));
        var session = registry.create(" Ola ", SelectedHand.RIGHT);
        assertThat(session.nickname()).isEqualTo("Ola");
        assertThat(session.createdAt()).isEqualTo(now);
        assertThat(session.expiresAt()).isEqualTo(now.plusSeconds(300));
        assertThat(registry.find(session.sessionId())).contains(session);
        assertThat(registry.create("Ola", SelectedHand.RIGHT).sessionId()).isNotEqualTo(session.sessionId());
        assertThat(registry.find(UUID.randomUUID())).isEmpty();
    }
}
