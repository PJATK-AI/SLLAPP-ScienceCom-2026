package pl.pjatk.ai.sllapp.model;

import java.time.Duration;
import pl.pjatk.ai.sllapp.ai.PredictionResult;
import pl.pjatk.ai.sllapp.config.session.RecognitionProperties;

/** Recognition state for a session. Callers hold the game session monitor. */
public final class LetterRecognition {
    private boolean requiresRelease;
    private int consecutiveNoHand;
    private int streak;
    private long firstCountedSampleNanos;
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
        resetStreak();
        consecutiveNoHand = 0;
        // Invalidating a result keeps its slot occupied until the call ends.
        attemptValid = false;
    }

    public boolean noHand(int releaseNoHandCount) {
        resetStreak();
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
            resetStreak();
            return false;
        }
        long sampleNanos = attempt.sampleNanos();
        if (streak > 0 && Duration.ofNanos(sampleNanos - lastCountedSampleNanos).compareTo(rules.maxSampleGap()) > 0) {
            resetStreak();
        }
        if (streak == 0) firstCountedSampleNanos = sampleNanos;
        lastCountedSampleNanos = sampleNanos;
        if (streak < rules.requiredPredictions()) streak++;
        return streak >= rules.requiredPredictions()
                && Duration.ofNanos(sampleNanos - firstCountedSampleNanos).compareTo(rules.minHoldDuration()) >= 0;
    }

    private void resetStreak() {
        streak = 0;
        firstCountedSampleNanos = 0;
        lastCountedSampleNanos = 0;
    }

    public record Attempt(long sampleNanos) {}
}
