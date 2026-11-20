package pl.pjatk.ai.sllapp.model;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import pl.pjatk.ai.sllapp.game.GameEngine;
import pl.pjatk.ai.sllapp.game.GameRules;
import pl.pjatk.ai.sllapp.game.GameState;
import pl.pjatk.ai.sllapp.game.WordPicker;

/** A player session. Callers hold its monitor for lifecycle and game operations. */
public final class GameSession {
    private final UUID sessionId;
    private final String nickname;
    private final SelectedHand selectedHand;
    private final Instant createdAt;
    private final LetterRecognition recognition = new LetterRecognition();
    private Instant expiresAt;
    private Instant deadline;
    private GameEngine engine;
    private GameResult result;
    private boolean resultSaved;

    public GameSession(UUID sessionId, String nickname, SelectedHand selectedHand,
                       Instant createdAt, Instant expiresAt) {
        this.sessionId = sessionId;
        this.nickname = nickname;
        this.selectedHand = selectedHand;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public UUID sessionId() { return sessionId; }
    public String nickname() { return nickname; }
    public SelectedHand selectedHand() { return selectedHand; }
    public Instant createdAt() { return createdAt; }
    public Instant expiresAt() { return expiresAt; }
    public LetterRecognition recognition() { return recognition; }

    public boolean start(Instant now, Duration duration, Duration finishedTtl, Supplier<WordPicker> pickers) {
        if (deadline != null || !now.isBefore(expiresAt)) return false;
        var deadline = now.plus(duration);
        var expiry = deadline.plus(finishedTtl);
        engine = new GameEngine(GameRules.defaults(), pickers.get(), now, deadline);
        this.deadline = deadline;
        expiresAt = expiry;
        return true;
    }

    public GameState state(Instant now) {
        return engine == null ? null : engine.state(now);
    }

    public boolean completePosition(long positionId, Instant now) {
        if (engine == null || !engine.completePosition(positionId, now)) return false;
        recognition.positionChanged(engine.state(now).repeatsPreviousLetter());
        return true;
    }

    public boolean finishIfDue(Instant now) {
        if (engine == null || now.isBefore(deadline)) return false;
        result = new GameResult(sessionId.toString(), nickname, engine.state(now).score(), deadline);
        engine = null;
        recognition.resetInput();
        return true;
    }

    public GameResult result() { return result; }

    public boolean hasPendingResult() { return result != null && !resultSaved; }

    public void markResultSaved() { resultSaved = true; }
}
