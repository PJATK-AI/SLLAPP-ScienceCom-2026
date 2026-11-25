<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue';
import { useGameSession } from '@/composables/game-session';
import type { SelectedHand } from '@/models';
import BrainRaster from './BrainRaster.vue';
import CameraTrackingPanel from './CameraTrackingPanel.vue';
import OrbitRing from './OrbitRing.vue';
import SignReference from './SignReference.vue';
import RoundRanking from './RoundRanking.vue';
import SessionDiagnostics from './SessionDiagnostics.vue';
import { randomNickname } from './random-nickname';

const diagnosticMode = import.meta.env.DEV && import.meta.env.MODE === 'diagnostics';
const cameraActive = ref(false);
const cameraReady = ref(false);
const nickname = ref('');
const selectedHand = ref<SelectedHand>('RIGHT');
const nicknameInput = ref<HTMLInputElement | null>(null);
const continueButton = ref<HTMLButtonElement | null>(null);
const session = useGameSession({ diagnostics: diagnosticMode });
const {
    pendingAction,
    isReady,
    gameState,
    nicknameConfirmation,
    error,
    lastClose,
    createSession,
    confirmNicknameReuse,
    dismissNicknameConfirmation,
    dismissError,
    startGame,
    reconnect,
    resetSession,
    sendHandSample,
    sendNoHand,
} = session;

const nicknameRejected = computed(
    () =>
        !gameState.value &&
        error.value?.source === 'server' &&
        error.value.code === 'INVALID_SESSION',
);
watch([nickname, selectedHand], dismissNicknameConfirmation, { flush: 'sync' });
watch(
    nickname,
    () => {
        if (nicknameRejected.value) dismissError();
    },
    { flush: 'sync' },
);
watch(
    nicknameRejected,
    (rejected) => {
        if (rejected) nicknameInput.value?.focus();
    },
    { flush: 'post' },
);
watch(
    nicknameConfirmation,
    (confirmation) => {
        if (confirmation) continueButton.value?.focus();
    },
    { flush: 'post' },
);
function changeNickname() {
    dismissNicknameConfirmation();
    nicknameInput.value?.focus();
}

const startableSessionId = computed(() =>
    isReady.value &&
    cameraReady.value &&
    pendingAction.value === null &&
    gameState.value?.status === 'CREATED'
        ? gameState.value.sessionId
        : null,
);
const countdown = ref<number | null>(null);
let countdownTimer: ReturnType<typeof setInterval> | undefined;

function cancelCountdown() {
    clearInterval(countdownTimer);
    countdownTimer = undefined;
    countdown.value = null;
}

function beginCountdown() {
    const sessionId = startableSessionId.value;
    if (!sessionId || countdown.value !== null) return;

    countdown.value = 3;
    countdownTimer = setInterval(() => {
        if (countdown.value === null) return;
        if (countdown.value > 1) {
            countdown.value--;
            return;
        }
        cancelCountdown();
        if (startableSessionId.value === sessionId) startGame();
    }, 1000);
}

watch(startableSessionId, cancelCountdown, { flush: 'sync' });
onScopeDispose(cancelCountdown);

const running = computed(() => (gameState.value?.status === 'RUNNING' ? gameState.value : null));
const remainingSeconds = ref(0);
const remainingMs = ref(0);
watch(running, (state, _previous, onCleanup) => {
    if (!state) return;
    const deadline = performance.now() + state.remainingMs;
    const tick = () => {
        remainingMs.value = Math.max(0, deadline - performance.now());
        remainingSeconds.value = Math.ceil(remainingMs.value / 1000);
    };
    tick();
    const interval = setInterval(() => {
        tick();
        if (remainingSeconds.value === 0) clearInterval(interval);
    }, 100);
    onCleanup(() => clearInterval(interval));
});

// The longest remaining time seen in this session is the round length that the orbit counts down.
const roundLength = ref({ sessionId: '', ms: 0 });
watch(running, (state) => {
    if (!state) return;
    if (roundLength.value.sessionId !== state.sessionId || state.remainingMs > roundLength.value.ms)
        roundLength.value = { sessionId: state.sessionId, ms: state.remainingMs };
});
const timeProgress = computed(() =>
    roundLength.value.ms > 0 ? Math.min(1, remainingMs.value / roundLength.value.ms) : 1,
);

