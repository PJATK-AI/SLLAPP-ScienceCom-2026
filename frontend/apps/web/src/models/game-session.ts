export type SelectedHand = 'LEFT' | 'RIGHT';
export const gameLetters = 'ABCEILMNOPRSTUWY';

export interface HandSampleData {
    handedness: SelectedHand;
    coordinateSpace: 'MEDIAPIPE_IMAGE';
    landmarks: { x: number; y: number; z: number }[];
    [key: string]: unknown;
}
interface GameStateBase {
    type: 'game_state';
    sessionId: string;
    nickname: string;
    selectedHand: SelectedHand;
}
export interface CreatedGameState extends GameStateBase {
    status: 'CREATED';
    positionId: null;
}
export interface RunningGameState extends GameStateBase {
    status: 'RUNNING';
    positionId: number;
    word: string;
    letterIndex: number;
    expectedLetter: string;
    remainingMs: number;
    score: number;
    requiresRelease: boolean;
}
export interface FinishedGameState extends GameStateBase {
    status: 'FINISHED';
    positionId: null;
    remainingMs: 0;
    score: number;
}
export type GameState = CreatedGameState | RunningGameState | FinishedGameState;

export type ServerMessage =
    | GameState
    | { type: 'error'; code: string; message: string }
    | { type: 'sample_ack'; seq: number };
export interface SessionError {
    source: 'configuration' | 'transport' | 'protocol' | 'server';
    code: string;
    message: string;
}
