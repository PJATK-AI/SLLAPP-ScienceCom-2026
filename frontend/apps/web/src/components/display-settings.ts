export type Theme = 'light' | 'dark';

export function readSetting<T extends string>(key: string, allowed: readonly T[], fallback: T): T {
    try {
        const value = localStorage.getItem(key);
        return allowed.includes(value as T) ? (value as T) : fallback;
    } catch {
        return fallback;
    }
}

export function writeSetting(key: string, value: string) {
    try {
        localStorage.setItem(key, value);
    } catch {
        // Storage can be unavailable (private mode); the setting then lasts for this visit only.
    }
}
