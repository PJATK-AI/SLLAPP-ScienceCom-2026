<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watchEffect } from 'vue';
import SessionPanel from './components/SessionPanel.vue';
import TopPlayers from './components/TopPlayers.vue';
import { readSetting, writeSetting, type Theme } from './components/display-settings';
import schoolLogoColor from '@/assets/pjatk-gdansk-logo-color.png';
import schoolLogoWhite from '@/assets/pjatk-gdansk-logo-white.png';

const theme = ref<Theme>(readSetting('sllapp-theme', ['light', 'dark'], 'light'));
const isFullscreen = ref(false);

// Fullscreen hides the browser bars on the kiosk screen; it needs a click (or key) to start.
function toggleFullscreen() {
    if (document.fullscreenElement) void document.exitFullscreen();
    else void document.documentElement.requestFullscreen?.();
}
function onFullscreenChange() {
    isFullscreen.value = !!document.fullscreenElement;
}

watchEffect(() => {
    document.documentElement.dataset.theme = theme.value;
    writeSetting('sllapp-theme', theme.value);
});

// Staff shortcuts: Alt+T switches the theme, Alt+F toggles fullscreen. Ignored while typing.
function onKeydown(event: KeyboardEvent) {
    if (!event.altKey || event.target instanceof HTMLInputElement) return;
    if (event.code === 'KeyT') theme.value = theme.value === 'light' ? 'dark' : 'light';
    else if (event.code === 'KeyF') toggleFullscreen();
    else return;
    event.preventDefault();
}
onMounted(() => {
    window.addEventListener('keydown', onKeydown);
    document.addEventListener('fullscreenchange', onFullscreenChange);
});
onBeforeUnmount(() => {
    window.removeEventListener('keydown', onKeydown);
    document.removeEventListener('fullscreenchange', onFullscreenChange);
});
</script>

<template>
    <main>
        <header class="app-header">
            <div class="brand">
                <p class="wordmark header-mark" aria-label="PJATK AI">
                    PJATK<span class="brace">{</span>AI<span class="brace">}</span>
                </p>
                <span class="brand-divider" aria-hidden="true"></span>
                <div class="school">
                    <img
                        class="school-logo on-light"
                        :src="schoolLogoColor"
                        alt="Polsko-Japońska Akademia Technik Komputerowych, filia w Gdańsku"
                    />
                    <img class="school-logo on-dark" :src="schoolLogoWhite" alt="" />
                </div>
            </div>
            <div class="header-actions">
                <button
                    type="button"
                    class="icon-button"
                    :aria-label="isFullscreen ? 'Wyjdź z pełnego ekranu' : 'Pełny ekran'"
                    :aria-pressed="isFullscreen"
                    @click="toggleFullscreen"
                >
                    <svg v-if="!isFullscreen" viewBox="0 0 24 24" aria-hidden="true">
                        <path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5" />
                    </svg>
                    <svg v-else viewBox="0 0 24 24" aria-hidden="true">
                        <path d="M9 4v5H4M15 4v5h5M9 20v-5H4M15 20v-5h5" />
                    </svg>
                </button>
                <div class="theme-switch" role="group" aria-label="Motyw">
                    <button
                        type="button"
                        aria-label="Jasny motyw"
                        :aria-pressed="theme === 'light'"
                        @click="theme = 'light'"
                    >
                        <svg viewBox="0 0 24 24" aria-hidden="true">
                            <circle cx="12" cy="12" r="4.5" />
                            <path
                                d="M12 2v2.5M12 19.5V22M2 12h2.5M19.5 12H22M4.9 4.9l1.8 1.8M17.3 17.3l1.8 1.8M4.9 19.1l1.8-1.8M17.3 6.7l1.8-1.8"
                            />
                        </svg>
                    </button>
                    <button
                        type="button"
                        aria-label="Ciemny motyw"
                        :aria-pressed="theme === 'dark'"
                        @click="theme = 'dark'"
                    >
                        <svg viewBox="0 0 24 24" aria-hidden="true">
                            <path d="M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z" />
                        </svg>
                    </button>
                </div>
            </div>
        </header>
        <SessionPanel>
            <template #setup-extra>
                <TopPlayers />
            </template>
        </SessionPanel>
    </main>
