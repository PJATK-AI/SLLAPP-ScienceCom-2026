import { fetchRanking } from '@/api';
import { provide, ref } from 'vue';
import { RankingKey, type RankingContext } from './key';
import type { RankingRrecord } from '@/models';

export function provideRanking() {
    const ranking = ref<RankingRrecord[]>([]);
    const isLoading = ref(false);
    const error = ref(null);

    const loadRanking = async (limit?: number) => {
        isLoading.value = true;

        try {
            ranking.value = await fetchRanking(limit);
        } catch (e: any) {
            error.value = e?.message || 'Failed to fetch ranking';
        } finally {
            isLoading.value = false;
        }
    };

    const context: RankingContext = {
        ranking,
        isLoading,
        error,
        loadRanking,
    };
    provide(RankingKey, context);

    return context;
}
