package pl.pjatk.ai.sllapp.game;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import static pl.pjatk.ai.sllapp.game.WordCatalog.MIN_WORD_LENGTH;

/** Owns one game. The caller serializes access and supplies the time for each operation. */
public final class GameEngine {
    private static final long MAX_POSITION_ID = 9_007_199_254_740_991L;
    private static final Duration MAX_REMAINING = Duration.ofMillis(Long.MAX_VALUE);

    private final GameRules rules;
    private final WordPicker picker;
    private final Instant deadline;
    private String word;
    private int letterIndex;
    private long positionId = 1;
    private int score;
    private boolean finished;
    private boolean repeatsPreviousLetter;

    public GameEngine(GameRules rules, WordPicker picker, Instant now, Instant deadline) {
        this.rules = Objects.requireNonNull(rules, "rules");
        this.picker = Objects.requireNonNull(picker, "picker");
        this.deadline = Objects.requireNonNull(deadline, "deadline");
        if (!deadline.isAfter(now)) {
            throw new IllegalArgumentException("Game deadline must be after the start time.");
        }
        picker.requireCoverage(rules.maxWordLength());
        word = pickWord(MIN_WORD_LENGTH);
    }

    public GameState state(Instant now) {
        long remainingMs = isFinishedAt(now) ? 0 : remainingMilliseconds(now);
        return new GameState(positionId, word, letterIndex, score, remainingMs, finished, repeatsPreviousLetter);
    }

    public boolean completePosition(long positionId, Instant now) {
        if (isFinishedAt(now) || this.positionId != positionId) {
            return false;
        }

        long nextPositionId = positionId + 1;
        if (nextPositionId > MAX_POSITION_ID) {
            throw new ArithmeticException("Position ID exceeds the JavaScript safe integer range.");
        }
        int length = word.length();
        int nextLetterIndex = letterIndex + 1;
        boolean completesWord = nextLetterIndex == length;
        int nextScore = Math.addExact(score, rules.pointsForPosition(length, completesWord));
        String nextWord = word;
        if (completesWord) {
            int nextLength = length < rules.maxWordLength() ? length + 1 : length;
            nextWord = pickWord(nextLength);
            nextLetterIndex = 0;
        }

        repeatsPreviousLetter = word.charAt(letterIndex) == nextWord.charAt(nextLetterIndex);
        word = nextWord;
        letterIndex = nextLetterIndex;
        this.positionId = nextPositionId;
        score = nextScore;
        return true;
    }

    private String pickWord(int length) {
        String picked = picker.pick(length);
        if (picked == null || picked.length() != length || !WordCatalog.usesSupportedLetters(picked)) {
            throw new IllegalStateException("Word picker must return a supported word of length " + length + ".");
        }
        return picked;
    }

    private boolean isFinishedAt(Instant now) {
        if (!now.isBefore(deadline)) {
            finished = true;
        }
        return finished;
    }

    private long remainingMilliseconds(Instant now) {
        var remaining = Duration.between(now, deadline);
        return remaining.compareTo(MAX_REMAINING) > 0 ? Long.MAX_VALUE : remaining.toMillis();
    }
}
