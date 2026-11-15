import type { Hand, Handedness } from './models';

/**
 * Selects one hand: the preferred hand if it is visible, else the hand with the highest score.
 * If two hands match the preferred hand, selects the one with the higher score.
 */
export function selectHand(hands: readonly Hand[], preferredHand?: Handedness): Hand | null {
    const preferred = preferredHand
        ? hands.filter((hand) => hand.handedness === preferredHand)
        : [];
    const candidates = preferred.length > 0 ? preferred : hands;

    let best: Hand | null = null;
    for (const hand of candidates) {
        if (best === null || hand.score > best.score) {
            best = hand;
        }
    }
    return best;
}
