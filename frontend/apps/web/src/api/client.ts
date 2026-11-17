import { isProblemDetail, RequestError, type ProblemDetail } from './error';

/**
 * Sends a request to the backend API and returns the parsed JSON body.
 * Throws a `RequestError` for a response with an error status.
 */
export async function apiFetch<T>(endpoint: string, options: RequestInit = {}): Promise<T> {
    const url = buildUrl(getBaseUrl(), endpoint);
    const headers = new Headers(options.headers);
    if (options.body != null && !headers.has('Content-Type')) {
        headers.set('Content-Type', 'application/json');
    }

    const response = await fetch(url, { ...options, headers });

    if (!response.ok) {
        throw new RequestError(response.status, await readProblem(response));
    }

    return (await response.json()) as T;
}

function getBaseUrl(): string {
    const baseUrl = import.meta.env.VITE_API_BASE_URL;
    if (!baseUrl) {
        throw new Error('VITE_API_BASE_URL is not set. See frontend/.env.example.');
    }
    return baseUrl;
}

async function readProblem(response: Response): Promise<ProblemDetail | null> {
    try {
        const body: unknown = await response.json();
        return isProblemDetail(body) ? body : null;
    } catch {
        return null;
    }
}

function buildUrl(baseUrl: string, endpoint: string): string {
    const cleanBase = baseUrl.replace(/\/+$/, '');
    const cleanEndpoint = endpoint.replace(/^\/+/, '');

    return `${cleanBase}/${cleanEndpoint}`;
}
