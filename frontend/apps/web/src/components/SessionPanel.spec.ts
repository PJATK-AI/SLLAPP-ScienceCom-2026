import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, expect, it, vi } from 'vitest';
import { nextTick } from 'vue';
import SessionPanel from './SessionPanel.vue';
import SignReference from './SignReference.vue';
import { gameLetters } from '@/models/game-session';
import {
    type Hand,
    type HandTrackerOptions,
    type UseHandTrackerReturn,
} from '@sllapp/hand-landmarker-vue';
import { onScopeDispose, ref, shallowRef } from 'vue';

const camera = vi.hoisted(() => ({ tracker: vi.fn() }));
vi.mock('@sllapp/hand-landmarker-vue', async (original) => ({
    ...(await original<typeof import('@sllapp/hand-landmarker-vue')>()),
    useHandTracker: camera.tracker,
}));

function mockCamera() {
    let frame!: (hand: Hand | null, timestamp: number) => void;
    const running = ref(false);
    const hand = shallowRef<Hand | null>(null);
    const stop = vi.fn(() => {
        running.value = false;
    });
    camera.tracker.mockImplementation(
        (_video, _options: HandTrackerOptions, callback: typeof frame) => {
            frame = (result, timestamp) => {
                hand.value = result;
                callback(result, timestamp);
            };
            onScopeDispose(stop);
            return {
                start: async () => {
                    running.value = true;
                },
                stop,
                isRunning: running,
                hand,
                error: shallowRef(null),
            } satisfies UseHandTrackerReturn;
        },
    );
    return { frame: (hand: Hand | null, timestamp: number) => frame(hand, timestamp), stop };
}
class Socket extends EventTarget {
    static OPEN = 1;
    static CLOSING = 2;
    static last: Socket;
    readyState = 0;
    bufferedAmount = 0;
    send = vi.fn<(text: string) => void>();
    close = vi.fn();
    constructor() {
        super();
        Socket.last = this;
    }
    message(value: unknown) {
        this.dispatchEvent(new MessageEvent('message', { data: JSON.stringify(value) }));
    }
}
afterEach(() => {
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
    vi.useRealTimers();
});
it('creates a selected session, sends both test samples and displays acknowledgements', async () => {
    mockCamera();
    vi.stubGlobal('WebSocket', Socket);
    vi.stubEnv('VITE_WS_URL', 'ws://localhost/ws');
    vi.stubEnv('MODE', 'diagnostics');
    vi.stubEnv('DEV', true);
    const wrapper = mount(SessionPanel);
    await wrapper.get('input').setValue('Ola');
    await wrapper.get('select').setValue('LEFT');
    await wrapper.get('form').trigger('submit');
    const socket = Socket.last;
    socket.readyState = 1;
    socket.dispatchEvent(new Event('open'));
    expect(JSON.parse(socket.send.mock.calls[0]![0])).toEqual({
        type: 'create_session',
        nickname: 'Ola',
        selectedHand: 'LEFT',
    });
    socket.message({
        type: 'game_state',
        sessionId: '12345678-1234-1234-1234-123456789abc',
        status: 'CREATED',
        nickname: 'Ola',
        selectedHand: 'LEFT',
        positionId: null,
    });
    await nextTick();
    const button = (text: string) => wrapper.findAll('button').find((b) => b.text() === text)!;
    await button('Wyślij testową dłoń').trigger('click');
    const hand = JSON.parse(socket.send.mock.calls[1]![0]);
    expect(JSON.parse(wrapper.get('.sample pre').text())).toEqual(hand);
    expect(hand.hand.handedness).toBe('LEFT');
    expect(hand.hand.coordinateSpace).toBe('MEDIAPIPE_IMAGE');
    expect(hand.hand.landmarks).toHaveLength(21);
    await button('Wyślij brak dłoni').trigger('click');
    expect(JSON.parse(socket.send.mock.calls[2]![0])).toEqual({
        type: 'no_hand',
        seq: 1,
        positionId: null,
    });
    socket.message({ type: 'sample_ack', seq: 1 });
    await nextTick();
    expect(wrapper.get('[data-testid="ack-seq"]').text()).toBe('1');
    socket.bufferedAmount = 1;
    await button('Wyślij brak dłoni').trigger('click');
    expect(JSON.parse(wrapper.get('.sample pre').text())).toEqual(
        JSON.parse(socket.send.mock.calls[2]![0]),
    );
    expect(wrapper.text()).toContain('Próbka pominięta');
    expect(wrapper.get('[data-testid="sent-seq"]').text()).toBe('1');
    expect(wrapper.find('.with-diagnostics > [aria-label="Runda"] video').exists()).toBe(true);
    const diagnostics = wrapper.get('.with-diagnostics > .diagnostics');
    expect(diagnostics.attributes('open')).toBeDefined();
    expect(diagnostics.text()).toContain('Odebrano · sample_ack · seq 1');
    socket.message({
        type: 'prediction_result',
        seq: 0,
        positionId: 1,
        elapsedMs: 12,
        letter: 'A',
        confidence: 0.971,
        httpStatus: 200,
        disposition: 'POSITION_COMPLETED',
    });
    await nextTick();
    expect(diagnostics.get('[aria-label="Predykcja AI"]').text()).toContain('Zaliczona litera');
    expect(diagnostics.get('[aria-label="Predykcja AI"]').text()).toContain('0.971');
    await button('Rozłącz').trigger('click');
    expect(button('Wyślij testową dłoń').attributes('disabled')).toBeDefined();
    expect(button('Wznów sesję').attributes('disabled')).toBeUndefined();
    wrapper.unmount();
});

