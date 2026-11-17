package pl.pjatk.ai.sllapp.web.websocket.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;
import pl.pjatk.ai.sllapp.model.GameSession;
import pl.pjatk.ai.sllapp.model.SelectedHand;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record GameStateMessage(String type, UUID sessionId, String status, String nickname,
                        SelectedHand selectedHand, Long positionId) {
    public static GameStateMessage from(GameSession session) {
        return new GameStateMessage("game_state", session.sessionId(), "CREATED", session.nickname(),
                session.selectedHand(), null);
    }
}
