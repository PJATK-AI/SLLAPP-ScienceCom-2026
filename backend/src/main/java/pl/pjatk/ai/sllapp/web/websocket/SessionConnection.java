package pl.pjatk.ai.sllapp.web.websocket;

import java.io.IOException;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import pl.pjatk.ai.sllapp.model.GameSession;
import pl.pjatk.ai.sllapp.web.websocket.message.SampleMessage;
import tools.jackson.databind.ObjectMapper;

final class SessionConnection {
    final WebSocketSession socket;
    volatile GameSession game;
    private final ObjectMapper mapper;
    // Accessed while holding the game session monitor.
    private SampleMessage lastSample;

    SessionConnection(WebSocketSession socket, ObjectMapper mapper) {
        this.socket = new ConcurrentWebSocketSessionDecorator(socket, 5000, 65536);
        this.mapper = mapper;
    }

    boolean acceptSample(SampleMessage sample) {
        if (lastSample != null && sample.seq() <= lastSample.seq()) {
            return false;
        }
        lastSample = sample;
        return true;
    }

    void send(Object message) throws IOException {
        if (socket.isOpen()) {
            socket.sendMessage(new TextMessage(mapper.writeValueAsString(message)));
        }
    }
}
