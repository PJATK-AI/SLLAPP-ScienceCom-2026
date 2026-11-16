package pl.pjatk.ai.sllapp.game.session;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class NicknamePolicyTest {
    private final NicknamePolicy policy = new NicknamePolicy(new SessionProperties(Duration.ofMinutes(5), List.of("bad")));

    @Test
    void trimsAndCountsUnicodeCodePoints() {
        assertThat(policy.validate("\u2003Łucja\u2003")).isEqualTo("Łucja");
        assertThat(policy.validate("😀".repeat(20))).isEqualTo("😀".repeat(20));
        assertThat(policy.validate("A")).isEqualTo("A");
        for (String invalid : new String[]{null, "", "  ", "a".repeat(21), "😀".repeat(21), "a\nb", "a\u200Bb", "\uD800"}) {
            assertThatIllegalArgumentException().isThrownBy(() -> policy.validate(invalid));
        }
    }

    @Test
    void matchesNormalizedWordsWithoutBlockingSubstrings() {
        for (String invalid : new String[]{"BAD", "bAd!", "ｂａｄ", "the bad name", "bad123"}) {
            assertThatIllegalArgumentException().isThrownBy(() -> policy.validate(invalid));
        }
        assertThat(policy.validate("Badminton")).isEqualTo("Badminton");
    }
}
