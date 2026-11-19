package pl.pjatk.ai.sllapp.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.random.RandomGenerator;

public final class RandomWordPicker implements WordPicker {
    private final WordCatalog catalog;
    private final RandomGenerator random;
    private final Map<Integer, List<String>> remaining = new HashMap<>();

    public RandomWordPicker(WordCatalog catalog) {
        this(catalog, RandomGenerator.getDefault());
    }

    public RandomWordPicker(WordCatalog catalog, RandomGenerator random) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.random = Objects.requireNonNull(random, "random");
    }

    @Override
    public void requireCoverage(int maxWordLength) {
        catalog.requireCoverage(maxWordLength);
    }

    @Override
    public String pick(int length) {
        var pool = remaining.get(length);
        if (pool == null || pool.isEmpty()) {
            var words = catalog.wordsOfLength(length);
            if (words.isEmpty()) {
                throw new IllegalArgumentException("Missing words of length " + length + ".");
            }
            pool = new ArrayList<>(words);
            remaining.put(length, pool);
        }
        return pool.remove(random.nextInt(pool.size()));
    }
}
