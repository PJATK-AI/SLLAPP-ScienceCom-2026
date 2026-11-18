import type { Handedness } from './handedness';
import type { Landmark } from './landmark';

export interface Hand {
    /** The hand of the person in front of the camera. */
    handedness: Handedness;
    /** Confidence of the handedness classification (0–1). */
    score: number;
    /** The 21 original MediaPipe landmarks, with x and y normalized to the image. */
    landmarks: Landmark[];
    /** The same landmarks translated so the wrist (index 0) is the origin. */
    wristRelativeLandmarks: Landmark[];
}
