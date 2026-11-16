import { inject } from 'vue';
import { RankingKey } from './inject';

export function useRanking() {
    const context = inject(RankingKey);

    if (!context) {
        throw new Error('useRanking must be called inside of provideRanking scope');
    }

    return context;
}
