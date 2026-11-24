package pl.pjatk.ai.sllapp.web.websocket;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import pl.pjatk.ai.sllapp.ai.AiPredictionClient;
import pl.pjatk.ai.sllapp.config.moderation.ModerationProperties;
import pl.pjatk.ai.sllapp.config.session.RecognitionProperties;
import pl.pjatk.ai.sllapp.config.session.SessionProperties;
import pl.pjatk.ai.sllapp.config.web.SessionWebSocketProperties;
import pl.pjatk.ai.sllapp.game.GameRules;
import pl.pjatk.ai.sllapp.model.GameResult;
import pl.pjatk.ai.sllapp.moderation.BlockedWordList;
import pl.pjatk.ai.sllapp.moderation.NicknamePolicy;
import pl.pjatk.ai.sllapp.repository.GameResultRepository;
import pl.pjatk.ai.sllapp.service.SessionService;
import pl.pjatk.ai.sllapp.support.MutableClock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class SessionOwnershipTest {
    private static final TextMessage START = new TextMessage("{\"type\":\"start_game\"}");
    private final ObjectMapper mapper = new ObjectMapper();
    private final MutableClock clock = new MutableClock();
    private final GameResultRepository results = mock(GameResultRepository.class);
    private final SessionService sessions = new SessionService(clock,
            new SessionProperties(Duration.ofMinutes(5), Duration.ofMinutes(1), Duration.ofMinutes(5)),
            GameRules.defaults(),
            new NicknamePolicy(new BlockedWordList(
                    new ModerationProperties("classpath:moderation/test-blocked-words.txt"),
                    new DefaultResourceLoader())), () -> length -> "A".repeat(length), results);
    private final SessionConnectionRegistry connections = new SessionConnectionRegistry(sessions, mapper, clock,
            mock(AiPredictionClient.class), new RecognitionProperties(0.8, 3, Duration.ofMillis(350), 2,
                    Duration.ofMillis(400)),
            Duration.ofMillis(250), System::nanoTime);
    private final SessionWebSocketHandler handler = new SessionWebSocketHandler(mapper,
            new SessionWebSocketProperties(65536, 262144, true), sessions, new SampleMessageValidator(),
            connections, clock);

    @Test
    void ignoresOldMessagesAndLateCloseCallbacksAfterReplacement() throws Exception {
        var old = socket();
        String id = create(old);
        var replacement = socket();
        resume(replacement, id);
        old.messages.clear();
        handler.handleMessage(old.socket, new TextMessage(SampleFixtures.noHand(900)));
        assertThat(old.messages).isEmpty();
        handler.afterConnectionClosed(old.socket, CloseStatus.NORMAL);
        handler.afterConnectionClosed(old.socket, CloseStatus.NORMAL);
        handler.handleMessage(replacement.socket, new TextMessage(SampleFixtures.noHand(0)));
        assertThat(replacement.messages.getLast().path("type").asString()).isEqualTo("sample_ack");
        assertThat(replacement.messages.getLast().path("seq").asLong()).isZero();
    }

    @Test
    void keepsReplacementUsableWhenClosingOldSocketFails() throws Exception {
        var old = socket();
        String id = create(old);
        doThrow(new IOException("simulated close failure")).when(old.socket).close(any(CloseStatus.class));
        var replacement = socket();
        resume(replacement, id);
        handler.handleMessage(replacement.socket, new TextMessage(SampleFixtures.noHand(0)));
        assertThat(replacement.messages.getLast().path("type").asString()).isEqualTo("sample_ack");
    }

    @Test
    void acceptsSamplesFromOnlyOneOwnerAfterConcurrentResumes() throws Exception {
        String id = create(socket());
        var candidates = new CopyOnWriteArrayList<RecordedSocket>();
        for (int i = 0; i < 8; i++) candidates.add(socket());
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(8)) {
            var tasks = IntStream.range(0, 8).mapToObj(index -> workers.submit(() -> {
                if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                resume(candidates.get(index), id);
                return null;
            })).toList();
            start.countDown();
            for (var task : tasks) task.get(5, TimeUnit.SECONDS);
        }
        for (var candidate : candidates) {
            handler.handleMessage(candidate.socket, new TextMessage(SampleFixtures.noHand(0)));
        }
        long acknowledgements = candidates.stream().flatMap(socket -> socket.messages.stream())
                .filter(message -> message.path("type").asString().equals("sample_ack")).count();
        assertThat(acknowledgements).isEqualTo(1);
    }

    @Test
    void startsAtMostOnceWhenTwoMessagesRace() throws Exception {
        var current = socket();
        create(current);
        var gate = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var tasks = IntStream.range(0, 2).mapToObj(index -> workers.submit(() -> {
                if (!gate.await(5, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                handler.handleMessage(current.socket, START);
                return null;
            })).toList();
            gate.countDown();
            for (var task : tasks) task.get(5, TimeUnit.SECONDS);
        }
        assertThat(current.messages).hasSize(3);
        assertThat(current.messages.get(1).path("status").asString()).isEqualTo("RUNNING");
        assertThat(current.messages.get(2).path("code").asString()).isEqualTo("GAME_ALREADY_STARTED");
    }

    @Test
    void persistsTheFinalResultEvenWhenTheSocketSendFails() throws Exception {
        var first = socket();
        var id = create(first);
        handler.handleMessage(first.socket, START);
        var game = sessions.find(UUID.fromString(id)).orElseThrow();
        assertThat(connections.completePosition(game, 1)).isTrue();
        doThrow(new IOException("simulated send failure")).when(first.socket).sendMessage(any());

        clock.advance(Duration.ofMinutes(1));
        connections.expireSessions();
        connections.expireSessions();
        verify(results).save(new GameResult(id, "Ola", 10, clock.instant()));

        var replacement = socket();
        resume(replacement, id);
        assertThat(replacement.messages.getFirst().path("status").asString()).isEqualTo("FINISHED");
        assertThat(replacement.messages.getFirst().path("score").intValue()).isEqualTo(10);
    }

    private String create(RecordedSocket socket) throws Exception {
        handler.handleMessage(socket.socket,
                new TextMessage("{\"type\":\"create_session\",\"nickname\":\"Ola\",\"selectedHand\":\"LEFT\"}"));
        return socket.messages.getFirst().path("sessionId").asString();
    }

    private void resume(RecordedSocket socket, String id) throws Exception {
        handler.handleMessage(socket.socket, new TextMessage("{\"type\":\"resume_session\",\"sessionId\":\"" + id + "\"}"));
    }

    private RecordedSocket socket() throws Exception {
        var socket = mock(WebSocketSession.class, withSettings().mockMaker(MockMakers.PROXY));
        var open = new AtomicBoolean(true);
        var messages = new CopyOnWriteArrayList<JsonNode>();
        when(socket.getId()).thenReturn(UUID.randomUUID().toString());
        when(socket.isOpen()).thenAnswer(invocation -> open.get());
        doAnswer(invocation -> {
            messages.add(mapper.readTree(((TextMessage) invocation.getArgument(0)).getPayload()));
            return null;
        }).when(socket).sendMessage(any());
        doAnswer(invocation -> { open.set(false); return null; }).when(socket).close(any(CloseStatus.class));
        handler.afterConnectionEstablished(socket);
        return new RecordedSocket(socket, messages);
    }

    private record RecordedSocket(WebSocketSession socket, List<JsonNode> messages) {}
}
