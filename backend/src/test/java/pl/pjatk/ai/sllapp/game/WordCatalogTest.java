package pl.pjatk.ai.sllapp.game;

import java.io.FileNotFoundException;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WordCatalogTest {
    @Test
    void loadsTheBundledWordsWithSeveralDistinctEntriesForEachLength() throws Exception {
        var catalog = WordCatalog.loadBundled();
        var distinctWords = new HashSet<String>();

        catalog.requireCoverage(7);
        for (int length = 3; length <= 7; length++) {
            var words = catalog.wordsOfLength(length);
            assertThat(words).hasSizeGreaterThanOrEqualTo(3);
            for (String word : words) {
                assertThat(word).hasSize(length).matches("[ABCEILMNOPRSTUWY]+");
                assertThat(distinctWords.add(word)).isTrue();
            }
        }
        assertThat(catalog.wordsOfLength(8)).isEmpty();
    }

    @Test
    void acceptsEverySupportedLetterAndStripsSurroundingWhitespace() {
        assertThat(WordCatalog.validate(List.of("  ABCEILMNOPRSTUWY\t"), 16)).isEmpty();
        var catalog = WordCatalog.fromLines(List.of(" LAS ", "", "\t", "LIS"), 3);

        assertThat(catalog.wordsOfLength(3)).containsExactly("LAS", "LIS");
        assertThatThrownBy(() -> catalog.wordsOfLength(3).add("SER"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void reportsAllInvalidLinesAndMultipleReasonsForOneWord() {
        var lines = List.of(" LAS ", "", "A", "CEBULA", "las", "ŁAN", "A B", "A1!", " LAS", "A!");
        var issues = WordCatalog.validate(lines, 5);
        assertThat(issues).extracting(WordCatalog.ValidationIssue::lineNumber)
                .containsExactly(3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(issues).extracting(WordCatalog.ValidationIssue::input)
                .containsExactly("A", "CEBULA", "las", "ŁAN", "A B", "A1!", " LAS", "A!");
        assertThat(issues.getFirst().reasons()).containsExactly("Length must be between 3 and 5");
        assertThat(issues.get(2).reasons()).containsExactly("Only the letters ABCEILMNOPRSTUWY are supported");
        assertThat(issues.get(6).reasons()).containsExactly("Duplicate word");
        assertThat(issues.getLast().reasons()).containsExactly("Length must be between 3 and 5",
                "Only the letters ABCEILMNOPRSTUWY are supported");
        assertThatExceptionOfType(WordCatalog.InvalidWordsException.class)
                .isThrownBy(() -> WordCatalog.fromLines(lines, 5))
                .withMessageContaining("Line 10 (A!)")
                .satisfies(exception -> assertThat(exception.issues()).isEqualTo(issues));
    }

    @Test
    void rejectsAnEmptyListAndReportsMissingResourceAsIoFailure() {
        assertThatIllegalArgumentException().isThrownBy(() -> WordCatalog.fromLines(List.of("", " "), 7))
                .withMessageContaining("Word list is empty");
        assertThatExceptionOfType(FileNotFoundException.class)
                .isThrownBy(() -> WordCatalog.loadResource("/game/missing.txt"))
                .withMessageContaining("/game/missing.txt");
    }

    @Test
    void checksGameCoverageIndependentlyFromSourceValidation() throws Exception {
        var bundled = WordCatalog.loadBundled();
        bundled.requireCoverage(3);
        bundled.requireCoverage(5);
        assertThatIllegalArgumentException().isThrownBy(() -> bundled.requireCoverage(8))
                .withMessageContaining("lengths 8.");
        assertThat(WordCatalog.validate(List.of("LAS", "CEBULA", "BARIERA"), 5))
                .extracting(WordCatalog.ValidationIssue::lineNumber).containsExactly(2, 3);

        var sparse = WordCatalog.fromLines(List.of("LAS", "ATLAS", "BARIERA"), 7);
        assertThatIllegalArgumentException().isThrownBy(() -> sparse.requireCoverage(9))
                .withMessageContaining("lengths 4, 6, 8-9.");
        var extended = WordCatalog.fromLines(List.of("LAS", "LATO", "ATLAS", "CEBULA", "BARIERA", "BALERINA"), 8);
        extended.requireCoverage(8);
    }
}
