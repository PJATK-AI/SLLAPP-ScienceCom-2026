<script setup lang="ts">
import { computed, onScopeDispose, useTemplateRef, watch } from 'vue';
import { useHandStream, type HandStreamSession } from '@/composables/hand-stream/use-hand-stream';

const props = defineProps<HandStreamSession>();
const emit = defineEmits<{ active: [value: boolean] }>();
const video = useTemplateRef<HTMLVideoElement>('video');
const { hand, start, stop, isRunning, error, frameError, configurationError } = useHandStream(
    video,
    props,
);
const seesHand = computed(() => hand.value?.handedness.toUpperCase() === props.selectedHand);
const cameraError = computed(() => {
    if (error.value?.code === 'camera-denied')
        return 'Zezwól na dostęp do kamery i spróbuj ponownie.';
    if (error.value?.code === 'camera-unavailable')
        return 'Nie można uruchomić kamery. Sprawdź, czy jest dostępna.';
    if (error.value || configurationError) return 'Nie udało się uruchomić śledzenia dłoni.';
    return frameError.value ? 'Utracono odczyt dłoni.' : '';
});
watch(isRunning, (value) => emit('active', value), { flush: 'sync' });
onScopeDispose(() => emit('active', false));
</script>

<template>
    <section class="card camera" aria-label="Śledzenie dłoni">
        <h3>Kamera</h3>
        <div class="preview">
            <video ref="video" muted playsinline aria-label="Podgląd kamery"></video>
            <div v-if="isRunning && hand && seesHand" class="landmarks" aria-hidden="true">
                <span
                    v-for="(point, index) in hand.landmarks"
                    :key="index"
                    :style="{ left: point.x * 100 + '%', top: point.y * 100 + '%' }"
                ></span>
            </div>
        </div>
        <p v-if="ready && isRunning" class="hand-feedback" role="status">
            {{ seesHand ? 'Dłoń jest w kadrze.' : 'Pokaż wybraną dłoń w kadrze.' }}
        </p>
        <p v-if="cameraError" class="error" role="alert">{{ cameraError }}</p>
        <div class="controls">
            <button v-if="!isRunning" :disabled="!ready || !!configurationError" @click="start">
                Uruchom kamerę
            </button>
            <button v-else @click="stop">Zatrzymaj kamerę</button>
            <slot />
        </div>
    </section>
</template>
<style scoped>
.preview {
    position: relative;
    overflow: hidden;
    border-radius: 0.65rem;
    background: #182e2b;
}
h3 {
    margin: 0 0 0.75rem;
}
.controls {
    display: flex;
    flex-wrap: wrap;
    gap: 0.5rem;
    margin-top: 0.875rem;
}
.hand-feedback {
    margin: 0.65rem 0 0;
    color: var(--muted);
}
.error {
    color: var(--error);
}
video {
    display: block;
    width: 100%;
    height: auto;
    min-height: 8rem;
}
.landmarks {
    position: absolute;
    inset: 0;
    pointer-events: none;
}
.landmarks span {
    position: absolute;
    width: 6px;
    height: 6px;
    border: 1px solid #14221e;
    border-radius: 50%;
    background: #58ffd2;
    transform: translate(-50%, -50%);
}
</style>
