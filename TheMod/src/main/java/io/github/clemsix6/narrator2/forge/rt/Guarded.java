package io.github.clemsix6.narrator2.forge.rt;

import io.github.clemsix6.narrator2.forge.ForgeLimits;
import io.github.clemsix6.narrator2.forge.api.Limits;
import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.StringConcatException;
import java.lang.invoke.StringConcatFactory;
import java.lang.invoke.TypeDescriptor;
import java.lang.runtime.ObjectMethods;
import java.lang.reflect.Array;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.SequencedCollection;
import java.util.SequencedMap;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

/**
 * The call-site guards of the rewritten bytecode (M5 §3.4): the rewriting ({@code forge/sandbox/Instrumenter.kt},
 * which holds the list, its capacities read from {@code SandboxPolicy}) sends each call of a module that allocates or
 * grows something through here, and each method
 * throws a {@link ForgeLimit} before it changes anything. The meter cannot bound these: it is only sampled at the
 * module's own ticks, and one call of the JDK allocates at once.
 *
 * <p>The forms: a check that gives its argument back ({@link #array}, {@link #capacity}, before {@code newarray} and
 * the constructors with a capacity), a static method in place of a static one, with the same descriptor
 * ({@code Arrays.copyOf}, {@code String.join}…), a static method in place of an instance one, the receiver first
 * ({@code add(Collection, Object)} for {@code collection.add(object)}), and two bootstraps in place of
 * {@code StringConcatFactory} and of the records' {@code toString} ({@link #concatenation}, {@link #record}).
 *
 * <p>The limits: an array, or a capacity asked at once, of {@code Limits.MAX_ARRAY} elements; a collection of
 * {@code Limits.MAX_ELEMENTS}; a text of {@code Limits.MAX_CHARS} characters, the text of a whole collection, map or
 * array included (its size would be the product of the two others); a range of a stream of {@code Limits.MAX_ARRAY}
 * numbers; a width or a precision of a format of {@code ForgeLimits.FORMAT_DIGITS} digits.
 *
 * <p>A module's class is never a {@code Collection}, a {@code Map} or a {@code CharSequence} (the rewriting refuses
 * it: the JDK trusts their sizes), so every receiver here is the JDK's own and its {@code size()} tells the truth.
 * Its elements may be the module's objects: their {@code equals}, {@code hashCode} and {@code toString} are the
 * module's metered code.
 */
public final class Guarded {
    private static final int ELEMENTS = Limits.MAX_ELEMENTS;
    private static final int CHARS = Limits.MAX_CHARS;
    private static final int ARRAY = Limits.MAX_ARRAY;
    private static final int CAPACITY = ForgeLimits.CAPACITY;

    private Guarded() {}

    // ── Arrays and capacities ───────────────────────────────────────────────────────────────────────────────────

    /** Before {@code newarray} and {@code anewarray}: the length, unchanged when allowed. */
    public static int array(int length) {
        if (length > ARRAY) throw new ForgeLimit("an array of " + length + " elements (at most " + ARRAY + ")");
        return length;
    }

    /** Before a constructor that reserves {@code capacity} elements or characters at once: the capacity, unchanged when allowed. */
    public static int capacity(int capacity) {
        if (capacity > CAPACITY) throw new ForgeLimit("a capacity of " + capacity + " (at most " + CAPACITY + ")");
        return capacity;
    }

    /** Before {@code new StringBuilder(CharSequence)}, which reserves the length it is told at once: a CharSequence whose length can be trusted, checked. */
    public static CharSequence trusted(CharSequence text) {
        CharSequence trusted = Texts.trusted(text);
        if (trusted != null) Texts.fits(trusted.length());
        return trusted;
    }

    /** In place of {@code HashMap.newHashMap}: a hash table allocates its whole table at its first {@code put}. */
    public static <K, V> HashMap<K, V> newHashMap(int mappings) {
        return HashMap.newHashMap(capacity(mappings));
    }

    public static <K, V> LinkedHashMap<K, V> newLinkedHashMap(int mappings) {
        return LinkedHashMap.newLinkedHashMap(capacity(mappings));
    }

    public static <E> HashSet<E> newHashSet(int elements) {
        return HashSet.newHashSet(capacity(elements));
    }

    public static <E> LinkedHashSet<E> newLinkedHashSet(int elements) {
        return LinkedHashSet.newLinkedHashSet(capacity(elements));
    }

    public static void ensureCapacity(ArrayList<?> list, int capacity) {
        list.ensureCapacity(capacity(capacity));
    }

