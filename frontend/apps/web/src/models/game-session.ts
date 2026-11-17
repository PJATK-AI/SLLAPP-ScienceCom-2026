export type SelectedHand = 'LEFT' | 'RIGHT';
export interface HandSampleData {
    handedness: SelectedHand;
    landmarks: { x: number; y: number; z: number }[];
    [key: string]: unknown;
}
export interface GameState {
    type: 'game_state';
    sessionId: string;
    status: 'CREATED';
    nickname: string;
    selectedHand: SelectedHand;
    positionId: null;
}
export type ServerMessage =
    | GameState
    | { type: 'error'; code: string; message: string }
    | { type: 'sample_ack'; seq: number };
export interface SessionError {
    source: 'configuration' | 'transport' | 'protocol' | 'server';
    code: string;
    message: string;
}
