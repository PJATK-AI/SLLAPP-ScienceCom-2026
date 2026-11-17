package pl.pjatk.ai.sllapp.config.moderation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("sllapp.moderation")
public record ModerationProperties(
        @DefaultValue("classpath:moderation/blocked-words.txt") String blockedWordsLocation) {
    public ModerationProperties {
        if (blockedWordsLocation == null || blockedWordsLocation.isBlank()
                || !(blockedWordsLocation.startsWith("classpath:") || blockedWordsLocation.startsWith("file:"))) {
            throw new IllegalArgumentException("Blocked words location must use classpath: or file:");
        }
    }
}
