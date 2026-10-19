package io.github.clemsix6.narrator2.forge.rt;

import io.github.clemsix6.narrator2.forge.ForgeLimits;
import io.github.clemsix6.narrator2.forge.api.Limits;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Formatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collector;

/**
 * The texts of {@link Guarded}: what the JDK makes in one call, made here within {@code Limits.MAX_CHARS}
 * characters. The text of a collection is the product of two limits (10,000 texts of 65,536 characters: 655 million
 * characters in one call of the JDK), so the text of a collection, a map, a map's entry, an optional or an atomic
 * reference of the JDK is made here, element by element, as the JDK writes it, and stops at the limit.
 */
final class Texts {
    /** The longest text of an {@code int}, a {@code long}, a {@code float}, a {@code double}. */
    static final int INT_CHARS = 11;
    static final int LONG_CHARS = 20;
    static final int FLOAT_CHARS = 15;
    static final int DOUBLE_CHARS = 24;

    private static final int CHARS = Limits.MAX_CHARS;

    private Texts() {}

    static void fits(long length) {
        if (length > CHARS) throw tooLong();
    }

    static void room(StringBuilder builder, long more) {
        fits(builder.length() + more);
    }

    static ForgeLimit tooLong() {
        return new ForgeLimit("a text over " + CHARS + " characters");
    }

    /** {@code String.valueOf(value)}, the text of a composite of the JDK made within the limit. */
    static String of(Object value) {
        if (value instanceof String text) return text;
        if (value == null) return "null";
        if (!composite(value)) return checked(value.toString());
        Out out = new Out();
        out.add(value, 0);
        return out.toString();
    }

    /** What the JDK makes a text of by walking what it holds. */
    private static boolean composite(Object value) {
        return value instanceof Collection || value instanceof Map || value instanceof Optional || value instanceof AtomicReference
            || value instanceof Map.Entry && value.getClass().getClassLoader() == null;
    }

    private static String checked(String text) {
        if (text != null) fits(text.length());
        return text;
    }

    /** {@code Arrays.toString} of any array. */
    static String ofArray(Object array) {
        if (array == null) return "null";
        Out out = new Out();
        out.elements(array);
        return out.toString();
    }

    /** {@code Arrays.deepToString}. */
    static String deep(Object[] array) {
        if (array == null) return "null";
        Out out = new Out();
        out.deep(array, new ArrayList<>());
        return out.toString();
    }

