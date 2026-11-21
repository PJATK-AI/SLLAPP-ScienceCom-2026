import { gameLetters, type HandSampleData, type ServerMessage } from '@/models/game-session';

function isUnsignedInteger(value: unknown): value is number {
    return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
}

function isPositionId(value: unknown): value is number {
    return isUnsignedInteger(value) && value > 0;
}

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
        typeof v.nickname === 'string' &&
        v.nickname.trim() &&
        (v.selectedHand === 'LEFT' || v.selectedHand === 'RIGHT')
    ) {
        if (v.status === 'CREATED' && v.positionId === null) return value as ServerMessage;
        if (
            v.status === 'RUNNING' &&
            isPositionId(v.positionId) &&
            typeof v.word === 'string' &&
            v.word.length > 0 &&
            [...v.word].every((letter) => gameLetters.includes(letter)) &&
            isUnsignedInteger(v.letterIndex) &&
            v.letterIndex < v.word.length &&
            v.expectedLetter === v.word[v.letterIndex] &&
            isUnsignedInteger(v.remainingMs) &&
            isUnsignedInteger(v.score) &&
            typeof v.requiresRelease === 'boolean'
        )
            return value as ServerMessage;
        if (
            v.status === 'FINISHED' &&
            v.positionId === null &&
            v.remainingMs === 0 &&
            isUnsignedInteger(v.score)
        )
            return value as ServerMessage;
    }
    if (v.type === 'error' && typeof v.code === 'string' && typeof v.message === 'string')
        return value as ServerMessage;
    if (v.type === 'sample_ack' && isUnsignedInteger(v.seq)) return value as ServerMessage;
    throw new Error('Invalid or unsupported server message.');
}

export function serializeSample(
    seq: number,
    positionId: number | null,
    hand?: HandSampleData,
): string {
    if (!isUnsignedInteger(seq))
        throw new Error('Sample sequence is exhausted. Reconnect the session.');
    if (positionId !== null && !isPositionId(positionId)) throw new Error('Invalid positionId.');
    if (
        hand !== undefined &&
        (!hand ||
            hand.coordinateSpace !== 'MEDIAPIPE_IMAGE' ||
            !['LEFT', 'RIGHT'].includes(hand.handedness) ||
            !Array.isArray(hand.landmarks) ||
            hand.landmarks.length !== 21 ||
            !hand.landmarks.every(
                (p) =>
                    p && [p.x, p.y, p.z].every((n) => typeof n === 'number' && Number.isFinite(n)),
            ))
    )
        throw new Error('Expected 21 MediaPipe image landmarks and LEFT or RIGHT handedness.');
    return JSON.stringify(
        hand === undefined
            ? { type: 'no_hand', seq, positionId }
            : { type: 'hand_sample', seq, positionId, hand },
    );
}
