package pl.pjatk.ai.sllapp.config.session;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("sllapp.game.recognition")
public record RecognitionProperties(@DefaultValue("0.80") double minConfidence,
                                    @DefaultValue("3") int requiredPredictions,
                                    @DefaultValue("350ms") Duration maxSampleGap,
                                    @DefaultValue("2") int releaseNoHandCount) {
    public RecognitionProperties {
        if (!Double.isFinite(minConfidence) || minConfidence < 0 || minConfidence > 1) {
            throw new IllegalArgumentException("Recognition confidence must be between 0 and 1.");
        }
        if (requiredPredictions < 1 || releaseNoHandCount < 1) {
            throw new IllegalArgumentException("Recognition event counts must be positive.");
        }
        if (maxSampleGap == null || maxSampleGap.isZero() || maxSampleGap.isNegative()) {
            throw new IllegalArgumentException("Recognition sample gap must be positive.");
        }
    }
}
