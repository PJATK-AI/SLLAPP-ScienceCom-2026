import type { InjectionKey, Ref } from 'vue';

export interface RankingContext {
    ranking: Ref<any>;
    isLoading: Ref<boolean>;
    error: Ref<string | null>;
    loadRanking: () => Promise<void>;
}

export const RankingKey: InjectionKey<RankingContext> = Symbol('raking');