const clock = computed(() => {
    const minutes = Math.floor(remainingSeconds.value / 60);
    const seconds = remainingSeconds.value % 60;
    return [minutes, seconds].map((part) => String(part).padStart(2, '0')).join(':');
});
const leaveDialog = ref<HTMLDialogElement | null>(null);
function leaveRound() {
    leaveDialog.value?.close();
    resetSession();
}
const hint = computed(() => {
    if (running.value?.requiresRelease)
        return 'Wyjmij dłoń z kadru, a potem zamigaj tę literę ponownie.';
    return remainingSeconds.value === 0 && isReady.value ? 'Czekamy na wynik rundy…' : '';
});
const lowTime = computed(() => !!running.value && remainingSeconds.value <= 10);
const handName = computed(() =>
    (gameState.value?.selectedHand ?? selectedHand.value) === 'LEFT' ? 'lewą' : 'prawą',
);
const connectionLost = computed(() => gameState.value && !isReady.value);
const gameError = computed(() => {
    if (connectionLost.value || gameState.value?.status === 'FINISHED') return '';
    if (lastClose.value?.code === 1008) return 'Sesja wygasła. Utwórz nową sesję.';
    if (nicknameRejected.value) return '';
    if (error.value?.code === 'SESSION_STORAGE_UNAVAILABLE')
        return 'Nie udało się sprawdzić nicku. Spróbuj ponownie.';
    if (error.value?.code === 'INVALID_SAMPLE' || error.value?.code === 'GAME_FINISHED') return '';
    return error.value ? 'Wystąpił problem z połączeniem. Spróbuj ponownie.' : '';
});
</script>

