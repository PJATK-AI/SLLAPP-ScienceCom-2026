<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type { useGameSession } from '@/composables/game-session';
import { createTestHand } from './session-test-hand';

const props = defineProps<{ session: ReturnType<typeof useGameSession>; cameraActive: boolean }>();
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
    lastPrediction,
    diagnosticEvents,
    disconnect,
    sendHandSample,
    sendNoHand,
} = props.session;
const open = ref(true);
const feedback = ref('');
const canTest = computed(
    () =>
        isReady.value &&
        gameState.value?.status === 'CREATED' &&
        !props.cameraActive &&
        !pendingAction.value,
);
const sampleJson = computed(() =>
    lastSentPayload.value ? JSON.stringify(JSON.parse(lastSentPayload.value), null, 2) : '',
);
const recentEvents = computed(() => [...diagnosticEvents.value].reverse());
const direction = { send: 'Wysłano', receive: 'Odebrano', connection: 'Połączenie' };
const disposition = {
    PROCESSED: 'Przetworzona',
    POSITION_COMPLETED: 'Zaliczona litera',
    TOO_LATE: 'Po limicie czasu',
    IGNORED: 'Pominięta',
};
watch(
    () => gameState.value?.sessionId,
    () => {
        feedback.value = '';
    },
);

function sample(hand: boolean) {
    const state = gameState.value;
    if (!state || !canTest.value) return;
    const sent = hand ? sendHandSample(createTestHand(state.selectedHand)) : sendNoHand();
    feedback.value = sent
        ? 'Próbka przekazana do wysłania.'
        : 'Próbka pominięta — bufor jest zajęty.';
}
</script>

<template>
    <details
        class="diagnostics card"
        :open="open"
        @toggle="open = ($event.currentTarget as HTMLDetailsElement).open"
    >
        <summary>Diagnostyka</summary>
        <div class="overview">
            <section aria-label="Predykcja AI">
                <h3>Ostatnia predykcja AI</h3>
                <template v-if="lastPrediction">
                    <p class="prediction">
                        {{
                            'letter' in lastPrediction
                                ? lastPrediction.letter
                                : lastPrediction.failure
                        }}
                    </p>
                    <dl>
                        <dt>Seq / pozycja</dt>
                        <dd>{{ lastPrediction.seq }} / {{ lastPrediction.positionId }}</dd>
                        <dt>Ufność</dt>
                        <dd>
                            {{
                                'confidence' in lastPrediction
                                    ? lastPrediction.confidence.toFixed(3)
                                    : '—'
                            }}
                        </dd>
                        <dt>Czas</dt>
                        <dd>{{ lastPrediction.elapsedMs }} ms</dd>
                        <dt>HTTP</dt>
                        <dd>{{ lastPrediction.httpStatus ?? '—' }}</dd>
                        <dt>Decyzja gry</dt>
                        <dd>{{ disposition[lastPrediction.disposition] }}</dd>
                    </dl>
                </template>
                <p v-else>Oczekiwanie na predykcję z rundy.</p>
            </section>
            <section aria-label="WebSocket">
                <h3>WebSocket</h3>
                <p>Połączenie: {{ connectionStatus }}</p>
                <p v-if="error">{{ error.code }}: {{ error.message }}</p>
                <p v-if="lastClose">Zamknięcie: {{ lastClose.code }} {{ lastClose.reason }}</p>
                <dl>
                    <dt>ID sesji</dt>
                    <dd>{{ gameState?.sessionId ?? '—' }}</dd>
                    <dt>Ostatni wysłany seq</dt>
                    <dd data-testid="sent-seq">{{ lastSentSeq ?? '—' }}</dd>
                    <dt>Ostatni ACK</dt>
                    <dd data-testid="ack-seq">{{ lastSampleAck ?? '—' }}</dd>
                </dl>
            </section>
        </div>
        <template v-if="open">
            <h3>Historia WebSocket</h3>
            <p v-if="recentEvents.length === 0">Brak zdarzeń.</p>
            <ol v-else>
                <li v-for="event in recentEvents" :key="event.id">
                    <details v-if="event.payload">
                        <summary>
                            #{{ event.connection }} · {{ direction[event.direction] }} ·
                            {{ event.label }}
                        </summary>
                        <pre tabindex="0">{{ event.payload }}</pre>
                    </details>
                    <span v-else
                        >#{{ event.connection }} · {{ direction[event.direction] }} ·
                        {{ event.label }}</span
                    >
                </li>
            </ol>
        </template>
        <details v-if="sampleJson" class="sample">
            <summary>Ostatnia próbka</summary>
            <pre tabindex="0">{{ sampleJson }}</pre>
        </details>
        <div class="actions">
            <button
                type="button"
                :disabled="connectionStatus !== 'open' && connectionStatus !== 'connecting'"
                @click="disconnect"
            >
                Rozłącz
            </button>
            <button type="button" :disabled="!canTest" @click="sample(true)">
                Wyślij testową dłoń
            </button>
            <button type="button" :disabled="!canTest" @click="sample(false)">
                Wyślij brak dłoni
            </button>
        </div>
        <p v-if="feedback" aria-live="polite">{{ feedback }}</p>
    </details>
</template>

<style scoped>
.diagnostics {
    container-type: inline-size;
    padding: 0.875rem;
    overflow-wrap: anywhere;
}
summary {
    cursor: pointer;
    font-weight: 650;
}
.overview {
    display: grid;
    gap: 0 0.875rem;
    font-size: 0.9rem;
}
.overview section {
    min-width: 0;
}
h3 {
    margin: 1rem 0 0.5rem;
}
p {
    margin: 0.5rem 0;
}
dl {
    display: grid;
    grid-template-columns: auto 1fr;
    column-gap: 0.5rem;
    margin: 0.5rem 0;
}
dt {
    color: var(--muted);
}
dd {
    margin: 0;
}
.prediction {
    font-size: 1.2rem;
    font-weight: 750;
}
.actions {
    display: flex;
    gap: 0.5rem;
    flex-wrap: wrap;
    margin-top: 0.75rem;
}
.actions button {
    min-height: 2.125rem;
    padding: 0.3rem 0.6rem;
    font-size: 0.875rem;
}
.sample {
    margin-top: 0.75rem;
}
pre {
    max-height: 16rem;
    overflow: auto;
    padding: 0.75rem;
    background: #132c2b;
    color: #f5fbfa;
    border-radius: 0.5rem;
}
ol {
    max-height: clamp(7rem, 26dvh, 16rem);
    overflow: auto;
    list-style: none;
    padding: 0;
    margin: 0;
}
li {
    padding: 0.45rem 0;
    border-bottom: 1px solid var(--border);
}
@container (min-width: 22rem) {
    .overview {
        grid-template-columns: repeat(2, minmax(0, 1fr));
    }
}
@container (max-width: 12rem) {
    .overview {
        font-size: 0.8rem;
    }
    h3 {
        margin: 0.75rem 0 0.25rem;
        font-size: 0.875rem;
    }
    dl {
        grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
        column-gap: 0.25rem;
    }
    ol,
    .sample {
        font-size: 0.8rem;
    }
}
</style>
