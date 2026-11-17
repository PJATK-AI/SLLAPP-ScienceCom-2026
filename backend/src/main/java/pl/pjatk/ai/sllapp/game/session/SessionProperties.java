package pl.pjatk.ai.sllapp.game.session;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("sllapp.session")
public record SessionProperties(
        @DefaultValue("5m") Duration creationTtl,
        @DefaultValue("classpath:moderation/blocked-words.txt") String blockedWordsLocation) {
    public SessionProperties {
        if (creationTtl.isNegative() || creationTtl.isZero()) {
            throw new IllegalArgumentException("Session TTL must be positive");
        }
        if (blockedWordsLocation == null || blockedWordsLocation.isBlank()
                || !(blockedWordsLocation.startsWith("classpath:") || blockedWordsLocation.startsWith("file:"))) {
            throw new IllegalArgumentException("Blocked words location must use classpath: or file:");
        }
    }
}
