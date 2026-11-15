import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { HandTrackerError, type HandTrackerOptions } from '../src/models';
import { createHandTracker } from '../src/hand-tracker';

const mediapipe = vi.hoisted(() => ({
    forVisionTasks: vi.fn(),
    createFromOptions: vi.fn(),
}));

vi.mock('@mediapipe/tasks-vision', () => ({
    FilesetResolver: { forVisionTasks: mediapipe.forVisionTasks },
    HandLandmarker: { createFromOptions: mediapipe.createFromOptions },
}));

const options: HandTrackerOptions = {
    modelAssetPath: '/mediapipe/hand_landmarker.task',
    wasmPath: '/mediapipe/wasm',
};

const landmark = { x: 0.1, y: 0.2, z: 0.3, visibility: 0 };

function detection(hands: { name: 'Left' | 'Right'; score: number }[]) {
    return {
        landmarks: hands.map(() => [landmark]),
        worldLandmarks: [],
        handednesses: [],
        handedness: hands.map(({ name, score }) => [
            { categoryName: name, score, index: 0, displayName: name },
        ]),
    };
}

let landmarker: { detectForVideo: ReturnType<typeof vi.fn>; close: ReturnType<typeof vi.fn> };
let track: { stop: ReturnType<typeof vi.fn> };
let stream: MediaStream;
let getUserMedia: ReturnType<typeof vi.fn>;
let frames: Map<number, FrameRequestCallback>;
let video: HTMLVideoElement;
let videoTime: number;

function runFrame(): void {
    const [id, callback] = [...frames][0] ?? [];
    if (id === undefined || callback === undefined) {
        throw new Error('No frame is scheduled.');
    }
    frames.delete(id);
    videoTime += 1;
    callback(0);
}

beforeEach(() => {
    landmarker = { detectForVideo: vi.fn(() => detection([])), close: vi.fn() };
    mediapipe.forVisionTasks.mockResolvedValue({});
    mediapipe.createFromOptions.mockResolvedValue(landmarker);

    track = { stop: vi.fn() };
    stream = { getTracks: () => [track] } as unknown as MediaStream;
    getUserMedia = vi.fn().mockResolvedValue(stream);
    vi.stubGlobal('navigator', { mediaDevices: { getUserMedia } });

    frames = new Map();
    let nextFrameId = 1;
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
        frames.set(nextFrameId, callback);
        return nextFrameId++;
    });
    vi.stubGlobal('cancelAnimationFrame', (id: number) => frames.delete(id));

    vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue();
    vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(() => {});

    video = document.createElement('video');
    videoTime = 0;
    Object.defineProperty(video, 'readyState', { get: () => HTMLMediaElement.HAVE_ENOUGH_DATA });
    Object.defineProperty(video, 'currentTime', { get: () => videoTime });
});

afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    mediapipe.forVisionTasks.mockReset();
    mediapipe.createFromOptions.mockReset();
});

