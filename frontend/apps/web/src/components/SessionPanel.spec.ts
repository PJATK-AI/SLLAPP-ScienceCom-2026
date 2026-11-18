import { mount } from '@vue/test-utils';
import { afterEach, expect, it, vi } from 'vitest';
import { nextTick } from 'vue';
import SessionPanel from './SessionPanel.vue';
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
});
it('creates a selected session, sends both test samples and displays acknowledgements', async () => {
    mockCamera();
    vi.stubGlobal('WebSocket', Socket);
    vi.stubEnv('VITE_WS_URL', 'ws://localhost/ws');
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
    expect(JSON.parse(wrapper.get('pre').text())).toEqual(hand);
    expect(hand.hand.handedness).toBe('LEFT');
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
    expect(JSON.parse(wrapper.get('pre').text())).toEqual(
        JSON.parse(socket.send.mock.calls[2]![0]),
    );
    expect(wrapper.text()).toContain('Próbka pominięta');
    expect(wrapper.get('[data-testid="sent-seq"]').text()).toBe('1');
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
        expect(JSON.parse(wrapper.get('pre').text())).toEqual(
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
        expect(wrapper.find('pre').exists()).toBe(false);
        tracker.frame(null, 5 * interval + 1);
        await nextTick();
        expect(JSON.parse(wrapper.get('pre').text())).toEqual(sample(resumed)[0]);
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
