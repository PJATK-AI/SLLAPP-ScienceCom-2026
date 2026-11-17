package pl.pjatk.ai.sllapp.config.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("sllapp.websocket")
public record SessionWebSocketProperties(
        @DefaultValue("65536") int maxMessageBytes,
        @DefaultValue("262144") int transportMaxMessageBytes,
        @DefaultValue("false") boolean diagnosticMode) {
    public SessionWebSocketProperties {
        if (maxMessageBytes < 1 || transportMaxMessageBytes <= maxMessageBytes) {
            throw new IllegalArgumentException("WebSocket limits must be positive and transport limit must exceed message limit");
        }
    }
}
