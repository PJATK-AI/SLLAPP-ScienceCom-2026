import { HandTrackerError, type Hand, type HandTrackerOptions } from '@sllapp/hand-landmarker';
import { mount } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { defineComponent, effectScope, h, nextTick, ref, useTemplateRef } from 'vue';
import { useHandTracker } from '../src/use-hand-tracker';

const core = vi.hoisted(() => ({ createHandTracker: vi.fn() }));

vi.mock('@sllapp/hand-landmarker', async (importOriginal) => ({
    ...(await importOriginal<typeof import('@sllapp/hand-landmarker')>()),
    createHandTracker: core.createHandTracker,
}));

const options: HandTrackerOptions = {
    modelAssetPath: '/mediapipe/hand_landmarker.task',
    wasmPath: '/mediapipe/wasm',
};

const rightHand: Hand = { handedness: 'Right', score: 0.9, landmarks: [{ x: 0, y: 0, z: 0 }] };

function createFakeTracker() {
    let onResult: (hand: Hand | null, timestamp: number) => void = () => {};
    let onError: (error: HandTrackerError) => void = () => {};
    const tracker = {
        isRunning: false,
        start: vi.fn(async () => {
            tracker.isRunning = true;
        }),
        stop: vi.fn(() => {
            tracker.isRunning = false;
        }),
        dispose: vi.fn(),
        onResult: vi.fn((callback: typeof onResult) => {
            onResult = callback;
            return () => {};
        }),
        onError: vi.fn((callback: typeof onError) => {
            onError = callback;
            return () => {};
        }),
        emitResult: (hand: Hand | null) => onResult(hand, 0),
        fail(error: HandTrackerError) {
            tracker.start.mockImplementationOnce(async () => {
                tracker.isRunning = false;
                onError(error);
                throw error;
            });
        },
    };
    return tracker;
}

let tracker: ReturnType<typeof createFakeTracker>;

beforeEach(() => {
    tracker = createFakeTracker();
    core.createHandTracker.mockReturnValue(tracker);
});

afterEach(() => {
    core.createHandTracker.mockReset();
});

function setup() {
    const video = ref<HTMLVideoElement | null>(document.createElement('video'));
    const scope = effectScope();
    const result = scope.run(() => useHandTracker(video, options))!;
    return { video, scope, ...result };
}

describe('useHandTracker', () => {
    it('creates the tracker with the given options', () => {
        setup();
        expect(core.createHandTracker).toHaveBeenCalledWith(options);
    });

    it('starts the tracker with the video element', async () => {
        const { video, start, isRunning } = setup();
        await start();

        expect(tracker.start).toHaveBeenCalledWith(video.value);
        expect(isRunning.value).toBe(true);
    });

    it('updates hand for each result', async () => {
        const { start, hand } = setup();
        await start();

        tracker.emitResult(rightHand);
        expect(hand.value).toEqual(rightHand);

        tracker.emitResult(null);
        expect(hand.value).toBeNull();
    });

    it('stores the error and does not reject when the tracker fails', async () => {
        const failure = new HandTrackerError('camera-denied', 'Camera access was denied.');
        tracker.fail(failure);
        const { start, error, isRunning } = setup();

        await expect(start()).resolves.toBeUndefined();
        expect(error.value).toBe(failure);
        expect(isRunning.value).toBe(false);
    });

    it('clears the previous error on start', async () => {
        tracker.fail(new HandTrackerError('camera-unavailable', 'No camera.'));
        const { start, error } = setup();
        await start();
        await start();

        expect(error.value).toBeNull();
    });

    it('rejects when the video element is not available', async () => {
        const { video, start } = setup();
        video.value = null;

        await expect(start()).rejects.toThrow('video element');
        expect(tracker.start).not.toHaveBeenCalled();
    });

    it('stops the tracker and clears the hand', async () => {
        const { start, stop, hand, isRunning } = setup();
        await start();
        tracker.emitResult(rightHand);
        stop();

        expect(tracker.stop).toHaveBeenCalled();
        expect(hand.value).toBeNull();
        expect(isRunning.value).toBe(false);
    });

    it('stops when the video element changes', async () => {
        const { video, start, isRunning } = setup();
        await start();
        video.value = document.createElement('video');
        await nextTick();

        expect(tracker.stop).toHaveBeenCalled();
        expect(isRunning.value).toBe(false);
    });

    it('disposes the tracker when the scope stops', () => {
        const { scope } = setup();
        scope.stop();

        expect(tracker.dispose).toHaveBeenCalled();
    });

    it('works with a template ref and disposes the tracker on unmount', async () => {
        let tracking!: ReturnType<typeof useHandTracker>;
        const Component = defineComponent({
            setup() {
                tracking = useHandTracker(useTemplateRef<HTMLVideoElement>('video'), options);
                return () => h('video', { ref: 'video' });
            },
        });
        const wrapper = mount(Component);
        await tracking.start();

        expect(tracker.start).toHaveBeenCalledWith(wrapper.get('video').element);

        wrapper.unmount();
        expect(tracker.dispose).toHaveBeenCalled();
    });
});
