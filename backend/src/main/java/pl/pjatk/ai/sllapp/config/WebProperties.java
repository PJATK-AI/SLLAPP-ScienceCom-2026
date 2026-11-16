package pl.pjatk.ai.sllapp.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("sllapp.web")
public record WebProperties(
        @DefaultValue({"http://localhost:5173", "http://127.0.0.1:5173"}) List<String> allowedOrigins) {
    public WebProperties {
        allowedOrigins = List.copyOf(allowedOrigins);
    }
}
