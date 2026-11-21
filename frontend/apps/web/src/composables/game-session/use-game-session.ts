import { getCurrentScope, onScopeDispose, readonly, ref, shallowRef } from 'vue';
import { parseSessionMessage, serializeSample } from '@/api/session-protocol';
import type {
    GameState,
    HandSampleData,
    PredictionDiagnostic,
    SelectedHand,
    SessionDiagnosticEvent,
    SessionError,
} from '@/models/game-session';

export function useGameSession(
    options: { url?: string; timeoutMs?: number; diagnostics?: boolean } = {},
) {
    const connectionStatus = ref<'idle' | 'connecting' | 'open' | 'closed'>('idle');
    const pendingAction = ref<'create' | 'resume' | 'start' | null>(null);
    const isReady = ref(false);
    const gameState = shallowRef<GameState | null>(null);
    const error = shallowRef<SessionError | null>(null);
    const lastClose = shallowRef<{ code: number; reason: string; wasClean: boolean } | null>(null);
    const lastSentSeq = ref<number | null>(null);
    const lastSentPayload = ref<string | null>(null);
    const lastSampleAck = ref<number | null>(null);
    const lastPrediction = shallowRef<PredictionDiagnostic | null>(null);
    const diagnosticEvents = shallowRef<SessionDiagnosticEvent[]>([]);
    let eventId = 0;
    let connectionNumber = 0;
    let socket: WebSocket | null = null;
    let cleanup = () => {};
    let timer: ReturnType<typeof setTimeout> | undefined;
    let disposed = false;
    let seq = 0;

    function record(
        direction: SessionDiagnosticEvent['direction'],
        payload: string,
        label: string,
    ) {
        if (!options.diagnostics) return;
        const event = {
            id: ++eventId,
            connection: connectionNumber,
            direction,
            label,
            payload: payload.length > 8192 ? payload.slice(0, 8192) + '…' : payload,
        };
        diagnosticEvents.value = [...diagnosticEvents.value.slice(-149), event];
    }

    function sendFrame(current: WebSocket, payload: string, type: string) {
        current.send(payload);
        record('send', payload, type);
    }

    function clearTimer() {
        clearTimeout(timer);
        timer = undefined;
    }
    function disconnect() {
        const old = socket;
        socket = null;
        cleanup();
        cleanup = () => {};
        clearTimer();
        isReady.value = false;
        pendingAction.value = null;
        connectionStatus.value = 'closed';
        if (old) record('connection', '', 'Rozłączono');
        if (old && old.readyState < WebSocket.CLOSING) old.close();
    }
    function fail(source: SessionError['source'], code: string, message: string, close = false) {
        error.value = { source, code, message };
        record('connection', message, code);
        if (close) disconnect();
    }
    function startTimer() {
        clearTimer();
        timer = setTimeout(
            () => fail('transport', 'TIMEOUT', 'Session request timed out. Try again.', true),
            options.timeoutMs ?? 10000,
        );
    }
    function connect(
        command:
            | { type: 'create_session'; nickname: string; selectedHand: SelectedHand }
            | { type: 'resume_session'; sessionId: string },
    ) {
        if (disposed || pendingAction.value || isReady.value) return;
        disconnect();
        error.value = null;
        lastClose.value = null;
        let url: URL;
        try {
            url = new URL(options.url ?? import.meta.env.VITE_WS_URL ?? '');
            if (!['ws:', 'wss:'].includes(url.protocol) || url.hash) throw new Error();
            if (options.diagnostics) url.searchParams.set('diagnostics', '1');
        } catch {
            fail('configuration', 'INVALID_URL', 'Set VITE_WS_URL to a valid ws:// or wss:// URL.');
            return;
        }
        let current: WebSocket;
        try {
            current = new WebSocket(url.href);
        } catch {
            fail('transport', 'CONNECT_FAILED', 'Could not open the WebSocket connection.');
            return;
        }
        socket = current;
        connectionNumber++;
        record('connection', '', 'Łączenie');
        connectionStatus.value = 'connecting';
        pendingAction.value = command.type === 'create_session' ? 'create' : 'resume';
        const active = () => socket === current && !disposed;
        const open = () => {
            if (!active()) return;
            connectionStatus.value = 'open';
            record('connection', '', 'Otwarto połączenie');
            try {
                sendFrame(current, JSON.stringify(command), command.type);
                startTimer();
            } catch {
                fail('transport', 'SEND_FAILED', 'Could not send the session request.', true);
            }
        };
        const message = (event: MessageEvent) => {
            if (!active()) return;
            const raw = typeof event.data === 'string' ? event.data : '';
            try {
                const value = parseSessionMessage(event.data);
                if (options.diagnostics) {
                    const name =
                        value.type === 'game_state' ? `game_state · ${value.status}` : value.type;
                    const label = 'seq' in value ? `${name} · seq ${value.seq}` : name;
                    record('receive', raw, label);
                }
                if (value.type === 'error') {
                    const action = pendingAction.value;
                    const needsResume =
                        value.code === 'GAME_ALREADY_STARTED' || value.code === 'SESSION_REQUIRED';
                    fail(
                        'server',
                        value.code,
                        value.message,
                        action === 'create' ||
                            action === 'resume' ||
                            (action === 'start' && needsResume),
                    );
                } else if (value.type === 'sample_ack') {
                    if (isReady.value) lastSampleAck.value = value.seq;
                } else if (value.type === 'prediction_result') {
                    if (isReady.value && options.diagnostics) lastPrediction.value = value;
                } else {
                    if (
                        (command.type === 'resume_session' &&
                            value.sessionId !== command.sessionId) ||
                        (command.type === 'create_session' &&
                            (value.selectedHand !== command.selectedHand ||
                                (!isReady.value && value.status !== 'CREATED'))) ||
                        (gameState.value &&
                            (value.sessionId !== gameState.value.sessionId ||
                                value.selectedHand !== gameState.value.selectedHand))
                    )
                        throw new Error('Session confirmation does not match the request.');
                    if (isReady.value && gameState.value?.status === 'FINISHED') return;
                    if (
                        isReady.value &&
                        gameState.value?.status === 'RUNNING' &&
                        value.status === 'CREATED'
                    )
                        throw new Error('An active round cannot return to CREATED.');
                    if (!isReady.value) {
                        seq = 0;
                        lastSentSeq.value = null;
                        lastSentPayload.value = null;
                        lastSampleAck.value = null;
                        lastPrediction.value = null;
                    }
                    gameState.value = value;
                    isReady.value = true;
                    if (pendingAction.value !== 'start' || value.status !== 'CREATED') {
                        pendingAction.value = null;
                        error.value = null;
                        clearTimer();
                    }
                }
            } catch (cause) {
                record('receive', raw, 'Nieprawidłowa wiadomość');
                fail(
                    'protocol',
                    'INVALID_MESSAGE',
                    cause instanceof Error ? cause.message : 'Invalid server message.',
                    pendingAction.value !== null,
                );
            }
        };
        const close = (event: CloseEvent) => {
            if (!active()) return;
            lastClose.value = { code: event.code, reason: event.reason, wasClean: event.wasClean };
            record('connection', event.reason, `Zamknięcie · ${event.code}`);
            if (event.code === 1008 && gameState.value?.status !== 'FINISHED')
                gameState.value = null;
            disconnect();
        };
        const transportError = () => {
            if (active())
                fail(
                    'transport',
                    'CONNECTION_ERROR',
                    'WebSocket connection failed. Reconnect manually.',
                    true,
                );
        };
        current.addEventListener('open', open);
        current.addEventListener('message', message);
        current.addEventListener('close', close);
        current.addEventListener('error', transportError);
        cleanup = () => {
            current.removeEventListener('open', open);
            current.removeEventListener('message', message);
            current.removeEventListener('close', close);
            current.removeEventListener('error', transportError);
        };
        startTimer();
    }
    function createSession(input: { nickname: string; selectedHand: SelectedHand }) {
        if (!gameState.value)
            connect({
                type: 'create_session',
                nickname: input.nickname,
                selectedHand: input.selectedHand,
            });
    }
    function reconnect() {
        if (gameState.value && gameState.value.status !== 'FINISHED')
            connect({ type: 'resume_session', sessionId: gameState.value.sessionId });
    }
    function startGame(): boolean {
        if (
            !isReady.value ||
            pendingAction.value ||
            gameState.value?.status !== 'CREATED' ||
            socket?.readyState !== WebSocket.OPEN
        )
            return false;
        pendingAction.value = 'start';
        error.value = null;
        try {
            sendFrame(socket, JSON.stringify({ type: 'start_game' }), 'start_game');
            startTimer();
            return true;
        } catch {
            fail('transport', 'SEND_FAILED', 'Could not send the start request.', true);
            return false;
        }
    }
    function send(hand?: HandSampleData): boolean {
        if (
            !isReady.value ||
            pendingAction.value === 'start' ||
            !gameState.value ||
            gameState.value.status === 'FINISHED' ||
            socket?.readyState !== WebSocket.OPEN ||
            socket.bufferedAmount > 0
        )
            return false;
        let payload: string;
        try {
            payload = serializeSample(seq, gameState.value.positionId, hand);
        } catch (cause) {
            fail(
                'protocol',
                'INVALID_SAMPLE',
                cause instanceof Error ? cause.message : 'Invalid sample.',
            );
            return false;
        }
        try {
            const type = hand ? 'hand_sample' : 'no_hand';
            sendFrame(socket, payload, options.diagnostics ? `${type} · seq ${seq}` : type);
            if (options.diagnostics) lastSentPayload.value = payload;
            lastSentSeq.value = seq++;
            return true;
        } catch {
            fail('transport', 'SEND_FAILED', 'Could not send the sample.', true);
            return false;
        }
    }
    function resetSession() {
        disconnect();
        gameState.value = null;
        error.value = null;
        lastClose.value = null;
        lastSentSeq.value = null;
        lastSentPayload.value = null;
        lastSampleAck.value = null;
        lastPrediction.value = null;
        diagnosticEvents.value = [];
    }
    function dispose() {
        if (disposed) return;
        disposed = true;
        resetSession();
    }
    if (getCurrentScope()) onScopeDispose(dispose);
    return {
        connectionStatus: readonly(connectionStatus),
        pendingAction: readonly(pendingAction),
        isReady: readonly(isReady),
        gameState: readonly(gameState),
        error: readonly(error),
        lastClose: readonly(lastClose),
        lastSentSeq: readonly(lastSentSeq),
        lastSentPayload: readonly(lastSentPayload),
        lastSampleAck: readonly(lastSampleAck),
        lastPrediction: readonly(lastPrediction),
        diagnosticEvents: readonly(diagnosticEvents),
        createSession,
        startGame,
        reconnect,
        disconnect,
        resetSession,
        dispose,
        sendHandSample: (hand: HandSampleData) => send(hand),
        sendNoHand: () => send(),
    };
}
