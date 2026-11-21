import { describe, expect, it } from 'vitest';
import { parseSessionMessage, serializeSample } from './session-protocol';

describe('session protocol', () => {
    it('preserves explicit null and hand metadata', () => {
        const hand = {
            handedness: 'LEFT' as const,
            coordinateSpace: 'MEDIAPIPE_IMAGE' as const,
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
        expect(JSON.parse(serializeSample(2, 7, hand))).toMatchObject({
            type: 'hand_sample',
            positionId: 7,
        });
        expect(JSON.parse(serializeSample(3, 7))).toEqual({
            type: 'no_hand',
            seq: 3,
            positionId: 7,
        });
        for (const invalid of [0, -1, 1.5, Number.MAX_SAFE_INTEGER + 1]) {
            expect(() => serializeSample(0, invalid)).toThrow('positionId');
        }
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
                coordinateSpace: 'MEDIAPIPE_IMAGE',
                landmarks: Array.from({ length: 21 }, () => ({ x: NaN, y: 0, z: 0 })),
            }),
        ).toThrow();
    });
    const game = {
        type: 'game_state',
        sessionId: '12345678-1234-1234-1234-123456789abc',
        nickname: 'Ola',
        selectedHand: 'RIGHT',
    };
    const running = {
        ...game,
        status: 'RUNNING',
        positionId: 2,
        word: 'LAS',
        letterIndex: 1,
        expectedLetter: 'A',
        remainingMs: 59800,
        score: 10,
        requiresRelease: false,
    };

    it.each([
        { ...game, status: 'CREATED', positionId: null },
        running,
        { ...game, status: 'FINISHED', positionId: null, remainingMs: 0, score: 20 },
    ])('parses the server snapshot in $status', (state) => {
        expect(parseSessionMessage(JSON.stringify(state))).toEqual(state);
    });

    it.each([
        { positionId: null },
        { positionId: Number.MAX_SAFE_INTEGER + 1 },
        { remainingMs: -1 },
        { score: 0.5 },
        { requiresRelease: 'false' },
        { letterIndex: 3 },
        { expectedLetter: 'S' },
        { word: 'GAS' },
        { status: 'FINISHED', positionId: null },
    ])('rejects an inconsistent or malformed game state: %j', (override) => {
        expect(() => parseSessionMessage(JSON.stringify({ ...running, ...override }))).toThrow();
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
    it('validates a prediction result independently of the game state', () => {
        const prediction = {
            type: 'prediction_result',
            seq: 0,
            positionId: 1,
            elapsedMs: 15,
            letter: 'L',
            confidence: 0.92,
            httpStatus: 200,
            disposition: 'PROCESSED',
        };
        expect(parseSessionMessage(JSON.stringify(prediction))).toEqual(prediction);
        expect(() =>
            parseSessionMessage(JSON.stringify({ ...prediction, confidence: 2 })),
        ).toThrow();
        expect(() =>
            parseSessionMessage(JSON.stringify({ ...prediction, failure: 'DISABLED' })),
        ).toThrow();
    });
});
