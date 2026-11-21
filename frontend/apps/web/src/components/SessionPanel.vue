<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useGameSession } from '@/composables/game-session';
import type { SelectedHand } from '@/models';
import CameraTrackingPanel from './CameraTrackingPanel.vue';
import SignReference from './SignReference.vue';
import RoundRanking from './RoundRanking.vue';
import SessionDiagnostics from './SessionDiagnostics.vue';

const diagnosticMode = import.meta.env.DEV && import.meta.env.MODE === 'diagnostics';
const cameraActive = ref(false);
const nickname = ref('');
const selectedHand = ref<SelectedHand>('RIGHT');
const session = useGameSession({ diagnostics: diagnosticMode });
const {
    pendingAction,
    isReady,
    gameState,
    error,
    lastClose,
    createSession,
    startGame,
    reconnect,
    resetSession,
    sendHandSample,
    sendNoHand,
} = session;

const running = computed(() => (gameState.value?.status === 'RUNNING' ? gameState.value : null));
const remainingSeconds = ref(0);
watch(running, (state, _previous, onCleanup) => {
    if (!state) return;
    const deadline = performance.now() + state.remainingMs;
    const tick = () => {
        remainingSeconds.value = Math.ceil(Math.max(0, deadline - performance.now()) / 1000);
    };
    tick();
    const interval = setInterval(() => {
        tick();
        if (remainingSeconds.value === 0) clearInterval(interval);
    }, 100);
    onCleanup(() => clearInterval(interval));
});

const clock = computed(() => {
    const minutes = Math.floor(remainingSeconds.value / 60);
    const seconds = remainingSeconds.value % 60;
    return [minutes, seconds].map((part) => String(part).padStart(2, '0')).join(':');
});
const connectionLost = computed(() => gameState.value && !isReady.value);
const gameError = computed(() => {
    if (connectionLost.value || gameState.value?.status === 'FINISHED') return '';
    if (lastClose.value?.code === 1008) return 'Sesja wygasła. Utwórz nową sesję.';
    if (error.value?.code === 'INVALID_SESSION') return 'Sprawdź nick i spróbuj ponownie.';
    if (error.value?.code === 'INVALID_SAMPLE' || error.value?.code === 'GAME_FINISHED') return '';
    return error.value ? 'Wystąpił problem z połączeniem. Spróbuj ponownie.' : '';
});
</script>

<template>
    <section v-if="gameState?.status === 'FINISHED'" class="result" aria-labelledby="result-title">
        <div class="result-summary card">
            <h2 id="result-title">Koniec rundy</h2>
            <p class="final-score">
                <strong>{{ gameState.score }}</strong> pkt
            </p>
            <button type="button" class="primary" @click="resetSession">Nowa sesja</button>
        </div>
        <RoundRanking :key="gameState.sessionId" />
    </section>

    <section v-else aria-label="Runda">
        <div v-if="!gameState" class="setup card">
            <h2>Przygotuj się do gry</h2>
            <p>Użyj dłoni, by zamigać litery wyświetlane w rundzie.</p>
            <form @submit.prevent="createSession({ nickname, selectedHand })">
                <fieldset :disabled="pendingAction !== null">
                    <label for="nickname">Nick</label>
                    <input id="nickname" v-model="nickname" required autocomplete="off" />
                    <label for="selected-hand">Dłoń do migania</label>
                    <select id="selected-hand" v-model="selectedHand">
                        <option value="RIGHT">Prawa</option>
                        <option value="LEFT">Lewa</option>
                    </select>
                    <button type="submit" class="primary">
                        {{ pendingAction === 'create' ? 'Łączenie…' : 'Utwórz sesję' }}
                    </button>
                </fieldset>
            </form>
        </div>

        <div v-if="connectionLost" class="connection card" role="status">
            <p>Połączenie przerwane. Wznów sesję, aby pobrać stan rundy.</p>
            <button
                type="button"
                class="primary"
                :disabled="pendingAction !== null"
                @click="reconnect"
            >
                {{ pendingAction === 'resume' ? 'Wznawianie…' : 'Wznów sesję' }}
            </button>
        </div>
        <p v-if="gameError" class="error" role="alert">{{ gameError }}</p>

        <div v-if="running" class="round-status card">
            <div>
                <p class="label">Zamigaj literę</p>
                <p class="requested-letter" aria-live="polite">{{ running.expectedLetter }}</p>
                <p class="word" :aria-label="`Słowo: ${running.word}`">
                    <span
                        v-for="(letter, index) in running.word"
                        :key="index"
                        :class="{
                            current: index === running.letterIndex,
                            complete: index < running.letterIndex,
                        }"
                        :aria-current="index === running.letterIndex ? 'step' : undefined"
                        >{{ letter }}</span
                    >
                </p>
            </div>
            <div class="metrics">
                <div>
                    <span class="label">Czas</span
                    ><strong role="timer" :aria-label="`Pozostało ${clock}`">{{ clock }}</strong>
                </div>
                <div>
                    <span class="label">Punkty</span
                    ><strong aria-live="polite">{{ running.score }}</strong>
                </div>
            </div>
            <p v-if="running.requiresRelease" class="release" role="status">
                Wyjmij dłoń z kadru, a potem zamigaj tę literę ponownie.
            </p>
            <p v-if="remainingSeconds === 0 && isReady" class="round-wait" role="status">
                Czekamy na wynik rundy…
            </p>
        </div>

        <div v-if="gameState" class="play-area" :class="{ 'has-reference': running }">
            <CameraTrackingPanel
                :key="gameState.sessionId"
                :ready="isReady"
                :selected-hand="gameState.selectedHand"
                :send-hand-sample="sendHandSample"
                :send-no-hand="sendNoHand"
                @active="cameraActive = $event"
            >
                <button
                    v-if="gameState.status === 'CREATED'"
                    type="button"
                    class="primary"
                    :disabled="!isReady || !cameraActive || pendingAction !== null"
                    @click="startGame"
                >
                    {{ pendingAction === 'start' ? 'Rozpoczynanie…' : 'Rozpocznij rundę' }}
                </button>
            </CameraTrackingPanel>
            <SignReference v-if="running" :expected-letter="running.expectedLetter" />
        </div>
    </section>

    <SessionDiagnostics v-if="diagnosticMode" :session="session" :camera-active="cameraActive" />
