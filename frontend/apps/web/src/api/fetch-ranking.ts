import type { Ranking } from '@/models';
import { apiFetch } from './client';

export async function fetchRanking(limit: number) {
    return apiFetch<Ranking>(`/ranking?limit=${limit}`);
}
