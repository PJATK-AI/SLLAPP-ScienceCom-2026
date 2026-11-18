import { expect, it } from 'vitest';
import { normalizeHandForModel, type Hand } from '../src';

function detection(handedness: Hand['handedness']): Hand {
    const landmarks = Array.from({ length: 21 }, (_, index) =>
        Object.freeze({ x: (index + 3) / 32, y: (index + 7) / 64, z: -index / 64 }),
    );
    Object.freeze(landmarks);
    return Object.freeze({
        handedness,
        score: 0.83,
        landmarks,
        wristRelativeLandmarks: landmarks.map(({ x, y, z }) => ({
            x: x - 3 / 32,
            y: y - 7 / 64,
            z,
        })),
    });
}

it('reflects all image points of a left hand while preserving depth, order and metadata', () => {
    const hand = detection('Left');
    const result = normalizeHandForModel(hand);

    expect(result).toEqual({
        handedness: 'Left',
        score: 0.83,
        landmarks: hand.landmarks.map(({ y, z }, index) => ({ x: (29 - index) / 32, y, z })),
    });
    expect(result.landmarks).not.toBe(hand.landmarks);
    result.landmarks.forEach((point, index) => expect(point).not.toBe(hand.landmarks[index]));
    expect(hand.landmarks[0]?.x).toBe(3 / 32);
});

it('copies a right hand without changing the coordinates', () => {
    const hand = detection('Right');
    const result = normalizeHandForModel(hand);

    expect(result).toEqual({ handedness: 'Right', score: hand.score, landmarks: hand.landmarks });
    expect(result.landmarks).not.toBe(hand.landmarks);
    result.landmarks.forEach((point, index) => expect(point).not.toBe(hand.landmarks[index]));
});

it('rejects incomplete and invalid detections', () => {
    const hand = detection('Left');
    expect(() => normalizeHandForModel({ ...hand, landmarks: hand.landmarks.slice(1) })).toThrow(
        RangeError,
    );

    const sparse = hand.landmarks.slice();
    delete sparse[5];
    expect(() => normalizeHandForModel({ ...hand, landmarks: sparse })).toThrow('Landmark 5');

    for (const coordinate of [NaN, Infinity]) {
        const landmarks = hand.landmarks.slice();
        landmarks[6] = { x: 0, y: coordinate, z: 0 };
        expect(() => normalizeHandForModel({ ...hand, landmarks })).toThrow('Landmark 6');
    }
});
