import { describe, expect, it } from 'vitest';
import { parseSessionMessage, serializeSample } from './session-protocol';

describe('session protocol', () => {
    it('preserves explicit null and hand metadata', () => {
        const hand = {
            handedness: 'LEFT' as const,
            landmarks: Array.from({ length: 21 }, () => ({ x: -1, y: 2, z: 0 })),
            score: 0.9,
        };
        expect(JSON.parse(serializeSample(0, null, hand))).toEqual({
            type: 'hand_sample',
            seq: 0,
            positionId: null,
            hand,
        });
        expect(JSON.parse(serializeSample(1, null))).toEqual({
            type: 'no_hand',
            seq: 1,
            positionId: null,
        });
    });
    it.each(['null', '[]', '{', '{"type":"sample_ack","seq":-1}', '{"type":"unknown"}'])(
        'rejects malformed input %s',
        (data) => {
            expect(() => parseSessionMessage(data)).toThrow();
        },
    );
    it('rejects nonfinite points before JSON can turn them into null', () => {
        expect(() =>
            serializeSample(0, null, {
                handedness: 'RIGHT',
                landmarks: Array.from({ length: 21 }, () => ({ x: NaN, y: 0, z: 0 })),
            }),
        ).toThrow();
    });
    it('accepts diagnostic replies and backend errors', () => {
        expect(parseSessionMessage('{"type":"sample_ack","seq":0}')).toEqual({
            type: 'sample_ack',
            seq: 0,
        });
        expect(
            parseSessionMessage(
                '{"type":"error","code":"INVALID_SESSION","message":"Invalid nickname"}',
            ).type,
        ).toBe('error');
    });
});
