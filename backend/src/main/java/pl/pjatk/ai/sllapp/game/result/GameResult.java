package pl.pjatk.ai.sllapp.game.result;

import java.time.Instant;

public record GameResult(String sessionId, String nickname, int score, Instant finishedAt) {
}