<template>
    <div class="panel" :class="{ 'with-diagnostics': diagnosticMode }">
        <section
            v-if="gameState?.status === 'FINISHED'"
            class="result"
            aria-labelledby="result-title"
        >
            <div class="decor" aria-hidden="true">
                <BrainRaster class="decor-brain" />
                <span class="decor-orbit"></span>
            </div>
            <div class="result-summary">
                <p class="label">{{ gameState.nickname }}</p>
                <h2 id="result-title">Koniec rundy</h2>
                <div class="score-disc">
                    <p class="final-score">
                        <strong>{{ gameState.score }}</strong> pkt
                    </p>
                </div>
                <button type="button" class="primary" @click="resetSession">Nowa sesja</button>
            </div>
            <RoundRanking
                :key="gameState.sessionId"
                class="result-ranking"
                :player="{ nickname: gameState.nickname, score: gameState.score }"
            />
        </section>

        <section
            v-else
            class="round"
            :class="gameState ? `is-${gameState.status.toLowerCase()}` : 'is-setup'"
            aria-label="Runda"
        >
            <template v-if="!gameState">
                <div class="decor" aria-hidden="true">
                    <BrainRaster class="decor-brain" />
                    <span class="decor-orbit"></span>
                    <span class="decor-ring"></span>
                    <span class="decor-disc"></span>
                    <span class="decor-dot"></span>
                </div>
                <div class="setup">
                    <div class="hero">
                        <p class="wordmark hero-mark" aria-label="PJATK AI">
                            PJATK<span class="brace">{</span>AI<span class="brace">}</span>
                        </p>
                        <span class="accent" aria-hidden="true"></span>
                        <h2>Przeliteruj<br />słowo.</h2>
                        <p>
                            Pokaż dłoń do kamery i zamigaj kolejne litery polskiego alfabetu
                            migowego.
                        </p>
                        <slot name="setup-extra" />
                    </div>
                    <form
                        class="setup-card"
                        @submit.prevent="createSession({ nickname, selectedHand })"
                    >
                        <fieldset :disabled="pendingAction !== null">
                            <label class="label" for="nickname">Twój nick</label>
                            <div class="nick-row">
                                <input
                                    id="nickname"
                                    ref="nicknameInput"
                                    v-model="nickname"
                                    required
                                    maxlength="20"
                                    autocomplete="off"
                                    spellcheck="false"
                                    :aria-invalid="nicknameRejected || undefined"
                                    :aria-describedby="
                                        nicknameRejected ? 'nickname-error' : undefined
                                    "
                                />
                                <button
                                    type="button"
                                    class="dice"
                                    @click="nickname = randomNickname()"
                                >
                                    <svg viewBox="0 0 24 24" aria-hidden="true">
                                        <rect x="3" y="3" width="18" height="18" rx="4" />
                                        <circle cx="8.5" cy="8.5" r="1.3" />
                                        <circle cx="12" cy="12" r="1.3" />
                                        <circle cx="15.5" cy="15.5" r="1.3" />
                                    </svg>
                                    Losuj
                                </button>
                            </div>
                            <p
                                v-if="nicknameRejected"
                                id="nickname-error"
                                class="nickname-error"
                                role="alert"
                            >
                                Ten nick jest niedozwolony. Wpisz inny lub kliknij „Losuj”.
                            </p>
                            <fieldset class="hands">
                                <legend class="label">Wybierz dłoń.</legend>
                                <div class="segmented">
                                    <label>
                                        <input
                                            v-model="selectedHand"
                                            type="radio"
                                            name="selected-hand"
                                            value="LEFT"
                                        />
                                        <span>Lewa</span>
                                    </label>
                                    <label>
                                        <input
                                            v-model="selectedHand"
                                            type="radio"
                                            name="selected-hand"
                                            value="RIGHT"
                                        />
                                        <span>Prawa</span>
                                    </label>
                                </div>
                            </fieldset>
                            <div v-if="nicknameConfirmation" class="notice nickname-confirmation">
                                <p id="nickname-reuse-message" role="status">
                                    Ten nick jest już w rankingu. Czy chcesz zagrać pod tym nickiem?
                                </p>
                                <button
                                    ref="continueButton"
                                    type="button"
                                    class="primary"
                                    aria-describedby="nickname-reuse-message"
                                    @click="confirmNicknameReuse"
                                >
                                    Kontynuuj
                                </button>
                                <button type="button" @click="changeNickname">Zmień nick</button>
                            </div>
                            <button v-else type="submit" class="primary start">
                                {{ pendingAction === 'create' ? 'Łączenie…' : 'Zaczynamy' }}
                            </button>
                        </fieldset>
                    </form>
                </div>
            </template>

            <div v-if="connectionLost" class="notice" role="status">
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
            <p v-if="gameError" class="notice error" role="alert">{{ gameError }}</p>

            <div v-if="gameState" class="play">
                <div class="camera-area">
                    <CameraTrackingPanel
                        :key="gameState.sessionId"
                        :ready="isReady"
                        :selected-hand="gameState.selectedHand"
                        :send-hand-sample="sendHandSample"
                        :send-no-hand="sendNoHand"
                        @active="cameraActive = $event"
                        @camera-ready="cameraReady = $event"
                    >
                        <button
                            v-if="gameState.status === 'CREATED'"
                            type="button"
                            class="primary"
                            :disabled="!startableSessionId || countdown !== null"
                            @click="beginCountdown"
                        >
                            {{ pendingAction === 'start' ? 'Rozpoczynanie…' : 'Rozpocznij rundę' }}
                        </button>
                        <button
                            v-if="gameState.status === 'CREATED'"
                            type="button"
                            class="quiet-action"
                            @click="resetSession"
                        >
                            Wróć
                        </button>
                        <button
                            v-if="running"
                            type="button"
                            class="end-action"
                            aria-haspopup="dialog"
                            @click="leaveDialog?.showModal()"
                        >
                            Opuść rundę
                        </button>
                    </CameraTrackingPanel>
                </div>

                <div
                    v-if="gameState.status === 'CREATED'"
                    class="ready-copy"
                    :class="{ 'is-counting-down': countdown !== null }"
                >
                    <template v-if="countdown === null">
                        <span class="accent" aria-hidden="true"></span>
                        <p class="label">{{ gameState.nickname }}</p>
                        <h2>Gotowy?</h2>
                        <p>Uruchom kamerę, pokaż {{ handName }} dłoń i rozpocznij rundę.</p>
                        <section class="round-rules" aria-labelledby="round-rules-title">
                            <h3 id="round-rules-title" class="label">Zasady rundy</h3>
                            <ul>
                                <li>
                                    <strong>Zamigaj podświetloną literę.</strong> Użyj wzoru i
                                    przytrzymaj znak, aż gra go zaliczy.
                                </li>
                                <li>
                                    <strong>Litera się powtarza?</strong> Wyjmij dłoń z kadru, a
                                    potem pokaż znak ponownie.
                                </li>
                                <li>
                                    <strong>Zbieraj punkty, zanim skończy się czas.</strong>
                                    Dostajesz je za litery, a za całe słowo dochodzi premia.
                                </li>
                            </ul>
                        </section>
                    </template>
                    <div class="countdown" role="status" aria-atomic="true">
                        <div v-if="countdown !== null" class="countdown-orbit">
                            <OrbitRing :progress="countdown / 3" :thickness="1.8" />
                            <div :key="countdown" class="countdown-tick">
                                <span class="countdown-number">{{ countdown }}</span>
                            </div>
                        </div>
                    </div>
                    <p v-if="countdown !== null">Przygotuj dłoń.</p>
                </div>

                <div v-if="running" class="hud">
                    <div class="letter-row">
                        <div class="letter-block with-ring">
                            <OrbitRing :progress="timeProgress" />
                            <div class="letter-disc">
                                <span class="disc-label">Zamigaj</span>
                                <span
                                    :key="running.positionId"
                                    class="requested-letter"
                                    aria-live="polite"
                                    >{{ running.expectedLetter }}</span
                                >
                                <span
                                    role="timer"
                                    class="disc-timer"
                                    :class="{ low: lowTime }"
                                    :aria-label="`Pozostało ${clock}`"
                                    >{{ clock }}</span
                                >
                            </div>
                        </div>
                        <SignReference
                            :expected-letter="running.expectedLetter"
                            :mirrored="running.selectedHand === 'RIGHT'"
                        />
                    </div>

                    <p :key="running.word" class="word" :aria-label="`Słowo: ${running.word}`">
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

                    <p class="hint" :class="{ empty: !hint }" role="status">{{ hint }}</p>

                    <div class="scoreline">
                        <p class="label">
                            Punkty
                            <strong :key="running.score" class="points" aria-live="polite">{{
                                running.score
                            }}</strong>
                        </p>
                        <p class="label player">{{ running.nickname }}</p>
                    </div>
                </div>
            </div>
            <dialog
                v-if="running"
                ref="leaveDialog"
                class="leave-dialog"
                aria-labelledby="leave-round-question"
                aria-describedby="leave-round-description"
            >
                <h2 id="leave-round-question">Opuścić rundę?</h2>
                <p id="leave-round-description">
                    Wrócisz do ekranu startowego. Po upływie czasu rundy wynik trafi do rankingu,
                    jeśli to pierwszy wynik pod tym nickiem lub poprawia zapisany rekord.
                </p>
                <div class="leave-actions">
                    <button type="button" autofocus @click="leaveDialog?.close()">
                        Graj dalej
                    </button>
                    <button type="button" class="leave-confirm" @click="leaveRound">
                        Tak, opuść rundę
                    </button>
                </div>
            </dialog>
        </section>

        <SessionDiagnostics
            v-if="diagnosticMode"
            :session="session"
            :camera-active="cameraActive"
        />
    </div>
