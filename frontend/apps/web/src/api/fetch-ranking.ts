import type { RankingEntry } from '@/models';
import { apiFetch } from './client';

/** Reads the ranking. Without `limit`, the backend returns its default number of entries. */
export function fetchRanking(limit?: number): Promise<RankingEntry[]> {
    const query = limit === undefined ? '' : `?${new URLSearchParams({ limit: String(limit) })}`;
    return apiFetch<RankingEntry[]>(`/ranking${query}`);
}
