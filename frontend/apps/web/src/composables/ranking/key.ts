import type { RankingEntry } from '@/models';
import type { InjectionKey, Ref } from 'vue';

export interface RankingContext {
    ranking: Ref<RankingEntry[]>;
    isLoading: Ref<boolean>;
    error: Ref<string | null>;
    loadRanking: (limit?: number) => Promise<void>;
}

export const RankingKey: InjectionKey<RankingContext> = Symbol('ranking');
