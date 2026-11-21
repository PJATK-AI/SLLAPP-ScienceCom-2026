package pl.pjatk.ai.sllapp.web.websocket;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration(proxyBeanMethods = false)
@Profile("recognition-test")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class RecognitionTestConfiguration implements WebSocketConfigurer, WebMvcConfigurer {
    private final RecognitionTestWebSocket socket;
    private final Path frontend;

    RecognitionTestConfiguration(RecognitionTestWebSocket socket,
                                 @Value("${sllapp.recognition-test.frontend-directory:../frontend}") String frontend) {
        this.socket = socket;
        this.frontend = Path.of(frontend).toAbsolutePath().normalize();
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(socket, "/recognition-test/ws");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/recognition-test/**")
                .addResourceLocations("classpath:/recognition-test/");
        registerAsset(registry, "/recognition-test/tracker/**", "packages/hand-landmarker/dist");
        registerAsset(registry, "/recognition-test/vision/**",
                "packages/hand-landmarker/node_modules/@mediapipe/tasks-vision");
        registerAsset(registry, "/recognition-test/mediapipe/**", "apps/web/public/mediapipe");
    }

    private void registerAsset(ResourceHandlerRegistry registry, String mapping, String directory) {
        var location = frontend.resolve(directory).toUri().toString();
        registry.addResourceHandler(mapping).addResourceLocations(location.endsWith("/") ? location : location + "/");
    }
}
