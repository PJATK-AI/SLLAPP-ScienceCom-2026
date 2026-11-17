import type { HandSampleData, ServerMessage } from '@/models/game-session';

export function parseSessionMessage(data: unknown): ServerMessage {
    if (typeof data !== 'string') throw new Error('Expected a JSON text message.');
    const value: unknown = JSON.parse(data);
    if (!value || typeof value !== 'object' || Array.isArray(value))
        throw new Error('Expected an object.');
    const v = value as Record<string, unknown>;
    if (
        v.type === 'game_state' &&
        typeof v.sessionId === 'string' &&
        /^[\da-f]{8}-[\da-f]{4}-[\da-f]{4}-[\da-f]{4}-[\da-f]{12}$/i.test(v.sessionId) &&
        v.status === 'CREATED' &&
        typeof v.nickname === 'string' &&
        v.nickname.trim() &&
        (v.selectedHand === 'LEFT' || v.selectedHand === 'RIGHT') &&
        v.positionId === null
    )
        return value as ServerMessage;
    if (v.type === 'error' && typeof v.code === 'string' && typeof v.message === 'string')
        return value as ServerMessage;
    if (
        v.type === 'sample_ack' &&
        typeof v.seq === 'number' &&
        Number.isSafeInteger(v.seq) &&
        v.seq >= 0
    )
        return value as ServerMessage;
    throw new Error('Invalid or unsupported server message.');
}

export function serializeSample(seq: number, positionId: null, hand?: HandSampleData): string {
    if (!Number.isSafeInteger(seq) || seq < 0)
        throw new Error('Sample sequence is exhausted. Reconnect the session.');
    if (positionId !== null) throw new Error('Expected positionId: null.');
    if (
        hand !== undefined &&
        (!hand ||
            !['LEFT', 'RIGHT'].includes(hand.handedness) ||
            !Array.isArray(hand.landmarks) ||
            hand.landmarks.length !== 21 ||
            !hand.landmarks.every(
                (p) =>
                    p && [p.x, p.y, p.z].every((n) => typeof n === 'number' && Number.isFinite(n)),
            ))
    )
        throw new Error('Expected a hand with 21 finite landmarks and LEFT or RIGHT handedness.');
    return JSON.stringify(
        hand === undefined
            ? { type: 'no_hand', seq, positionId }
            : { type: 'hand_sample', seq, positionId, hand },
    );
}
