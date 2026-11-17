package pl.pjatk.ai.sllapp.config.moderation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ModerationProperties.class)
class ModerationConfiguration {
}
