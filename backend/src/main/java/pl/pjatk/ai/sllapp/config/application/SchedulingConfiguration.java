package pl.pjatk.ai.sllapp.config.application;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfiguration {
}
