import type { Handedness } from './handedness';

export interface HandTrackerOptions {
    /** URL of the `hand_landmarker.task` model that the application serves. */
    modelAssetPath: string;
    /** URL of the folder with the MediaPipe WASM files that the application serves. */
    wasmPath: string;
    /** The hand to return when two hands are visible. */
    preferredHand?: Handedness;
    /** Minimum confidence for hand detection and tracking (0–1). Default: 0.5. */
    minConfidence?: number;
}
