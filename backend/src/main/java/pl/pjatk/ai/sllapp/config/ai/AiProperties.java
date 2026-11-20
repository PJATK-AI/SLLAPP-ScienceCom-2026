package pl.pjatk.ai.sllapp.config.ai;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("sllapp.ai")
public record AiProperties(@DefaultValue("false") boolean enabled, URI baseUrl,
                           @DefaultValue("250ms") Duration timeout) {
    public AiProperties {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("AI prediction timeout must be positive.");
        }
        if (enabled && !isHttpUrl(baseUrl)) {
            throw new IllegalArgumentException("Enabled AI prediction requires an absolute HTTP(S) base URL.");
        }
    }

    private static boolean isHttpUrl(URI url) {
        return url != null && ("http".equalsIgnoreCase(url.getScheme()) || "https".equalsIgnoreCase(url.getScheme()))
                && url.getHost() != null && url.getPort() != 0 && url.getPort() <= 65535
                && url.getRawUserInfo() == null && url.getRawQuery() == null && url.getRawFragment() == null;
    }
}
