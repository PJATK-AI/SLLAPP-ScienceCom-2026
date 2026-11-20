package pl.pjatk.ai.sllapp.game;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class GameRulesTest {
    @Test
    void exposesTheDefaultSettings() {
        assertThat(GameRules.defaults())
                .isEqualTo(new GameRules(7, BigDecimal.TEN, BigDecimal.valueOf(20), BigDecimal.ONE));
    }

    @ParameterizedTest
    @CsvSource({"3, 10, 20, 50", "4, 20, 40, 120", "5, 30, 60, 210", "6, 40, 80, 320", "7, 50, 100, 450"})
    void calculatesLetterAwardsAndWordTotals(int length, int letter, int bonus, int total) {
        var rules = new GameRules(7, BigDecimal.TEN, BigDecimal.valueOf(20), BigDecimal.ONE);
        assertThat(rules.pointsForPosition(length, false)).isEqualTo(letter);
        assertThat(rules.pointsForPosition(length, true)).isEqualTo(letter + bonus);
        assertThat((length - 1) * letter + rules.pointsForPosition(length, true)).isEqualTo(total);
    }

    @Test
    void roundsEachAwardHalfUpBeforeAddingThem() {
        var rules = new GameRules(4, BigDecimal.TWO, BigDecimal.TWO, new BigDecimal("0.25"));
        assertThat(rules.pointsForPosition(3, true)).isEqualTo(4);
        assertThat(rules.pointsForPosition(4, false)).isEqualTo(3);
        assertThat(rules.pointsForPosition(4, true)).isEqualTo(6);
    }

    @Test
    void supportsZeroPointsAndMultiplierStep() {
        var rules = new GameRules(7, BigDecimal.ZERO, BigDecimal.TWO, BigDecimal.ZERO);
        assertThat(rules.pointsForPosition(7, false)).isZero();
        assertThat(rules.pointsForPosition(7, true)).isEqualTo(2);
    }

    @Test
    void rejectsInvalidSettings() {
        var negative = BigDecimal.ONE.negate();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new GameRules(2, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE))
                .withMessageContaining("Maximum word length");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new GameRules(3, negative, BigDecimal.ONE, BigDecimal.ONE))
                .withMessageContaining("letterPoints");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new GameRules(3, BigDecimal.ONE, negative, BigDecimal.ONE))
                .withMessageContaining("wordBonus");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new GameRules(3, BigDecimal.ONE, BigDecimal.ONE, negative))
                .withMessageContaining("multiplierStep");
        assertThatNullPointerException().isThrownBy(() -> new GameRules(3, null, BigDecimal.ONE, BigDecimal.ONE));
    }

    @Test
    void checksBothAwardConversionAndAdditionForOverflow() {
        var max = BigDecimal.valueOf(Integer.MAX_VALUE);
        var tooLarge = new GameRules(4, max, BigDecimal.ZERO, BigDecimal.ONE);
        assertThatExceptionOfType(ArithmeticException.class).isThrownBy(() -> tooLarge.pointsForPosition(4, false));
        var sumTooLarge = new GameRules(3, max, BigDecimal.ONE, BigDecimal.ZERO);
        assertThatExceptionOfType(ArithmeticException.class).isThrownBy(() -> sumTooLarge.pointsForPosition(3, true));
    }
}
