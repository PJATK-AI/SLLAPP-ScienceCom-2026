import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiFetch } from './client';
import { RequestError } from './error';

const fetchMock = vi.fn<typeof fetch>();

function jsonResponse(body: unknown, status = 200, contentType = 'application/json'): Response {
    return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': contentType } });
}

describe('apiFetch', () => {
    beforeEach(() => {
        vi.stubEnv('VITE_API_BASE_URL', 'http://127.0.0.1:8080/api/');
        vi.stubGlobal('fetch', fetchMock);
    });

    afterEach(() => {
        fetchMock.mockReset();
        vi.unstubAllEnvs();
        vi.unstubAllGlobals();
    });

    it('joins the base URL and endpoint and returns the JSON body', async () => {
        fetchMock.mockResolvedValue(jsonResponse([{ rank: 1 }]));

        await expect(apiFetch('/ranking')).resolves.toEqual([{ rank: 1 }]);

        const [url] = fetchMock.mock.calls[0]!;
        expect(url).toBe('http://127.0.0.1:8080/api/ranking');
    });

    it('does not send Content-Type for a request without a body', async () => {
        fetchMock.mockResolvedValue(jsonResponse([]));

        await apiFetch('/ranking');

        const [, init] = fetchMock.mock.calls[0]!;
        expect(new Headers(init?.headers).has('Content-Type')).toBe(false);
    });

    it('sends JSON Content-Type for a request with a body', async () => {
        fetchMock.mockResolvedValue(jsonResponse({}));

        await apiFetch('/items', { method: 'POST', body: '{}' });

        const [, init] = fetchMock.mock.calls[0]!;
        expect(new Headers(init?.headers).get('Content-Type')).toBe('application/json');
    });

    it('throws a RequestError with the ProblemDetail of an error response', async () => {
        fetchMock.mockResolvedValue(
            jsonResponse(
                {
                    type: 'about:blank',
                    title: 'Bad Request',
                    status: 400,
                    detail: 'limit must be an integer between 1 and 100.',
                    instance: '/api/ranking',
                },
                400,
                'application/problem+json',
            ),
        );

        const error = await apiFetch('/ranking?limit=0').catch((cause: unknown) => cause);

        expect(error).toBeInstanceOf(RequestError);
        expect(error).toMatchObject({
            status: 400,
            message: 'limit must be an integer between 1 and 100.',
            problem: { title: 'Bad Request', instance: '/api/ranking' },
        });
    });

    it('throws a RequestError with the HTTP status for a non-JSON error response', async () => {
        fetchMock.mockResolvedValue(new Response('Internal Server Error', { status: 500 }));

        const error = await apiFetch('/ranking').catch((cause: unknown) => cause);

        expect(error).toBeInstanceOf(RequestError);
        expect(error).toMatchObject({ status: 500, message: 'HTTP Error: 500', problem: null });
    });

    it('throws a configuration error when VITE_API_BASE_URL is not set', async () => {
        vi.stubEnv('VITE_API_BASE_URL', '');

        await expect(apiFetch('/ranking')).rejects.toThrow('VITE_API_BASE_URL is not set');
        expect(fetchMock).not.toHaveBeenCalled();
    });
});