</template>

<style scoped>
.panel {
    height: 100%;
    min-height: 0;
}
.round,
.result {
    height: 100%;
    min-height: 0;
}
h2 {
    margin: 0;
    font-family: var(--font-display);
    font-weight: 400;
    line-height: 1.06;
    letter-spacing: 0.02em;
    text-transform: uppercase;
}
.accent {
    display: block;
    width: clamp(3rem, 7vmin, 6rem);
    height: clamp(5px, 0.7vmin, 8px);
    border-radius: 4px;
    background: var(--red);
}

/* Decorative brand motifs: brain raster, orbits and discs from the posters. */
.decor {
    position: fixed;
    inset: 0;
    z-index: 0;
    overflow: hidden;
    pointer-events: none;
}
.decor-brain {
    position: absolute;
    right: -8vmin;
    top: 12vmin;
    width: min(70vmin, 760px);
    color: var(--brain);
}
.decor-orbit,
.decor-ring,
.decor-disc,
.decor-dot {
    position: absolute;
    border-radius: 50%;
}
.decor-orbit {
    right: -36vmin;
    top: -26vmin;
    width: 70vmin;
    height: 70vmin;
    border: 4px solid var(--red);
}
.decor-ring {
    right: -22vmin;
    bottom: -20vmin;
    width: 58vmin;
    height: 58vmin;
    border: 4px solid var(--red);
}
.decor-disc {
    right: -13vmin;
    bottom: -15vmin;
    width: 38vmin;
    height: 38vmin;
    background: var(--disc);
}
.decor-dot {
    right: 20vmin;
    bottom: 19vmin;
    width: 6vmin;
    height: 6vmin;
    background: var(--disc-small);
}