    public static boolean[] copyOf(boolean[] original, int length) {
        return Arrays.copyOf(original, array(length));
    }

    public static byte[] copyOf(byte[] original, int length) {
        return Arrays.copyOf(original, array(length));
    }

    public static char[] copyOf(char[] original, int length) {
        return Arrays.copyOf(original, array(length));
    }

    public static short[] copyOf(short[] original, int length) {
        return Arrays.copyOf(original, array(length));
    }

    public static int[] copyOf(int[] original, int length) {
        return Arrays.copyOf(original, array(length));
    }

    public static long[] copyOf(long[] original, int length) {
        return Arrays.copyOf(original, array(length));
    }

    public static float[] copyOf(float[] original, int length) {
        return Arrays.copyOf(original, array(length));
    }

    public static double[] copyOf(double[] original, int length) {
        return Arrays.copyOf(original, array(length));
    }

    public static <T> T[] copyOf(T[] original, int length) {
        return Arrays.copyOf(original, array(length));
    }

    public static <T, U> T[] copyOf(U[] original, int length, Class<? extends T[]> type) {
        return Arrays.copyOf(original, array(length), type);
    }

    public static boolean[] copyOfRange(boolean[] original, int from, int to) {
        return Arrays.copyOfRange(original, from, span(from, to));
    }

    public static byte[] copyOfRange(byte[] original, int from, int to) {
        return Arrays.copyOfRange(original, from, span(from, to));
    }

    public static char[] copyOfRange(char[] original, int from, int to) {
        return Arrays.copyOfRange(original, from, span(from, to));
    }

    public static short[] copyOfRange(short[] original, int from, int to) {
        return Arrays.copyOfRange(original, from, span(from, to));
    }

    public static int[] copyOfRange(int[] original, int from, int to) {
        return Arrays.copyOfRange(original, from, span(from, to));
    }

    public static long[] copyOfRange(long[] original, int from, int to) {
        return Arrays.copyOfRange(original, from, span(from, to));
    }

    public static float[] copyOfRange(float[] original, int from, int to) {
        return Arrays.copyOfRange(original, from, span(from, to));
    }

    public static double[] copyOfRange(double[] original, int from, int to) {
        return Arrays.copyOfRange(original, from, span(from, to));
    }

    public static <T> T[] copyOfRange(T[] original, int from, int to) {
        return Arrays.copyOfRange(original, from, span(from, to));
    }

    public static <T, U> T[] copyOfRange(U[] original, int from, int to, Class<? extends T[]> type) {
        return Arrays.copyOfRange(original, from, span(from, to), type);
    }

    /** {@code to}, when an array of {@code to - from} elements is allowed. */
    private static int span(int from, int to) {
        if ((long) to - from > ARRAY) throw new ForgeLimit("an array of " + ((long) to - from) + " elements (at most " + ARRAY + ")");
        return to;
    }

    // ── Ranges and builders of streams: a JDK loop without the module's code in it ──────────────────────────────

    public static IntStream range(int from, int to) {
        numbers((long) to - from);
        return IntStream.range(from, to);
    }

    public static IntStream rangeClosed(int from, int to) {
        numbers((long) to - from + 1);
        return IntStream.rangeClosed(from, to);
    }

    public static LongStream range(long from, long to) {
        if (to > from) numbers(to - from < 0 ? Long.MAX_VALUE : to - from);
        return LongStream.range(from, to);
    }

    public static LongStream rangeClosed(long from, long to) {
        if (to >= from) numbers(to - from < 0 || to - from == Long.MAX_VALUE ? Long.MAX_VALUE : to - from + 1);
        return LongStream.rangeClosed(from, to);
    }

    private static void numbers(long count) {
        if (count > ARRAY) throw new ForgeLimit("a range of " + count + " numbers (at most " + ARRAY + ")");
    }

    /** In place of {@code Stream.builder()}: a builder holds as many elements as a collection. */
    public static <T> Stream.Builder<T> builder() {
        return Views.builder();
    }

    public static IntStream.Builder intBuilder() {
        return Views.intBuilder();
    }

    public static LongStream.Builder longBuilder() {
        return Views.longBuilder();
    }

    public static DoubleStream.Builder doubleBuilder() {
        return Views.doubleBuilder();
    }

    // ── Collections: Limits.MAX_ELEMENTS each ───────────────────────────────────────────────────────────────────

    public static <E> boolean add(Collection<E> collection, E element) {
        if (collection.size() >= ELEMENTS && !already(collection, element)) throw full();
        if (collection instanceof Set) hashGuard(element);
        return collection.add(element);
    }

