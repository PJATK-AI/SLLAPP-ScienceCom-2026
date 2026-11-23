<script setup lang="ts">
import { computed, onScopeDispose, ref, useTemplateRef, watch } from 'vue';
import { useHandStream, type HandStreamSession } from '@/composables/hand-stream/use-hand-stream';
import BrainRaster from './BrainRaster.vue';

const props = defineProps<HandStreamSession>();
const emit = defineEmits<{ active: [value: boolean] }>();
const video = useTemplateRef<HTMLVideoElement>('video');
const { hand, start, stop, isRunning, error, frameError, configurationError } = useHandStream(
    video,
    props,
);
const seesHand = computed(() => hand.value?.handedness.toUpperCase() === props.selectedHand);
const showHand = computed(() => isRunning.value && !!hand.value && seesHand.value);
const wrongHand = computed(() => isRunning.value && !!hand.value && !seesHand.value);
const handName = computed(() => (props.selectedHand === 'LEFT' ? 'lewą' : 'prawą'));
const otherHandName = computed(() => (props.selectedHand === 'LEFT' ? 'prawa' : 'lewa'));
const cameraError = computed(() => {
    if (error.value?.code === 'camera-denied')
        return 'Zezwól na dostęp do kamery i spróbuj ponownie.';
    if (error.value?.code === 'camera-unavailable')
        return 'Nie można uruchomić kamery. Sprawdź, czy jest dostępna.';
    if (error.value || configurationError) return 'Nie udało się uruchomić śledzenia dłoni.';
    return frameError.value ? 'Utracono odczyt dłoni.' : '';
});

// The preview keeps the camera's aspect ratio so the drawn landmarks stay on the hand when cropped.
const aspect = ref(4 / 3);
function readAspect() {
    const element = video.value;
    if (element?.videoWidth && element.videoHeight) {
        aspect.value = element.videoWidth / element.videoHeight;
    }
}

const fingertips = new Set([4, 8, 12, 16, 20]);
const bones: [number, number][] = [
    [0, 1],
    [1, 2],
    [2, 3],
    [3, 4],
    [0, 5],
    [5, 6],
    [6, 7],
    [7, 8],
    [5, 9],
    [9, 10],
    [10, 11],
    [11, 12],
    [9, 13],
    [13, 14],
    [14, 15],
    [15, 16],
    [13, 17],
    [0, 17],
    [17, 18],
    [18, 19],
    [19, 20],
];
const boneLines = computed(() => {
    const points = hand.value?.landmarks ?? [];
    return bones.flatMap(([from, to]) => {
        const a = points[from];
        const b = points[to];
        return a && b ? [{ key: `${from}-${to}`, x1: a.x, y1: a.y, x2: b.x, y2: b.y }] : [];
    });
});

watch(isRunning, (value) => emit('active', value), { flush: 'sync' });
onScopeDispose(() => emit('active', false));
</script>

<template>
    <section class="camera" aria-label="Śledzenie dłoni">
        <div class="stage">
            <div class="viewport">
                <div class="lens">
                    <div class="frame" :style="{ '--aspect': aspect }">
                        <video
                            ref="video"
                            muted
                            playsinline
                            aria-label="Podgląd kamery"
                            @loadedmetadata="readAspect"
                        ></video>
                        <svg
                            v-if="showHand"
                            class="bones"
                            viewBox="0 0 1 1"
                            preserveAspectRatio="none"
                            aria-hidden="true"
                        >
                            <line
                                v-for="line in boneLines"
                                :key="line.key"
                                :x1="line.x1"
                                :y1="line.y1"
                                :x2="line.x2"
                                :y2="line.y2"
                            />
                        </svg>
                        <div v-if="showHand" class="landmarks" aria-hidden="true">
                            <span
                                v-for="(point, index) in hand!.landmarks"
                                :key="index"
                                :class="{ tip: fingertips.has(index) }"
                                :style="{ left: point.x * 100 + '%', top: point.y * 100 + '%' }"
                            ></span>
                        </div>
                    </div>
                    <div v-if="!isRunning" class="idle" aria-hidden="true">
                        <BrainRaster />
                    </div>
                    <p v-if="wrongHand" class="chip alert" role="alert">
                        <svg viewBox="0 0 24 24" aria-hidden="true">
                            <circle cx="12" cy="12" r="10" />
                            <path d="M12 7.5v5.5M12 16.5v.5" />
                        </svg>
                        To {{ otherHandName }} dłoń. Pokaż {{ handName }}.
                    </p>
                    <p v-else-if="ready && isRunning && !hand" class="chip" role="status">
                        Pokaż {{ handName }} dłoń
                    </p>
                </div>
            </div>
        </div>
        <p v-if="cameraError" class="error" role="alert">{{ cameraError }}</p>
        <div class="controls">
            <button
                v-if="!isRunning"
                type="button"
                :disabled="!ready || !!configurationError"
                @click="start"
            >
                Uruchom kamerę
            </button>
            <button v-else type="button" class="quiet" @click="stop">Zatrzymaj kamerę</button>
            <slot />
        </div>
    </section>
