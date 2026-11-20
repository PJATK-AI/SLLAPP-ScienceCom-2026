package pl.pjatk.ai.sllapp.game;

public record GameState(long positionId, String word, int letterIndex, int score, long remainingMs,
                        boolean finished, boolean repeatsPreviousLetter) {
    public char expectedLetter() {
        return word.charAt(letterIndex);
    }
}
