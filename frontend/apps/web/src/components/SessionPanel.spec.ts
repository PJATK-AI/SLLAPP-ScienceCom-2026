import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, expect, it, vi } from 'vitest';
import { nextTick } from 'vue';
import SessionPanel from './SessionPanel.vue';
import SignReference from './SignReference.vue';
import { CameraSettingsKey, type CameraSettingsContext } from '@/composables/camera-settings/key';
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
    const hasProcessedFrame = ref(false);
    const stop = vi.fn(() => {
        running.value = false;
        hasProcessedFrame.value = false;
        hand.value = null;
    });
    camera.tracker.mockImplementation(
        (_video, _options: HandTrackerOptions, callback: typeof frame) => {
            frame = (result, timestamp) => {
                if (!running.value) return;
                hand.value = result;
                hasProcessedFrame.value = true;
                callback(result, timestamp);
            };
            onScopeDispose(stop);
            return {
                start: async () => {
                    running.value = true;
                },
                stop,
                isRunning: running,
                hasProcessedFrame,
                hand,
                error: shallowRef(null),
            } satisfies UseHandTrackerReturn;
        },
    );
    return { frame: (hand: Hand | null, timestamp: number) => frame(hand, timestamp), stop };
}
function mountPanel(attachTo?: HTMLElement) {
    const cameraSettings: CameraSettingsContext = { crop: ref(null), saveCrop: vi.fn() };
    return mount(SessionPanel, {
        attachTo,
        global: { provide: { [CameraSettingsKey]: cameraSettings } },
    });
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
    const wrapper = mountPanel();
    await wrapper.get('input').setValue('Ola');
    await wrapper.get('input[value="LEFT"]').setValue(true);
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
        const wrapper = mountPanel();
        const button = (name: string) => wrapper.findAll('button').find((b) => b.text() === name)!;
        await wrapper.get('input').setValue('Test');
        await wrapper.get(`input[value="${selectedHand}"]`).setValue(true);
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

it.each(['typing', 'randomizing'])(
    'shows an inline nickname error and allows retry after %s',
    async (action) => {
        vi.stubGlobal('WebSocket', Socket);
        vi.stubEnv('VITE_WS_URL', 'ws://localhost/ws');
        mockCamera();
        const wrapper = mountPanel(document.body);
        try {
            const nickname = wrapper.get('#nickname');
            const input = nickname.element as HTMLInputElement;
            const form = wrapper.get('form');
            await nickname.setValue('67');
            await form.trigger('submit');
            const rejected = Socket.last;
            openSocket(rejected);
            rejected.message({
                type: 'error',
                code: 'INVALID_SESSION',
                message: 'Invalid nickname',
            });
            await nextTick();

            const alert = form.get('[role="alert"]');
            expect(alert.text()).toBe(
                'Ten nick jest niedozwolony. Wpisz inny lub kliknij „Losuj”.',
            );
            expect(input.value).toBe('67');
            expect(nickname.attributes('aria-invalid')).toBe('true');
            expect(nickname.attributes('aria-describedby')).toBe(alert.attributes('id'));
            expect(document.activeElement).toBe(input);

            if (action === 'typing') await nickname.setValue('Ola');
            else await form.get('.dice').trigger('click');
            const corrected = input.value;
            expect(corrected).not.toBe('67');
            expect(form.find('[role="alert"]').exists()).toBe(false);
            expect(nickname.attributes('aria-invalid')).toBeUndefined();
            expect(nickname.attributes('aria-describedby')).toBeUndefined();

            await form.trigger('submit');
            const retried = Socket.last;
            expect(retried).not.toBe(rejected);
            openSocket(retried);
            expect(JSON.parse(retried.send.mock.calls[0]![0])).toMatchObject({
                type: 'create_session',
                nickname: corrected,
            });
            retried.message({ ...createdRound, nickname: corrected });
            await nextTick();
            expect(wrapper.find('form').exists()).toBe(false);
            expect(wrapper.find('video').exists()).toBe(true);
        } finally {
            wrapper.unmount();
        }
    },
);

it('offers to reuse a nickname or focuses the field to enter a different one', async () => {
    vi.stubGlobal('WebSocket', Socket);
    vi.stubEnv('VITE_WS_URL', 'ws://localhost/ws');
    const wrapper = mountPanel(document.body);
    try {
        const nickname = wrapper.get('#nickname');
        const button = (text: string) =>
            wrapper.findAll('button').find((item) => item.text() === text)!;
        const form = wrapper.get('form');
        await nickname.setValue('Ola');
        await form.trigger('submit');
        const socket = Socket.last;
        socket.readyState = 1;
        socket.dispatchEvent(new Event('open'));
        socket.message({ type: 'error', code: 'NICKNAME_EXISTS', message: 'Confirm reuse' });
        await nextTick();
        expect(wrapper.get('#nickname-reuse-message').text()).toBe(
            'Ten nick jest już w rankingu. Czy chcesz zagrać pod tym nickiem?',
        );
        expect(nickname.attributes('aria-invalid')).toBeUndefined();
        expect(document.activeElement).toBe(button('Kontynuuj').element);

        await button('Zmień nick').trigger('click');
        expect(wrapper.find('#nickname-reuse-message').exists()).toBe(false);
        expect(document.activeElement).toBe(nickname.element);
        expect(Socket.last).toBe(socket);
        await nickname.setValue('Jan');
        await form.trigger('submit');
        const newSocket = Socket.last;
        newSocket.readyState = 1;
        newSocket.dispatchEvent(new Event('open'));
        expect(newSocket).not.toBe(socket);
        expect(JSON.parse(newSocket.send.mock.calls[0]![0])).toEqual({
            type: 'create_session',
            nickname: 'Jan',
            selectedHand: 'RIGHT',
        });
    } finally {
        wrapper.unmount();
    }
});

it('sends a confirmation from the form only for the nickname and hand that were checked', async () => {
    vi.stubGlobal('WebSocket', Socket);
    vi.stubEnv('VITE_WS_URL', 'ws://localhost/ws');
    const wrapper = mountPanel();
    try {
        const form = wrapper.get('form');
        await wrapper.get('#nickname').setValue('ola');
        const challenge = async () => {
            await form.trigger('submit');
            const socket = Socket.last;
            socket.readyState = 1;
            socket.dispatchEvent(new Event('open'));
            socket.message({ type: 'error', code: 'NICKNAME_EXISTS', message: 'Confirm reuse' });
            await nextTick();
        };
        await challenge();
        await wrapper.get('input[value="LEFT"]').setValue(true);
        expect(wrapper.find('#nickname-reuse-message').exists()).toBe(false);
        await challenge();
        const continueButton = wrapper
            .findAll('button')
            .find((item) => item.text() === 'Kontynuuj')!;
        await continueButton.trigger('click');
        const confirmed = Socket.last;
        confirmed.readyState = 1;
        confirmed.dispatchEvent(new Event('open'));
        expect(JSON.parse(confirmed.send.mock.calls[0]![0])).toEqual({
            type: 'create_session',
            nickname: 'ola',
            selectedHand: 'LEFT',
            confirmExistingNickname: true,
        });
    } finally {
        wrapper.unmount();
    }
});

const createdRound = {
    type: 'game_state',
    sessionId: '12345678-1234-1234-1234-123456789abc',
    status: 'CREATED',
    nickname: 'Ola',
    selectedHand: 'RIGHT',
    positionId: null,
};
const runningRound = {
    ...createdRound,
    status: 'RUNNING',
    positionId: 1,
    word: 'LAS',
    letterIndex: 0,
    expectedLetter: 'L',
    remainingMs: 10000,
    score: 0,
    requiresRelease: true,
};

function openSocket(socket: Socket) {
    socket.readyState = Socket.OPEN;
    socket.dispatchEvent(new Event('open'));
}

function startRequests(socket: Socket) {
    return socket.send.mock.calls
        .map(([json]) => JSON.parse(json))
        .filter((msg) => msg.type === 'start_game');
}

async function mountCreatedRound() {
    vi.stubGlobal('WebSocket', Socket);
    vi.stubEnv('VITE_WS_URL', 'ws://localhost/ws');
    vi.useFakeTimers({
        toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'performance'],
    });
    const tracker = mockCamera();
    const wrapper = mountPanel();
    const button = (text: string) =>
        wrapper.findAll('button').find((item) => item.text() === text)!;
    await wrapper.get('input').setValue(createdRound.nickname);
    await wrapper.get('form').trigger('submit');
    const socket = Socket.last;
    openSocket(socket);
    socket.message(createdRound);
    await nextTick();
    return { wrapper, button, tracker, socket };
}