/* Start screen. */
.setup {
    position: relative;
    z-index: 1;
    display: grid;
    align-content: center;
    gap: calc(var(--pad) * 1.2);
    height: 100%;
    max-width: 62rem;
}
.hero {
    display: grid;
    gap: calc(var(--pad) * 0.7);
}
.hero-mark {
    margin-bottom: calc(var(--pad) * 0.3);
    font-size: clamp(2rem, 6vmin, 4.6rem);
}
.hero h2 {
    font-size: clamp(2.6rem, 9.5vmin, 7.5rem);
}
.hero p {
    margin: 0;
    max-width: 34rem;
    font-size: clamp(1.05rem, 2.8vmin, 2.1rem);
    line-height: 1.4;
    color: var(--muted);
}
.setup-card {
    padding: calc(var(--pad) * 1.1);
    border-radius: calc(var(--radius) * 1.3);
    background: var(--glass);
    backdrop-filter: blur(24px);
    -webkit-backdrop-filter: blur(24px);
    border: 1px solid var(--glass-line);
    box-shadow: var(--glass-shadow);
}
.setup-card > fieldset {
    display: grid;
    gap: calc(var(--pad) * 0.55);
    margin: 0;
    padding: 0;
    border: 0;
}
.nick-row {
    display: flex;
    gap: 0.75rem;
    margin-top: -0.3rem;
}
.nick-row input {
    flex: 1;
    min-width: 0;
    height: clamp(3.4rem, 7.5vmin, 6rem);
    padding: 0 1.1rem;
    border: 2px solid var(--field-line);
    border-radius: calc(var(--radius) * 0.8);
    background: var(--field);
    font-family: var(--font-display);
    font-size: clamp(1.3rem, 3.4vmin, 2.6rem);
    letter-spacing: 0.06em;
    text-transform: uppercase;
}
.nick-row input:focus-visible {
    outline-offset: 0;
}
.nick-row input[aria-invalid='true'] {
    border-color: var(--error);
}
.nickname-error {
    margin: 0;
    color: var(--error);
    font-size: clamp(1rem, 2vmin, 1.5rem);
}
.dice {
    display: flex;
    align-items: center;
    gap: 0.55rem;
    height: clamp(3.4rem, 7.5vmin, 6rem);
    border-radius: calc(var(--radius) * 0.8);
    font-size: clamp(1rem, 2.2vmin, 1.7rem);
}
.dice svg {
    width: 1.3em;
    height: 1.3em;
    fill: none;
    stroke: currentColor;
    stroke-width: 2;
}
.dice circle {
    fill: currentColor;
    stroke: none;
}
.hands {
    margin: 0;
    padding: 0;
    border: 0;
}
.hands legend {
    padding: 0;
    margin-bottom: 0.6rem;
}
.segmented {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 0.4rem;
    padding: 0.4rem;
    border-radius: calc(var(--radius) * 0.9);
    background: var(--surface-strong);
}
.segmented label {
    position: relative;
    cursor: pointer;
}
.segmented input {
    position: absolute;
    opacity: 0;
    inset: 0;
    margin: 0;
    cursor: pointer;
}
.segmented span {
    display: grid;
    place-items: center;
    height: clamp(3rem, 6.4vmin, 5rem);
    border-radius: calc(var(--radius) * 0.7);
    font-weight: 600;
    font-size: clamp(1.05rem, 2.5vmin, 1.9rem);
    color: var(--muted);
    transition:
        background-color 0.2s ease,
        color 0.2s ease;
}
.segmented input:checked + span {
    background: var(--toggle-on-bg);
    color: var(--toggle-on-fg);
}
.segmented input:focus-visible + span {
    outline: 3px solid var(--red);
    outline-offset: 2px;
}
.start {
    height: clamp(4rem, 9vmin, 7.5rem);
    font-size: clamp(1.4rem, 3.6vmin, 2.8rem);
}

