package pl.pjatk.ai.sllapp.config.session;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("sllapp.game.scoring")
public record ScoringProperties(BigDecimal letterPoints, BigDecimal wordBonus, BigDecimal multiplierStep) {
    public ScoringProperties {
        requireNonnegative(letterPoints, "letter-points");
        requireNonnegative(wordBonus, "word-bonus");
        requireNonnegative(multiplierStep, "multiplier-step");
    }

    private static void requireNonnegative(BigDecimal value, String property) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException("sllapp.game.scoring." + property + " must be nonnegative.");
        }
    }
}
