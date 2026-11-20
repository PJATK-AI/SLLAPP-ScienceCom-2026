package pl.pjatk.ai.sllapp.web.websocket;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import pl.pjatk.ai.sllapp.ai.AiPredictionClient;
import pl.pjatk.ai.sllapp.ai.PredictionResult;
import pl.pjatk.ai.sllapp.ai.PredictionResult.Failure;
import pl.pjatk.ai.sllapp.ai.PredictionResult.FailureKind;
import pl.pjatk.ai.sllapp.ai.PredictionResult.Prediction;
import pl.pjatk.ai.sllapp.config.moderation.ModerationProperties;
import pl.pjatk.ai.sllapp.config.session.RecognitionProperties;
import pl.pjatk.ai.sllapp.config.session.SessionProperties;
import pl.pjatk.ai.sllapp.config.web.SessionWebSocketProperties;
import pl.pjatk.ai.sllapp.model.GameResult;
import pl.pjatk.ai.sllapp.model.SelectedHand;
import pl.pjatk.ai.sllapp.moderation.BlockedWordList;
import pl.pjatk.ai.sllapp.moderation.NicknamePolicy;
import pl.pjatk.ai.sllapp.repository.GameResultRepository;
import pl.pjatk.ai.sllapp.service.SessionService;
import pl.pjatk.ai.sllapp.support.MutableClock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionRecognitionTest {
    private static final Duration ROUND = Duration.ofMinutes(1);
    private static final Prediction A = new Prediction('A', 0.80);
    private static final RecognitionProperties DEFAULTS = new RecognitionProperties(0.80, 3, Duration.ofMillis(350), 2);
    private final ObjectMapper mapper = new ObjectMapper();
    private final MutableClock clock = new MutableClock();
    private final AtomicLong sampleNanos = new AtomicLong();
    private final GameResultRepository results = mock(GameResultRepository.class);
    private final AiPredictionClient ai = mock(AiPredictionClient.class);
    private final List<CompletableFuture<PredictionResult>> calls = new ArrayList<>();
    private final SessionService sessions = new SessionService(clock,
            new SessionProperties(Duration.ofMinutes(5), ROUND, Duration.ofMinutes(5)),
            new NicknamePolicy(new BlockedWordList(new ModerationProperties("classpath:moderation/test-blocked-words.txt"),
                    new DefaultResourceLoader())), () -> length -> length == 3 ? "AAB" : "B".repeat(length), results);
    private SessionConnectionRegistry connections;
    private SessionWebSocketHandler handler;
    private UUID id;
    private SelectedHand selectedHand;

    @Test
    void completesAtTheConfidenceAndSampleGapBoundaries() throws Exception {
        var socket = start(DEFAULTS);
        predict(socket, A);
        advance(350);
        socket.hand(1);
        advance(249);
        lastCall().complete(A);
        assertState(socket, 1, 0, false);
        advance(101);
        predict(socket, A);
        assertState(socket, 2, 10, true);
        assertThat(socket.states()).hasSize(3);
        var hand = mapper.readTree(SampleFixtures.hand(0)).path("hand");
        verify(ai, times(3)).predict(id, hand);
    }

    @ParameterizedTest
    @EnumSource(Interruption.class)
    void restartsTheStreakAfterAnInterruption(Interruption interruption) throws Exception {
        var socket = start(DEFAULTS);
        predict(socket, A);
        predict(socket, A);
        switch (interruption) {
            case ANOTHER_LETTER -> predict(socket, new Prediction('B', 1));
            case LOW_CONFIDENCE -> predict(socket, new Prediction('A', Math.nextDown(0.80)));
            case FAILURE -> predict(socket, new Failure(FailureKind.OVERLOADED, null, null));
            case NO_HAND -> socket.noHand(1);
            case OTHER_HAND -> socket.otherHand(1);
            case BUDGET -> {
                socket.hand(1);
                advance(250);
                lastCall().complete(A);
            }
            case EXCEPTIONAL_STAGE -> {
                socket.hand(1);
                lastCall().completeExceptionally(new IOException("AI request failed"));
            }
        }
        assertState(socket, 1, 0, false);
        predict(socket, A);
        predict(socket, A);
        assertState(socket, 1, 0, false);
        predict(socket, A);
        assertState(socket, 2, 10, true);
    }

    @Test
    void startsANewStreakWithTheFirstGoodPredictionAfterAGap() throws Exception {
        var socket = start(DEFAULTS);
        predict(socket, A);
        predict(socket, A);
        advance(351);
        predict(socket, A);
        predict(socket, A);
        assertState(socket, 1, 0, false);
        predict(socket, A);
        assertState(socket, 2, 10, true);
    }

    @Test
    void usesConfiguredConfidenceAndGap() throws Exception {
        var socket = start(new RecognitionProperties(0.90, 2, Duration.ofMillis(100), 2));
        var confident = new Prediction('A', 0.90);
        predict(socket, confident);
        predict(socket, A);
        assertState(socket, 1, 0, false);
        predict(socket, confident);
        advance(101);
        predict(socket, confident);
        assertState(socket, 1, 0, false);
        advance(100);
        predict(socket, confident);
        assertState(socket, 2, 10, true);
    }

    @Test
    void replaysCameraEventsWithMissingHandsAndASampleGap() throws Exception {
        var socket = start(DEFAULTS, SelectedHand.RIGHT);
        try (var input = getClass().getResourceAsStream("/recognition/camera-trace.csv")) {
            var rows = new String(input.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.startsWith("#")).skip(1).toList();
            long previousMs = 0;
            for (String row : rows) {
                var event = row.split(",");
                long timestampMs = Long.parseLong(event[0]);
                long position = socket.states().getLast().path("positionId").longValue();
                advance(timestampMs - previousMs);
                int before = calls.size();
                switch (event[1]) {
                    case "hand_sample" -> {
                        socket.hand(position);
                        if (calls.size() > before) lastCall().complete(A);
                    }
                    case "no_hand" -> {
                        socket.noHand(position);
                        assertThat(calls).hasSize(before);
                    }
                    default -> throw new AssertionError("Invalid camera event: " + row);
                }
                long expectedPosition = Long.parseLong(event[2]);
                assertState(socket, expectedPosition, expectedPosition == 1 ? 0 : 10, Boolean.parseBoolean(event[3]));
                previousMs = timestampMs;
            }
        }
        assertThat(calls).hasSize(6);
        assertThat(socket.states()).hasSize(4);
    }

    @ParameterizedTest
    @EnumSource(value = Interruption.class, names = {"NO_HAND", "OTHER_HAND"})
    void absenceInvalidatesThePendingResultWithoutOpeningAnotherSlot(Interruption interruption) throws Exception {
        var socket = start(DEFAULTS);
        predict(socket, A);
        predict(socket, A);
        socket.hand(1);
        var pending = lastCall();
        socket.hand(1);
        if (interruption == Interruption.NO_HAND) socket.noHand(1);
        else socket.otherHand(1);
        socket.hand(1);
        assertThat(calls).hasSize(3);
        pending.complete(A);
        assertState(socket, 1, 0, false);
        assertThat(calls).hasSize(3);
        predict(socket, A);
        predict(socket, A);
        assertState(socket, 1, 0, false);
        predict(socket, A);
        assertState(socket, 2, 10, true);
    }

    @Test
    void ignoresAResponseForThePreviousPositionEvenWhenItRepeatsTheLetter() throws Exception {
        var socket = start(new RecognitionProperties(0.80, 1, Duration.ofMillis(350), 1));
        socket.hand(1);
        var game = sessions.find(id).orElseThrow();
        assertThat(connections.completePosition(game, 1)).isTrue();
        socket.noHand(2);
        assertState(socket, 2, 10, false);
        lastCall().complete(A);
        assertState(socket, 2, 10, false);
        socket.hand(2);
        lastCall().complete(A);
        assertState(socket, 3, 20, false);
    }

    @Test
    void publishesAndPersistsTheFinalScoreWhenPredictionArrivesAtTheDeadline() throws Exception {
        var socket = start(DEFAULTS);
        predict(socket, A);
        predict(socket, A);
        socket.hand(1);
        clock.advance(ROUND);
        lastCall().complete(A);
        assertThat(socket.states().getLast().path("status").asString()).isEqualTo("FINISHED");
        assertThat(socket.states().getLast().path("score").intValue()).isZero();
        connections.expireSessions();
        verify(results).save(new GameResult(id.toString(), "Ola", 0, clock.instant()));
    }

    @Test
    void releaseSurvivesResumeButPartialCountsAndOtherHandsInterruptIt() throws Exception {
        var old = start(new RecognitionProperties(0.80, 1, Duration.ofMillis(350), 2));
        predict(old, A);
        old.noHand(2);
        old.hand(2);
        old.noHand(2);
        old.otherHand(2);
        old.noHand(2);
        assertState(old, 2, 10, true);
        assertThat(old.states()).hasSize(3);
        assertThat(calls).hasSize(1);

        var current = resume();
        current.noHand(2);
        assertState(current, 2, 10, true);
        current.noHand(2);
        assertState(current, 2, 10, false);
        assertThat(current.states()).hasSize(2);
        var resumed = resume();
        assertState(resumed, 2, 10, false);
        resumed.hand(2);
        lastCall().complete(A);
        resumed.hand(3);
        lastCall().complete(new Prediction('B', 1));
        assertState(resumed, 4, 50, true);
    }

    @ParameterizedTest
    @EnumSource(SocketChange.class)
    void keepsTheCallSlotAcrossDisconnectAndRejectsOldResponses(SocketChange change) throws Exception {
        var old = start(new RecognitionProperties(0.80, 1, Duration.ofMillis(350), 2));
        old.hand(1);
        var pending = lastCall();
        if (change == SocketChange.CLOSE) handler.afterConnectionClosed(old.socket, CloseStatus.NORMAL);
        var current = resume();
        handler.afterConnectionClosed(old.socket, CloseStatus.NORMAL);
        current.hand(1);
        assertThat(calls).hasSize(1);
        pending.complete(A);
        assertState(current, 1, 0, false);
        predict(current, A);
        assertState(current, 2, 10, true);
    }

    @Test
    void recoversFromAnImmediateFailureAndASubmissionException() throws Exception {
        var socket = start(DEFAULTS);
        predict(socket, A);
        predict(socket, A);
        doReturn(CompletableFuture.completedStage(new Failure(FailureKind.DISABLED, null, null)))
                .doThrow(new IllegalStateException("AI submission failed"))
                .doAnswer(invocation -> enqueueCall()).when(ai).predict(eq(id), any());
        socket.hand(1);
        socket.hand(1);
        predict(socket, A);
        predict(socket, A);
        assertState(socket, 1, 0, false);
        predict(socket, A);
        assertState(socket, 2, 10, true);
    }

    @Test
    void recoversAfterAnAcknowledgementSendFails() throws Exception {
        var socket = start(DEFAULTS);
        doThrow(new IOException("ack failed")).when(socket.socket).sendMessage(any());
        assertThatThrownBy(() -> socket.hand(1)).isInstanceOf(IOException.class);
        assertThat(calls).isEmpty();
        var current = resume();
        predict(current, A);
        assertThat(calls).hasSize(1);
    }

    @Test
    void ignoresInvalidPositionsAndDuplicateSequencesBeforeRecognition() throws Exception {
        var socket = start(DEFAULTS);
        predict(socket, A);
        predict(socket, A);
        socket.send(SampleFixtures.noHand(30).replace("null", "2"));
        assertThat(socket.messages.getLast().path("code").asString()).isEqualTo("INVALID_SAMPLE");
        socket.send(SampleFixtures.noHand(1).replace("null", "1"));
        predict(socket, A);
        assertState(socket, 2, 10, true);
    }

    private RecordingSocket start(RecognitionProperties rules) throws Exception {
        return start(rules, SelectedHand.LEFT);
    }

    private RecordingSocket start(RecognitionProperties rules, SelectedHand hand) throws Exception {
        selectedHand = hand;
        connections = new SessionConnectionRegistry(sessions, mapper, clock, ai, rules, Duration.ofMillis(250),
                sampleNanos::get);
        handler = new SessionWebSocketHandler(mapper, new SessionWebSocketProperties(65536, 262144, true),
                sessions, new SampleMessageValidator(), connections, clock);
        var socket = new RecordingSocket();
        socket.send("{\"type\":\"create_session\",\"nickname\":\"Ola\",\"selectedHand\":\"" + hand + "\"}");
        id = UUID.fromString(socket.states().getFirst().path("sessionId").asString());
        var game = sessions.find(id).orElseThrow();
        when(ai.predict(eq(id), any())).thenAnswer(invocation -> {
            assertThat(Thread.holdsLock(game)).isFalse();
            return enqueueCall();
        });
        socket.send("{\"type\":\"start_game\"}");
        return socket;
    }

    private RecordingSocket resume() throws Exception {
        var socket = new RecordingSocket();
        socket.send("{\"type\":\"resume_session\",\"sessionId\":\"" + id + "\"}");
        return socket;
    }

    private CompletableFuture<PredictionResult> enqueueCall() {
        var call = new CompletableFuture<PredictionResult>();
        calls.add(call);
        return call;
    }

    private CompletableFuture<PredictionResult> lastCall() {
        return calls.getLast();
    }

    private void predict(RecordingSocket socket, PredictionResult response) throws IOException {
        int previousCount = calls.size();
        socket.hand(1);
        assertThat(calls).hasSize(previousCount + 1);
        lastCall().complete(response);
    }

    private void advance(long milliseconds) {
        sampleNanos.addAndGet(Duration.ofMillis(milliseconds).toNanos());
    }

    private static void assertState(RecordingSocket socket, long position, int score, boolean requiresRelease) {
        var state = socket.states().getLast();
        assertThat(state.path("status").asString()).isEqualTo("RUNNING");
        assertThat(state.path("positionId").asLong()).isEqualTo(position);
        assertThat(state.path("score").intValue()).isEqualTo(score);
        assertThat(state.path("requiresRelease").booleanValue()).isEqualTo(requiresRelease);
    }

    private enum Interruption { ANOTHER_LETTER, LOW_CONFIDENCE, FAILURE, NO_HAND, OTHER_HAND, BUDGET, EXCEPTIONAL_STAGE }

    private enum SocketChange { REPLACEMENT, CLOSE }

    private final class RecordingSocket {
        private final WebSocketSession socket = mock(WebSocketSession.class);
        private final List<JsonNode> messages = new ArrayList<>();
        private long sequence;

        private RecordingSocket() throws IOException {
            when(socket.getId()).thenReturn(UUID.randomUUID().toString());
            when(socket.isOpen()).thenReturn(true);
            doAnswer(invocation -> {
                messages.add(mapper.readTree(((TextMessage) invocation.getArgument(0)).getPayload()));
                return null;
            }).when(socket).sendMessage(any());
            handler.afterConnectionEstablished(socket);
        }

        private void hand(long position) throws IOException {
            hand(position, selectedHand);
        }

        private void otherHand(long position) throws IOException {
            hand(position, selectedHand == SelectedHand.LEFT ? SelectedHand.RIGHT : SelectedHand.LEFT);
        }

        private void hand(long position, SelectedHand hand) throws IOException {
            send(atPosition(SampleFixtures.hand(sequence++).replace("LEFT", hand.name()), position));
        }

        private void noHand(long position) throws IOException {
            send(atPosition(SampleFixtures.noHand(sequence++), position));
        }

        private void send(String payload) throws IOException {
            handler.handleTextMessage(socket, new TextMessage(payload));
        }

        private List<JsonNode> states() {
            return messages.stream().filter(message -> "game_state".equals(message.path("type").asString())).toList();
        }

        private String atPosition(String sample, long position) {
            return sample.replace("\"positionId\":null", "\"positionId\":" + position);
        }
    }
}
