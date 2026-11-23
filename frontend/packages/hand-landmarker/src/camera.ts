import { HandTrackerError } from './models';

/**
 * Opens the user-facing camera with the same settings that the tracker uses.
 * Rejects with a `HandTrackerError` with the code `camera-denied` or `camera-unavailable`.
 */
export async function openCamera(): Promise<MediaStream> {
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

/** Stops all tracks of a camera stream. */
export function stopStream(mediaStream: MediaStream): void {
    for (const track of mediaStream.getTracks()) {
        track.stop();
    }
}
