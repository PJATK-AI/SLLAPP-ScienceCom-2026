package pl.pjatk.ai.sllapp.moderation;

import org.springframework.stereotype.Component;

@Component
public class NicknamePolicy {
    private final BlockedWordList blockedWords;

    public NicknamePolicy(BlockedWordList blockedWords) {
        this.blockedWords = blockedWords;
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
        if (blockedWords.containsBlockedWord(trimmed)) {
            throw new IllegalArgumentException("Nickname contains a blocked word.");
        }
        return trimmed;
    }
}
