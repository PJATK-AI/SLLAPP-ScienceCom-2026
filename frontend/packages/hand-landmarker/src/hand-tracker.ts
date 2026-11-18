import {
    FilesetResolver,
    HandLandmarker,
    type HandLandmarkerOptions,
    type HandLandmarkerResult,
} from '@mediapipe/tasks-vision';
import { HandTrackerError, type Hand, type HandTracker, type HandTrackerOptions } from './models';
import { selectHand } from './select-hand';

type ResultCallback = Parameters<HandTracker['onResult']>[0];
type ErrorCallback = Parameters<HandTracker['onError']>[0];

const NUM_HANDS = 2;
const DEFAULT_MIN_CONFIDENCE = 0.5;

export function createHandTracker(options: HandTrackerOptions): HandTracker {
    const resultCallbacks = new Set<ResultCallback>();
    const errorCallbacks = new Set<ErrorCallback>();

    let landmarkerPromise: Promise<HandLandmarker> | null = null;
    let landmarker: HandLandmarker | null = null;
    let startPromise: Promise<void> | null = null;
    let stream: MediaStream | null = null;
    let video: HTMLVideoElement | null = null;
    let frameId: number | null = null;
    let lastVideoTime = -1;
    let running = false;
    let disposed = false;
    // Increases at each stop, so that a start that is still in progress can see that it was cancelled.
    let session = 0;

    function loadLandmarker(): Promise<HandLandmarker> {
        landmarkerPromise ??= createLandmarker(options).catch((cause: unknown) => {
            landmarkerPromise = null;
            throw new HandTrackerError(
                'model-load-failed',
                'Failed to load the hand landmarker model.',
                { cause },
            );
        });
        return landmarkerPromise;
    }

    function reportError(error: HandTrackerError): void {
        for (const callback of errorCallbacks) {
            callback(error);
        }
    }

    function detectFrame(): void {
        frameId = null;
        if (!running || video === null || landmarker === null) {
            return;
        }
        if (
            video.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA &&
            video.currentTime !== lastVideoTime
        ) {
            lastVideoTime = video.currentTime;
            const timestamp = performance.now();
            const result = landmarker.detectForVideo(video, timestamp);
            const hand = selectHand(toHands(result), options.preferredHand);
            for (const callback of resultCallbacks) {
                callback(hand, timestamp);
            }
        }
        if (running) {
            frameId = requestAnimationFrame(detectFrame);
        }
    }

    async function runStart(element: HTMLVideoElement, currentSession: number): Promise<void> {
        const [landmarkerResult, cameraResult] = await Promise.allSettled([
            loadLandmarker(),
            openCamera(),
        ]);
        const cancelled = currentSession !== session;

        if (
            cancelled ||
            landmarkerResult.status === 'rejected' ||
            cameraResult.status === 'rejected'
        ) {
            if (cameraResult.status === 'fulfilled') {
                stopStream(cameraResult.value);
            }
            if (cancelled) {
                return;
            }
            running = false;
            throw landmarkerResult.status === 'rejected'
                ? landmarkerResult.reason
                : (cameraResult as PromiseRejectedResult).reason;
        }

        landmarker = landmarkerResult.value;
        stream = cameraResult.value;
        video = element;
        element.muted = true;
        element.playsInline = true;
        element.srcObject = stream;
        try {
            await element.play();
        } catch (cause) {
            if (currentSession !== session) {
                return;
            }
            stop();
            throw new HandTrackerError('camera-unavailable', 'Failed to play the camera stream.', {
                cause,
            });
        }
        if (currentSession === session) {
            frameId = requestAnimationFrame(detectFrame);
        }
    }

    function start(element: HTMLVideoElement): Promise<void> {
        if (disposed) {
            return Promise.reject(new Error('The hand tracker is disposed.'));
        }
        if (startPromise !== null) {
            return startPromise;
        }
        if (running) {
            return Promise.resolve();
        }

        running = true;
        const currentSession = session;
        startPromise = runStart(element, currentSession)
            .catch((error: unknown) => {
                if (error instanceof HandTrackerError) {
                    reportError(error);
                }
                throw error;
            })
            .finally(() => {
                if (currentSession === session) {
                    startPromise = null;
                }
            });
        return startPromise;
    }

    function stop(): void {
        session++;
        running = false;
        startPromise = null;
        if (frameId !== null) {
            cancelAnimationFrame(frameId);
            frameId = null;
        }
        if (stream !== null) {
            stopStream(stream);
            stream = null;
        }
        if (video !== null) {
            video.pause();
            video.srcObject = null;
            video = null;
        }
        lastVideoTime = -1;
    }

    return {
        start,
        stop,
        onResult(callback) {
            resultCallbacks.add(callback);
            return () => resultCallbacks.delete(callback);
        },
        onError(callback) {
            errorCallbacks.add(callback);
            return () => errorCallbacks.delete(callback);
        },
        get isRunning() {
            return running;
        },
        dispose() {
            if (disposed) {
                return;
            }
            stop();
            disposed = true;
            resultCallbacks.clear();
            errorCallbacks.clear();
            landmarkerPromise?.then((instance) => instance.close()).catch(() => {});
            landmarkerPromise = null;
            landmarker = null;
        },
    };
}

