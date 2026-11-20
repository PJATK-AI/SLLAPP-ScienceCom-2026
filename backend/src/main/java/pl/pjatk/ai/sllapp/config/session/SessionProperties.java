package pl.pjatk.ai.sllapp.config.session;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("sllapp.session")
public record SessionProperties(@DefaultValue("5m") Duration creationTtl,
                                @DefaultValue("60s") Duration gameDuration,
                                @DefaultValue("5m") Duration finishedTtl) {
    public SessionProperties {
        requirePositive(creationTtl, "creation-ttl");
        requirePositive(gameDuration, "game-duration");
        requirePositive(finishedTtl, "finished-ttl");
    }

    private static void requirePositive(Duration value, String property) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(property + " must be positive");
        }
    }
}