</template>

<style>
@font-face {
    font-family: 'OCR One';
    src: url('/fonts/ocr-one.ttf') format('truetype');
    font-display: swap;
}

/* PJATK {AI} brand tokens from the design hub (Figma variables --pjatk-*). */
:root {
    --pjatk-black: #000;
    --pjatk-graphite: #1a1a1a;
    --pjatk-red: #e31837;
    --pjatk-white: #fff;
    --pjatk-dot-gray: #ededed;

    --red: var(--pjatk-red);
    --font-display: 'OCR One', ui-monospace, 'SF Mono', Menlo, monospace;
    --font-body: system-ui, -apple-system, 'Segoe UI', sans-serif;
    --pad: clamp(1rem, 3.2vmin, 3.5rem);
    --radius: clamp(1rem, 2.4vmin, 2.5rem);

    color-scheme: light;
    --bg: #fff;
    --fg: #000;
    --muted: #555;
    --surface: #f5f5f5;
    --surface-strong: #ededed;
    --line: #e3e3e3;
    --track: #ededed;
    --brain: var(--pjatk-dot-gray);
    --disc: var(--pjatk-black);
    --disc-small: var(--pjatk-red);
    --glass: rgba(255, 255, 255, 0.74);
    --glass-line: #e6e6e6;
    --glass-shadow: 0 1.8rem 5rem rgba(0, 0, 0, 0.08);
    --field: #fff;
    --field-line: #000;
    --toggle-on-bg: #000;
    --toggle-on-fg: #fff;
    --done: #bdbdbd;
    --error: #b3122b;

    font-family: var(--font-body);
    color: var(--fg);
    background: var(--bg);
}
:root[data-theme='dark'] {
    color-scheme: dark;
    --bg: #000;
    --fg: #fff;
    --muted: #9a9a9a;
    --surface: #121212;
    --surface-strong: #1a1a1a;
    --line: #262626;
    --track: #1f1f1f;
    --brain: #2a2a2a;
    --disc: var(--pjatk-red);
    --disc-small: var(--pjatk-white);
    --glass: rgba(26, 26, 26, 0.62);
    --glass-line: rgba(255, 255, 255, 0.14);
    --glass-shadow: 0 1.8rem 5rem rgba(0, 0, 0, 0.5);
    --field: #0d0d0d;
    --field-line: #3a3a3a;
    --toggle-on-bg: #fff;
    --toggle-on-fg: #000;
    --done: #4a4a4a;
    --error: #ff5a6e;
}