/* Notices. */
.notice {
    position: relative;
    z-index: 3;
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 1rem;
    margin: 0 0 1rem;
    padding: 1rem 1.25rem;
    border-radius: var(--radius);
    background: var(--surface);
    border: 1px solid var(--line);
}
.notice p {
    margin: 0;
}
.notice.error {
    color: var(--error);
}
.nickname-confirmation {
    margin: 0;
}
.nickname-confirmation p {
    flex-basis: 100%;
}

/* Camera and round. */
.round:not(.is-setup) {
    display: flex;
    flex-direction: column;
}
.play {
    flex: 1;
    display: grid;
    grid-template-rows: minmax(0, 1fr) auto;
    gap: calc(var(--pad) * 0.9);
    min-height: 0;
}
.is-created .play {
    overflow-y: auto;
}
.camera-area {
    align-self: stretch;
    min-height: 0;
    height: 100%;
}
.ready-copy {
    display: grid;
    gap: 0.8rem;
    justify-items: start;
}
.ready-copy.is-counting-down {
    justify-items: center;
    text-align: center;
}
.ready-copy h2 {
    font-size: clamp(2.2rem, 7vmin, 5.5rem);
}
.ready-copy:not(.is-counting-down) .countdown {
    position: absolute;
}
.countdown-orbit {
    --countdown-size: clamp(9rem, min(30vw, 52dvh), 26rem);
    position: relative;
    width: var(--countdown-size);
    aspect-ratio: 1;
}
.countdown-tick {
    position: absolute;
    inset: 0;
    display: grid;
    place-items: center;
}
.countdown-tick::before {
    position: absolute;
    inset: 9%;
    border: 2px solid var(--red);
    border-radius: 50%;
    content: '';
    animation: countdown-ripple 0.85s ease-out both;
}
.countdown-number {
    color: var(--red);
    font-family: var(--font-display);
    font-size: calc(var(--countdown-size) * 0.68);
    line-height: 1;
    animation: pop 0.48s cubic-bezier(0.2, 1.4, 0.4, 1) both;
}
@keyframes countdown-ripple {
    from {
        transform: scale(0.78);
        opacity: 0.42;
    }
    to {
        transform: scale(1.08);
        opacity: 0;
    }
}
.ready-copy p {
    margin: 0;
    max-width: 34rem;
    font-size: clamp(1rem, 2.4vmin, 1.8rem);
    line-height: 1.4;
}
.ready-copy p:not(.label) {
    color: var(--muted);
}
.round-rules {
    width: 100%;
    max-width: 42rem;
    margin-top: calc(var(--pad) * 0.25);
    padding-top: calc(var(--pad) * 0.55);
    border-top: 1px solid var(--line);
}
.round-rules h3 {
    margin: 0;
}
.round-rules ul {
    display: grid;
    gap: 0.55em;
    margin: 0.7em 0 0;
    padding-inline-start: 1.3em;
    font-size: clamp(1rem, 2.35vmin, 1.7rem);
    line-height: 1.4;
    color: var(--muted);
}
.round-rules li::marker {
    color: var(--red);
}
.round-rules strong {
    color: var(--fg);
}
.disc-timer {
    font-family: var(--font-display);
    letter-spacing: 0.08em;
    font-variant-numeric: tabular-nums;
}
@keyframes pulse {
    50% {
        transform: scale(1.06);
    }
}
.hud {
    display: grid;
    gap: calc(var(--pad) * 0.7);
    min-height: 0;
}
.letter-row {
    display: grid;
    grid-template-columns: auto minmax(0, 1fr);
    gap: calc(var(--pad) * 0.9);
    align-items: center;
    height: var(--letter-size);
    --letter-size: clamp(9rem, min(34vw, 21dvh), 26rem);
}
.letter-block {
    position: relative;
    width: var(--letter-size);
    height: var(--letter-size);
}
.letter-disc {
    position: absolute;
    inset: 0;
    display: grid;
    place-content: center;
    justify-items: center;
    border-radius: 50%;
    background: var(--red);
    color: #fff;
}
.requested-letter {
    animation: pop 0.45s cubic-bezier(0.2, 1.4, 0.4, 1);
}
.with-ring .letter-disc {
    inset: 9%;
    background: transparent;
    color: var(--fg);
}
.with-ring .disc-label {
    color: var(--muted);
}
.with-ring .disc-timer {
    color: var(--red);
}
@keyframes pop {
    from {
        transform: scale(0.6);
        opacity: 0;
    }
}
.disc-label {
    font-family: var(--font-display);
    font-size: calc(var(--letter-size) * 0.055);
    letter-spacing: 0.2em;
    text-transform: uppercase;
    opacity: 0.9;
}
.requested-letter {
    font-family: var(--font-display);
    font-size: calc(var(--letter-size) * 0.58);
    line-height: 1;
}
.with-ring .requested-letter {
    font-size: calc(var(--letter-size) * 0.46);
}
.disc-timer {
    font-size: calc(var(--letter-size) * 0.1);
}
.disc-timer.low {
    animation: pulse 1s ease-in-out infinite;
}
.word {
    display: flex;
    gap: clamp(0.4rem, 1.2vmin, 1rem);
    margin: 0;
    font-family: var(--font-display);
    font-size: clamp(2rem, min(8vw, 5.5dvh), 6rem);
    line-height: 1;
}
.word span {
    flex: 1;
    display: grid;
    place-items: center;
    min-width: 0;
    padding: 0.32em 0;
    border-radius: calc(var(--radius) * 0.7);
    border: 2px solid var(--line);
    transition:
        background-color 0.3s ease,
        color 0.3s ease,
        border-color 0.3s ease;
}
.word .complete {
    border-color: transparent;
    background: var(--surface-strong);
    color: var(--done);
}
.word .current {
    border-color: var(--red);
    background: var(--red);
    color: #fff;
    animation: pop 0.4s cubic-bezier(0.2, 1.4, 0.4, 1);
}
.hint {
    min-height: 3.2em;
    display: grid;
    place-items: center;
    margin: 0;
    padding: 0.8em 1.1em;
    border-radius: var(--radius);
    background: var(--surface-strong);
    font-weight: 600;
    font-size: clamp(1rem, 2.2vmin, 1.6rem);
    text-align: center;
}
.hint.empty {
    background: transparent;
}
.scoreline {
    display: flex;
    justify-content: space-between;
    align-items: baseline;
    gap: 1rem;
    padding-top: 0.8rem;
    border-top: 2px solid var(--fg);
}
.points {
    display: inline-block;
    margin-left: 0.6em;
    font-size: 2.6em;
    font-weight: 400;
    color: var(--fg);
    letter-spacing: 0.02em;
    animation: bump 0.4s ease;
}
@keyframes bump {
    40% {
        transform: scale(1.25);
        color: var(--red);
    }
}
.player {
    color: var(--fg);
}

