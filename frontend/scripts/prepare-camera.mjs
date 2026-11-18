import { cp, mkdir, writeFile } from 'node:fs/promises';

const assets = new URL('../apps/web/public/mediapipe/', import.meta.url);
const wasm = new URL(
    '../packages/hand-landmarker/node_modules/@mediapipe/tasks-vision/wasm/',
    import.meta.url,
);
const model =
    'https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task';

const response = await fetch(model);
if (!response.ok) throw new Error(`Could not download hand landmarker: HTTP ${response.status}`);
const bytes = new Uint8Array(await response.arrayBuffer());
await mkdir(assets, { recursive: true });
await cp(wasm, new URL('wasm/', assets), { recursive: true });
await writeFile(new URL('hand_landmarker.task', assets), bytes);