it('plays and resumes a round, then keeps the final score through a ranking failure', async () => {
    vi.stubEnv('VITE_API_BASE_URL', 'http://localhost/api');
    const fetchRanking = vi
        .fn()
        .mockRejectedValueOnce(new Error('offline'))
        .mockResolvedValueOnce(
            new Response(JSON.stringify([{ rank: 1, nickname: 'Ola', score: 100 }])),
        );
    vi.stubGlobal('fetch', fetchRanking);
    const { wrapper, button, tracker, socket } = await mountCreatedRound();
    const rules = wrapper.get('section[aria-labelledby="round-rules-title"]');
    expect(rules.get('h3').text()).toBe('Zasady rundy');
    expect(rules.findAll('li')).toHaveLength(3);
    expect(button('Rozpocznij rundę').attributes('disabled')).toBeDefined();
    await button('Uruchom kamerę').trigger('click');
    expect(wrapper.text()).toContain('Uruchamianie kamery…');
    const start = button('Rozpocznij rundę');
    expect(start.attributes('disabled')).toBeDefined();
    tracker.frame(null, 0);
    await nextTick();
    expect(start.attributes('disabled')).toBeUndefined();
    await Promise.all([start.trigger('click'), start.trigger('click')]);

    const countdown = () => wrapper.get('.ready-copy [role="status"]').text();
    expect(start.attributes('disabled')).toBeDefined();
    expect(countdown()).toBe('3');
    expect(wrapper.find('.round-rules').exists()).toBe(false);
    const firstNumber = wrapper.get('.countdown-number').element;
    await vi.advanceTimersByTimeAsync(1000);
    expect(countdown()).toBe('2');
    expect(wrapper.get('.countdown-number').element).not.toBe(firstNumber);
    await vi.advanceTimersByTimeAsync(1000);
    expect(countdown()).toBe('1');
    await vi.advanceTimersByTimeAsync(999);
    expect(startRequests(socket)).toHaveLength(0);
    expect(wrapper.find('[role="timer"]').exists()).toBe(false);
    await vi.advanceTimersByTimeAsync(1);
    expect(startRequests(socket)).toEqual([{ type: 'start_game' }]);
    expect(button('Rozpoczynanie…').attributes('disabled')).toBeDefined();

    socket.message(runningRound);
    await nextTick();
    expect(wrapper.get('[aria-current="step"]').text()).toBe('L');
    expect(wrapper.get('[role="img"]').attributes('aria-label')).toContain('litery L');
    expect(wrapper.text()).toContain('Wyjmij dłoń z kadru');
    expect(wrapper.get('[role="timer"]').text()).toBe('00:10');
    await vi.advanceTimersByTimeAsync(2000);
    expect(wrapper.get('[role="timer"]').text()).toBe('00:08');
    socket.message({ ...runningRound, requiresRelease: false, remainingMs: 8100 });
    await nextTick();
    expect(wrapper.text()).not.toContain('Wyjmij dłoń z kadru');
    const nextPosition = {
        ...runningRound,
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
    openSocket(resumed);
    const final = { ...createdRound, status: 'FINISHED', remainingMs: 0, score: 20 };
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
    expect(wrapper.findAll('tbody td').map((cell) => cell.text())).toEqual(['01', 'Ola', '100']);
    expect(wrapper.get('.final-score').text()).toBe('20 pkt');
    expect(fetchRanking).toHaveBeenCalledTimes(2);
    await button('Nowa sesja').trigger('click');
    expect(wrapper.find('form').exists()).toBe(true);
    expect(resumed.close).toHaveBeenCalled();
    wrapper.unmount();
});

it('cancels the countdown when the camera stops and requires a new frame before retry', async () => {
    const { wrapper, button, tracker, socket } = await mountCreatedRound();
    await button('Uruchom kamerę').trigger('click');
    tracker.frame(null, 0);
    await nextTick();
    await button('Rozpocznij rundę').trigger('click');
    await vi.advanceTimersByTimeAsync(1000);

    await button('Zatrzymaj kamerę').trigger('click');
    expect(wrapper.get('.round-rules h3').text()).toBe('Zasady rundy');
    await button('Uruchom kamerę').trigger('click');
    expect(button('Rozpocznij rundę').attributes('disabled')).toBeDefined();
    tracker.frame(null, 1000);
    await vi.advanceTimersByTimeAsync(3000);
    expect(startRequests(socket)).toHaveLength(0);

    await button('Rozpocznij rundę').trigger('click');
    expect(wrapper.get('.countdown-number').text()).toBe('3');
    await vi.advanceTimersByTimeAsync(3000);
    expect(startRequests(socket)).toHaveLength(1);
    wrapper.unmount();
});

it('cancels on disconnect and starts a fresh countdown after resuming a created session', async () => {
    const { wrapper, button, tracker, socket } = await mountCreatedRound();
    await button('Uruchom kamerę').trigger('click');
    tracker.frame(null, 0);
    await nextTick();
    await button('Rozpocznij rundę').trigger('click');
    await vi.advanceTimersByTimeAsync(1000);

    socket.dispatchEvent(new CloseEvent('close', { code: 1006 }));
    await nextTick();
    await button('Wznów sesję').trigger('click');
    const resumed = Socket.last;
    openSocket(resumed);
    resumed.message(createdRound);
    await vi.advanceTimersByTimeAsync(3000);
    expect(startRequests(socket)).toHaveLength(0);
    expect(startRequests(resumed)).toHaveLength(0);

    await button('Rozpocznij rundę').trigger('click');
    expect(wrapper.get('.countdown-number').text()).toBe('3');
    await vi.advanceTimersByTimeAsync(3000);
    expect(startRequests(resumed)).toHaveLength(1);
    wrapper.unmount();
});

it.each(['back', 'unmount'])('clears the countdown on %s', async (action) => {
    const { wrapper, button, tracker, socket } = await mountCreatedRound();
    await button('Uruchom kamerę').trigger('click');
    tracker.frame(null, 0);
    await nextTick();
    await button('Rozpocznij rundę').trigger('click');
    await vi.advanceTimersByTimeAsync(1000);
    expect(vi.getTimerCount()).toBe(1);

    if (action === 'back') {
        await button('Wróć').trigger('click');
        expect(wrapper.find('form').exists()).toBe(true);
        wrapper.unmount();
    } else {
        wrapper.unmount();
    }
    expect(vi.getTimerCount()).toBe(0);
    await vi.advanceTimersByTimeAsync(3000);
    expect(startRequests(socket)).toHaveLength(0);
});

it('resumes a running round directly and explains the ranking when leaving it', async () => {
    const { wrapper, button, tracker, socket } = await mountCreatedRound();
    await button('Uruchom kamerę').trigger('click');
    tracker.frame(null, 0);
    await nextTick();
    await button('Rozpocznij rundę').trigger('click');
    await vi.advanceTimersByTimeAsync(3000);
    expect(startRequests(socket)).toHaveLength(1);

    socket.dispatchEvent(new CloseEvent('close', { code: 1006 }));
    await nextTick();
    await button('Wznów sesję').trigger('click');
    const resumed = Socket.last;
    openSocket(resumed);
    resumed.message({ ...runningRound, remainingMs: 8000, score: 10 });
    await nextTick();
    expect(wrapper.get('[role="timer"]').text()).toBe('00:08');
    expect(wrapper.find('.countdown-number').exists()).toBe(false);
    expect(startRequests(resumed)).toHaveLength(0);

    const dialog = wrapper.get('dialog.leave-dialog');
    // jsdom supports the open attribute but does not put dialogs in the modal top layer.
    Object.assign(dialog.element, {
        showModal: () => dialog.element.setAttribute('open', ''),
        close: () => dialog.element.removeAttribute('open'),
    });
    expect(dialog.attributes('open')).toBeUndefined();
    expect(wrapper.find('.camera-area dialog').exists()).toBe(false);
    await button('Opuść rundę').trigger('click');
    expect(dialog.attributes('open')).toBeDefined();
    expect(dialog.get('#leave-round-description').text()).toContain('Po upływie czasu rundy');
    expect(dialog.get('#leave-round-description').text()).toContain('poprawia zapisany rekord');
    expect(button('Graj dalej').attributes('autofocus')).toBeDefined();
    await vi.advanceTimersByTimeAsync(1000);
    expect(wrapper.get('[role="timer"]').text()).toBe('00:07');
    expect(tracker.stop).not.toHaveBeenCalled();
    await button('Graj dalej').trigger('click');
    expect(dialog.attributes('open')).toBeUndefined();
    expect(resumed.close).not.toHaveBeenCalled();

    await button('Opuść rundę').trigger('click');
    await button('Tak, opuść rundę').trigger('click');
    expect(wrapper.find('form').exists()).toBe(true);
    expect(wrapper.find('dialog.leave-dialog').exists()).toBe(false);
    expect(wrapper.find('video').exists()).toBe(false);
    expect(tracker.stop).toHaveBeenCalledOnce();
    expect(resumed.close).toHaveBeenCalledOnce();
    wrapper.unmount();
});
