package pl.pjatk.ai.sllapp.web.websocket;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

final class WebSocketTestClient implements WebSocket.Listener, AutoCloseable {
    private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
    private final StringBuilder fragments = new StringBuilder();
    final CompletableFuture<Integer> closed = new CompletableFuture<>();
    final WebSocket socket;

    WebSocketTestClient(HttpClient client, int port, String origin) throws Exception {
        var builder = client.newWebSocketBuilder();
        if (origin != null) {
            builder.header("Origin", origin);
        }
        socket = builder.buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws"), this)
                .get(5, TimeUnit.SECONDS);
    }

    void send(String json) throws Exception {
        socket.sendText(json, true).get(5, TimeUnit.SECONDS);
    }

    void binary() throws Exception {
        socket.sendBinary(ByteBuffer.wrap(new byte[]{1, 2}), true).get(5, TimeUnit.SECONDS);
    }

    JsonNode next() throws Exception {
        String message = messages.poll(5, TimeUnit.SECONDS);
        assertThat(message).as("server message").isNotNull();
        return new ObjectMapper().readTree(message);
    }

    void assertSilent() throws Exception {
        assertThat(messages.poll(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        fragments.append(data);
        if (last) {
            messages.add(fragments.toString());
            fragments.setLength(0);
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        closed.complete(statusCode);
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        closed.completeExceptionally(error);
    }

    @Override
    public void close() {
        socket.abort();
    }
}
