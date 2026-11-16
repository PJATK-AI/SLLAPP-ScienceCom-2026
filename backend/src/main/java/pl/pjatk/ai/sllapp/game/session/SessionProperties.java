package pl.pjatk.ai.sllapp.game.session;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("sllapp.session")
public record SessionProperties(
        @DefaultValue("5m") Duration creationTtl,
        @DefaultValue({"kurwa", "chuj", "pizda", "jebać", "fuck", "shit"}) List<String> blockedWords) {
    public SessionProperties {
        if (creationTtl.isNegative() || creationTtl.isZero()) {
            throw new IllegalArgumentException("Session TTL must be positive");
        }
        blockedWords = List.copyOf(blockedWords);
    }
}
