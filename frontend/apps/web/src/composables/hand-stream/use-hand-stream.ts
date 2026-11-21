import { normalizeHandForModel, useHandTracker, type Hand } from '@sllapp/hand-landmarker-vue';
import { ref, watch, type Ref } from 'vue';
import type { HandSampleData, SelectedHand } from '@/models/game-session';

export interface HandStreamSession {
    ready: boolean;
    selectedHand: SelectedHand;
    sendHandSample(hand: HandSampleData): boolean;
    sendNoHand(): boolean;
}

export function useHandStream(
    video: Readonly<Ref<HTMLVideoElement | null>>,
    session: HandStreamSession,
) {
    const hz = Number(import.meta.env.VITE_HAND_SAMPLE_HZ?.trim() || 10);
    const interval = 1000 / hz;
    const configurationError =
        interval > 0 && Number.isFinite(interval)
            ? ''
            : 'VITE_HAND_SAMPLE_HZ musi określać dodatnią częstotliwość.';
    const frameError = ref('');
    let lastAttempt = -Infinity;
    const tracker = useHandTracker(
        video,
        {
            modelAssetPath:
                import.meta.env.VITE_HAND_LANDMARKER_MODEL_URL?.trim() ||
                '/mediapipe/hand_landmarker.task',
            wasmPath: import.meta.env.VITE_HAND_LANDMARKER_WASM_PATH?.trim() || '/mediapipe/wasm',
            get preferredHand() {
                return session.selectedHand === 'LEFT' ? 'Left' : 'Right';
            },
        },
        onFrame,
    );

    function onFrame(hand: Hand | null, timestamp: number) {
        if (!tracker.isRunning.value || !session.ready || timestamp - lastAttempt < interval)
            return;
        lastAttempt = timestamp;
        frameError.value = '';
        const physicalHand = hand?.handedness === 'Left' ? 'LEFT' : 'RIGHT';
        if (!hand || physicalHand !== session.selectedHand) {
            session.sendNoHand();
            return;
        }
        try {
            const { score, landmarks } = normalizeHandForModel(hand);
            session.sendHandSample({
                handedness: physicalHand,
                coordinateSpace: 'MEDIAPIPE_IMAGE',
                score,
                landmarks,
            });
        } catch (cause) {
            frameError.value = cause instanceof Error ? cause.message : 'Błędne punkty dłoni.';
        }
    }

    watch(
        () => session.ready,
        () => {
            lastAttempt = -Infinity;
        },
        { flush: 'sync' },
    );

    function start() {
        if (!video.value || !session.ready || tracker.isRunning.value || configurationError) return;
        lastAttempt = -Infinity;
        frameError.value = '';
        return tracker.start();
    }
    return { ...tracker, start, frameError, configurationError };
}
