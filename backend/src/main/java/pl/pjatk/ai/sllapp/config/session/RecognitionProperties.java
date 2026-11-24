package pl.pjatk.ai.sllapp.config.session;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("sllapp.game.recognition")
public record RecognitionProperties(Double minConfidence, int requiredPredictions, Duration maxSampleGap,
                                    int releaseNoHandCount, Duration minHoldDuration) {
    public RecognitionProperties {
        if (minConfidence == null || !Double.isFinite(minConfidence) || minConfidence < 0 || minConfidence > 1) {
            throw new IllegalArgumentException("sllapp.game.recognition.min-confidence must be finite and between 0 and 1.");
        }
        if (requiredPredictions < 1) {
            throw new IllegalArgumentException("sllapp.game.recognition.required-predictions must be at least 1.");
        }
        if (releaseNoHandCount < 1) {
            throw new IllegalArgumentException("sllapp.game.recognition.release-no-hand-count must be at least 1.");
        }
        if (maxSampleGap == null || maxSampleGap.isZero() || maxSampleGap.isNegative()) {
            throw new IllegalArgumentException("sllapp.game.recognition.max-sample-gap must be positive.");
        }
        if (minHoldDuration == null || minHoldDuration.isNegative()) {
            throw new IllegalArgumentException("sllapp.game.recognition.min-hold-duration must be nonnegative.");
        }
    }
}
