import { afterEach, describe, expect, it, vi } from 'vitest';
import { apiFetch } from './client';
import { fetchRanking } from './fetch-ranking';

vi.mock('./client', () => ({ apiFetch: vi.fn() }));

describe('fetchRanking', () => {
    afterEach(() => {
        vi.mocked(apiFetch).mockReset();
    });

    it('requests the ranking without a query when no limit is given', async () => {
        vi.mocked(apiFetch).mockResolvedValue([]);

        await fetchRanking();

        expect(apiFetch).toHaveBeenCalledWith('/ranking');
    });

    it('sends the limit as a query parameter', async () => {
        const entries = [{ rank: 1, nickname: 'Ola', score: 10 }];
        vi.mocked(apiFetch).mockResolvedValue(entries);

        await expect(fetchRanking(5)).resolves.toEqual(entries);

        expect(apiFetch).toHaveBeenCalledWith('/ranking?limit=5');
    });

    it('sends a limit of 0 so that the backend can reject it', async () => {
        vi.mocked(apiFetch).mockResolvedValue([]);

        await fetchRanking(0);

        expect(apiFetch).toHaveBeenCalledWith('/ranking?limit=0');
    });
});
