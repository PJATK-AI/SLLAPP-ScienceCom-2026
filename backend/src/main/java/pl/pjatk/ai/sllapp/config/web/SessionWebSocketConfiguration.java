package pl.pjatk.ai.sllapp.config.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import pl.pjatk.ai.sllapp.web.websocket.SessionWebSocketHandler;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableWebSocket
@EnableConfigurationProperties(SessionWebSocketProperties.class)
class SessionWebSocketConfiguration implements WebSocketConfigurer {
    private final SessionWebSocketHandler handler;
    private final WebProperties web;

    SessionWebSocketConfiguration(SessionWebSocketHandler handler, WebProperties web) {
        this.handler = handler;
        this.web = web;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws").setAllowedOrigins(web.allowedOrigins().toArray(String[]::new));
    }

    @Bean
    ServletServerContainerFactoryBean webSocketContainer(SessionWebSocketProperties properties) {
        var container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(properties.transportMaxMessageBytes());
        container.setMaxBinaryMessageBufferSize(properties.transportMaxMessageBytes());
        return container;
    }
}
