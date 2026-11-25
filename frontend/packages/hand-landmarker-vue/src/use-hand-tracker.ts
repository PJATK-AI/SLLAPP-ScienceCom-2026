import {
    createHandTracker,
    HandTrackerError,
    type Hand,
    type HandTrackerOptions,
} from '@sllapp/hand-landmarker';
import {
    getCurrentScope,
    onScopeDispose,
    ref,
    shallowReadonly,
    shallowRef,
    toValue,
    watch,
    type MaybeRefOrGetter,
    type Ref,
    type ShallowRef,
} from 'vue';

export interface UseHandTrackerReturn {
    /** The selected hand with image and wrist-relative landmarks, or null if no hand is visible. */
    hand: Readonly<ShallowRef<Hand | null>>;
    /** True from the call to `start()` until the call to `stop()` or an error. */
    isRunning: Readonly<Ref<boolean>>;
    /** True after the current camera run processes a frame, even if no hand is visible. */
    hasProcessedFrame: Readonly<Ref<boolean>>;
    /** The latest error, or null. `start()` clears it. */
    error: Readonly<ShallowRef<HandTrackerError | null>>;
    /**
     * Starts the camera and hand detection in the video element.
     * Errors go to `error`; the promise does not reject for them.
     */
    start(): Promise<void>;
    /** Stops hand detection and the camera. */
    stop(): void;
}

/**
 * Tracks one hand in a video element. Stops the camera and releases the model
 * when the component unmounts or the effect scope stops.
 */
export function useHandTracker(
    video: MaybeRefOrGetter<HTMLVideoElement | null | undefined>,
    options: HandTrackerOptions,
    onFrame?: (hand: Hand | null, timestamp: number) => void,
): UseHandTrackerReturn {
    const tracker = createHandTracker(options);
    const hand = shallowRef<Hand | null>(null);
    const isRunning = ref(false);
    const hasProcessedFrame = ref(false);
    const error = shallowRef<HandTrackerError | null>(null);

    const unsubscribeResult = tracker.onResult((result, timestamp) => {
        if (!isRunning.value) return;
        hand.value = result;
        hasProcessedFrame.value = true;
        onFrame?.(result, timestamp);
    });
    const unsubscribeError = tracker.onError((trackerError) => {
        error.value = trackerError;
        isRunning.value = false;
        hasProcessedFrame.value = false;
        hand.value = null;
    });

    async function start(): Promise<void> {
        const element = toValue(video);
        if (!element) {
            throw new Error('The video element is not available. Call start() after mount.');
        }
        error.value = null;
        isRunning.value = true;
        try {
            await tracker.start(element);
        } catch (cause) {
            // The onError callback already stored a HandTrackerError.
            if (!(cause instanceof HandTrackerError)) {
                throw cause;
            }
        } finally {
            isRunning.value = tracker.isRunning;
        }
    }

    function stop(): void {
        tracker.stop();
        isRunning.value = false;
        hasProcessedFrame.value = false;
        hand.value = null;
    }

    // The camera stream belongs to one video element. If the element changes, stop.
    watch(
        () => toValue(video),
        (element, previous) => {
            if (previous && element !== previous && isRunning.value) {
                stop();
            }
        },
    );

    if (getCurrentScope()) {
        onScopeDispose(() => {
            unsubscribeResult();
            unsubscribeError();
            tracker.dispose();
            isRunning.value = false;
            hasProcessedFrame.value = false;
            hand.value = null;
        });
    }

    return {
        hand: shallowReadonly(hand),
        isRunning: shallowReadonly(isRunning),
        hasProcessedFrame: shallowReadonly(hasProcessedFrame),
        error: shallowReadonly(error),
        start,
        stop,
    };
}
