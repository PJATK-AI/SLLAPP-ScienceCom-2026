package pl.pjatk.ai.sllapp.moderation;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import pl.pjatk.ai.sllapp.config.moderation.ModerationProperties;

@Component
public class BlockedWordList {
    private static final String TOKEN_PATTERN = "(?:[\\p{L}\\p{M}]+|\\p{Nd}+)";
    private static final Pattern TOKEN = Pattern.compile(TOKEN_PATTERN);
    private static final Pattern ENTRY = Pattern.compile(TOKEN_PATTERN + "(?:\\s+" + TOKEN_PATTERN + ")*",
            Pattern.UNICODE_CHARACTER_CLASS);
    private final Set<String> singleTokens;
    private final List<List<String>> phrases;

    public BlockedWordList(ModerationProperties properties, ResourceLoader resourceLoader) {
        var entries = load(resourceLoader.getResource(properties.blockedWordsLocation()));
        singleTokens = entries.stream().filter(entry -> entry.size() == 1).map(List::getFirst)
                .collect(Collectors.toUnmodifiableSet());
        phrases = entries.stream().filter(entry -> entry.size() > 1).toList();
    }

    public boolean containsBlockedWord(String nickname) {
        var nicknameTokens = tokens(normalize(nickname));
        return nicknameTokens.stream().anyMatch(singleTokens::contains)
                || phrases.stream().anyMatch(phrase -> Collections.indexOfSubList(nicknameTokens, phrase) >= 0);
    }

    private Set<List<String>> load(Resource resource) {
        if (!resource.isReadable()) {
            throw new IllegalStateException("Cannot read UTF-8 blocked-word list: " + resource.getDescription(),
                    new FileNotFoundException("Resource is missing, unreadable or a directory"));
        }
        var loaded = new HashSet<List<String>>();
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
                if (!ENTRY.matcher(entry).matches()) {
                    throw new IllegalStateException("Invalid blocked-word entry at " + resource.getDescription()
                            + ":" + lineNumber + ". Expected words or numbers separated by whitespace.");
                }
                loaded.add(tokens(entry));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read UTF-8 blocked-word list: " + resource.getDescription(), exception);
        }
        if (loaded.isEmpty()) {
            throw new IllegalStateException("Blocked-word list is empty: " + resource.getDescription());
        }
        return Set.copyOf(loaded);
    }

    private static List<String> tokens(String value) {
        return TOKEN.matcher(value).results().map(MatchResult::group).toList();
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
}
