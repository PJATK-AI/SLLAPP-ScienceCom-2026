<script setup lang="ts">
import { computed, ref } from 'vue';
import { useGameSession } from '@/composables/game-session';
import type { SelectedHand } from '@/models';
import { createTestHand } from './session-test-hand';
import CameraTrackingPanel from './CameraTrackingPanel.vue';
const cameraActive = ref(false);
const nickname = ref('');
const selectedHand = ref<SelectedHand>('RIGHT');
const feedback = ref('');
const {
    connectionStatus,
    pendingAction,
    isReady,
    gameState,
    error,
    lastClose,
    lastSentSeq,
    lastSentPayload,
    lastSampleAck,
    createSession,
    reconnect,
    disconnect,
    sendHandSample,
    sendNoHand,
} = useGameSession();
const sampleJson = computed(() =>
    lastSentPayload.value === null
        ? ''
        : JSON.stringify(JSON.parse(lastSentPayload.value), null, 2),
);
function sample(hand: boolean) {
    if (!gameState.value) return;
    const sent = hand ? sendHandSample(createTestHand(gameState.value.selectedHand)) : sendNoHand();
    feedback.value = sent
        ? 'Próbka przekazana do wysłania.'
        : 'Próbka pominięta — połączenie niegotowe lub bufor zajęty.';
}
</script>

<template>
    <section aria-labelledby="session-title">
        <h2 id="session-title">Test połączenia z backendem</h2>
        <form @submit.prevent="createSession({ nickname, selectedHand })">
            <fieldset :disabled="pendingAction !== null || gameState !== null">
                <legend>Nowa sesja</legend>
                <label>Nick <input v-model="nickname" required /></label>
                <label
                    >Ręka
                    <select v-model="selectedHand">
                        <option value="RIGHT">Prawa</option>
                        <option value="LEFT">Lewa</option>
                    </select></label
                >
                <button type="submit">Utwórz sesję</button>
            </fieldset>
        </form>
        <p aria-live="polite">
            Połączenie: {{ connectionStatus }} · Sesja: {{ isReady ? 'gotowa' : 'niegotowa' }}
            <span v-if="pendingAction">· Oczekiwanie na {{ pendingAction }}</span>
        </p>
        <button
            :disabled="connectionStatus !== 'open' && connectionStatus !== 'connecting'"
            @click="disconnect"
        >
            Rozłącz
        </button>
        <button :disabled="!gameState || isReady || pendingAction !== null" @click="reconnect">
            Wznów sesję
        </button>
        <p v-if="error" role="alert">{{ error.code }}: {{ error.message }}</p>
        <p v-if="lastClose">Zamknięcie: {{ lastClose.code }} {{ lastClose.reason }}</p>
        <dl>
            <dt>ID sesji</dt>
            <dd>{{ gameState?.sessionId ?? '—' }}</dd>
            <dt>Gracz / ręka</dt>
            <dd>{{ gameState ? `${gameState.nickname} / ${gameState.selectedHand}` : '—' }}</dd>
            <dt>Ostatni wysłany seq</dt>
            <dd data-testid="sent-seq">{{ lastSentSeq ?? '—' }}</dd>
            <dt>Ostatni sample_ack</dt>
            <dd data-testid="ack-seq">{{ lastSampleAck ?? '—' }}</dd>
        </dl>
        <CameraTrackingPanel
            v-if="gameState"
            :key="gameState.sessionId"
            :ready="isReady"
            :selected-hand="gameState.selectedHand"
            :send-hand-sample="sendHandSample"
            :send-no-hand="sendNoHand"
            @active="cameraActive = $event"
        />
        <template v-if="gameState">
            <h3>Ostatnia wysłana próbka (JSON)</h3>
            <pre v-if="sampleJson" tabindex="0">{{ sampleJson }}</pre>
            <p v-else>Oczekiwanie na pierwszą próbkę.</p>
        </template>
        <button :disabled="!isReady || cameraActive" @click="sample(true)">
            Wyślij testową dłoń
        </button>
        <button :disabled="!isReady || cameraActive" @click="sample(false)">
            Wyślij brak dłoni
        </button>
        <p aria-live="polite">{{ feedback }}</p>
        <p>Testowa dłoń to syntetyczne 21 punktów. Ten panel sprawdza transport danych.</p>
        <p>
            Potwierdzenia sample_ack wymagają diagnostic-mode=true na backendzie. Potwierdzają
            przyjęcie próbki, nie rozpoznanie gestu. Wysyłanie działa także bez potwierdzeń.
        </p>
    </section>
</template>

<style scoped>
pre {
    max-height: 18rem;
    overflow: auto;
    padding: 0.75rem;
    background: #14221e;
    color: #f2faf6;
}
section {
    max-width: 46rem;
    margin: auto;
    font-family: system-ui, sans-serif;
}
fieldset {
    display: flex;
    gap: 1rem;
    flex-wrap: wrap;
    padding: 1rem;
}
button,
input,
select {
    padding: 0.5rem;
    font: inherit;
}
button {
    margin: 0.25rem;
}
dd {
    margin-bottom: 0.5rem;
    overflow-wrap: anywhere;
}
[role='alert'] {
    color: #a01818;
}
</style>
