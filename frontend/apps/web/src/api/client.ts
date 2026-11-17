export const BASE_URL = import.meta.env.VITE_API_BASE_URL;

export async function apiFetch<T>(endpoint: string, options: RequestInit = {}): Promise<T> {
    const url = buildUrl(BASE_URL, endpoint);
    const { headers, ...restOptions } = options;

    const response = await fetch(url, {
        ...restOptions,
        headers: {
            'Content-Type': 'application/json',
            ...headers,
        },
    });

    if (!response.ok) {
        throw new Error(`HTTP Error: ${response.status}`);
    }

    return response.json() as Promise<T>;
}

function buildUrl(baseUrl: string, endpoint: string): string {
    const cleanBase = baseUrl.replace(/\/+$/, '');
    const cleanEndpoint = endpoint.replace(/^\/+/, '');

    return `${cleanBase}/${cleanEndpoint}`;
}
