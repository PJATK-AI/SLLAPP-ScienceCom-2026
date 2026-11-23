import type { CameraCrop } from './camera-crop';
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
    /**
     * The part of the camera image to detect hands in. Default: the full image.
     * The tracker reads it for each frame, so a getter can change it while the tracker runs.
     * Landmarks are then normalized to the crop, not to the full image.
     */
    crop?: CameraCrop | null;
}