async function createLandmarker(options: HandTrackerOptions): Promise<HandLandmarker> {
    const fileset = await FilesetResolver.forVisionTasks(options.wasmPath);
    const minConfidence = options.minConfidence ?? DEFAULT_MIN_CONFIDENCE;
    const landmarkerOptions = (delegate: 'GPU' | 'CPU'): HandLandmarkerOptions => ({
        baseOptions: { modelAssetPath: options.modelAssetPath, delegate },
        runningMode: 'VIDEO',
        numHands: NUM_HANDS,
        minHandDetectionConfidence: minConfidence,
        minHandPresenceConfidence: minConfidence,
        minTrackingConfidence: minConfidence,
    });

    try {
        return await HandLandmarker.createFromOptions(fileset, landmarkerOptions('GPU'));
    } catch {
        // The GPU delegate needs WebGL. Without it, use the CPU.
        return HandLandmarker.createFromOptions(fileset, landmarkerOptions('CPU'));
    }
}

async function openCamera(): Promise<MediaStream> {
    if (typeof navigator === 'undefined' || !navigator.mediaDevices?.getUserMedia) {
        throw new HandTrackerError(
            'camera-unavailable',
            'This browser does not support camera access.',
        );
    }
    try {
        return await navigator.mediaDevices.getUserMedia({
            video: { facingMode: 'user' },
            audio: false,
        });
    } catch (cause) {
        const denied =
            cause instanceof DOMException &&
            (cause.name === 'NotAllowedError' || cause.name === 'SecurityError');
        throw denied
            ? new HandTrackerError('camera-denied', 'Camera access was denied.', { cause })
            : new HandTrackerError('camera-unavailable', 'The camera is not available.', { cause });
    }
}

function stopStream(mediaStream: MediaStream): void {
    for (const track of mediaStream.getTracks()) {
        track.stop();
    }
}

function toHands(result: HandLandmarkerResult): Hand[] {
    const hands: Hand[] = [];
    for (const [index, landmarks] of result.landmarks.entries()) {
        const wrist = landmarks[0];
        if (!wrist || landmarks.length !== 21) continue;

        const points = [];
        for (const point of landmarks) {
            if (!point || ![point.x, point.y, point.z].every(Number.isFinite)) break;
            const { x, y, z } = point;
            points.push({ x, y, z });
        }
        if (points.length !== 21) continue;

        const category = result.handedness[index]?.[0];
        hands.push({
            handedness: category?.categoryName === 'Left' ? 'Left' : 'Right',
            score: category?.score ?? 0,
            landmarks: points,
            wristRelativeLandmarks: points.map(({ x, y, z }) => ({
                x: x - wrist.x,
                y: y - wrist.y,
                z: z - wrist.z,
            })),
        });
    }
    return hands;
}
