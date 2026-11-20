package pl.pjatk.ai.sllapp.web.websocket;

import java.math.BigInteger;
import org.springframework.stereotype.Component;
import pl.pjatk.ai.sllapp.web.websocket.message.SampleMessage;
import tools.jackson.databind.JsonNode;

@Component
class SampleMessageValidator {
    private static final BigInteger MAX_SEQ = BigInteger.valueOf(9_007_199_254_740_991L);

    SampleMessage validate(JsonNode input, Long activePositionId) {
        String type = input.path("type").asString();
        if (!"hand_sample".equals(type) && !"no_hand".equals(type)) {
            throw new IllegalArgumentException("Expected hand_sample or no_hand.");
        }
        var seq = input.path("seq");
        if (!seq.isIntegralNumber() || seq.bigIntegerValue().signum() < 0
                || seq.bigIntegerValue().compareTo(MAX_SEQ) > 0) {
            throw new IllegalArgumentException("seq must be an integer between 0 and 9007199254740991.");
        }
        validatePosition(input.path("positionId"), activePositionId);
        if ("no_hand".equals(type)) return new SampleMessage(type, seq.longValue(), activePositionId, null);

        var hand = input.path("hand");
        var handedness = hand.path("handedness");
        if (!hand.isObject() || !handedness.isString()
                || !("LEFT".equals(handedness.asString()) || "RIGHT".equals(handedness.asString()))) {
            throw new IllegalArgumentException("hand.handedness must be LEFT or RIGHT.");
        }
        var points = hand.path("landmarks");
        if (!points.isArray() || points.size() != 21) {
            throw new IllegalArgumentException("hand.landmarks must contain 21 points.");
        }
        for (var point : points) {
            for (String axis : new String[]{"x", "y", "z"}) {
                var coordinate = point.path(axis);
                if (!coordinate.isNumber() || !Double.isFinite(coordinate.doubleValue())) {
                    throw new IllegalArgumentException("Each landmark must contain finite numeric x, y and z.");
                }
            }
        }
        return new SampleMessage(type, seq.longValue(), activePositionId, hand.deepCopy());
    }

    private void validatePosition(JsonNode position, Long activePositionId) {
        if (activePositionId == null) {
            if (!position.isNull()) {
                throw new IllegalArgumentException("positionId must be null in CREATED state.");
            }
        } else if (!position.isIntegralNumber() || !position.bigIntegerValue().equals(BigInteger.valueOf(activePositionId))) {
            throw new IllegalArgumentException("positionId must match the active position in RUNNING state.");
        }
    }
}
