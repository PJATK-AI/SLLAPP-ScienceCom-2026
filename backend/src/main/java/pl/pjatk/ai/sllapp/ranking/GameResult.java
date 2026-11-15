package pl.pjatk.ai.sllapp.ranking;

import java.time.Instant;

public record GameResult(String sessionId, String nickname, int score, Instant finishedAt) {
}
