package pl.pjatk.ai.sllapp.web.rest.dto;

import pl.pjatk.ai.sllapp.model.RankingEntry;

public record RankingEntryResponse(int rank, String nickname, int score) {
    public static RankingEntryResponse from(RankingEntry entry) {
        return new RankingEntryResponse(entry.rank(), entry.nickname(), entry.score());
    }
}