/* End / back controls under the camera. */
.quiet-action,
.end-action {
    min-height: clamp(2.8rem, 5.4vmin, 4.4rem);
    padding-inline: 1.4em;
    font-size: clamp(0.95rem, 1.8vmin, 1.4rem);
}
.quiet-action {
    border-color: transparent;
    color: var(--muted);
}
.leave-dialog {
    width: min(calc(100% - 2rem), 34rem);
    max-height: 90dvh;
    padding: clamp(1.25rem, 4vmin, 2rem);
    border: 1px solid var(--glass-line);
    border-radius: var(--radius);
    background: var(--bg);
    color: var(--fg);
    box-shadow: var(--glass-shadow);
}
.leave-dialog[open] {
    animation: leave-dialog-in 0.18s ease-out both;
}
.leave-dialog::backdrop {
    background: rgba(0, 0, 0, 0.55);
    backdrop-filter: blur(6px);
}
.leave-dialog h2 {
    font-size: clamp(1.55rem, 4vmin, 2.6rem);
}
.leave-dialog p {
    margin: 0.9rem 0 1.3rem;
    color: var(--muted);
    line-height: 1.5;
}
.leave-actions {
    display: flex;
    flex-wrap: wrap;
    justify-content: flex-end;
    gap: 0.6rem;
}
.leave-confirm {
    border-color: var(--red);
    background: var(--red);
    color: #fff;
}
@keyframes leave-dialog-in {
    from {
        transform: translateY(0.35rem);
        opacity: 0;
    }
}