    public static <E> boolean addAll(Collection<E> collection, Collection<? extends E> elements) {
        room(collection, elements.size(), elements);
        if (collection instanceof Set) deep(elements, false);
        return collection.addAll(elements);
    }

    @SafeVarargs
    public static <T> boolean addAll(Collection<? super T> collection, T... elements) {
        room(collection, elements.length, Arrays.asList(elements));
        if (collection instanceof Set) {
            for (T element : elements) hashGuard(element);
        }
        return Collections.addAll(collection, elements);
    }

    public static <E> void add(List<E> list, int index, E element) {
        if (list.size() >= ELEMENTS) throw full();
        list.add(index, element);
    }

    public static <E> boolean addAll(List<E> list, int index, Collection<? extends E> elements) {
        if ((long) list.size() + elements.size() > ELEMENTS) throw full();
        return list.addAll(index, elements);
    }

    public static <E> void addFirst(SequencedCollection<E> collection, E element) {
        if (collection.size() >= ELEMENTS && !already(collection, element)) throw full();
        if (collection instanceof Set) hashGuard(element);
        collection.addFirst(element);
    }

    public static <E> void addLast(SequencedCollection<E> collection, E element) {
        if (collection.size() >= ELEMENTS && !already(collection, element)) throw full();
        if (collection instanceof Set) hashGuard(element);
        collection.addLast(element);
    }

    public static <E> boolean offer(Queue<E> queue, E element) {
        if (queue.size() >= ELEMENTS) throw full();
        return queue.offer(element);
    }

    public static <E> boolean offerFirst(Deque<E> deque, E element) {
        if (deque.size() >= ELEMENTS) throw full();
        return deque.offerFirst(element);
    }

    public static <E> boolean offerLast(Deque<E> deque, E element) {
        if (deque.size() >= ELEMENTS) throw full();
        return deque.offerLast(element);
    }

    public static <E> void push(Deque<E> deque, E element) {
        if (deque.size() >= ELEMENTS) throw full();
        deque.push(element);
    }

    /** In place of {@code List.subList}: adding through the view grows the list it views, whose size is the one to bound. */
    public static <E> List<E> subList(List<E> list, int from, int to) {
        return Views.subList(list, from, to);
    }

    /** In place of {@code List.listIterator()}: {@code ListIterator.add} grows the list. */
    public static <E> ListIterator<E> listIterator(List<E> list) {
        return Views.listIterator(list, list.listIterator());
    }

    public static <E> ListIterator<E> listIterator(List<E> list, int index) {
        return Views.listIterator(list, list.listIterator(index));
    }

    /** In place of {@code Collections.nCopies}: the JDK walks such a list element by element, without the module's code. */
    public static <T> List<T> nCopies(int count, T element) {
        if (count > ELEMENTS) throw full();
        return Collections.nCopies(count, element);
    }

    public static <K, V> V put(Map<K, V> map, K key, V value) {
        if (map.size() >= ELEMENTS && !map.containsKey(key)) throw full();
        hashGuard(key);
        return map.put(key, value);
    }

    public static <K, V> void putAll(Map<K, V> map, Map<? extends K, ? extends V> entries) {
        if ((long) map.size() + entries.size() > ELEMENTS) {
            long added = map.size();
            for (K key : entries.keySet()) {
                if (!map.containsKey(key) && ++added > ELEMENTS) throw full();
            }
        }
        deep(entries.keySet(), false);
        map.putAll(entries);
    }

    public static <K, V> V putIfAbsent(Map<K, V> map, K key, V value) {
        if (map.size() >= ELEMENTS && !map.containsKey(key)) throw full();
        hashGuard(key);
        return map.putIfAbsent(key, value);
    }

    public static <K, V> V merge(Map<K, V> map, K key, V value, BiFunction<? super V, ? super V, ? extends V> remapping) {
        if (map.size() >= ELEMENTS && !map.containsKey(key)) throw full();
        hashGuard(key);
        return map.merge(key, value, remapping);
    }

    public static <K, V> V compute(Map<K, V> map, K key, BiFunction<? super K, ? super V, ? extends V> remapping) {
        if (map.size() >= ELEMENTS && !map.containsKey(key)) throw full();
        hashGuard(key);
        return map.compute(key, remapping);
    }

    public static <K, V> V computeIfAbsent(Map<K, V> map, K key, Function<? super K, ? extends V> mapping) {
        if (map.size() >= ELEMENTS && !map.containsKey(key)) throw full();
        hashGuard(key);
        return map.computeIfAbsent(key, mapping);
    }

