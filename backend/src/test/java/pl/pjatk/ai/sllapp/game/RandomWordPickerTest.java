package pl.pjatk.ai.sllapp.game;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class RandomWordPickerTest {
    private final WordCatalog catalog = WordCatalog.fromLines(
            List.of("LAS", "LIS", "SER", "AUTO", "SITO", "ATLAS"), 5);

    @Test
    void drawsTheWholePoolBeforeRepeatingAndRefillsForAnotherCycle() {
        var picker = new RandomWordPicker(catalog, new Random(42));
        for (int cycle = 0; cycle < 2; cycle++) {
            assertThat(List.of(picker.pick(3), picker.pick(3), picker.pick(3)))
                    .containsExactlyInAnyOrderElementsOf(catalog.wordsOfLength(3));
        }
    }

    @Test
    void keepsEachLengthIndependentIncludingWhenASingleWordRefills() {
        var picker = new RandomWordPicker(catalog, new Random(42));
        String first = picker.pick(3);
        assertThat(List.of(picker.pick(4), picker.pick(4))).containsExactlyInAnyOrder("AUTO", "SITO");
        assertThat(List.of(picker.pick(5), picker.pick(5))).containsExactly("ATLAS", "ATLAS");
        assertThat(List.of(first, picker.pick(3), picker.pick(3))).containsExactlyInAnyOrder("LAS", "LIS", "SER");
        assertThatIllegalArgumentException().isThrownBy(() -> picker.pick(6)).withMessageContaining("length 6");
    }

    @Test
    void keepsEachGamesPoolIndependentAndRepeatsDrawsForTheSameSeed() {
        var first = new RandomWordPicker(catalog, new Random(42));
        var second = new RandomWordPicker(catalog, new Random(42));

        var firstSequence = List.of(first.pick(3), first.pick(4), first.pick(3), first.pick(3), first.pick(3));
        assertThat(List.of(second.pick(3), second.pick(4), second.pick(3), second.pick(3), second.pick(3)))
                .isEqualTo(firstSequence);
    }
}
