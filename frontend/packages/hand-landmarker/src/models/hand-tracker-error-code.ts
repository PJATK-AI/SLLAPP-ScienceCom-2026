export type HandTrackerErrorCode =
    'model-load-failed' | 'camera-unavailable' | 'camera-denied' | 'detection-failed';

export class HandTrackerError extends Error {
    readonly code: HandTrackerErrorCode;

    constructor(code: HandTrackerErrorCode, message: string, options?: { cause?: unknown }) {
        super(message, options);
        this.name = 'HandTrackerError';
        this.code = code;
    }
}