/* Result. */
.result {
    position: relative;
    display: grid;
    grid-template-rows: auto minmax(0, 1fr);
    gap: calc(var(--pad) * 1.2);
}
.result-summary {
    position: relative;
    z-index: 1;
    display: grid;
    justify-items: start;
    gap: calc(var(--pad) * 0.6);
}
.result-summary h2 {
    font-size: clamp(2.2rem, 7vmin, 5.5rem);
}
.score-disc {
    display: grid;
    place-items: center;
    width: clamp(12rem, min(46vw, 30dvh), 30rem);
    aspect-ratio: 1;
    border-radius: 50%;
    background: var(--red);
    color: #fff;
    animation: pop 0.6s cubic-bezier(0.2, 1.4, 0.4, 1);
}
.final-score {
    margin: 0;
    font-family: var(--font-display);
    font-size: clamp(1.2rem, 3vmin, 2.4rem);
    text-align: center;
}
.final-score strong {
    display: block;
    font-weight: 400;
    font-size: 3em;
    line-height: 1;
}
.result-summary .primary {
    height: clamp(3.6rem, 8vmin, 6.5rem);
    padding-inline: 2.2em;
    font-size: clamp(1.2rem, 3vmin, 2.2rem);
}
.result-ranking {
    position: relative;
    z-index: 1;
}
.result .decor-brain {
    top: auto;
    bottom: -6vmin;
}

/* Landscape: camera on the left, everything else on the right. */
@media (orientation: landscape) {
    .setup {
        grid-template-columns: minmax(0, 1.1fr) minmax(0, 1fr);
        align-items: center;
        max-width: none;
    }
    .play {
        grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr);
        grid-template-rows: minmax(0, 1fr);
    }
    .hud {
        align-self: center;
    }
    .ready-copy {
        align-self: safe center;
    }
    .letter-row {
        --letter-size: clamp(8rem, min(17vw, 30dvh), 22rem);
    }
    .word {
        font-size: clamp(2.2rem, min(6vw, 10dvh), 7rem);
    }
    .result {
        grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
        grid-template-rows: minmax(0, 1fr);
        align-items: center;
    }
}

/* Portrait kiosk (e.g. a 32" screen on its side): size type by width, which is the short side. */
@media (orientation: portrait) {
    .is-created .play {
        grid-template-columns: minmax(0, 1fr);
        grid-template-rows: minmax(13rem, 1fr) auto;
    }
    .countdown-orbit {
        --countdown-size: clamp(9rem, min(54vw, 31dvh), 22rem);
    }
    .setup {
        align-content: space-evenly;
    }
    .hero-mark {
        font-size: clamp(2rem, 8vw, 6rem);
    }
    .hero h2 {
        font-size: clamp(2.6rem, 12.5vw, 9.5rem);
    }
    .hero p {
        max-width: 46rem;
        font-size: clamp(1.05rem, 3.6vw, 2.5rem);
    }
    .setup-card > fieldset {
        gap: calc(var(--pad) * 0.8);
    }
    .ready-copy h2,
    .result-summary h2 {
        font-size: clamp(2.2rem, 10vw, 7.5rem);
    }
    .ready-copy p {
        font-size: clamp(1rem, 3.4vw, 2.3rem);
    }
    .hint {
        font-size: clamp(1rem, 2.8vw, 2rem);
    }
    .scoreline .label {
        font-size: clamp(0.8rem, 2.2vw, 1.6rem);
    }
    /* Keep the brain motif beside the score disc, away from the ranking numbers. */
    .result .decor-brain {
        top: 14vh;
        bottom: auto;
        right: -12vw;
        width: 58vw;
    }
}

/* Diagnostics mode is a developer view: let the page scroll. */
.with-diagnostics {
    display: grid;
    align-items: start;
    gap: 1rem;
    height: auto;
}
.with-diagnostics > section {
    min-width: 0;
    height: auto;
}
.with-diagnostics .play {
    height: auto;
}
.with-diagnostics .camera-area {
    height: 60vh;
}
@media (min-width: 48rem) {
    .with-diagnostics {
        grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr);
    }
    .with-diagnostics .diagnostics {
        position: sticky;
        top: 0.75rem;
        max-height: calc(100dvh - 1.5rem);
        overflow: auto;
    }
}
</style>
