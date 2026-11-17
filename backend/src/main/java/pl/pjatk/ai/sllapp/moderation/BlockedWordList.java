package pl.pjatk.ai.sllapp.moderation;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import pl.pjatk.ai.sllapp.config.moderation.ModerationProperties;

@Component
public class BlockedWordList {
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{M}]+");
    private final Set<String> words;

    public BlockedWordList(ModerationProperties properties, ResourceLoader resourceLoader) {
        words = load(resourceLoader.getResource(properties.blockedWordsLocation()));
    }

    public boolean containsBlockedWord(String nickname) {
        return WORD.matcher(normalize(nickname)).results().anyMatch(word -> words.contains(word.group()));
    }

    private Set<String> load(Resource resource) {
        if (!resource.isReadable()) {
            throw new IllegalStateException("Cannot read UTF-8 blocked-word list: " + resource.getDescription(),
                    new FileNotFoundException("Resource is missing, unreadable or a directory"));
        }
        var loaded = new HashSet<String>();
        try (var reader = new BufferedReader(new InputStreamReader(
                resource.getInputStream(), StandardCharsets.UTF_8.newDecoder()))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (lineNumber == 1 && line.startsWith("\uFEFF")) line = line.substring(1);
                String entry = line.strip();
                if (entry.isEmpty() || entry.startsWith("#")) continue;
                entry = normalize(entry);
                if (!WORD.matcher(entry).matches()) {
                    throw new IllegalStateException("Invalid blocked-word entry at " + resource.getDescription()
                            + ":" + lineNumber + "; expected one word containing letters and combining marks");
                }
                loaded.add(entry);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read UTF-8 blocked-word list: " + resource.getDescription(), exception);
        }
        if (loaded.isEmpty()) {
            throw new IllegalStateException("Blocked-word list is empty: " + resource.getDescription());
        }
        return Set.copyOf(loaded);
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
}
