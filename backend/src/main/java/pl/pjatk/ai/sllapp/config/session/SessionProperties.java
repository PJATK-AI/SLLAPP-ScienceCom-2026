package pl.pjatk.ai.sllapp.config.session;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("sllapp.session")
public record SessionProperties(Duration creationTtl, Duration gameDuration, Duration finishedTtl) {
    public SessionProperties {
        requirePositive(creationTtl, "creation-ttl");
        requirePositive(gameDuration, "game-duration");
        requirePositive(finishedTtl, "finished-ttl");
    }

    private static void requirePositive(Duration value, String property) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("sllapp.session." + property + " must be positive.");
        }
    }
}
