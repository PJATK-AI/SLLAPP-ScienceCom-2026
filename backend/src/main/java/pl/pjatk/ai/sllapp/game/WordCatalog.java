package pl.pjatk.ai.sllapp.game;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

public final class WordCatalog {
    public static final int MIN_WORD_LENGTH = 3;
    private static final int BUNDLED_MAX_WORD_LENGTH = 7;
    private static final String BUNDLED_RESOURCE = "/game/words.txt";
    private static final String ALPHABET = "ABCEILMNOPRSTUWY";

    private final SortedMap<Integer, List<String>> groups;

    private WordCatalog(SortedMap<Integer, List<String>> groups) {
        this.groups = Collections.unmodifiableSortedMap(groups);
    }

    public static WordCatalog loadBundled() throws IOException {
        return loadResource(BUNDLED_RESOURCE);
    }

    static WordCatalog loadResource(String path) throws IOException {
        try (var stream = WordCatalog.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new FileNotFoundException("Missing classpath word list: " + path);
            }
            var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8.newDecoder()));
            var lines = new ArrayList<String>();
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
            return fromLines(lines, BUNDLED_MAX_WORD_LENGTH);
        }
    }

    public static WordCatalog fromLines(List<String> lines, int maxWordLength) {
        var issues = validate(lines, maxWordLength);
        if (!issues.isEmpty()) {
            throw new InvalidWordsException(issues);
        }

        var groups = new TreeMap<Integer, List<String>>();
        for (String line : lines) {
            String word = line.strip();
            if (!word.isEmpty()) {
                groups.computeIfAbsent(word.length(), length -> new ArrayList<>()).add(word);
            }
        }
        if (groups.isEmpty()) {
            throw new IllegalArgumentException("Word list is empty.");
        }
        groups.replaceAll((length, words) -> List.copyOf(words));
        return new WordCatalog(groups);
    }

    public static List<ValidationIssue> validate(List<String> lines, int maxWordLength) {
        requireValidMaximum(maxWordLength);
        var issues = new ArrayList<ValidationIssue>();
        var seen = new HashSet<String>();
        int lineNumber = 0;
        for (String line : lines) {
            lineNumber++;
            String word = line.strip();
            if (word.isEmpty()) {
                continue;
            }

            var reasons = new ArrayList<String>();
            int length = word.codePointCount(0, word.length());
            if (length < MIN_WORD_LENGTH || length > maxWordLength) {
                reasons.add("Length must be between " + MIN_WORD_LENGTH + " and " + maxWordLength);
            }
            if (!usesSupportedLetters(word)) {
                reasons.add("Only the letters " + ALPHABET + " are supported");
            }
            if (!seen.add(word)) {
                reasons.add("Duplicate word");
            }
            if (!reasons.isEmpty()) {
                issues.add(new ValidationIssue(lineNumber, line, reasons));
            }
        }
        return List.copyOf(issues);
    }

    public List<String> wordsOfLength(int length) {
        return groups.getOrDefault(length, List.of());
    }

    public void requireCoverage(int maxWordLength) {
        requireValidMaximum(maxWordLength);
        var missing = new ArrayList<String>();
        long nextLength = MIN_WORD_LENGTH;
        for (int length : groups.keySet()) {
            if (length > maxWordLength) {
                break;
            }
            addMissingRange(missing, nextLength, length - 1);
            nextLength = (long) length + 1;
        }
        addMissingRange(missing, nextLength, maxWordLength);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Missing words of lengths " + String.join(", ", missing) + ".");
        }
    }

    private static void addMissingRange(List<String> missing, long first, int last) {
        if (first <= last) {
            missing.add(first == last ? Long.toString(first) : first + "-" + last);
        }
    }

    static void requireValidMaximum(int maxWordLength) {
        if (maxWordLength < MIN_WORD_LENGTH) {
            throw new IllegalArgumentException("Maximum word length must be at least " + MIN_WORD_LENGTH + ".");
        }
    }

    static boolean usesSupportedLetters(String word) {
        return word.chars().allMatch(letter -> ALPHABET.indexOf(letter) >= 0);
    }

    public record ValidationIssue(int lineNumber, String input, List<String> reasons) {
        public ValidationIssue {
            reasons = List.copyOf(reasons);
        }
    }

    public static final class InvalidWordsException extends IllegalArgumentException {
        private final List<ValidationIssue> issues;

        private InvalidWordsException(List<ValidationIssue> issues) {
            super(issues.stream()
                    .map(issue -> "Line " + issue.lineNumber() + " (" + issue.input() + "): "
                            + String.join(", ", issue.reasons()))
                    .collect(Collectors.joining("\n")));
            this.issues = issues;
        }

        public List<ValidationIssue> issues() {
            return issues;
        }
    }
}
