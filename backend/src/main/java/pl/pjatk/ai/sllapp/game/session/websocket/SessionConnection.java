package pl.pjatk.ai.sllapp.game.session.websocket;

import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import pl.pjatk.ai.sllapp.game.session.GameSession;

final class SessionConnection {
    final WebSocketSession socket;
    volatile GameSession game;

    SessionConnection(WebSocketSession socket) {
        this.socket = new ConcurrentWebSocketSessionDecorator(socket, 5000, 65536);
    }
}
