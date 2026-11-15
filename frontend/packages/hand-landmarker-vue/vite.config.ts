import { fileURLToPath, URL } from 'node:url';
import { defineConfig } from 'vitest/config';

export default defineConfig({
    build: {
        lib: {
            entry: fileURLToPath(new URL('./src/index.ts', import.meta.url)),
            formats: ['es'],
            fileName: 'index',
        },
        rollupOptions: {
            external: ['vue', '@sllapp/hand-landmarker'],
        },
    },
    test: {
        environment: 'jsdom',
    },
});