</template>

<style scoped>
.camera {
    display: grid;
    grid-template-rows: minmax(0, 1fr) auto auto;
    gap: calc(var(--pad) * 0.5);
    height: 100%;
    min-height: 0;
}
.stage {
    container-type: size;
    display: grid;
    place-items: center;
    min-height: 0;
}
.viewport {
    position: relative;
    width: 100%;
    height: 100%;
}
.lens {
    position: absolute;
    inset: 0;
    overflow: hidden;
    border-radius: var(--radius);
    background: radial-gradient(ellipse at 50% 42%, #2b2b2b 0%, #141414 60%, #0a0a0a 100%);
    box-shadow: inset 0 0 0 1px var(--line);
    container-type: size;
}
.frame {
    position: absolute;
    left: 50%;
    top: 50%;
    width: max(100cqw, calc(100cqh * var(--aspect)));
    height: max(100cqh, calc(100cqw / var(--aspect)));
    /* Mirror the preview and its landmarks together, like a selfie camera. */
    transform: translate(-50%, -50%) scaleX(-1);
}
video {
    display: block;
    width: 100%;
    height: 100%;
    object-fit: fill;
}
.bones,
.landmarks {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    pointer-events: none;
}
.bones line {
    stroke: rgba(255, 255, 255, 0.85);
    stroke-width: 3px;
    stroke-linecap: round;
    vector-effect: non-scaling-stroke;
}
.landmarks span {
    position: absolute;
    width: clamp(8px, 1.3cqmin, 16px);
    height: clamp(8px, 1.3cqmin, 16px);
    border-radius: 50%;
    background: var(--red);
    box-shadow: 0 0 0 2px #fff;
    transform: translate(-50%, -50%);
}
.landmarks span.tip {
    background: #fff;
    box-shadow:
        0 0 0 2px var(--red),
        0 0 0 6px rgba(227, 24, 55, 0.35);
}
.idle {
    position: absolute;
    inset: 0;
    display: grid;
    place-items: center;
    color: rgba(255, 255, 255, 0.12);
}
.idle :deep(.brain-raster) {
    width: 55%;
    animation: breathe 5s ease-in-out infinite;
}
@keyframes breathe {
    50% {
        transform: scale(1.04);
        opacity: 0.6;
    }
}
.chip {
    position: absolute;
    left: 50%;
    top: 9%;
    transform: translateX(-50%);
    display: flex;
    align-items: center;
    gap: 0.6rem;
    margin: 0;
    padding: 0.7em 1.3em;
    border-radius: 999px;
    white-space: nowrap;
    font-size: clamp(0.95rem, 2.2vmin, 1.6rem);
    font-weight: 600;
    color: #fff;
    background: rgba(255, 255, 255, 0.16);
    backdrop-filter: blur(18px);
    -webkit-backdrop-filter: blur(18px);
    border: 1px solid rgba(255, 255, 255, 0.28);
}
.chip.alert {
    background: var(--red);
    border-color: var(--red);
    box-shadow: 0 0.8rem 2.4rem rgba(227, 24, 55, 0.45);
    animation: nudge 0.5s ease;
}
.chip svg {
    width: 1.2em;
    height: 1.2em;
    fill: none;
    stroke: currentColor;
    stroke-width: 2.4;
    stroke-linecap: round;
}
@keyframes nudge {
    25% {
        transform: translateX(calc(-50% - 6px));
    }
    75% {
        transform: translateX(calc(-50% + 6px));
    }
}
.controls {
    display: flex;
    flex-wrap: wrap;
    justify-content: center;
    gap: 0.75rem;
}
.controls button {
    min-height: clamp(3rem, 6vmin, 5rem);
    padding-inline: 1.6em;
    font-size: clamp(1rem, 2vmin, 1.6rem);
}
.controls button.quiet {
    min-height: 2.5rem;
    border-color: transparent;
    font-size: clamp(0.85rem, 1.4vmin, 1.1rem);
    font-weight: 500;
    color: var(--muted);
}
.error {
    margin: 0;
    text-align: center;
    color: var(--error);
}
</style>
