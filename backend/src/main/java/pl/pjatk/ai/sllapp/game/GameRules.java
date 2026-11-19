package pl.pjatk.ai.sllapp.game;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import static pl.pjatk.ai.sllapp.game.WordCatalog.MIN_WORD_LENGTH;

public record GameRules(int maxWordLength, BigDecimal letterPoints, BigDecimal wordBonus, BigDecimal multiplierStep) {
    private static final GameRules DEFAULTS = new GameRules(7, BigDecimal.TEN, BigDecimal.valueOf(20), BigDecimal.ONE);

    public GameRules {
        WordCatalog.requireValidMaximum(maxWordLength);
        requireNonnegative(letterPoints, "letterPoints");
        requireNonnegative(wordBonus, "wordBonus");
        requireNonnegative(multiplierStep, "multiplierStep");
    }

    public static GameRules defaults() {
        return DEFAULTS;
    }

    int pointsForPosition(int wordLength, boolean completesWord) {
        var multiplier = BigDecimal.ONE.add(multiplierStep.multiply(BigDecimal.valueOf(wordLength - MIN_WORD_LENGTH)));
        int letterAward = scaledPoints(letterPoints, multiplier);
        if (completesWord) {
            return Math.addExact(letterAward, scaledPoints(wordBonus, multiplier));
        }
        return letterAward;
    }

    private static int scaledPoints(BigDecimal points, BigDecimal multiplier) {
        return points.multiply(multiplier).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    private static void requireNonnegative(BigDecimal value, String name) {
        if (Objects.requireNonNull(value, name).signum() < 0) {
            throw new IllegalArgumentException(name + " must be nonnegative.");
        }
    }
}
