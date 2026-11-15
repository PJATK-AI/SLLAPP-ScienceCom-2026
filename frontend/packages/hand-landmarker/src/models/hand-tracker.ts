import type { Hand } from './hand';
import type { HandTrackerError } from './hand-tracker-error-code';

export interface HandTracker {
    /**
     * Loads the model, starts the camera, shows its stream in the video element and starts detection.
     * Rejects with a `HandTrackerError` and also calls the `onError` callbacks if it fails.
     */
    start(video: HTMLVideoElement): Promise<void>;
    /** Stops detection and the camera. */
    stop(): void;
    /** Calls the callback for each processed video frame. Returns a function that removes the callback. */
    onResult(callback: (hand: Hand | null, timestamp: number) => void): () => void;
    /** Calls the callback when an error occurs. Returns a function that removes the callback. */
    onError(callback: (error: HandTrackerError) => void): () => void;
    /** True from the call to `start()` until the call to `stop()` or a start error. */
    readonly isRunning: boolean;
    /** Stops the tracker and releases the model. The tracker cannot start again after this call. */
    dispose(): void;
}
