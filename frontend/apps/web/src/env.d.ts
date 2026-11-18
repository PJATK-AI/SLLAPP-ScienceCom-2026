/// <reference types="vite/client" />

interface ImportMetaEnv {
    readonly VITE_WS_URL?: string;
    readonly VITE_HAND_SAMPLE_HZ?: string;
    readonly VITE_HAND_LANDMARKER_MODEL_URL?: string;
    readonly VITE_HAND_LANDMARKER_WASM_PATH?: string;
    /** Base URL of the backend REST API, for example `http://127.0.0.1:8080/api`. */
    readonly VITE_API_BASE_URL?: string;
}

interface ImportMeta {
    readonly env: ImportMetaEnv;
}