    /** A CharSequence whose length can be trusted: the JDK's own, or a copy of another, read once. */
    static CharSequence trusted(CharSequence text) {
        if (text == null || text.getClass().getClassLoader() == null) return text;
        int length = text.length();
        fits(length);
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) chars[i] = text.charAt(i);
        return new String(chars);
    }

    /**
     * {@code String.format}: widths and precisions of {@code ForgeLimits.FORMAT_DIGITS} digits at most (the formatter
     * pads and computes digits in one call), each composite argument made a text within the limit (a {@code %s} of a
     * list), and the whole written into a bounded text. A {@code %h} of a composite gives its text's hash.
     */
    static String format(boolean localized, Locale locale, String format, Object[] args) {
        widths(format);
        Object[] safe = args;
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                if (composite(args[i])) {
                    if (safe == args) safe = args.clone();
                    safe[i] = of(args[i]);
                }
            }
        }
        Out out = new Out();
        Formatter formatter = localized ? new Formatter(out, locale) : new Formatter(out);
        formatter.format(format, safe);
        return out.toString();
    }

    private static void widths(String format) {
        int length = format.length();
        for (int i = 0; i < length; i++) {
            if (format.charAt(i) != '%') continue;
            int j = i + 1;
            int end = digits(format, j);
            if (end < length && format.charAt(end) == '$') j = end + 1;
            while (j < length && "-#+ 0,(<".indexOf(format.charAt(j)) >= 0) j++;
            end = digits(format, j);
            if (end - j > ForgeLimits.FORMAT_DIGITS) throw wide();
            j = end;
            if (j < length && format.charAt(j) == '.') {
                end = digits(format, j + 1);
                if (end - j - 1 > ForgeLimits.FORMAT_DIGITS) throw wide();
                j = end;
            }
            i = j;
        }
    }

    private static int digits(String text, int from) {
        int at = from;
        while (at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') at++;
        return at;
    }

    private static ForgeLimit wide() {
        return new ForgeLimit("a format width or precision over " + ForgeLimits.FORMAT_DIGITS + " digits");
    }

    /** {@code String.join}: each element's text, then the sum checked before the JDK joins them. */
    static String join(CharSequence delimiter, Iterable<? extends CharSequence> elements) {
        String glue = delimiter.toString();
        Objects.requireNonNull(elements);
        List<String> texts = new ArrayList<>();
        long length = 0;
        for (CharSequence element : elements) {
            String text = String.valueOf(element);
            length += text.length() + (texts.isEmpty() ? 0 : glue.length());
            fits(length);
            texts.add(text);
        }
        return String.join(glue, texts);
    }

    /** {@code Collectors.joining()}, bounded: the JDK's joins the elements without the module's code. */
    static Collector<CharSequence, ?, String> joining() {
        return Collector.<CharSequence, StringBuilder, String>of(StringBuilder::new, Guarded::append, (left, right) -> Guarded.append(left, (CharSequence) right), StringBuilder::toString);
    }

    static Collector<CharSequence, ?, String> joining(CharSequence delimiter, CharSequence prefix, CharSequence suffix) {
        return Collector.<CharSequence, StringJoiner, String>of(() -> new StringJoiner(delimiter, prefix, suffix), Texts::add, Texts::merge, StringJoiner::toString);
    }

    /** {@code StringJoiner.add}: checked before (the element) and after (its delimiter, which a joiner does not tell). */
    static StringJoiner add(StringJoiner joiner, CharSequence element) {
        String text = String.valueOf(element);
        fits((long) joiner.length() + text.length());
        joiner.add(text);
        fits(joiner.length());
        return joiner;
    }

    static StringJoiner merge(StringJoiner joiner, StringJoiner other) {
        fits((long) joiner.length() + other.length());
        joiner.merge(other);
        fits(joiner.length());
        return joiner;
    }

    /** The lines of a text, as {@code String.lines} counts them. */
    static long lines(String text) {
        long lines = 0;
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                lines++;
            } else if (c == '\r') {
                lines++;
                if (i + 1 < length && text.charAt(i + 1) == '\n') i++;
            }
        }
        char last = text.charAt(length - 1);
        return last == '\n' || last == '\r' ? lines : lines + 1;
    }

    /** Non-overlapping matches of a non-empty {@code target}, as {@code String.replace} finds them. */
    static long matches(String text, String target) {
        long matches = 0;
        for (int at = text.indexOf(target); at >= 0; at = text.indexOf(target, at + target.length())) matches++;
        return matches;
    }

    /** A text made piece by piece, never past the limit: also the {@link Appendable} of a bounded {@code Formatter}. */
    private static final class Out implements Appendable {
        private final StringBuilder text = new StringBuilder();

        private void put(String piece) {
            room(text, piece.length());
            text.append(piece);
        }

        @Override
        public Out append(CharSequence piece) {
            put(String.valueOf(piece));
            return this;
        }

        @Override
        public Out append(CharSequence piece, int start, int end) {
            CharSequence from = piece == null ? "null" : piece;
            Objects.checkFromToIndex(start, end, from.length());
            room(text, end - start);
            text.append(from, start, end);
            return this;
        }

        @Override
        public Out append(char c) {
            room(text, 1);
            text.append(c);
            return this;
        }

        /** {@code value}'s text as the JDK writes it, {@code depth} composites deep. */
        void add(Object value, int depth) {
            if (value == null || value instanceof String || !composite(value)) {
                put(value == null ? "null" : String.valueOf(value.toString()));
                return;
            }
            if (depth >= Limits.MAX_DEPTH) throw new ForgeLimit("a text of collections nested over " + Limits.MAX_DEPTH + " levels");
            switch (value) {
                case Collection<?> collection -> {
                    put("[");
                    boolean first = true;
                    for (Object element : collection) {
                        if (!first) put(", ");
                        first = false;
                        if (element == collection) put("(this Collection)");
                        else add(element, depth + 1);
                    }
                    put("]");
                }
                case Map<?, ?> map -> {
                    put("{");
                    boolean first = true;
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        if (!first) put(", ");
                        first = false;
                        if (entry.getKey() == map) put("(this Map)");
                        else add(entry.getKey(), depth + 1);
                        put("=");
                        if (entry.getValue() == map) put("(this Map)");
                        else add(entry.getValue(), depth + 1);
                    }
                    put("}");
                }
                case Map.Entry<?, ?> entry -> {
                    add(entry.getKey(), depth + 1);
                    put("=");
                    add(entry.getValue(), depth + 1);
                }
                case Optional<?> optional -> {
                    if (optional.isEmpty()) {
                        put("Optional.empty");
                    } else {
                        put("Optional[");
                        add(optional.get(), depth + 1);
                        put("]");
                    }
                }
                case AtomicReference<?> reference -> add(reference.get(), depth + 1);
                default -> put(String.valueOf(value.toString()));
            }
        }

        /** {@code Arrays.toString} of an array of any type. */
        void elements(Object array) {
            int length = Array.getLength(array);
            put("[");
            for (int i = 0; i < length; i++) {
                if (i > 0) put(", ");
                add(Array.get(array, i), 1);
            }
            put("]");
        }

        /** {@code Arrays.deepToString}: an array met again on its own path is {@code [...]}. */
        void deep(Object[] array, List<Object[]> path) {
            if (path.size() >= Limits.MAX_DEPTH) throw new ForgeLimit("a text of arrays nested over " + Limits.MAX_DEPTH + " levels");
            path.add(array);
            put("[");
            for (int i = 0; i < array.length; i++) {
                if (i > 0) put(", ");
                Object element = array[i];
                if (element instanceof Object[] inner) {
                    if (path.stream().anyMatch(seen -> seen == inner)) put("[...]");
                    else deep(inner, path);
                } else if (element != null && element.getClass().isArray()) {
                    elements(element);
                } else {
                    add(element, path.size());
                }
            }
            put("]");
            path.removeLast();
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }
}
