<script setup lang="ts">
import { computed } from 'vue';
import alphabetImage from '@/assets/pjm-fingerspelling-zpe.png';

// mirrored: flip the drawing so it matches the mirrored camera preview for a right-handed player.
const props = defineProps<{ expectedLetter: string; mirrored?: boolean }>();

// Tile positions inside the 490 × 774 ZPE fingerspelling chart.
const alphabetRows = ['AĄBCĆ·', '·DEĘFG', 'HIJKLŁ', 'MNŃOÓP', 'R·SŚ·T', 'UWYZŹŻ'];
const columns = [3, 84, 165, 247, 328, 411];
const rows = [4, 133, 262, 390, 519, 648];
const tileWidth = 77;
const tileHeight = 125;
const crop = computed(() => {
    const row = alphabetRows.findIndex((line) => line.includes(props.expectedLetter));
    const column = alphabetRows[row]?.indexOf(props.expectedLetter) ?? -1;
    const left = columns[column];
    const top = rows[row];
    if (left === undefined || top === undefined) return undefined;
    return {
        backgroundImage: `url(${alphabetImage})`,
        aspectRatio: `${tileWidth} / ${tileHeight}`,
        backgroundSize: `${(490 / tileWidth) * 100}% ${(774 / tileHeight) * 100}%`,
        backgroundPosition: `${(left / (490 - tileWidth)) * 100}% ${(top / (774 - tileHeight)) * 100}%`,
    };
});
</script>

<template>
    <figure v-if="crop" class="reference">
        <figcaption class="label">Wzór znaku · {{ expectedLetter }}</figcaption>
        <div class="tile" :class="{ mirrored }">
            <div
                class="sign"
                role="img"
                :aria-label="`Układ dłoni dla litery ${expectedLetter}`"
                :style="crop"
            ></div>
        </div>
        <small>
            <a href="https://zpe.gov.pl/a/w-swiecie-znakow/DNLn0RJB9#RuTDZ6bESDuoy">ZPE</a>
            · <a href="https://creativecommons.org/licenses/by-sa/3.0/">CC BY-SA 3.0</a>
        </small>
    </figure>
</template>

<style scoped>
.reference {
    display: grid;
    grid-template-rows: auto minmax(0, 1fr) auto;
    gap: 0.6rem;
    height: 100%;
    min-height: 0;
    margin: 0;
    padding: clamp(0.8rem, 2vmin, 1.6rem);
    border-radius: var(--radius);
    background: var(--surface);
    border: 1px solid var(--line);
}
.tile {
    display: grid;
    place-items: center;
    min-height: 0;
    border-radius: calc(var(--radius) * 0.7);
    background: #fff;
    padding: 0.5rem;
}
.sign {
    position: relative;
    height: 100%;
    max-height: 100%;
    max-width: 100%;
    background-repeat: no-repeat;
}
/* Hide the chart's small handwritten letter; it would read backwards when mirrored. */
.sign::after {
    content: '';
    position: absolute;
    right: 0;
    bottom: 0;
    width: 34%;
    height: 21%;
    background: #fff;
}
.mirrored .sign {
    transform: scaleX(-1);
}
/* Dark-theme treatment of the drawing lives in App.vue (global styles). */
small {
    font-size: 0.75rem;
    color: var(--muted);
}
a {
    color: inherit;
}
</style>