    public static <K, V> V putFirst(SequencedMap<K, V> map, K key, V value) {
        if (map.size() >= ELEMENTS && !map.containsKey(key)) throw full();
        hashGuard(key);
        return map.putFirst(key, value);
    }

    public static <K, V> V putLast(SequencedMap<K, V> map, K key, V value) {
        if (map.size() >= ELEMENTS && !map.containsKey(key)) throw full();
        hashGuard(key);
        return map.putLast(key, value);
    }

    /** A set that holds the element already does not grow. */
    private static boolean already(Collection<?> collection, Object element) {
        return collection instanceof Set<?> set && set.contains(element);
    }

    /** Throws unless {@code collection} can take {@code elements}, {@code count} of them (a set only the ones it lacks). */
    private static void room(Collection<?> collection, int count, Collection<?> elements) {
        if ((long) collection.size() + count <= ELEMENTS) return;
        if (!(collection instanceof Set<?> set)) throw full();
        long size = set.size();
        for (Object element : elements) {
            if (!set.contains(element) && ++size > ELEMENTS) throw full();
        }
    }

    static ForgeLimit full() {
        return new ForgeLimit("a collection over " + ELEMENTS + " elements");
    }

    // ── Texts: Limits.MAX_CHARS characters each ─────────────────────────────────────────────────────────────────

    /** In place of {@code String.valueOf(Object)} and {@code Objects.toString(Object)}: the text of a collection, a map, an optional… made within the limit. */
    public static String valueOf(Object value) {
        return Texts.of(value);
    }

    /** In place of {@code value.toString()} on a type of the JDK: the same text, made within the limit. */
    public static String toString(Object value) {
        return Texts.of(Objects.requireNonNull(value));
    }

    /** In place of {@code Objects.toString(Object, String)}. */
    public static String toString(Object value, String nullDefault) {
        return value != null ? Texts.of(value) : nullDefault;
    }

    public static String arrayToString(boolean[] array) {
        return Texts.ofArray(array);
    }

    public static String arrayToString(byte[] array) {
        return Texts.ofArray(array);
    }

    public static String arrayToString(char[] array) {
        return Texts.ofArray(array);
    }

    public static String arrayToString(short[] array) {
        return Texts.ofArray(array);
    }

    public static String arrayToString(int[] array) {
        return Texts.ofArray(array);
    }

    public static String arrayToString(long[] array) {
        return Texts.ofArray(array);
    }

    public static String arrayToString(float[] array) {
        return Texts.ofArray(array);
    }

    public static String arrayToString(double[] array) {
        return Texts.ofArray(array);
    }

    public static String arrayToString(Object[] array) {
        return Texts.ofArray(array);
    }

    public static String deepToString(Object[] array) {
        return Texts.deep(array);
    }

    public static String format(String format, Object... args) {
        return Texts.format(false, null, format, args);
    }

    public static String format(Locale locale, String format, Object... args) {
        return Texts.format(true, locale, format, args);
    }

    public static String formatted(String format, Object... args) {
        return Texts.format(false, null, format, args);
    }

    public static String join(CharSequence delimiter, CharSequence... elements) {
        return Texts.join(delimiter, Arrays.asList(elements));
    }

    public static String join(CharSequence delimiter, Iterable<? extends CharSequence> elements) {
        return Texts.join(delimiter, elements);
    }

    public static Collector<CharSequence, ?, String> joining() {
        return Texts.joining();
    }

    public static Collector<CharSequence, ?, String> joining(CharSequence delimiter) {
        return Texts.joining(delimiter, "", "");
    }

    public static Collector<CharSequence, ?, String> joining(CharSequence delimiter, CharSequence prefix, CharSequence suffix) {
        return Texts.joining(delimiter, prefix, suffix);
    }

    public static String repeat(String text, int count) {
        if (count > 0) Texts.fits((long) text.length() * count);
        return text.repeat(count);
    }

    /** In place of {@code String.indent}: each line gains {@code n} spaces. */
    public static String indent(String text, int n) {
        if (n > 0 && !text.isEmpty()) Texts.fits(text.length() + Texts.lines(text) * ((long) n + 1));
        return text.indent(n);
    }

    public static String concat(String text, String more) {
        Texts.fits((long) text.length() + more.length());
        return text.concat(more);
    }

    /** In place of {@code String.replace(CharSequence, CharSequence)}: each match may grow the text. */
    public static String replace(String text, CharSequence target, CharSequence replacement) {
        String from = target.toString();
        String to = replacement.toString();
        long growth = to.length() - from.length();
        if (growth > 0) {
            long most = from.isEmpty() ? text.length() + 1L : text.length() / from.length();
            if (text.length() + most * growth > CHARS) Texts.fits(text.length() + (from.isEmpty() ? most : Texts.matches(text, from)) * growth);
        }
        return text.replace(from, to);
    }

