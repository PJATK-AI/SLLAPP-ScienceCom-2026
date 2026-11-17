package pl.pjatk.ai.sllapp.model;

import java.time.Instant;

public record GameResult(String sessionId, String nickname, int score, Instant finishedAt) {
}
