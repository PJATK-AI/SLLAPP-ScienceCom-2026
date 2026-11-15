import type { Handedness } from './handedness';
import type { Landmark } from './landmark';

export interface Hand {
    /** The hand of the person in front of the camera. */
    handedness: Handedness;
    /** Confidence of the handedness classification (0–1). */
    score: number;
    /** The 21 hand landmarks, in the MediaPipe order. */
    landmarks: Landmark[];
}
