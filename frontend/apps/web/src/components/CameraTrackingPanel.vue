<script setup lang="ts">
import { onScopeDispose, useTemplateRef, watch } from 'vue';
import { useHandStream, type HandStreamSession } from '@/composables/hand-stream/use-hand-stream';

const props = defineProps<HandStreamSession>();
const emit = defineEmits<{ active: [value: boolean] }>();
const video = useTemplateRef<HTMLVideoElement>('video');
const { hand, start, stop, isRunning, error, frameError, configurationError } = useHandStream(
    video,
    props,
);
watch(isRunning, (value) => emit('active', value), { flush: 'sync' });
onScopeDispose(() => emit('active', false));
</script>

<template>
    <section aria-label="Śledzenie dłoni">
        <h3>Kamera</h3>
        <div class="preview">
            <video ref="video" muted playsinline aria-label="Podgląd kamery"></video>
            <div
                v-if="isRunning && hand && hand.handedness.toUpperCase() === selectedHand"
                class="landmarks"
                aria-hidden="true"
            >
                <span
                    v-for="(point, index) in hand.landmarks"
                    :key="index"
                    :style="{ left: point.x * 100 + '%', top: point.y * 100 + '%' }"
                ></span>
            </div>
        </div>
        <button :disabled="!ready || isRunning || !!configurationError" @click="start">
            Uruchom kamerę
        </button>
        <button :disabled="!isRunning" @click="stop">Zatrzymaj kamerę</button>
        <p v-if="isRunning && !ready">Wysyłanie wstrzymane do wznowienia sesji.</p>
        <p v-if="configurationError || error || frameError" role="alert">
            {{ configurationError || error?.message || frameError }}
        </p>
    </section>
</template>
<style scoped>
.preview {
    position: relative;
    width: min(100%, 32rem);
    overflow: hidden;
}
video {
    display: block;
    width: 100%;
    height: auto;
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
