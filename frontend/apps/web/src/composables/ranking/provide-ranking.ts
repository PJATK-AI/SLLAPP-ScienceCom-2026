import { fetchRanking } from '@/api';
import type { RankingEntry } from '@/models';
import { provide, ref } from 'vue';
import { RankingKey, type RankingContext } from './key';

export function provideRanking(): RankingContext {
    const ranking = ref<RankingEntry[]>([]);
    const isLoading = ref(false);
    const error = ref<string | null>(null);
    // Only the latest request may change the state, so an older response cannot overwrite a newer one.
    let latestRequest = 0;

    async function loadRanking(limit?: number): Promise<void> {
        const request = ++latestRequest;
        isLoading.value = true;
        error.value = null;

        try {
            const entries = await fetchRanking(limit);
            if (request === latestRequest) {
                ranking.value = entries;
            }
        } catch (cause) {
            if (request === latestRequest) {
                error.value = cause instanceof Error ? cause.message : 'Failed to fetch ranking';
            }
        } finally {
            if (request === latestRequest) {
                isLoading.value = false;
            }
        }
    }

    const context: RankingContext = {
        ranking,
        isLoading,
        error,
        loadRanking,
    };
    provide(RankingKey, context);

    return context;
}
