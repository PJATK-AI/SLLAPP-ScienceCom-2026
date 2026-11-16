import { fetchRanking } from '@/api';
import { provide, ref } from 'vue';
import { RankingKey, type RankingContext } from './inject';
import type { Ranking } from '@/models';

export function provideRanking(limit: number = 10) {
    const ranking = ref<null | Ranking>();
    const isLoading = ref(false);
    const error = ref(null);

    const loadRanking = async () => {
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
