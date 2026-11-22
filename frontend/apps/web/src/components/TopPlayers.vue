<script setup lang="ts">
import { onMounted } from 'vue';
import { provideRanking } from '@/composables/ranking';

// A quiet top-three teaser for the start screen; failures simply hide it.
const { ranking, error, loadRanking } = provideRanking();
onMounted(() => void loadRanking(3));
</script>

<template>
    <section v-if="!error && ranking.length > 0" class="top-players" aria-labelledby="top-title">
        <h2 id="top-title" class="label">Najlepsi gracze</h2>
        <ol>
            <li v-for="entry in ranking" :key="entry.rank">
                <span class="rank">{{ String(entry.rank).padStart(2, '0') }}</span>
                <span class="name">{{ entry.nickname }}</span>
                <span class="score">{{ entry.score }}</span>
            </li>
        </ol>
    </section>
</template>

<style scoped>
.top-players {
    display: grid;
    gap: 0.6rem;
    max-width: 30rem;
}
h2 {
    margin: 0;
}
ol {
    margin: 0;
    padding: 0;
    list-style: none;
}
li {
    display: grid;
    grid-template-columns: 3em minmax(0, 1fr) auto;
    gap: 0.5rem;
    padding: 0.45em 0;
    border-bottom: 1px solid var(--line);
    font-size: clamp(0.95rem, 2vmin, 1.5rem);
}
.rank,
.score {
    font-family: var(--font-display);
}
.rank {
    color: var(--muted);
}
li:first-child .rank {
    color: var(--red);
}
.name {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}
</style>