*,
*::before,
*::after {
    box-sizing: border-box;
}
html,
body {
    margin: 0;
    height: 100%;
    background: var(--bg);
}
body {
    overflow: hidden;
    transition: background-color 0.3s ease;
}
#app {
    height: 100%;
}
main {
    position: relative;
    display: grid;
    grid-template-rows: auto minmax(0, 1fr);
    height: 100dvh;
    padding: var(--pad);
    gap: calc(var(--pad) * 0.6);
    overflow: hidden;
}
.app-header {
    position: relative;
    z-index: 2;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 1rem;
}
.wordmark {
    margin: 0;
    font-family: var(--font-display);
    font-size: clamp(1.4rem, 3.4vmin, 2.8rem);
    letter-spacing: 0.16em;
    line-height: 1;
}
.brace {
    color: var(--red);
}
.brand {
    display: flex;
    align-items: center;
    gap: clamp(0.8rem, 2.4vmin, 2rem);
    min-width: 0;
}
.header-mark {
    font-size: clamp(1.8rem, 4.8vmin, 3.8rem);
}
.brand-divider {
    width: 2px;
    height: clamp(2rem, 4.6vmin, 3.6rem);
    background: var(--line);
}
.brand .school-logo {
    height: clamp(2rem, 4.4vmin, 3.4rem);
}
/* Start screen: the school logo leads in the header, the club logo is the big hero mark. */
main:has(.is-setup) .header-mark,
main:has(.is-setup) .brand-divider {
    display: none;
}
main:has(.is-setup) .brand .school-logo {
    height: clamp(2.8rem, 6.5vmin, 5.2rem);
}
.header-actions {
    display: flex;
    align-items: center;
    gap: 0.6rem;
}
.icon-button {
    display: grid;
    place-items: center;
    width: clamp(3.2rem, 5.6vmin, 4.6rem);
    height: clamp(3.2rem, 5.6vmin, 4.6rem);
    min-height: 0;
    padding: 0;
    border: 1px solid var(--line);
    background: var(--surface-strong);
    color: var(--fg);
}
.icon-button svg {
    width: 45%;
    height: 45%;
    fill: none;
    stroke: currentColor;
    stroke-width: 2.2;
    stroke-linecap: round;
    stroke-linejoin: round;
}
.theme-switch {
    display: flex;
    gap: 0.25rem;
    padding: 0.3rem;
    border-radius: 999px;
    background: var(--surface-strong);
    border: 1px solid var(--line);
}
.theme-switch button {
    display: grid;
    place-items: center;
    width: clamp(2.75rem, 5vmin, 4rem);
    height: clamp(2.5rem, 4.4vmin, 3.4rem);
    min-height: 0;
    padding: 0;
    border: 0;
    border-radius: 999px;
    background: transparent;
    color: var(--muted);
}
.theme-switch button[aria-pressed='true'] {
    background: var(--toggle-on-bg);
    color: var(--toggle-on-fg);
}
.theme-switch svg {
    width: 45%;
    height: 45%;
    fill: none;
    stroke: currentColor;
    stroke-width: 2;
    stroke-linecap: round;
    stroke-linejoin: round;
}

/* Shared controls. */
button,
input,
select {
    font: inherit;
    color: inherit;
}
button {
    min-height: 2.75rem;
    padding: 0.55rem 1.1rem;
    border: 2px solid var(--field-line);
    border-radius: 999px;
    background: transparent;
    font-weight: 600;
    cursor: pointer;
    transition:
        transform 0.15s ease,
        background-color 0.2s ease,
        opacity 0.2s ease;
}
button:active:not(:disabled) {
    transform: scale(0.97);
}
button:disabled {
    opacity: 0.4;
    cursor: default;
}
button.primary {
    border-color: var(--red);
    background: var(--red);
    color: #fff;
    font-family: var(--font-display);
    font-weight: 400;
    letter-spacing: 0.14em;
    text-transform: uppercase;
}
:focus-visible {
    outline: 3px solid var(--red);
    outline-offset: 3px;
}
.label {
    margin: 0;
    font-family: var(--font-display);
    font-size: clamp(0.8rem, 1.5vmin, 1.25rem);
    letter-spacing: 0.16em;
    text-transform: uppercase;
    color: var(--muted);
}
.card {
    padding: clamp(1rem, 2.4vmin, 2rem);
    border: 1px solid var(--line);
    border-radius: var(--radius);
    background: var(--surface);
    min-width: 0;
}
/* Theme-dependent assets live here: scoped :global() rules would drop their descendant part. */
.school-logo {
    display: block;
    height: clamp(2.8rem, 6.5vmin, 5.2rem);
    width: auto;
}
.school-logo.on-dark,
:root[data-theme='dark'] .school-logo.on-light {
    display: none;
}
:root[data-theme='dark'] .school-logo.on-dark {
    display: block;
}
:root[data-theme='dark'] .reference .tile {
    background: var(--surface-strong);
}
:root[data-theme='dark'] .reference .sign {
    filter: invert(1);
    mix-blend-mode: screen;
}

/* Diagnostics mode is a developer view that needs to scroll. */
body:has(.with-diagnostics) {
    overflow: auto;
}
main:has(.with-diagnostics) {
    height: auto;
    min-height: 100dvh;
    overflow: visible;
}
@media (prefers-reduced-motion: reduce) {
    *,
    *::before,
    *::after {
        animation-duration: 0.01ms !important;
        transition-duration: 0.01ms !important;
    }
}
</style>
