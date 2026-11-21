<script setup lang="ts">
import { onMounted } from 'vue';
import { provideRanking } from '@/composables/ranking';

const { ranking, isLoading, error, loadRanking } = provideRanking();
onMounted(() => void loadRanking());
</script>

<template>
    <section class="card" aria-labelledby="ranking-title">
        <h2 id="ranking-title">Ranking</h2>
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
                <tr v-for="entry in ranking" :key="entry.rank">
                    <td>{{ entry.rank }}</td>
                    <td>{{ entry.nickname }}</td>
                    <td>{{ entry.score }}</td>
                </tr>
            </tbody>
        </table>
    </section>
</template>

<style scoped>
h2 {
    margin: 0;
}
table {
    width: 100%;
    margin-top: 1rem;
    border-collapse: collapse;
    text-align: left;
}
th,
td {
    padding: 0.55rem 0.4rem;
    border-bottom: 1px solid var(--border);
}
td {
    overflow-wrap: anywhere;
}
th:last-child,
td:last-child {
    text-align: right;
}
</style>
