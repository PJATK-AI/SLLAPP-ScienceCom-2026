package pl.pjatk.ai.sllapp.config.session;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("sllapp.session")
public record SessionProperties(@DefaultValue("5m") Duration creationTtl) {
    public SessionProperties {
        if (creationTtl.isNegative() || creationTtl.isZero()) {
            throw new IllegalArgumentException("Session TTL must be positive");
        }
    }
}
