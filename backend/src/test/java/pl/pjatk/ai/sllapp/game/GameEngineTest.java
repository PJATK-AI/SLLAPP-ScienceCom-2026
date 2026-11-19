package pl.pjatk.ai.sllapp.game;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class GameEngineTest {
    private static final Instant START = Instant.parse("2026-11-19T12:00:00Z");
    private static final Instant DEADLINE = START.plusSeconds(30);

    @Test
    void startsAtTheFirstLetterWithZeroPointsAndReturnsAnImmutableSnapshot() {
        var picker = new FixedPicker("LAS");
        var game = start(picker);
        var initial = game.state(START);
        assertThat(initial).isEqualTo(new GameState(1, "LAS", 0, 0, 30_000, false, false));
        assertThat(initial.expectedLetter()).isEqualTo('L');
        assertThat(picker.requestedLengths).containsExactly(3);

        assertThat(game.completePosition(1, START.plusSeconds(1))).isTrue();
        assertThat(game.state(START.plusSeconds(1)))
                .isEqualTo(new GameState(2, "LAS", 1, 10, 29_000, false, false));
        assertThat(initial.expectedLetter()).isEqualTo('L');
        assertThat(initial.score()).isZero();
    }

    @Test
    void awardsPointsForEveryLetterAndAddsTheBonusOnlyAtEachWordBoundary() {
        var picker = new FixedPicker("LAS", "SITO", "ATLAS", "CEBULA", "BARIERA", "WERBENA", "CYTRYNA");
        var game = start(picker);
        int[] letterPoints = {10, 20, 30, 40, 50, 50};
        int[] wordTotals = {50, 120, 210, 320, 450, 450};

        for (int round = 0; round < wordTotals.length; round++) {
            var firstPosition = game.state(START);
            int length = Math.min(3 + round, 7);
            assertThat(firstPosition.word()).hasSize(length);
            assertThat(firstPosition.letterIndex()).isZero();
            for (int index = 0; index < length; index++) {
                var active = game.state(START);
                assertThat(active.letterIndex()).isEqualTo(index);
                assertThat(active.expectedLetter()).isEqualTo(active.word().charAt(index));
                assertThat(game.completePosition(active.positionId(), START)).isTrue();
                var next = game.state(START);
                assertThat(next.positionId()).isEqualTo(active.positionId() + 1);
                if (index + 1 < length) {
                    assertThat(next.score()).isEqualTo(firstPosition.score() + (index + 1) * letterPoints[round]);
                }
            }
            assertThat(game.state(START).score() - firstPosition.score()).isEqualTo(wordTotals[round]);
        }
        assertThat(game.state(START).word()).isEqualTo("CYTRYNA");
        assertThat(game.state(START).letterIndex()).isZero();
        assertThat(picker.requestedLengths).containsExactly(3, 4, 5, 6, 7, 7, 7);
    }

    @Test
    void supportsCustomMaximumAndFractionalScoring() {
        var rules = new GameRules(4, BigDecimal.TWO, BigDecimal.TWO, new BigDecimal("0.25"));
        var picker = new FixedPicker("LAS", "SITO", "AUTO");
        var game = new GameEngine(rules, picker, START, DEADLINE);

        completeWord(game);
        assertThat(game.state(START).score()).isEqualTo(8);
        completeWord(game);
        assertThat(game.state(START).score()).isEqualTo(23);
        assertThat(picker.requestedLengths).containsExactly(3, 4, 4);
    }

    @Test
    void playsAtTheMinimumOrAboveTheDefaultWithACoveredCatalog() throws Exception {
        var minimumRules = new GameRules(3, BigDecimal.TEN, BigDecimal.TWO, BigDecimal.ONE);
        var minimumGame = new GameEngine(minimumRules, new RandomWordPicker(WordCatalog.loadBundled()), START, DEADLINE);
        completeWord(minimumGame);
        assertThat(minimumGame.state(START).word()).hasSize(3);

        var extended = WordCatalog.fromLines(List.of("LAS", "LATO", "ATLAS", "CEBULA", "BARIERA", "BALERINA"), 8);
        var extendedRules = new GameRules(8, BigDecimal.TEN, BigDecimal.TWO, BigDecimal.ONE);
        var extendedGame = new GameEngine(extendedRules, new RandomWordPicker(extended), START, DEADLINE);
        for (int length = 3; length < 8; length++) {
            assertThat(extendedGame.state(START).word()).hasSize(length);
            completeWord(extendedGame);
        }
        assertThat(extendedGame.state(START).word()).isEqualTo("BALERINA");
        completeWord(extendedGame);
        assertThat(extendedGame.state(START).word()).isEqualTo("BALERINA");
    }

    @Test
    void rejectsIncompleteCatalogBeforePlay() throws Exception {
        var rules = new GameRules(8, BigDecimal.TEN, BigDecimal.TWO, BigDecimal.ONE);
        var picker = new RandomWordPicker(WordCatalog.loadBundled());
        assertThatIllegalArgumentException().isThrownBy(() -> new GameEngine(rules, picker, START, DEADLINE))
                .withMessageContaining("lengths 8.");
    }

    @Test
    void rejectsAnElapsedStartingDeadlineWithoutPicking() {
        var picker = new FixedPicker();
        for (Instant deadline : List.of(START, START.minusNanos(1))) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new GameEngine(GameRules.defaults(), picker, START, deadline))
                    .withMessageContaining("deadline must be after the start time");
        }
        assertThat(picker.requestedLengths).isEmpty();
    }

    @Test
    void flagsRepeatedLettersWithinAndAcrossWordsAndRejectsStaleOrUnknownIds() {
        var picker = new FixedPicker("LAS", "SSIE");
        var game = start(picker);
        assertThat(game.state(START).repeatsPreviousLetter()).isFalse();
        assertThat(game.completePosition(1, START)).isTrue();
        assertThat(game.state(START).repeatsPreviousLetter()).isFalse();
        assertThat(game.completePosition(2, START)).isTrue();

        var last = game.state(START);
        assertThat(last.expectedLetter()).isEqualTo('S');
        for (long id : new long[]{-1, 2, 4, Long.MAX_VALUE}) {
            assertThat(game.completePosition(id, START)).isFalse();
            assertThat(game.state(START)).isEqualTo(last);
        }
        assertThat(picker.requestedLengths).containsExactly(3);
        assertThat(game.completePosition(last.positionId(), START)).isTrue();
        var next = game.state(START);
        assertThat(next).isEqualTo(new GameState(4, "SSIE", 0, 50, 30_000, false, true));
        assertThat(game.completePosition(last.positionId(), START)).isFalse();
        assertThat(game.state(START)).isEqualTo(next);
        assertThat(picker.requestedLengths).containsExactly(3, 4);

        assertThat(game.completePosition(next.positionId(), START)).isTrue();
        assertThat(game.state(START).expectedLetter()).isEqualTo('S');
        assertThat(game.state(START).repeatsPreviousLetter()).isTrue();
        assertThat(game.completePosition(5, START)).isTrue();
        assertThat(game.state(START).expectedLetter()).isEqualTo('I');
        assertThat(game.state(START).repeatsPreviousLetter()).isFalse();
    }

    @Test
    void acceptsUntilTheExactDeadlineAndThenPreservesScoreAndPosition() {
        var picker = new FixedPicker("LAS");
        var game = start(picker);
        assertThat(game.completePosition(1, START)).isTrue();
        var justBeforeDeadline = DEADLINE.minusNanos(1);
        assertThat(game.state(justBeforeDeadline).remainingMs()).isZero();
        assertThat(game.state(justBeforeDeadline).finished()).isFalse();
        assertThat(game.completePosition(2, justBeforeDeadline)).isTrue();

        assertThat(game.completePosition(3, DEADLINE)).isFalse();
        var finalState = new GameState(3, "LAS", 2, 20, 0, true, false);
        assertThat(game.state(DEADLINE.plusNanos(1))).isEqualTo(finalState);
        assertThat(game.completePosition(3, DEADLINE.plusNanos(1))).isFalse();
        assertThat(game.completePosition(3, START)).isFalse();
        assertThat(game.state(START)).isEqualTo(finalState);
        assertThat(picker.requestedLengths).containsExactly(3);
    }

    @Test
    void firstReadAfterTheDeadlineFinishesAnIdleGame() {
        var game = start(new FixedPicker("LAS"));
        var expired = new GameState(1, "LAS", 0, 0, 0, true, false);
        assertThat(game.state(DEADLINE.plusNanos(1))).isEqualTo(expired);
        assertThat(game.state(START)).isEqualTo(expired);
    }

    @Test
    void rejectsScoreOverflowBeforeChangingStateOrPickingTheNextWord() {
        var rules = new GameRules(4, BigDecimal.ONE, BigDecimal.valueOf(Integer.MAX_VALUE - 1), BigDecimal.ZERO);
        var picker = new FixedPicker("LAS", "SITO");
        var game = new GameEngine(rules, picker, START, DEADLINE);
        assertThat(game.completePosition(1, START)).isTrue();
        assertThat(game.completePosition(2, START)).isTrue();
        var active = game.state(START);

        assertThatExceptionOfType(ArithmeticException.class)
                .isThrownBy(() -> game.completePosition(active.positionId(), START));
        assertThat(game.state(START)).isEqualTo(active);
        assertThat(picker.requestedLengths).containsExactly(3);
    }

    @Test
    void validatesWordsFromCustomPickersAndRetainsStateIfADrawFails() {
        assertThatIllegalStateException().isThrownBy(() -> start(length -> "DOM"))
                .withMessageContaining("supported word of length 3");
        var game = start(new FixedPicker("LAS", "ATLAS"));
        assertThat(game.completePosition(1, START)).isTrue();
        assertThat(game.completePosition(2, START)).isTrue();
        var active = game.state(START);
        assertThatIllegalStateException().isThrownBy(() -> game.completePosition(active.positionId(), START))
                .withMessageContaining("supported word of length 4");
        assertThat(game.state(START)).isEqualTo(active);
    }

    private static GameEngine start(WordPicker picker) {
        return new GameEngine(GameRules.defaults(), picker, START, DEADLINE);
    }

    private static void completeWord(GameEngine game) {
        int length = game.state(START).word().length();
        for (int index = 0; index < length; index++) {
            assertThat(game.completePosition(game.state(START).positionId(), START)).isTrue();
        }
    }

    private static final class FixedPicker implements WordPicker {
        private final ArrayDeque<String> words;
        private final List<Integer> requestedLengths = new ArrayList<>();

        private FixedPicker(String... words) {
            this.words = new ArrayDeque<>(List.of(words));
        }

        @Override
        public String pick(int length) {
            requestedLengths.add(length);
            return words.removeFirst();
        }
    }
}
