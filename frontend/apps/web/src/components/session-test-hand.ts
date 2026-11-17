import type { HandSampleData, SelectedHand } from '@/models';
/** Synthetic transport fixture, not a recognized sign or camera detection. */
export function createTestHand(handedness: SelectedHand): HandSampleData {
    return {
        handedness,
        landmarks: Array.from({ length: 21 }, (_, i) => ({
            x: 0.3 + i * 0.01,
            y: 0.6 - i * 0.01,
            z: -i * 0.001,
        })),
    };
}
