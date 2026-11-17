import { afterEach, expect, it, vi } from 'vitest';
import { effectScope } from 'vue';
import { useGameSession } from './use-game-session';
class Socket extends EventTarget {
    static OPEN = 1;
    static CLOSING = 2;
    static all: Socket[] = [];
    readyState = 0;
    bufferedAmount = 0;
    send = vi.fn<(text: string) => void>();
    close = vi.fn(() => {
        this.readyState = 3;
    });
    constructor(public url: string) {
        super();
        Socket.all.push(this);
    }
    open() {
        this.readyState = 1;
        this.dispatchEvent(new Event('open'));
    }
    message(data: unknown) {
        this.dispatchEvent(new MessageEvent('message', { data: JSON.stringify(data) }));
    }
    end(code = 1006) {
        this.readyState = 3;
        this.dispatchEvent(new CloseEvent('close', { code }));
    }
}
const state = {
    type: 'game_state',
    sessionId: '12345678-1234-1234-1234-123456789abc',
    status: 'CREATED',
    nickname: 'Ola',
    selectedHand: 'RIGHT',
    positionId: null,
};
const scopes: ReturnType<typeof effectScope>[] = [];
function setup() {
    vi.stubGlobal('WebSocket', Socket);
    vi.useFakeTimers();
    const scope = effectScope();
    scopes.push(scope);
    const client = scope.run(() => useGameSession({ url: 'ws://localhost/ws', timeoutMs: 100 }))!;
    client.createSession({ nickname: 'Ola', selectedHand: 'RIGHT' });
    return { client, socket: Socket.all.at(-1)!, scope };
}
afterEach(() => {
    scopes.splice(0).forEach((s) => s.stop());
    Socket.all = [];
    vi.unstubAllGlobals();
    vi.useRealTimers();
});
it('waits for open and confirmation; prevents duplicates and shares sequence', () => {
    const { client, socket } = setup();
    client.createSession({ nickname: 'Other', selectedHand: 'LEFT' });
    expect(Socket.all).toHaveLength(1);
    expect(socket.send).not.toHaveBeenCalled();
    expect(client.sendNoHand()).toBe(false);
    socket.open();
    expect(client.isReady.value).toBe(false);
    socket.message(state);
    client.sendNoHand();
    client.sendHandSample({
        handedness: 'RIGHT',
        landmarks: Array.from({ length: 21 }, () => ({ x: 1, y: 2, z: 3 })),
    });
    expect(socket.send.mock.calls.slice(1).map((c) => JSON.parse(c[0]))).toMatchObject([
        { type: 'no_hand', seq: 0, positionId: null },
        { type: 'hand_sample', seq: 1, positionId: null },
    ]);
    socket.message(state);
    client.sendNoHand();
    expect(client.lastSentSeq.value).toBe(2);
});
it('resumes manually without replay and ignores old socket events', () => {
    const { client, socket } = setup();
    socket.open();
    socket.message(state);
    client.sendNoHand();
    socket.end();
    expect(client.sendNoHand()).toBe(false);
    expect(Socket.all).toHaveLength(1);
    client.reconnect();
    const next = Socket.all.at(-1)!;
    next.open();
    expect(JSON.parse(next.send.mock.calls[0]![0])).toEqual({
        type: 'resume_session',
        sessionId: state.sessionId,
    });
    expect(client.sendNoHand()).toBe(false);
    next.message(state);
    client.sendNoHand();
    expect(client.lastSentSeq.value).toBe(0);
    socket.end(1008);
    expect(client.isReady.value).toBe(true);
    next.end(1008);
    expect(client.gameState.value).toBeNull();
});
it('drops congestion and sends without ACK', () => {
    const { client, socket } = setup();
    socket.open();
    socket.message(state);
    socket.bufferedAmount = 1;
    expect(client.sendNoHand()).toBe(false);
    socket.bufferedAmount = 0;
    client.sendNoHand();
    expect(client.lastSentSeq.value).toBe(0);
    client.sendNoHand();
    socket.message({ type: 'sample_ack', seq: 1 });
    expect(client.lastSampleAck.value).toBe(1);
});
it('handles server and malformed messages without losing confirmed state', () => {
    const { client, socket } = setup();
    socket.open();
    socket.message(state);
    socket.message({ type: 'error', code: 'INVALID_SAMPLE', message: 'Bad sample' });
    expect(client.isReady.value).toBe(true);
    socket.message(null);
    expect(client.error.value?.source).toBe('protocol');
    expect(client.gameState.value?.sessionId).toBe(state.sessionId);
});
it('ends a rejected create and allows retry', () => {
    const { client, socket } = setup();
    socket.open();
    socket.message({ type: 'error', code: 'INVALID_SESSION', message: 'Bad nick' });
    expect(client.pendingAction.value).toBeNull();
    expect(socket.close).toHaveBeenCalled();
    client.createSession({ nickname: 'Ola', selectedHand: 'RIGHT' });
    expect(Socket.all).toHaveLength(2);
});
it('times out and releases resources on disposal', () => {
    const { client, socket, scope } = setup();
    vi.advanceTimersByTime(100);
    expect(client.error.value?.code).toBe('TIMEOUT');
    scope.stop();
    socket.open();
    socket.message(state);
    expect(client.isReady.value).toBe(false);
    expect(vi.getTimerCount()).toBe(0);
    client.createSession({ nickname: 'Ola', selectedHand: 'RIGHT' });
    expect(Socket.all).toHaveLength(1);
});
it('rejects mismatched resume confirmation', () => {
    const { client, socket } = setup();
    socket.open();
    socket.message(state);
    socket.end();
    client.reconnect();
    const next = Socket.all.at(-1)!;
    next.open();
    next.message({ ...state, sessionId: 'aaaaaaaa-1234-1234-1234-123456789abc' });
    expect(client.isReady.value).toBe(false);
    expect(client.gameState.value?.sessionId).toBe(state.sessionId);
});
it('handles confirmation timeout and send exceptions', () => {
    const { client, socket } = setup();
    socket.open();
    vi.advanceTimersByTime(100);
    expect(client.error.value?.code).toBe('TIMEOUT');
    client.createSession({ nickname: 'Ola', selectedHand: 'RIGHT' });
    const next = Socket.all.at(-1)!;
    next.open();
    next.message(state);
    next.send.mockImplementation(() => {
        throw new Error('network');
    });
    expect(client.sendNoHand()).toBe(false);
    expect(client.isReady.value).toBe(false);
});
it('rejects invalid URLs before constructing a socket', () => {
    vi.stubGlobal('WebSocket', Socket);
    const client = useGameSession({ url: 'https://localhost/ws' });
    client.createSession({ nickname: 'Ola', selectedHand: 'RIGHT' });
    expect(client.error.value?.source).toBe('configuration');
    expect(Socket.all).toHaveLength(0);
    client.dispose();
});
