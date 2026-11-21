package pl.pjatk.ai.sllapp.web.websocket.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;
import pl.pjatk.ai.sllapp.model.GameSession;
import pl.pjatk.ai.sllapp.model.SelectedHand;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record GameStateMessage(String type, UUID sessionId, String status, String nickname,
                               SelectedHand selectedHand, @JsonInclude(JsonInclude.Include.ALWAYS) Long positionId,
                               String word, Integer letterIndex, String expectedLetter, Long remainingMs,
                               Integer score, Boolean requiresRelease) {
    public static GameStateMessage from(GameSession session, Instant now) {
        var result = session.result();
        if (result != null) {
            return new GameStateMessage("game_state", session.sessionId(), "FINISHED", session.nickname(),
                    session.selectedHand(), null, null, null, null, 0L, result.score(), null);
        }
        var state = session.state(now);
        if (state == null) {
            return new GameStateMessage("game_state", session.sessionId(), "CREATED", session.nickname(),
                    session.selectedHand(), null, null, null, null, null, null, null);
        }
        return new GameStateMessage("game_state", session.sessionId(), "RUNNING", session.nickname(),
                session.selectedHand(), state.positionId(), state.word(), state.letterIndex(),
                String.valueOf(state.expectedLetter()), state.remainingMs(), state.score(),
                session.recognition().requiresRelease());
    }
}
