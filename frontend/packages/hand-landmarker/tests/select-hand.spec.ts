import { describe, expect, it } from 'vitest';
import { selectHand } from '../src/select-hand';
import type { Hand, Handedness } from '../src/models';

function hand(handedness: Handedness, score: number): Hand {
    return { handedness, score, landmarks: [] };
}

describe('selectHand', () => {
    it('returns null when no hand is detected', () => {
        expect(selectHand([], 'Left')).toBeNull();
    });

    it('returns the preferred hand when it is visible', () => {
        const left = hand('Left', 0.6);
        expect(selectHand([hand('Right', 0.9), left], 'Left')).toBe(left);
    });

    it('returns the preferred hand with the higher score when two hands match', () => {
        const best = hand('Left', 0.8);
        expect(selectHand([hand('Left', 0.7), best, hand('Right', 0.95)], 'Left')).toBe(best);
    });

    it('returns the hand with the highest score when no hand matches the preferred hand', () => {
        const best = hand('Right', 0.9);
        expect(selectHand([hand('Right', 0.7), best], 'Left')).toBe(best);
    });

    it('returns the hand with the highest score when there is no preferred hand', () => {
        const best = hand('Left', 0.9);
        expect(selectHand([hand('Right', 0.8), best])).toBe(best);
    });
});
