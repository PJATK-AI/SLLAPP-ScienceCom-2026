package pl.pjatk.ai.sllapp.game.session.websocket;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SessionWebSocketHandler extends AbstractWebSocketHandler {
    private final ObjectMapper mapper;
    private final ObjectReader reader;
    private final SessionWebSocketProperties properties;

    SessionWebSocketHandler(ObjectMapper mapper, SessionWebSocketProperties properties) {
        this.mapper = mapper;
        this.reader = mapper.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.properties = properties;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        if (message.getPayload().getBytes(StandardCharsets.UTF_8).length > properties.maxMessageBytes()) {
            error(session, "MESSAGE_TOO_LARGE", "Message exceeds the application size limit.");
            return;
        }
        JsonNode input;
        try {
            input = reader.readValue(message.getPayload());
        } catch (JacksonException exception) {
            error(session, "INVALID_MESSAGE", "Expected one JSON object with a string type.");
            return;
        }
        if (input == null || !input.isObject() || !input.path("type").isString()) {
            error(session, "INVALID_MESSAGE", "Expected one JSON object with a string type.");
            return;
        }
        error(session, "UNKNOWN_TYPE", "Unknown message type.");
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws IOException {
        error(session, "INVALID_MESSAGE", "Expected a text JSON message.");
    }

    private void error(WebSocketSession session, String code, String message) throws IOException {
        synchronized (session) {
            session.sendMessage(new TextMessage(mapper.writeValueAsString(new ErrorMessage("error", code, message))));
        }
    }

    private record ErrorMessage(String type, String code, String message) {}
}
