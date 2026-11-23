<script setup lang="ts">
import {
    HandTrackerError,
    openCamera,
    stopStream,
    type CameraCrop,
} from '@sllapp/hand-landmarker-vue';
import { computed, onBeforeUnmount, ref, shallowRef, useTemplateRef, watch } from 'vue';
import {
    cropCenter,
    cropFromZoom,
    cropZoom,
    MAX_CROP_ZOOM,
    moveCrop,
    useCameraSettings,
} from '@/composables/camera-settings';

const open = defineModel<boolean>('open', { required: true });

const { crop: savedCrop, saveCrop } = useCameraSettings();
const dialog = useTemplateRef<HTMLDialogElement>('dialog');
const video = useTemplateRef<HTMLVideoElement>('video');
const frame = useTemplateRef<HTMLDivElement>('frame');

const draft = shallowRef<CameraCrop | null>(null);
const aspect = ref(4 / 3);
const cameraError = ref('');
const cameraReady = ref(false);
let stream: MediaStream | null = null;
// Increases at each close, so that a camera that opens after the close is stopped at once.
let session = 0;

const zoom = computed({
    get: () => cropZoom(draft.value),
    set: (value: number) => {
        const center = cropCenter(draft.value);
        draft.value = cropFromZoom(value, center.x, center.y);
    },
});
const zoomLabel = computed(() => `${zoom.value.toFixed(1).replace('.', ',')}×`);
const cropStyle = computed(() => {
    const crop = draft.value ?? { x: 0, y: 0, width: 1, height: 1 };
    return {
        left: `${crop.x * 100}%`,
        top: `${crop.y * 100}%`,
        width: `${crop.width * 100}%`,
        height: `${crop.height * 100}%`,
    };
});

async function startPreview() {
    const current = ++session;
    cameraError.value = '';
    cameraReady.value = false;
    try {
        const opened = await openCamera();
        if (current !== session || !video.value) {
            stopStream(opened);
            return;
        }
        stream = opened;
        video.value.srcObject = opened;
        await video.value.play();
        cameraReady.value = true;
    } catch (cause) {
        if (current !== session) return;
        stopPreview();
        cameraError.value =
            cause instanceof HandTrackerError && cause.code === 'camera-denied'
                ? 'Zezwól na dostęp do kamery, aby ustawić kadr.'
                : 'Nie można uruchomić kamery. Sprawdź, czy jest dostępna.';
    }
}

function stopPreview() {
    session++;
    cameraReady.value = false;
    if (stream) stopStream(stream);
    stream = null;
    if (video.value) video.value.srcObject = null;
}

function readAspect() {
    const element = video.value;
    if (element?.videoWidth && element.videoHeight) {
        aspect.value = element.videoWidth / element.videoHeight;
    }
}

watch(
    open,
    (value) => {
        if (value) {
            draft.value = savedCrop.value;
            if (!dialog.value?.open) dialog.value?.showModal();
            void startPreview();
        } else {
            stopPreview();
            if (dialog.value?.open) dialog.value.close();
        }
    },
    { flush: 'post' },
);
onBeforeUnmount(stopPreview);

function close() {
    open.value = false;
}
function save() {
    saveCrop(draft.value);
    close();
}