describe('createHandTracker', () => {
    it('loads the model from the paths that the application gives', async () => {
        const tracker = createHandTracker(options);
        await tracker.start(video);

        expect(mediapipe.forVisionTasks).toHaveBeenCalledWith('/mediapipe/wasm');
        expect(mediapipe.createFromOptions).toHaveBeenCalledWith(
            {},
            expect.objectContaining({
                baseOptions: { modelAssetPath: '/mediapipe/hand_landmarker.task', delegate: 'GPU' },
                runningMode: 'VIDEO',
                numHands: 2,
            }),
        );
    });

    it('uses the CPU when the GPU delegate fails', async () => {
        mediapipe.createFromOptions
            .mockRejectedValueOnce(new Error('No WebGL'))
            .mockResolvedValueOnce(landmarker);
        const tracker = createHandTracker(options);
        await tracker.start(video);

        expect(mediapipe.createFromOptions).toHaveBeenLastCalledWith(
            {},
            expect.objectContaining({
                baseOptions: { modelAssetPath: options.modelAssetPath, delegate: 'CPU' },
            }),
        );
    });

    it('shows the camera stream in the video element', async () => {
        const tracker = createHandTracker(options);
        await tracker.start(video);

        expect(getUserMedia).toHaveBeenCalledWith({ video: { facingMode: 'user' }, audio: false });
        expect(video.srcObject).toBe(stream);
        expect(video.muted).toBe(true);
        expect(tracker.isRunning).toBe(true);
    });

    it('returns the preferred hand for each frame', async () => {
        landmarker.detectForVideo.mockReturnValue(
            detection([
                { name: 'Right', score: 0.9 },
                { name: 'Left', score: 0.6 },
            ]),
        );
        const tracker = createHandTracker({ ...options, preferredHand: 'Left' });
        const onResult = vi.fn();
        tracker.onResult(onResult);
        await tracker.start(video);
        runFrame();

        expect(landmarker.detectForVideo).toHaveBeenCalledWith(video, expect.any(Number));
        expect(onResult).toHaveBeenCalledWith(
            { handedness: 'Left', score: 0.6, landmarks: [{ x: 0.1, y: 0.2, z: 0.3 }] },
            expect.any(Number),
        );
    });

    it('returns null when no hand is detected', async () => {
        const tracker = createHandTracker(options);
        const onResult = vi.fn();
        tracker.onResult(onResult);
        await tracker.start(video);
        runFrame();

        expect(onResult).toHaveBeenCalledWith(null, expect.any(Number));
    });

    it('does not process the same video frame twice', async () => {
        const tracker = createHandTracker(options);
        await tracker.start(video);
        runFrame();
        videoTime -= 1;
        runFrame();

        expect(landmarker.detectForVideo).toHaveBeenCalledTimes(1);
    });

    it('stops calling a removed result callback', async () => {
        const tracker = createHandTracker(options);
        const onResult = vi.fn();
        const remove = tracker.onResult(onResult);
        remove();
        await tracker.start(video);
        runFrame();

        expect(onResult).not.toHaveBeenCalled();
    });

    it('stops detection and the camera', async () => {
        const tracker = createHandTracker(options);
        await tracker.start(video);
        tracker.stop();

        expect(track.stop).toHaveBeenCalled();
        expect(video.srcObject).toBeNull();
        expect(frames.size).toBe(0);
        expect(tracker.isRunning).toBe(false);
    });

    it('releases the camera when stop is called during start', async () => {
        const tracker = createHandTracker(options);
        const starting = tracker.start(video);
        tracker.stop();
        await starting;

        expect(track.stop).toHaveBeenCalled();
        expect(video.srcObject).not.toBe(stream);
        expect(tracker.isRunning).toBe(false);
        expect(frames.size).toBe(0);
    });

    it('reports an error when the model fails to load', async () => {
        mediapipe.createFromOptions.mockRejectedValue(new Error('404'));
        const tracker = createHandTracker(options);
        const onError = vi.fn();
        tracker.onError(onError);

        const error = await tracker.start(video).catch((reason: unknown) => reason);

        expect(error).toBeInstanceOf(HandTrackerError);
        expect(error).toMatchObject({ code: 'model-load-failed' });
        expect(onError).toHaveBeenCalledWith(error);
        expect(track.stop).toHaveBeenCalled();
        expect(tracker.isRunning).toBe(false);
    });

    it('reports an error when camera access is denied', async () => {
        getUserMedia.mockRejectedValue(new DOMException('Denied', 'NotAllowedError'));
        const tracker = createHandTracker(options);

        await expect(tracker.start(video)).rejects.toMatchObject({ code: 'camera-denied' });
        expect(tracker.isRunning).toBe(false);
    });

    it('reports an error when no camera is found', async () => {
        getUserMedia.mockRejectedValue(new DOMException('No camera', 'NotFoundError'));
        const tracker = createHandTracker(options);

        await expect(tracker.start(video)).rejects.toMatchObject({ code: 'camera-unavailable' });
    });

    it('reports an error when the browser does not support camera access', async () => {
        vi.stubGlobal('navigator', {});
        const tracker = createHandTracker(options);

        await expect(tracker.start(video)).rejects.toMatchObject({ code: 'camera-unavailable' });
    });

    it('can start again after an error', async () => {
        getUserMedia.mockRejectedValueOnce(new DOMException('No camera', 'NotFoundError'));
        const tracker = createHandTracker(options);
        await tracker.start(video).catch(() => {});
        await tracker.start(video);

        expect(tracker.isRunning).toBe(true);
    });

    it('releases the model on dispose', async () => {
        const tracker = createHandTracker(options);
        await tracker.start(video);
        tracker.dispose();
        await Promise.resolve();

        expect(landmarker.close).toHaveBeenCalled();
        expect(track.stop).toHaveBeenCalled();
        await expect(tracker.start(video)).rejects.toThrow('disposed');
    });
});
