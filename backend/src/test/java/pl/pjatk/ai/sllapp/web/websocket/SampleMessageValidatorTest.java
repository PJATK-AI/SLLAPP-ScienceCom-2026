package pl.pjatk.ai.sllapp.web.websocket;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class SampleMessageValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SampleMessageValidator validator = new SampleMessageValidator();

    @Test
    void retainsAdditionalHandFieldsAndAcceptsBothHands() {
        var input = mapper.readTree(SampleFixtures.hand(0));
        var sample = validator.validate(input);
        assertThat(sample.hand()).isEqualTo(input.path("hand"));
        assertThat(sample.hand()).isNotSameAs(input.path("hand"));
        assertThat(sample.hand().path("extra").path("source").asString()).isEqualTo("camera");
        ((ObjectNode) input.path("hand")).put("handedness", "RIGHT");
        assertThat(validator.validate(input).hand().path("handedness").asString()).isEqualTo("RIGHT");
        assertThat(validator.validate(mapper.readTree(SampleFixtures.noHand(0))).hand()).isNull();
        assertThat(validator.validate(mapper.readTree(SampleFixtures.noHand(9_007_199_254_740_991L))).seq())
                .isEqualTo(9_007_199_254_740_991L);
    }

    @Test
    void rejectsInvalidSequenceAndPosition() {
        for (String seq : new String[]{"-1", "1.5", "0.0", "\"0\"", "null", "9007199254740992", "18446744073709551616"}) {
            var input = mapper.readTree("{\"type\":\"no_hand\",\"seq\":" + seq + ",\"positionId\":null}");
            assertThatIllegalArgumentException().as("seq=%s", seq).isThrownBy(() -> validator.validate(input));
        }
        for (String position : new String[]{"0", "\"0\"", "{}", "false"}) {
            var input = mapper.readTree("{\"type\":\"no_hand\",\"seq\":0,\"positionId\":" + position + "}");
            assertThatIllegalArgumentException().isThrownBy(() -> validator.validate(input));
        }
        for (String field : new String[]{"seq", "positionId", "type"}) {
            var input = (ObjectNode) mapper.readTree(SampleFixtures.noHand(0));
            input.remove(field);
            assertThatIllegalArgumentException().isThrownBy(() -> validator.validate(input));
        }
    }

    @Test
    void rejectsBadHandednessAndLandmarkCounts() {
        for (String field : new String[]{"handedness", "landmarks"}) {
            var input = mapper.readTree(SampleFixtures.hand(0));
            ((ObjectNode) input.path("hand")).remove(field);
            assertThatIllegalArgumentException().isThrownBy(() -> validator.validate(input));
        }
        for (String handedness : new String[]{"Left", "", "unknown"}) {
            var input = mapper.readTree(SampleFixtures.hand(0));
            ((ObjectNode) input.path("hand")).put("handedness", handedness);
            assertThatIllegalArgumentException().isThrownBy(() -> validator.validate(input));
        }
        for (String handedness : new String[]{"{}", "[\"LEFT\"]", "1", "true", "null"}) {
            var input = mapper.readTree(SampleFixtures.hand(0));
            ((ObjectNode) input.path("hand")).set("handedness", mapper.readTree(handedness));
            assertThatIllegalArgumentException().as("handedness=%s", handedness)
                    .isThrownBy(() -> validator.validate(input));
        }
        for (String hand : new String[]{"null", "5", "\"LEFT\"", "[]"}) {
            var input = (ObjectNode) mapper.readTree(SampleFixtures.hand(0));
            input.set("hand", mapper.readTree(hand));
            assertThatIllegalArgumentException().as("hand=%s", hand).isThrownBy(() -> validator.validate(input));
        }
        for (int count : new int[]{20, 22}) {
            var input = mapper.readTree(SampleFixtures.hand(0));
            var points = (ArrayNode) input.path("hand").path("landmarks");
            if (count == 20) points.remove(20);
            else points.add(points.get(0).deepCopy());
            assertThatIllegalArgumentException().isThrownBy(() -> validator.validate(input));
        }
    }

    @Test
    void checksEveryCoordinateAndRejectsNonFiniteNumbers() {
        for (int index : new int[]{0, 10, 20}) {
            for (String axis : new String[]{"x", "y", "z"}) {
                for (String value : new String[]{"null", "\"0.5\"", "true", "1e999"}) {
                    var input = mapper.readTree(SampleFixtures.hand(0));
                    ((ObjectNode) input.path("hand").path("landmarks").get(index)).set(axis, mapper.readTree(value));
                    assertThatIllegalArgumentException().as("point %s %s=%s", index, axis, value)
                            .isThrownBy(() -> validator.validate(input));
                }
                var input = mapper.readTree(SampleFixtures.hand(0));
                ((ObjectNode) input.path("hand").path("landmarks").get(index)).remove(axis);
                assertThatIllegalArgumentException().isThrownBy(() -> validator.validate(input));
            }
        }
    }
}