it.each(['LEFT', 'RIGHT'] as const)(
    'streams current %s frames across disconnect and resume',
    async (selectedHand) => {
        vi.stubGlobal('WebSocket', Socket);
        vi.stubEnv('VITE_WS_URL', 'ws://localhost/ws');
        vi.stubEnv('MODE', 'diagnostics');
        vi.stubEnv('DEV', true);
        // Exercise the default and an explicit alternative rate.
        vi.stubEnv('VITE_HAND_SAMPLE_HZ', selectedHand === 'LEFT' ? '' : '20');
        const interval = selectedHand === 'LEFT' ? 100 : 50;
        const tracker = mockCamera();
        const wrapper = mount(SessionPanel);
        const button = (name: string) => wrapper.findAll('button').find((b) => b.text() === name)!;
        await wrapper.get('input').setValue('Test');
        await wrapper.get('select').setValue(selectedHand);
        await wrapper.get('form').trigger('submit');
        expect(wrapper.find('video').exists()).toBe(false);
        const socket = Socket.last;
        const confirm = {
            type: 'game_state',
            sessionId: '12345678-1234-1234-1234-123456789abc',
            status: 'CREATED',
            nickname: 'Test',
            selectedHand,
            positionId: null,
        };
        function open(s: Socket) {
            s.readyState = 1;
            s.dispatchEvent(new Event('open'));
        }
        open(socket);
        socket.message(confirm);
        await nextTick();
        await button('Uruchom kamerę').trigger('click');
        expect(button('Wyślij testową dłoń').attributes('disabled')).toBeDefined();
        const preferred = selectedHand === 'LEFT' ? 'Left' : 'Right';
        expect(camera.tracker.mock.lastCall?.[1].preferredHand).toBe(preferred);
        const hand: Hand = {
            handedness: preferred,
            score: 0.8,
            landmarks: Array.from({ length: 21 }, () => ({ x: 0.25, y: 0.4, z: 0.1 })),
            wristRelativeLandmarks: [],
        };
        tracker.frame(hand, 0);
        await nextTick();
        const points = () => wrapper.findAll('.landmarks span');
        expect(points()).toHaveLength(21);
        expect(points()[0]!.attributes('style')).toContain('left: 25%');
        expect(points()[0]!.attributes('style')).toContain('top: 40%');
        tracker.frame(null, interval - 1);
        await nextTick();
        expect(JSON.parse(wrapper.get('.sample pre').text())).toEqual(
            JSON.parse(socket.send.mock.calls[1]![0]),
        );
        expect(points()).toHaveLength(0);
        tracker.frame({ ...hand, handedness: preferred === 'Left' ? 'Right' : 'Left' }, interval);
        await nextTick();
        expect(points()).toHaveLength(0);
        socket.bufferedAmount = 1;
        tracker.frame(hand, 2 * interval);
        socket.bufferedAmount = 0;
        tracker.frame(hand, 3 * interval - 1);
        tracker.frame(null, 3 * interval);
        const sample = (s: Socket) => s.send.mock.calls.slice(1).map(([json]) => JSON.parse(json));
        expect(sample(socket)).toEqual([
            {
                type: 'hand_sample',
                seq: 0,
                positionId: null,
                hand: {
                    handedness: selectedHand,
                    coordinateSpace: 'MEDIAPIPE_IMAGE',
                    score: 0.8,
                    landmarks: hand.landmarks.map((p) => ({
                        ...p,
                        x: preferred === 'Left' ? 0.75 : 0.25,
                    })),
                },
            },
            { type: 'no_hand', seq: 1, positionId: null },
            { type: 'no_hand', seq: 2, positionId: null },
        ]);
        await button('Rozłącz').trigger('click');
        tracker.frame(hand, 4 * interval);
        await button('Wznów sesję').trigger('click');
        const resumed = Socket.last;
        open(resumed);
        tracker.frame(hand, 5 * interval);
        resumed.message(confirm);
        await nextTick();
        expect(sample(resumed)).toEqual([]);
        expect(wrapper.find('.sample pre').exists()).toBe(false);
        tracker.frame(null, 5 * interval + 1);
        await nextTick();
        expect(JSON.parse(wrapper.get('.sample pre').text())).toEqual(sample(resumed)[0]);
        await button('Zatrzymaj kamerę').trigger('click');
        tracker.frame(hand, 6 * interval + 1);
        expect(button('Wyślij testową dłoń').attributes('disabled')).toBeUndefined();
        await button('Uruchom kamerę').trigger('click');
        resumed.dispatchEvent(new CloseEvent('close', { code: 1008 }));
        await nextTick();
        expect(wrapper.find('video').exists()).toBe(false);
        expect(tracker.stop).toHaveBeenCalledTimes(2);
        tracker.frame(hand, 7 * interval);
        expect(sample(resumed)).toEqual([{ type: 'no_hand', seq: 0, positionId: null }]);
        wrapper.unmount();
    },
);

