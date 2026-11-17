import { fetchRanking } from '@/api';
import type { RankingEntry } from '@/models';
import { mount } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { defineComponent, h } from 'vue';
import type { RankingContext } from './key';
import { provideRanking } from './provide-ranking';
import { useRanking } from './use-ranking';

vi.mock('@/api', () => ({ fetchRanking: vi.fn() }));

const OLA: RankingEntry = { rank: 1, nickname: 'Ola', score: 10 };
const JAN: RankingEntry = { rank: 1, nickname: 'Jan', score: 20 };

function setup(): { provided: RankingContext; injected: RankingContext } {
    let provided!: RankingContext;
    let injected!: RankingContext;
    const Child = defineComponent({
        setup() {
            injected = useRanking();
            return () => null;
        },
    });
    mount(
        defineComponent({
            setup() {
                provided = provideRanking();
                return () => h(Child);
            },
        }),
    );
    return { provided, injected };
}

function deferred<T>() {
    let resolve!: (value: T) => void;
    let reject!: (reason: unknown) => void;
    const promise = new Promise<T>((onResolve, onReject) => {
        resolve = onResolve;
        reject = onReject;
    });
    return { promise, resolve, reject };
}

describe('provideRanking', () => {
    afterEach(() => {
        vi.mocked(fetchRanking).mockReset();
    });

    it('provides the same context to descendants', () => {
        const { provided, injected } = setup();

        expect(injected).toBe(provided);
    });

    it('loads the ranking with the given limit', async () => {
        vi.mocked(fetchRanking).mockResolvedValue([OLA]);
        const { provided } = setup();

        await provided.loadRanking(5);

        expect(fetchRanking).toHaveBeenCalledWith(5);
        expect(provided.ranking.value).toEqual([OLA]);
        expect(provided.isLoading.value).toBe(false);
        expect(provided.error.value).toBeNull();
    });

    it('sets isLoading while the request is in progress', async () => {
        const request = deferred<RankingEntry[]>();
        vi.mocked(fetchRanking).mockReturnValue(request.promise);
        const { provided } = setup();

        const loading = provided.loadRanking();
        expect(provided.isLoading.value).toBe(true);

        request.resolve([]);
        await loading;
        expect(provided.isLoading.value).toBe(false);
    });

    it('stores the error message and keeps the previous ranking', async () => {
        vi.mocked(fetchRanking).mockResolvedValueOnce([OLA]);
        vi.mocked(fetchRanking).mockRejectedValueOnce(new Error('limit must be an integer.'));
        const { provided } = setup();

        await provided.loadRanking();
        await provided.loadRanking(0);

        expect(provided.error.value).toBe('limit must be an integer.');
        expect(provided.ranking.value).toEqual([OLA]);
        expect(provided.isLoading.value).toBe(false);
    });

    it('clears the previous error when a new load starts', async () => {
        vi.mocked(fetchRanking).mockRejectedValueOnce(new Error('Network error'));
        const { provided } = setup();
        await provided.loadRanking();

        const request = deferred<RankingEntry[]>();
        vi.mocked(fetchRanking).mockReturnValueOnce(request.promise);
        const loading = provided.loadRanking();
        expect(provided.error.value).toBeNull();

        request.resolve([OLA]);
        await loading;
        expect(provided.error.value).toBeNull();
        expect(provided.ranking.value).toEqual([OLA]);
    });

    it('ignores the response of an older request', async () => {
        const older = deferred<RankingEntry[]>();
        const newer = deferred<RankingEntry[]>();
        vi.mocked(fetchRanking)
            .mockReturnValueOnce(older.promise)
            .mockReturnValueOnce(newer.promise);
        const { provided } = setup();

        const first = provided.loadRanking();
        const second = provided.loadRanking();
        newer.resolve([JAN]);
        await second;
        older.resolve([OLA]);
        await first;

        expect(provided.ranking.value).toEqual([JAN]);
        expect(provided.isLoading.value).toBe(false);
    });

    it('ignores the error of an older request', async () => {
        const older = deferred<RankingEntry[]>();
        vi.mocked(fetchRanking).mockReturnValueOnce(older.promise).mockResolvedValueOnce([JAN]);
        const { provided } = setup();

        const first = provided.loadRanking();
        await provided.loadRanking();
        older.reject(new Error('Old failure'));
        await first;

        expect(provided.error.value).toBeNull();
        expect(provided.ranking.value).toEqual([JAN]);
    });
});

describe('useRanking', () => {
    it('throws outside of a provideRanking scope', () => {
        // Vue warns about the missing injection before useRanking throws.
        const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
        const Orphan = defineComponent({
            setup() {
                useRanking();
                return () => null;
            },
        });

        expect(() => mount(Orphan)).toThrow(
            'useRanking must be called inside of provideRanking scope',
        );
        warn.mockRestore();
    });
});
