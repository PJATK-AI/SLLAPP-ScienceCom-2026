package pl.pjatk.ai.sllapp.game.session;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class NicknamePolicy {
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{M}]+");
    private final Set<String> blockedWords;

    public NicknamePolicy(SessionProperties properties) {
        blockedWords = properties.blockedWords().stream().map(NicknamePolicy::normalize)
                .collect(Collectors.toUnmodifiableSet());
        if (blockedWords.stream().anyMatch(word -> !WORD.matcher(word).matches())) {
            throw new IllegalArgumentException("Blocked words must contain letters only");
        }
    }

    public String validate(String nickname) {
        if (nickname == null) throw new IllegalArgumentException("Nickname is required.");
        String trimmed = nickname.strip();
        int length = trimmed.codePointCount(0, trimmed.length());
        if (length < 1 || length > 20 || trimmed.codePoints().anyMatch(c ->
                Character.isISOControl(c) || Character.getType(c) == Character.SURROGATE
                        || Character.getType(c) == Character.FORMAT)) {
            throw new IllegalArgumentException("Nickname must contain 1 to 20 characters without control or format characters.");
        }
        if (WORD.matcher(normalize(trimmed)).results().anyMatch(word -> blockedWords.contains(word.group()))) {
            throw new IllegalArgumentException("Nickname contains a blocked word.");
        }
        return trimmed;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
}
