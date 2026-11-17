package pl.pjatk.ai.sllapp.game.session.websocket;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;
import pl.pjatk.ai.sllapp.game.session.GameSession;
import pl.pjatk.ai.sllapp.game.session.SelectedHand;

@JsonInclude(JsonInclude.Include.ALWAYS)
record GameStateMessage(String type, UUID sessionId, String status, String nickname,
                        SelectedHand selectedHand, Long positionId) {
    static GameStateMessage from(GameSession session) {
        return new GameStateMessage("game_state", session.sessionId(), "CREATED", session.nickname(),
                session.selectedHand(), null);
    }
}
