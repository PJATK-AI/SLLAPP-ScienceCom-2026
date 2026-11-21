<script setup lang="ts">
import SessionPanel from './components/SessionPanel.vue';
</script>
<template>
    <main>
        <header>
            <h1>SLLAPP</h1>
            <p>Polski alfabet palcowy</p>
        </header>
        <SessionPanel />
    </main>
</template>

<style>
:root {
    font-family: system-ui, sans-serif;
    color: #192b29;
    background: #f2f5f3;
    --muted: #4c625f;
    --accent: #006358;
    --accent-soft: #dbf1eb;
    --border: #cad8d4;
    --error: #972427;
}
*,
*::before,
*::after {
    box-sizing: border-box;
}
body {
    margin: 0;
}
main {
    width: min(100% - 2rem, 62rem);
    margin: 0 auto 2rem;
}
header {
    display: flex;
    align-items: baseline;
    gap: 0.75rem;
    flex-wrap: wrap;
    padding: 1.5rem 0;
}
header h1,
header p {
    margin: 0;
}
header h1 {
    font-size: 1.5rem;
    letter-spacing: -0.035em;
}
header p {
    color: var(--muted);
}
.card {
    padding: clamp(1rem, 3vw, 1.5rem);
    border: 1px solid var(--border);
    border-radius: 0.9rem;
    background: #fff;
    min-width: 0;
}
button,
input,
select {
    min-height: 2.75rem;
    font: inherit;
    color: inherit;
    border: 1px solid var(--border);
    border-radius: 0.5rem;
    padding: 0.55rem 0.75rem;
    background: #fff;
}
button {
    font-weight: 650;
    cursor: pointer;
}
button:hover:not(:disabled) {
    border-color: var(--accent);
    background: var(--accent-soft);
}
button.primary {
    border-color: var(--accent);
    color: white;
    background: var(--accent);
}
button.primary:hover:not(:disabled) {
    background: #004c44;
}
button:disabled {
    color: var(--muted);
    border-color: var(--border);
    background: #edf1ef;
    cursor: default;
}
:focus-visible {
    outline: 3px solid #207d71;
    outline-offset: 2px;
}
</style>
