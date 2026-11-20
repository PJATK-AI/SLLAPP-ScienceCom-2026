package pl.pjatk.ai.sllapp.game;

@FunctionalInterface
public interface WordPicker {
    /** Returns a supported word of the requested length. Each game owns its picker. */
    String pick(int length);

    /** Catalog-backed implementations check coverage before the first pick. */
    default void requireCoverage(int maxWordLength) {
    }
}
