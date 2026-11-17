package pl.pjatk.ai.sllapp.config.session;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SessionProperties.class)
class SessionConfiguration {
}
