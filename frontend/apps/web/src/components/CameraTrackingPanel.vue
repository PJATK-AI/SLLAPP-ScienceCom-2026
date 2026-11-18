<script setup lang="ts">
import { onScopeDispose, useTemplateRef, watch } from 'vue';
import { useHandStream, type HandStreamSession } from '@/composables/hand-stream/use-hand-stream';

const props = defineProps<HandStreamSession>();
const emit = defineEmits<{ active: [value: boolean] }>();
const video = useTemplateRef<HTMLVideoElement>('video');
const { start, stop, isRunning, error, frameError, configurationError } = useHandStream(
    video,
    props,
);
watch(isRunning, (value) => emit('active', value), { flush: 'sync' });
onScopeDispose(() => emit('active', false));
</script>

<template>
    <section aria-label="Śledzenie dłoni">
        <h3>Kamera</h3>
        <video ref="video" muted playsinline aria-label="Podgląd kamery"></video>
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
video {
    display: block;
    width: min(100%, 32rem);
}
</style>
