package io.github.clemsix6.narrator2.forge.api;

import io.github.clemsix6.narrator2.core.ModelText;
import io.github.clemsix6.narrator2.mechanics.challenge.Answers;
import io.github.clemsix6.narrator2.tool.ToolText;
import java.util.ArrayList;
import java.util.List;

/**
 * Text without regular expressions, which a module may not use (a player's message could make one run for ever):
 * the Narrator's own rules, as its quizzes and tools apply them. A null text reads as an empty one.
 */
public final class Text {
    private Text() {}

    /** The words of {@code text}, {@link #normalize normalized}: « L'Œuf, déjà ! » is {@code [l, oeuf, deja]}. */
    public static List<String> words(String text) {
        String normalized = normalize(text);
        List<String> words = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= normalized.length(); i++) {
            if (i == normalized.length() || normalized.charAt(i) == ' ') {
                if (i > start) words.add(normalized.substring(start, i));
                start = i + 1;
            }
        }
        return List.copyOf(words);
    }

    /**
     * {@code text} as the quizzes compare answers: lower case, without accents ({@code œ} is {@code oe}), every
     * character that is neither a letter nor a digit as a space, the spaces folded and trimmed.
     */
    public static String normalize(String text) {
        return text == null ? "" : Answers.INSTANCE.normalize(text);
    }

    /** {@code text} cut to {@code max} characters, the last being {@code …} when it is cut. */
    public static String cut(String text, int max) {
        return text == null ? "" : ModelText.INSTANCE.cut(text, max);
    }

    /** A duration as the Narrator writes it: {@code 36s}, {@code 1m05s}, {@code 25m}, {@code 1h12m}, {@code 2d4h}. */
    public static String duration(long millis) {
        return ToolText.INSTANCE.duration(millis);
    }
}
