package pl.pjatk.ai.sllapp.game.session;

import java.time.Instant;
import java.util.UUID;

public record GameSession(UUID sessionId, String nickname, SelectedHand selectedHand,
                          Instant createdAt, Instant expiresAt) {}
