import type { RankingRrecord } from '@/models';
import { apiFetch } from './client';
import { isError, RequestError, type IReqiestError } from './error';

export async function fetchRanking(limit?: number) {
    const query = [];
    if (limit) {
        query.push(`limit=${limit}`);
    }
    const res = await apiFetch<RankingRrecord[] | IReqiestError>(`/ranking?${query.join('&')}`);

    if (isError(res)) {
        throw new RequestError(res);
    }

    return res;
}
