package pl.pjatk.ai.sllapp.game.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class BlockedWordListTest {
    @TempDir
    Path directory;

    @Test
    void loadsUtf8CommentsBomAndNormalizedDuplicatesOnce() throws Exception {
        var file = directory.resolve("words.txt");
        Files.writeString(file, "\uFEFF# Comment\r\n\r\n BAD \r\nbad\r\nｂａｄ\r\nżółć\r\n  # Another comment\r\n");
        var list = load(file.toUri().toString());
        assertThat(list.containsBlockedWord("BAD123")).isTrue();
        assertThat(list.containsBlockedWord("ｂａｄ")).isTrue();
        assertThat(list.containsBlockedWord("ŻÓŁĆ")).isTrue();
        assertThat(list.containsBlockedWord("Badminton")).isFalse();
        assertThat(list.containsBlockedWord("Comment")).isFalse();
        Files.writeString(file, "different");
        assertThat(list.containsBlockedWord("bad")).isTrue();
        assertThat(list.containsBlockedWord("different")).isFalse();
    }

    @Test
    void loadsBundledPolishAndEnglishWords() {
        var list = load("classpath:moderation/blocked-words.txt");
        assertThat(list.containsBlockedWord("skurwysyn")).isTrue();
        assertThat(list.containsBlockedWord("motherfucker")).isTrue();
        assertThat(list.containsBlockedWord("Ola")).isFalse();
        assertThat(list.containsBlockedWord("Łucja")).isFalse();
    }

    @Test
    void failsForMissingUnreadableOrMalformedUtf8Resources() throws Exception {
        var wordDirectory = Files.createDirectory(directory.resolve("words"));
        Files.writeString(wordDirectory.resolve("bad"), "bad");
        for (String location : new String[]{"classpath:moderation/missing.txt",
                directory.resolve("missing.txt").toUri().toString(), wordDirectory.toUri().toString()}) {
            assertThatIllegalStateException().isThrownBy(() -> load(location))
                    .withMessageContaining("Cannot read UTF-8 blocked-word list").withCauseInstanceOf(IOException.class);
        }
        var file = directory.resolve("malformed.txt");
        Files.write(file, new byte[]{(byte) 0xC3, (byte) 0x28});
        assertThatIllegalStateException().isThrownBy(() -> load(file.toUri().toString()))
                .withMessageContaining("Cannot read UTF-8 blocked-word list");
    }

    @Test
    void failsForEmptyListsAndReportsInvalidLineNumbers() throws Exception {
        var file = directory.resolve("words.txt");
        Files.writeString(file, "# Only comments\n\n");
        assertThatIllegalStateException().isThrownBy(() -> load(file.toUri().toString()))
                .withMessageContaining("Blocked-word list is empty");
        for (String invalid : new String[]{"two words", "bad*", "bad123"}) {
            Files.writeString(file, "# Comment\nbad\n" + invalid);
            assertThatIllegalStateException().isThrownBy(() -> load(file.toUri().toString()))
                    .withMessageContaining("Invalid blocked-word entry").withMessageContaining(":3;");
        }
    }

    @Test
    void rejectsEmptyAndRemoteLocations() {
        for (String location : new String[]{null, "", " ", "https://example.com/words.txt", "words.txt"}) {
            assertThatIllegalArgumentException().isThrownBy(() -> load(location))
                    .withMessageContaining("classpath: or file:");
        }
    }

    private BlockedWordList load(String location) {
        return new BlockedWordList(new SessionProperties(Duration.ofMinutes(5), location), new DefaultResourceLoader());
    }
}
