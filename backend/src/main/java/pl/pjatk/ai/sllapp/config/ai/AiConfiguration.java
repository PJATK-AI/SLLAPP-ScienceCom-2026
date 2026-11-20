package pl.pjatk.ai.sllapp.config.ai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pl.pjatk.ai.sllapp.ai.AiPredictionClient;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiProperties.class)
class AiConfiguration {
    @Bean
    AiPredictionClient aiPredictionClient(AiProperties properties, ObjectMapper mapper) {
        return new AiPredictionClient(properties, mapper);
    }
}
