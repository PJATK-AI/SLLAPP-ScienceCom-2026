package pl.pjatk.ai.sllapp.model;

import java.time.Duration;
import pl.pjatk.ai.sllapp.ai.PredictionResult;
import pl.pjatk.ai.sllapp.config.session.RecognitionProperties;

/** Recognition state for a session. Callers hold the game session monitor. */
public final class LetterRecognition {
    private boolean requiresRelease;
    private int consecutiveNoHand;
    private int streak;
    private long lastCountedSampleNanos;
    private Attempt inFlight;
    private boolean attemptValid;

    public boolean requiresRelease() {
        return requiresRelease;
    }

    public void positionChanged(boolean repeatsPreviousLetter) {
        resetInput();
        requiresRelease = repeatsPreviousLetter;
    }

    public void resetInput() {
        streak = 0;
        consecutiveNoHand = 0;
        // Invalidating a result keeps its slot occupied until the call ends.
        attemptValid = false;
    }

    public boolean noHand(int releaseNoHandCount) {
        streak = 0;
        attemptValid = false;
        if (!requiresRelease || ++consecutiveNoHand < releaseNoHandCount) return false;
        requiresRelease = false;
        consecutiveNoHand = 0;
        return true;
    }

    public Attempt selectedHand(long sampleNanos) {
        consecutiveNoHand = 0;
        if (requiresRelease || inFlight != null) return null;
        inFlight = new Attempt(sampleNanos);
        attemptValid = true;
        return inFlight;
    }

    public boolean finishAttempt(Attempt attempt) {
        if (inFlight != attempt) return false;
        inFlight = null;
        boolean valid = attemptValid;
        attemptValid = false;
        return valid;
    }

    public boolean countPrediction(Attempt attempt, PredictionResult result, char expectedLetter,
                                   RecognitionProperties rules) {
        if (!(result instanceof PredictionResult.Prediction prediction) || prediction.letter() != expectedLetter
                || !Double.isFinite(prediction.confidence()) || prediction.confidence() < rules.minConfidence()) {
            streak = 0;
            return false;
        }
        long sampleNanos = attempt.sampleNanos();
        if (streak > 0 && Duration.ofNanos(sampleNanos - lastCountedSampleNanos).compareTo(rules.maxSampleGap()) > 0) {
            streak = 0;
        }
        lastCountedSampleNanos = sampleNanos;
        return ++streak >= rules.requiredPredictions();
    }

    public record Attempt(long sampleNanos) {}
}
