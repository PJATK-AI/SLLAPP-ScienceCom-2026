/// <reference types="vite/client" />

interface ImportMetaEnv {
    /** Base URL of the backend REST API, for example `http://127.0.0.1:8080/api`. */
    readonly VITE_API_BASE_URL?: string;
}

interface ImportMeta {
    readonly env: ImportMetaEnv;
}