it('shows exactly one reference crop for each supported letter', async () => {
    const wrapper = mount(SignReference, { props: { expectedLetter: 'A' } });
    const crops = new Set<string>();
    for (const letter of gameLetters) {
        await wrapper.setProps({ expectedLetter: letter });
        const images = wrapper.findAll('[role="img"]');
        expect(images).toHaveLength(1);
        expect(images[0]!.attributes('aria-label')).toBe(`Układ dłoni dla litery ${letter}`);
        crops.add((images[0]!.element as HTMLElement).style.backgroundPosition);
    }
    expect(crops.size).toBe(gameLetters.length);
    wrapper.unmount();
});

it('plays and resumes a round, then keeps the final score through a ranking failure', async () => {
    vi.stubGlobal('WebSocket', Socket);
    vi.stubEnv('VITE_WS_URL', 'ws://localhost/ws');
    vi.stubEnv('VITE_API_BASE_URL', 'http://localhost/api');
    vi.useFakeTimers({
        toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'performance'],
    });
    const fetchRanking = vi
        .fn()
        .mockRejectedValueOnce(new Error('offline'))
        .mockResolvedValueOnce(
            new Response(JSON.stringify([{ rank: 1, nickname: 'Ola', score: 20 }])),
        );
    vi.stubGlobal('fetch', fetchRanking);
    const tracker = mockCamera();
    const wrapper = mount(SessionPanel);
    const button = (text: string) =>
        wrapper.findAll('button').find((item) => item.text() === text)!;
    await wrapper.get('input').setValue('Ola');
    await wrapper.get('form').trigger('submit');
    const socket = Socket.last;
    socket.readyState = 1;
    socket.dispatchEvent(new Event('open'));
    const created = {
        type: 'game_state',
        sessionId: '12345678-1234-1234-1234-123456789abc',
        status: 'CREATED',
        nickname: 'Ola',
        selectedHand: 'RIGHT',
        positionId: null,
    };
    socket.message(created);
    await nextTick();
    expect(button('Rozpocznij rundę').attributes('disabled')).toBeDefined();
    await button('Uruchom kamerę').trigger('click');
    expect(button('Rozpocznij rundę').attributes('disabled')).toBeUndefined();
    await button('Rozpocznij rundę').trigger('click');
    expect(JSON.parse(socket.send.mock.calls[1]![0])).toEqual({ type: 'start_game' });
    expect(button('Rozpoczynanie…').attributes('disabled')).toBeDefined();

    const running = {
        ...created,
        status: 'RUNNING',
        positionId: 1,
        word: 'LAS',
        letterIndex: 0,
        expectedLetter: 'L',
        remainingMs: 10000,
        score: 0,
        requiresRelease: true,
    };
    socket.message(running);
    await nextTick();
    expect(wrapper.get('[aria-current="step"]').text()).toBe('L');
    expect(wrapper.get('[role="img"]').attributes('aria-label')).toContain('litery L');
    expect(wrapper.text()).toContain('Wyjmij dłoń z kadru');
    expect(wrapper.get('[role="timer"]').text()).toBe('00:10');
    await vi.advanceTimersByTimeAsync(2000);
    expect(wrapper.get('[role="timer"]').text()).toBe('00:08');
    socket.message({ ...running, requiresRelease: false, remainingMs: 8100 });
    await nextTick();
    expect(wrapper.text()).not.toContain('Wyjmij dłoń z kadru');
    const nextPosition = {
        ...running,
        positionId: 2,
        letterIndex: 1,
        expectedLetter: 'A',
        remainingMs: 1000,
        score: 10,
        requiresRelease: false,
    };
    socket.message(nextPosition);
    await nextTick();
    expect(wrapper.get('[aria-current="step"]').text()).toBe('A');
    expect(wrapper.get('[role="img"]').attributes('aria-label')).toContain('litery A');
    await vi.advanceTimersByTimeAsync(1000);
    expect(wrapper.get('[role="timer"]').text()).toBe('00:00');
    expect(wrapper.text()).not.toContain('Koniec rundy');

    socket.dispatchEvent(new CloseEvent('close', { code: 1006 }));
    await nextTick();
    await button('Wznów sesję').trigger('click');
    const resumed = Socket.last;
    resumed.readyState = 1;
    resumed.dispatchEvent(new Event('open'));
    const final = { ...created, status: 'FINISHED', remainingMs: 0, score: 20 };
    resumed.message(final);
    await flushPromises();
    expect(tracker.stop).toHaveBeenCalledOnce();
    expect(wrapper.find('video').exists()).toBe(false);
    expect(wrapper.get('.final-score').text()).toBe('20 pkt');
    expect(wrapper.text()).toContain('Nie udało się pobrać rankingu');
    resumed.message(final);
    await nextTick();
    expect(fetchRanking).toHaveBeenCalledTimes(1);

    await button('Ponów').trigger('click');
    await flushPromises();
    expect(wrapper.get('tbody').text()).toContain('Ola');
    expect(fetchRanking).toHaveBeenCalledTimes(2);
    await button('Nowa sesja').trigger('click');
    expect(wrapper.find('form').exists()).toBe(true);
    expect(resumed.close).toHaveBeenCalled();
    wrapper.unmount();
});
