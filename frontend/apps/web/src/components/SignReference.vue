<script setup lang="ts">
import { computed } from 'vue';
import alphabetImage from '@/assets/pjm-fingerspelling-zpe.png';

const props = defineProps<{ expectedLetter: string }>();

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
    <figure v-if="crop" class="card reference">
        <figcaption>
            <h3>Znak w PJM · {{ expectedLetter }}</h3>
        </figcaption>
        <div
            class="sign"
            role="img"
            :aria-label="`Układ dłoni dla litery ${expectedLetter}`"
            :style="crop"
        ></div>
        <small>
            <a href="https://zpe.gov.pl/a/w-swiecie-znakow/DNLn0RJB9#RuTDZ6bESDuoy">ZPE</a>
            · <a href="https://creativecommons.org/licenses/by-sa/3.0/">CC BY-SA 3.0</a>
        </small>
    </figure>
</template>

<style scoped>
.reference {
    margin: 0;
}
h3 {
    margin: 0 0 0.75rem;
}
.sign {
    width: min(100%, 10rem);
    background-repeat: no-repeat;
}
small {
    display: block;
    margin-top: 0.5rem;
    color: var(--muted);
}
a {
    color: inherit;
}
</style>
