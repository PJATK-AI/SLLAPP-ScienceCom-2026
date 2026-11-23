<script setup lang="ts">
import { computed } from 'vue';

// The brand's orbit motif doubles as the round timer: the red arc shrinks as time runs out.
const props = withDefaults(defineProps<{ progress?: number; thickness?: number }>(), {
    progress: 1,
    thickness: 2.2,
});
// pathLength="100" makes the dash pattern a percentage of the circle at any rendered size.
// The remaining time is the arc that ends at 12 o'clock; it empties clockwise like a clock hand.
const remaining = computed(() => Math.min(1, Math.max(0, props.progress)) * 100);
const dash = computed(() => `${remaining.value.toFixed(2)} ${(100 - remaining.value).toFixed(2)}`);
const offset = computed(() => (remaining.value - 100).toFixed(2));
</script>

<template>
    <svg
        class="orbit-ring"
        viewBox="0 0 100 100"
        aria-hidden="true"
        focusable="false"
        :style="{ strokeWidth: thickness }"
    >
        <circle class="track" cx="50" cy="50" r="48" pathLength="100" />
        <circle
            v-if="progress > 0"
            class="arc"
            cx="50"
            cy="50"
            r="48"
            pathLength="100"
            :stroke-dasharray="dash"
            :stroke-dashoffset="offset"
        />
    </svg>
</template>

<style scoped>
.orbit-ring {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    overflow: visible;
    transform: rotate(-90deg);
    pointer-events: none;
}
circle {
    fill: none;
}
.track {
    stroke: var(--track);
}
.arc {
    stroke: var(--red);
    stroke-linecap: round;
    transition:
        stroke-dasharray 0.12s linear,
        stroke-dashoffset 0.12s linear;
}
</style>
