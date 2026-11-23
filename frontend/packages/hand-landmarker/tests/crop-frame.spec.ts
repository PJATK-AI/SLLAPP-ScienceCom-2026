import { describe, expect, it } from 'vitest';
import { MIN_CROP_SIZE, normalizeCrop } from '../src/crop-frame';

describe('normalizeCrop', () => {
    it('returns null for no crop, the full image or invalid values', () => {
        expect(normalizeCrop(null)).toBeNull();
        expect(normalizeCrop(undefined)).toBeNull();
        expect(normalizeCrop({ x: 0, y: 0, width: 1, height: 1 })).toBeNull();
        expect(normalizeCrop({ x: 0, y: 0, width: 2, height: 3 })).toBeNull();
        expect(normalizeCrop({ x: Number.NaN, y: 0, width: 0.5, height: 0.5 })).toBeNull();
    });

    it('keeps a crop that is inside the image', () => {
        const crop = { x: 0.2, y: 0.1, width: 0.5, height: 0.6 };
        expect(normalizeCrop(crop)).toEqual(crop);
    });

    it('moves a crop back inside the image', () => {
        expect(normalizeCrop({ x: 0.8, y: -0.2, width: 0.5, height: 0.5 })).toEqual({
            x: 0.5,
            y: 0,
            width: 0.5,
            height: 0.5,
        });
    });

    it('limits the crop to the smallest size', () => {
        expect(normalizeCrop({ x: 0, y: 0, width: 0, height: 0.01 })).toEqual({
            x: 0,
            y: 0,
            width: MIN_CROP_SIZE,
            height: MIN_CROP_SIZE,
        });
    });
});