// Dragging moves the crop. The preview is mirrored, so a move to the right moves the crop left in the image.
let drag: { pointerId: number; x: number; y: number } | null = null;
function onPointerDown(event: PointerEvent) {
    if (!draft.value) return;
    drag = { pointerId: event.pointerId, x: event.clientX, y: event.clientY };
    (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
}
function onPointerMove(event: PointerEvent) {
    const bounds = frame.value?.getBoundingClientRect();
    if (!drag || drag.pointerId !== event.pointerId || !bounds?.width || !bounds.height) return;
    const dx = -(event.clientX - drag.x) / bounds.width;
    const dy = (event.clientY - drag.y) / bounds.height;
    drag = { ...drag, x: event.clientX, y: event.clientY };
    draft.value = moveCrop(draft.value, dx, dy);
}
function onPointerUp(event: PointerEvent) {
    if (drag?.pointerId === event.pointerId) drag = null;
}

const KEY_STEP = 0.02;
function onCropKeydown(event: KeyboardEvent) {
    const moves: Record<string, [number, number]> = {
        ArrowLeft: [KEY_STEP, 0],
        ArrowRight: [-KEY_STEP, 0],
        ArrowUp: [0, -KEY_STEP],
        ArrowDown: [0, KEY_STEP],
    };
    const move = moves[event.key];
    if (!move) return;
    event.preventDefault();
    draft.value = moveCrop(draft.value, ...move);
}
</script>

<template>
    <dialog
        ref="dialog"
        class="camera-settings"
        aria-labelledby="camera-settings-title"
        @cancel.prevent="close"
    >
        <div v-if="open" class="sheet">
            <header class="sheet-header">
                <h2 id="camera-settings-title">Ustawienia kamery</h2>
                <button type="button" class="close" aria-label="Zamknij" @click="close">
                    <svg viewBox="0 0 24 24" aria-hidden="true">
                        <path d="M6 6l12 12M18 6L6 18" />
                    </svg>
                </button>
            </header>

            <p class="hint">
                Ustaw kadr, w którym gra szuka dłoni. Przeciągnij ramkę, aby ją przesunąć. Gra
                pokaże i przeanalizuje tylko ten fragment obrazu.
            </p>

            <div class="preview">
                <div ref="frame" class="frame" :style="{ '--aspect': aspect }">
                    <video
                        ref="video"
                        muted
                        playsinline
                        aria-label="Podgląd kamery"
                        @loadedmetadata="readAspect"
                    ></video>
                    <div
                        v-if="cameraReady"
                        class="crop"
                        :class="{ movable: !!draft }"
                        :style="cropStyle"
                        role="group"
                        aria-label="Kadr kamery. Użyj strzałek, aby go przesunąć."
                        :tabindex="draft ? 0 : -1"
                        @pointerdown="onPointerDown"
                        @pointermove="onPointerMove"
                        @pointerup="onPointerUp"
                        @pointercancel="onPointerUp"
                        @keydown="onCropKeydown"
                    ></div>
                </div>
                <p v-if="!cameraReady && !cameraError" class="status" role="status">
                    Uruchamianie kamery…
                </p>
                <p v-if="cameraError" class="status error" role="alert">{{ cameraError }}</p>
            </div>

            <label class="zoom">
                <span class="label">Przybliżenie</span>
                <input
                    v-model.number="zoom"
                    type="range"
                    min="1"
                    :max="MAX_CROP_ZOOM"
                    step="0.05"
                    :aria-valuetext="zoomLabel"
                />
                <output>{{ zoomLabel }}</output>
            </label>

            <footer class="actions">
                <button type="button" class="quiet" :disabled="!draft" @click="draft = null">
                    Pełny obraz
                </button>
                <span class="spacer"></span>
                <button type="button" @click="close">Anuluj</button>
                <button type="button" class="primary" @click="save">Zapisz</button>
            </footer>
        </div>
    </dialog>
</template>

<style scoped>
.camera-settings {
    width: min(92vw, 56rem);
    max-height: 92dvh;
    padding: 0;
    border: 1px solid var(--glass-line);
    border-radius: var(--radius);
    background: var(--bg);
    color: var(--fg);
    box-shadow: var(--glass-shadow);
}
.camera-settings::backdrop {
    background: rgba(0, 0, 0, 0.55);
    backdrop-filter: blur(6px);
}
.sheet {
    display: grid;
    gap: clamp(0.8rem, 2vmin, 1.4rem);
    padding: clamp(1.2rem, 3vmin, 2.4rem);
}
.sheet-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 1rem;
}
h2 {
    margin: 0;
    font-family: var(--font-display);
    font-size: clamp(1.2rem, 2.8vmin, 2rem);
    font-weight: 400;
    letter-spacing: 0.12em;
    text-transform: uppercase;
}
.close {
    display: grid;
    place-items: center;
    width: 2.75rem;
    padding: 0;
    border-color: var(--line);
}
.close svg {
    width: 1.1rem;
    height: 1.1rem;
    fill: none;
    stroke: currentColor;
    stroke-width: 2.4;
    stroke-linecap: round;
}
.hint {
    margin: 0;
    color: var(--muted);
}
.preview {
    position: relative;
    display: grid;
    place-items: center;
    overflow: hidden;
    border-radius: calc(var(--radius) * 0.6);
    background: #0a0a0a;
}
.frame {
    position: relative;
    width: min(100%, calc(55dvh * var(--aspect)));
    aspect-ratio: var(--aspect);
    overflow: hidden;
    /* Mirror the preview and the crop together, like the game camera. */
    transform: scaleX(-1);
}
video {
    display: block;
    width: 100%;
    height: 100%;
    object-fit: fill;
}
.crop {
    position: absolute;
    border: 3px solid var(--red);
    border-radius: 0.4rem;
    box-shadow: 0 0 0 100vmax rgba(0, 0, 0, 0.55);
    touch-action: none;
}
.crop.movable {
    cursor: grab;
}
.crop.movable:active {
    cursor: grabbing;
}
.status {
    position: absolute;
    margin: 0;
    padding: 0 1rem;
    text-align: center;
    color: #fff;
}
.status.error {
    color: #ff8a99;
}
.zoom {
    display: grid;
    grid-template-columns: auto minmax(0, 1fr) 3.5rem;
    align-items: center;
    gap: 1rem;
}
.zoom input {
    width: 100%;
    accent-color: var(--red);
}
.zoom output {
    font-variant-numeric: tabular-nums;
    text-align: right;
}
.actions {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.6rem;
}
.spacer {
    flex: 1;
}
.actions .quiet {
    border-color: transparent;
    color: var(--muted);
}
</style>