</template>

<style scoped>
h2 {
    margin: 0;
    font-size: clamp(1.5rem, 4vw, 2rem);
}
.setup {
    max-width: 34rem;
}
.setup p {
    color: var(--muted);
}
fieldset {
    display: grid;
    gap: 0.5rem;
    border: 0;
    padding: 0;
    margin: 1.5rem 0 0;
}
fieldset select,
fieldset input {
    margin-bottom: 0.75rem;
}
label {
    font-weight: 650;
}
.connection {
    display: flex;
    gap: 1rem;
    align-items: center;
    justify-content: space-between;
    flex-wrap: wrap;
    margin-bottom: 1rem;
}
.connection p {
    margin: 0;
}
.error {
    color: var(--error);
}
.play-area {
    display: grid;
    align-items: start;
    gap: 1rem;
}
.round-status {
    display: grid;
    grid-template-columns: 1fr auto;
    align-items: start;
    gap: 1rem;
    margin-bottom: 1rem;
}
.label {
    margin: 0;
    font-weight: 650;
    color: var(--muted);
}
.requested-letter {
    margin: 0.25rem 0 0.75rem;
    color: var(--accent);
    font-size: clamp(3.5rem, 10vw, 5.5rem);
    font-weight: 750;
    line-height: 1;
}
.word {
    display: flex;
    flex-wrap: wrap;
    gap: 0.35rem;
    margin: 0;
    font-size: 1.125rem;
    font-weight: 700;
}
.word span {
    padding: 0.1rem 0.4rem;
    border-bottom: 3px solid transparent;
}
.word .current {
    border-color: var(--accent);
    color: var(--accent);
}
.word .complete {
    color: var(--muted);
}
.metrics {
    display: flex;
    flex-wrap: wrap;
    gap: 0.5rem;
}
.metrics div {
    display: grid;
    padding: 0.5rem 0.8rem;
    border: 1px solid var(--border);
    border-radius: 0.6rem;
}
.metrics strong {
    font-size: clamp(1.25rem, 4vw, 2rem);
    font-variant-numeric: tabular-nums;
}
.release,
.round-wait {
    grid-column: 1 / -1;
    margin: 0;
}
.release {
    padding: 0.75rem;
    border-radius: 0.5rem;
    background: #fff1ce;
    color: #583e06;
    font-weight: 650;
}
.result {
    display: grid;
    gap: 1rem;
    align-items: start;
}
.final-score {
    margin: 0.65rem 0 1rem;
    color: var(--accent);
}
.final-score strong {
    font-size: clamp(2.5rem, 8vw, 4rem);
}
@media (min-width: 48rem) {
    .play-area.has-reference {
        grid-template-columns: minmax(0, 1.35fr) minmax(0, 1fr);
    }
    .result {
        grid-template-columns: minmax(0, 1fr) minmax(0, 1.4fr);
    }
}
@media (max-width: 26rem) {
    .round-status {
        grid-template-columns: 1fr;
    }
}
</style>
