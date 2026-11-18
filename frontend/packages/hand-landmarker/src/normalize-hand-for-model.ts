import type { Hand } from './models';

export type ModelHand = Pick<Hand, 'handedness' | 'score' | 'landmarks'>;

/** Reflect the left hand in image coordinates for a model trained on right hands. */
export function normalizeHandForModel(hand: Hand): ModelHand {
    if (hand.landmarks.length !== 21) {
        throw new RangeError('Expected exactly 21 image landmarks.');
    }

    const landmarks = [];
    for (const [index, point] of hand.landmarks.entries()) {
        if (!point || ![point.x, point.y, point.z].every(Number.isFinite)) {
            throw new RangeError(`Landmark ${index} must have finite x, y and z coordinates.`);
        }
        const { x, y, z } = point;
        landmarks.push({ x: hand.handedness === 'Left' ? 1 - x : x, y, z });
    }

    return { handedness: hand.handedness, score: hand.score, landmarks };
}