    public static StringBuilder append(StringBuilder builder, String text) {
        Texts.room(builder, text == null ? 4 : text.length());
        return builder.append(text);
    }

    public static StringBuilder append(StringBuilder builder, Object value) {
        return append(builder, Texts.of(value));
    }

    public static StringBuilder append(StringBuilder builder, CharSequence text) {
        CharSequence trusted = Texts.trusted(text);
        Texts.room(builder, trusted == null ? 4 : trusted.length());
        return builder.append(trusted);
    }

    public static StringBuilder append(StringBuilder builder, CharSequence text, int start, int end) {
        CharSequence trusted = text == null ? "null" : Texts.trusted(text);
        Objects.checkFromToIndex(start, end, trusted.length());
        Texts.room(builder, end - start);
        return builder.append(trusted, start, end);
    }

    public static StringBuilder append(StringBuilder builder, char[] chars) {
        Texts.room(builder, chars.length);
        return builder.append(chars);
    }

    public static StringBuilder append(StringBuilder builder, char[] chars, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, chars.length);
        Texts.room(builder, length);
        return builder.append(chars, offset, length);
    }

    public static StringBuilder append(StringBuilder builder, boolean value) {
        Texts.room(builder, value ? 4 : 5);
        return builder.append(value);
    }

    public static StringBuilder append(StringBuilder builder, char value) {
        Texts.room(builder, 1);
        return builder.append(value);
    }

    public static StringBuilder append(StringBuilder builder, int value) {
        if (builder.length() > CHARS - Texts.INT_CHARS) Texts.room(builder, Integer.toString(value).length());
        return builder.append(value);
    }

    public static StringBuilder append(StringBuilder builder, long value) {
        if (builder.length() > CHARS - Texts.LONG_CHARS) Texts.room(builder, Long.toString(value).length());
        return builder.append(value);
    }

    public static StringBuilder append(StringBuilder builder, float value) {
        if (builder.length() > CHARS - Texts.FLOAT_CHARS) Texts.room(builder, Float.toString(value).length());
        return builder.append(value);
    }

    public static StringBuilder append(StringBuilder builder, double value) {
        if (builder.length() > CHARS - Texts.DOUBLE_CHARS) Texts.room(builder, Double.toString(value).length());
        return builder.append(value);
    }

    public static StringBuilder appendCodePoint(StringBuilder builder, int codePoint) {
        Texts.room(builder, Character.charCount(codePoint));
        return builder.appendCodePoint(codePoint);
    }

    public static StringBuilder insert(StringBuilder builder, int offset, String text) {
        Texts.room(builder, text == null ? 4 : text.length());
        return builder.insert(offset, text);
    }

    public static StringBuilder insert(StringBuilder builder, int offset, Object value) {
        return insert(builder, offset, Texts.of(value));
    }

    public static StringBuilder insert(StringBuilder builder, int offset, CharSequence text) {
        CharSequence trusted = Texts.trusted(text);
        Texts.room(builder, trusted == null ? 4 : trusted.length());
        return builder.insert(offset, trusted);
    }

    public static StringBuilder insert(StringBuilder builder, int offset, CharSequence text, int start, int end) {
        CharSequence trusted = text == null ? "null" : Texts.trusted(text);
        Objects.checkFromToIndex(start, end, trusted.length());
        Texts.room(builder, end - start);
        return builder.insert(offset, trusted, start, end);
    }

    public static StringBuilder insert(StringBuilder builder, int offset, char[] chars) {
        Texts.room(builder, chars.length);
        return builder.insert(offset, chars);
    }

    public static StringBuilder insert(StringBuilder builder, int index, char[] chars, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, chars.length);
        Texts.room(builder, length);
        return builder.insert(index, chars, offset, length);
    }

    public static StringBuilder insert(StringBuilder builder, int offset, boolean value) {
        Texts.room(builder, value ? 4 : 5);
        return builder.insert(offset, value);
    }

    public static StringBuilder insert(StringBuilder builder, int offset, char value) {
        Texts.room(builder, 1);
        return builder.insert(offset, value);
    }

    public static StringBuilder insert(StringBuilder builder, int offset, int value) {
        if (builder.length() > CHARS - Texts.INT_CHARS) Texts.room(builder, Integer.toString(value).length());
        return builder.insert(offset, value);
    }

    public static StringBuilder insert(StringBuilder builder, int offset, long value) {
        if (builder.length() > CHARS - Texts.LONG_CHARS) Texts.room(builder, Long.toString(value).length());
        return builder.insert(offset, value);
    }

    public static StringBuilder insert(StringBuilder builder, int offset, float value) {
        if (builder.length() > CHARS - Texts.FLOAT_CHARS) Texts.room(builder, Float.toString(value).length());
        return builder.insert(offset, value);
    }

    public static StringBuilder insert(StringBuilder builder, int offset, double value) {
        if (builder.length() > CHARS - Texts.DOUBLE_CHARS) Texts.room(builder, Double.toString(value).length());
        return builder.insert(offset, value);
    }

    public static StringBuilder replace(StringBuilder builder, int start, int end, String text) {
        int length = builder.length();
        if (start >= 0 && start <= length && start <= end) Texts.fits((long) length - (Math.min(end, length) - start) + text.length());
        return builder.replace(start, end, text);
    }

    public static void setLength(StringBuilder builder, int length) {
        Texts.fits(length);
        builder.setLength(length);
    }

    public static void ensureCapacity(StringBuilder builder, int capacity) {
        builder.ensureCapacity(capacity(capacity));
    }

    public static StringBuilder repeat(StringBuilder builder, int codePoint, int count) {
        if (count > 0) Texts.room(builder, (long) Character.charCount(codePoint) * count);
        return builder.repeat(codePoint, count);
    }

    public static StringBuilder repeat(StringBuilder builder, CharSequence text, int count) {
        CharSequence trusted = text == null ? "null" : Texts.trusted(text);
        if (count > 0) Texts.room(builder, (long) trusted.length() * count);
        return builder.repeat(trusted, count);
    }

    /** In place of {@code StringJoiner.add}: the joiner's text is made whole by its {@code toString}. */
    public static StringJoiner add(StringJoiner joiner, CharSequence element) {
        return Texts.add(joiner, element);
    }

    public static StringJoiner merge(StringJoiner joiner, StringJoiner other) {
        return Texts.merge(joiner, other);
    }

    /** The text of a concatenation or a record, checked. */
    static String text(String text) {
        Texts.fits(text.length());
        return text;
    }

    // ── Deep hashing and equality: a single JDK call the meter cannot see ─────────────────────────────────────────

    /**
     * Element slots (counted with multiplicity, as the JDK's own {@code hashCode} and {@code equals} count them) one
     * deep hash or comparison may visit: past this it throws, before the JDK does the work. The bound (M5-05's open
     * finding): a size-bounded but <em>shared</em> structure — a list of 10,000 references to one list of 10,000, whose
     * {@code hashCode} walks 10^8 element slots in one uninterruptible call of the JDK — never runs for more than a few
     * milliseconds. A non-shared structure a module can build and retain fits in 16-32 MiB, so at most ~2 million slots;
     * this cap is well above that and reached in about 3.5 ms (JDK 25), far under a call's budget.
     */
    static final int DEEP_VISITS = 4_000_000;

    /**
     * Walks [value] the way the JDK's {@code hashCode}/{@code equals} would, counting the element slots it would visit
     * (with multiplicity: a shared child is counted every time it is reached, as the JDK re-hashes it), and throws a
     * {@link ForgeLimit} past {@link #DEEP_VISITS} — before the JDK call that would do it uninterruptibly. Never calls a
     * module object's own method (opacity, M5 §4.1): collections, maps, optionals and atomic references of the JDK are
     * walked; everything else (a module's record, an enum, a box, a string) is a leaf. Arrays are leaves unless
     * [arrays] (for {@code Arrays.deepHashCode}/{@code deepEquals}, which descend into nested arrays).
     */
    private static void deep(Object value, boolean arrays) {
        if (!composite(value, arrays)) return;
        long visited = 0;
        ArrayDeque<Object> stack = new ArrayDeque<>();
        stack.push(value);
        while (!stack.isEmpty()) {
            Object node = stack.pop();
            if (node instanceof Collection<?> collection) {
                for (Object element : collection) {
                    if (++visited > DEEP_VISITS) throw deepLimit();
                    if (composite(element, arrays)) stack.push(element);
                }
            } else if (node instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if ((visited += 2) > DEEP_VISITS) throw deepLimit();
                    if (composite(entry.getKey(), arrays)) stack.push(entry.getKey());
                    if (composite(entry.getValue(), arrays)) stack.push(entry.getValue());
                }
            } else if (node instanceof Object[] array) {
                for (Object element : array) {
                    if (++visited > DEEP_VISITS) throw deepLimit();
                    if (composite(element, arrays)) stack.push(element);
                }
            } else if (node.getClass().isArray()) {
                if ((visited += Array.getLength(node)) > DEEP_VISITS) throw deepLimit();
            } else if (node instanceof Optional<?> optional) {
                if (++visited > DEEP_VISITS) throw deepLimit();
                if (optional.isPresent() && composite(optional.get(), arrays)) stack.push(optional.get());
            } else if (node instanceof AtomicReference<?> reference) {
                if (++visited > DEEP_VISITS) throw deepLimit();
                Object held = reference.get();
                if (composite(held, arrays)) stack.push(held);
            } else if (node instanceof Map.Entry<?, ?> entry) {
                if ((visited += 2) > DEEP_VISITS) throw deepLimit();
                if (composite(entry.getKey(), arrays)) stack.push(entry.getKey());
                if (composite(entry.getValue(), arrays)) stack.push(entry.getValue());
            }
        }
    }

    /** Whether the JDK walks [value] when it hashes or compares it: a collection, a map, an optional, an atomic, a JDK map entry, and (only [arrays]) an array. A module's class is never a collection, a map nor a CharSequence (the guard forbids it), so every one here is the JDK's own. */
    private static boolean composite(Object value, boolean arrays) {
        if (value == null) return false;
        if (value instanceof Collection || value instanceof Map || value instanceof Optional || value instanceof AtomicReference) return true;
        if (value instanceof Map.Entry && value.getClass().getClassLoader() == null) return true;
        return arrays && value.getClass().isArray();
    }

    private static ForgeLimit deepLimit() {
        return new ForgeLimit("a structure too large to hash or compare in one call (over " + DEEP_VISITS + " nested elements): it is shared or nested too deeply");
    }

    /** In place of {@code value.hashCode()} and {@code Objects.hashCode(value)} on the JDK's own types: bounded first. */
    public static int hashCode(Object value) {
        deep(value, false);
        return value == null ? 0 : value.hashCode();
    }

    /** In place of {@code a.equals(b)} and {@code Objects.equals(a, b)}: both operands bounded first. */
    public static boolean equals(Object a, Object b) {
        deep(a, false);
        deep(b, false);
        return Objects.equals(a, b);
    }

    /** In place of {@code Objects.hash(values)}: each value bounded first. */
    public static int hash(Object... values) {
        if (values != null) {
            for (Object value : values) deep(value, false);
        }
        return Arrays.hashCode(values);
    }

    /** In place of {@code Objects.deepEquals(a, b)}. */
    public static boolean objectsDeepEquals(Object a, Object b) {
        deep(a, true);
        deep(b, true);
        return Objects.deepEquals(a, b);
    }

    public static boolean containsAll(Collection<?> collection, Collection<?> other) {
        deep(collection, false);
        deep(other, false);
        return collection.containsAll(other);
    }

    public static boolean removeAll(Collection<?> collection, Collection<?> other) {
        deep(collection, false);
        deep(other, false);
        return collection.removeAll(other);
    }

    public static boolean retainAll(Collection<?> collection, Collection<?> other) {
        deep(collection, false);
        deep(other, false);
        return collection.retainAll(other);
    }

    /** In place of {@code collection.contains(element)}: the probed element bounded (its hash, or its comparisons). */
    public static boolean contains(Collection<?> collection, Object element) {
        deep(element, false);
        return collection.contains(element);
    }

    public static boolean containsKey(Map<?, ?> map, Object key) {
        deep(key, false);
        return map.containsKey(key);
    }

    public static Object get(Map<?, ?> map, Object key) {
        deep(key, false);
        return map.get(key);
    }

    /** In place of {@code Arrays.hashCode(Object[])}: nested arrays are hashed by identity (as the JDK does), composites by value. */
    public static int arraysHashCode(Object[] array) {
        if (array != null) {
            for (Object element : array) deep(element, false);
        }
        return Arrays.hashCode(array);
    }

    /** In place of {@code Arrays.deepHashCode(Object[])}: it descends into nested arrays and composites. */
    public static int deepHashCode(Object[] array) {
        deep(array, true);
        return Arrays.deepHashCode(array);
    }

    public static boolean arraysEquals(Object[] a, Object[] b) {
        if (a != null) {
            for (Object element : a) deep(element, false);
        }
        if (b != null) {
            for (Object element : b) deep(element, false);
        }
        return Arrays.equals(a, b);
    }

    public static boolean arraysDeepEquals(Object[] a, Object[] b) {
        deep(a, true);
        deep(b, true);
        return Arrays.deepEquals(a, b);
    }

    /** Bounds a composite key or element the JDK is about to hash (a list put as a key, a map added to a set). */
    private static void hashGuard(Object keyOrElement) {
        deep(keyOrElement, false);
    }

    /** Filters a record component through {@link #deep}, for the record's {@code hashCode}/{@code equals} bootstrap. */
    public static Object component(Object value) {
        deep(value, false);
        return value;
    }

    // ── Bootstraps ──────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * In place of {@code StringConcatFactory.makeConcatWithConstants}: the same concatenation, but each object in it
     * becomes its text through {@link #valueOf} (a map in {@code "Scores: " + map} is no longer made whole by the
     * JDK), and the result is checked.
     */
    public static CallSite concatenation(MethodHandles.Lookup lookup, String name, MethodType type, String recipe, Object... constants)
        throws StringConcatException {
        MethodType texts = type;
        for (int i = 0; i < type.parameterCount(); i++) {
            if (Handles.objectText(type.parameterType(i))) texts = texts.changeParameterType(i, String.class);
        }
        MethodHandle concat = StringConcatFactory.makeConcatWithConstants(lookup, name, texts, recipe, constants).getTarget();
        for (int i = 0; i < type.parameterCount(); i++) {
            if (Handles.objectText(type.parameterType(i))) concat = MethodHandles.filterArguments(concat, i, Handles.valueOf(type.parameterType(i)));
        }
        return new ConstantCallSite(MethodHandles.filterReturnValue(concat, Handles.TEXT));
    }

    /**
     * In place of {@code ObjectMethods.bootstrap} for a record's {@code toString}: each component that is an object
     * becomes its text through {@link #valueOf}, and the result is checked. {@code equals} and {@code hashCode} are
     * left to the JDK.
     */
    public static Object record(MethodHandles.Lookup lookup, String name, TypeDescriptor type, Class<?> recordClass, String names,
                                MethodHandle... getters) throws Throwable {
        if ("toString".equals(name)) {
            MethodHandle[] texts = getters.clone();
            for (int i = 0; i < getters.length; i++) {
                Class<?> component = getters[i].type().returnType();
                if (Handles.objectText(component)) texts[i] = MethodHandles.filterReturnValue(getters[i], Handles.valueOf(component));
            }
            Object made = ObjectMethods.bootstrap(lookup, name, type, recordClass, names, texts);
            if (made instanceof CallSite site) return new ConstantCallSite(MethodHandles.filterReturnValue(site.getTarget(), Handles.TEXT));
            return MethodHandles.filterReturnValue((MethodHandle) made, Handles.TEXT);
        }
        if ("hashCode".equals(name) || "equals".equals(name)) {
            // Each composite component is bounded before the record's own hashCode/equals reaches into it (a record
            // wrapping a shared structure would otherwise hash it in one uninterruptible call; M5-04 finding, M5-18).
            MethodHandle[] bounded = getters.clone();
            for (int i = 0; i < getters.length; i++) {
                Class<?> component = getters[i].type().returnType();
                if (!component.isPrimitive()) bounded[i] = MethodHandles.filterReturnValue(getters[i], Handles.component(component));
            }
            return ObjectMethods.bootstrap(lookup, name, type, recordClass, names, bounded);
        }
        return ObjectMethods.bootstrap(lookup, name, type, recordClass, names, getters);
    }

    private static final class Handles {
        static final MethodHandle VALUE_OF;
        static final MethodHandle TEXT;
        static final MethodHandle COMPONENT;

        static {
            try {
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                VALUE_OF = lookup.findStatic(Guarded.class, "valueOf", MethodType.methodType(String.class, Object.class));
                TEXT = lookup.findStatic(Guarded.class, "text", MethodType.methodType(String.class, String.class));
                COMPONENT = lookup.findStatic(Guarded.class, "component", MethodType.methodType(Object.class, Object.class));
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        /** Whether the JDK would make a text of a value of this type by its {@code toString} (a primitive's and a box's are short). */
        static boolean objectText(Class<?> type) {
            return !type.isPrimitive() && type != String.class && type != Integer.class && type != Long.class && type != Double.class
                && type != Float.class && type != Short.class && type != Byte.class && type != Character.class && type != Boolean.class;
        }

        static MethodHandle valueOf(Class<?> type) {
            return VALUE_OF.asType(MethodType.methodType(String.class, type));
        }

        static MethodHandle component(Class<?> type) {
            return COMPONENT.asType(MethodType.methodType(type, type));
        }
    }
}
