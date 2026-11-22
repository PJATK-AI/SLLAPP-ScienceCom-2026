<script setup lang="ts">
import { onMounted } from 'vue';
import { provideRanking } from '@/composables/ranking';

const props = defineProps<{ player?: { nickname: string; score: number } }>();
const { ranking, isLoading, error, loadRanking } = provideRanking();
onMounted(() => void loadRanking());

const isPlayer = (entry: { nickname: string; score: number }) =>
    entry.nickname === props.player?.nickname && entry.score === props.player?.score;
</script>

<template>
    <section class="ranking" aria-labelledby="ranking-title">
        <h2 id="ranking-title" class="label">Ranking</h2>
        <p v-if="isLoading" role="status">Ładowanie rankingu…</p>
        <template v-else-if="error">
            <p role="alert">Nie udało się pobrać rankingu.</p>
            <button type="button" @click="loadRanking()">Ponów</button>
        </template>
        <p v-else-if="ranking.length === 0">Brak wyników.</p>
        <table v-else>
            <thead>
                <tr>
                    <th scope="col">Miejsce</th>
                    <th scope="col">Gracz</th>
                    <th scope="col">Punkty</th>
                </tr>
            </thead>
            <tbody>
                <tr
                    v-for="entry in ranking"
                    :key="entry.rank"
                    :class="{ me: isPlayer(entry) }"
                    :aria-current="isPlayer(entry) ? 'true' : undefined"
                >
                    <td>{{ String(entry.rank).padStart(2, '0') }}</td>
                    <td>{{ entry.nickname }}</td>
                    <td>{{ entry.score }}</td>
                </tr>
            </tbody>
        </table>
    </section>
</template>

<style scoped>
.ranking {
    display: flex;
    flex-direction: column;
    gap: 0.75rem;
    min-height: 0;
    overflow: auto;
}
h2 {
    margin: 0;
}
table {
    width: 100%;
    border-collapse: collapse;
    text-align: left;
    font-size: clamp(1rem, 2.2vmin, 1.8rem);
}
thead {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip: rect(0 0 0 0);
}
td {
    padding: 0.55em 0.2em;
    border-bottom: 1px solid var(--line);
    overflow-wrap: anywhere;
}
td:first-child {
    width: 3.5em;
    font-family: var(--font-display);
    color: var(--muted);
}
tr:first-child td:first-child {
    color: var(--red);
}
td:last-child {
    text-align: right;
    font-family: var(--font-display);
}
tr.me td {
    color: var(--red);
    font-weight: 700;
}
</style>
