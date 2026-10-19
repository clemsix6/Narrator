package io.github.clemsix6.narrator2.forge.rt;

import java.util.Collection;
import java.util.Map;

/**
 * The call-site guards of the rewritten bytecode (M5 §3.4): the rewriting sends each call of a module that allocates
 * or grows something through here first, and each throws a {@link ForgeLimit} before changing anything. The meter
 * cannot bound these: it is only sampled at the module's own ticks, and one call of the JDK allocates at once.
 *
 * <p>Task M5-04 owns this class (its methods and their bodies; until then they limit nothing) and must cover, for the
 * types of the allow-list (task M5-03 lists the same members as rewritten):
 * <ul>
 *   <li>arrays: {@code newarray}, {@code anewarray} ({@code Limits.MAX_ARRAY});</li>
 *   <li>growth, 10,000 elements a collection ({@code Limits.MAX_ELEMENTS}): {@code add}, {@code addAll},
 *       {@code addFirst}, {@code addLast}, {@code offer…}, {@code push}, {@code put}, {@code putAll},
 *       {@code putIfAbsent}, {@code merge}, {@code compute…}, {@code Collections.addAll}, {@code ListIterator.add};</li>
 *   <li>texts, 65,536 characters ({@code Limits.MAX_CHARS}): every concatenation, {@code StringBuilder.append} and
 *       {@code insert} (a {@code CharSequence} a module implements may say any length), {@code String.repeat},
 *       {@code String.format}/{@code formatted} (width and precision of 3 digits at most);</li>
 *   <li>capacities, 65,536 at most ({@code Limits.MAX_ARRAY}), checked before the call: {@code ArrayList(int)},
 *       {@code ArrayList.ensureCapacity}, {@code ArrayDeque(int)}, {@code PriorityQueue(int…)},
 *       {@code StringBuilder(int)}, {@code StringBuilder(CharSequence)}, {@code StringBuilder.ensureCapacity},
 *       {@code setLength}, {@code StringBuilder.repeat}, {@code String.indent}, and the hash tables, which allocate
 *       their whole table at their first {@code put}: {@code HashMap(int…)}, {@code LinkedHashMap(int…)},
 *       {@code HashSet(int…)}, {@code LinkedHashSet(int…)}, {@code HashMap.newHashMap},
 *       {@code LinkedHashMap.newLinkedHashMap}, {@code HashSet.newHashSet}, {@code LinkedHashSet.newLinkedHashSet}
 *       (M5-01 handoff: measured, 134 MB for 20 million);</li>
 *   <li>copies and ranges: {@code Arrays.copyOf}, {@code copyOfRange}, {@code IntStream}/{@code LongStream}
 *       {@code range}/{@code rangeClosed};</li>
 *   <li>texts made of a whole collection, whose size is the product of two limits (10,000 strings of 65,536
 *       characters: 655 million characters in one call): {@code toString} of a collection, a map or an array,
 *       {@code String.valueOf(Object)}, {@code Objects.toString}, {@code StringBuilder.append(Object)}, the objects
 *       of a concatenation, {@code String.join}, {@code Collectors.joining}, {@code Arrays.toString} (M5-01 handoff).</li>
 * </ul>
 */
public final class Guarded {
    private Guarded() {}

    /** Before {@code newarray} and {@code anewarray}: the length, unchanged when allowed. */
    public static int array(int length) {
        return length;
    }

    /** Before a call that allocates or reserves {@code capacity} elements or characters at once: the capacity, unchanged when allowed. */
    public static int capacity(int capacity) {
        return capacity;
    }

    /** After each concatenation of the module: the text, unchanged when allowed. */
    public static String text(String text) {
        return text;
    }

    /** In place of {@code collection.add(element)}. */
    public static boolean add(Collection<Object> collection, Object element) {
        return collection.add(element);
    }

    /** In place of {@code map.put(key, value)}. */
    public static Object put(Map<Object, Object> map, Object key, Object value) {
        return map.put(key, value);
    }

    /** In place of {@code builder.append(text)}. */
    public static StringBuilder append(StringBuilder builder, String text) {
        return builder.append(text);
    }
}
