@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package io.github.clemsix6.narrator2.forge.rt

import io.github.clemsix6.narrator2.forge.api.Limits
import org.junit.jupiter.api.Test
import java.util.AbstractMap
import java.util.ArrayDeque
import java.util.Locale
import java.util.Optional
import java.util.PriorityQueue
import java.util.StringJoiner
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicReference
import java.util.stream.Collectors
import java.util.stream.Stream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The call-site guards on their own (M5 §3.4): below their limits they give exactly what the JDK gives, at their limit
 * they throw before anything changes.
 */
class GuardedTest {
    private val chars = Limits.MAX_CHARS
    private val elements = Limits.MAX_ELEMENTS

    private fun full(size: Int = elements) = java.util.ArrayList<Int>((0 until size).toList())

    @Test
    fun `the text of a composite is the JDK's own, within the limit`() {
        val self = java.util.ArrayList<Any>().apply { add(1); add(this) }
        val selfMap = java.util.HashMap<Any, Any>().apply { put("me", this) }
        val samples: List<Any?> = listOf(
            null, "text", 42, 'c', 1.5, listOf(1, 2), listOf(listOf("a", null), emptyList<Any>()), mapOf("a" to listOf(1), "b" to mapOf(1 to 2)),
            Optional.of(listOf("x")), Optional.empty<Any>(), AtomicReference(listOf(1)), AbstractMap.SimpleEntry("k", listOf(1)), self, selfMap,
            ArrayDeque(listOf(3, 1)), PriorityQueue(listOf(3, 1, 2)), TreeMap(mapOf(2 to "b", 1 to "a")).descendingMap(), java.util.List.of(1, 2, 3).reversed(),
            java.util.Collections.unmodifiableMap(mapOf("u" to setOf(1))), java.util.EnumSet.of(Thread.State.NEW), mapOf(1 to 2).entries.first(), arrayOf(1, 2),
        )
        for (sample in samples) assertEquals(java.lang.String.valueOf(sample as Any?), Guarded.valueOf(sample), "$sample")
        assertEquals("null", Guarded.valueOf(null))
        assertFailsWith<NullPointerException> { Guarded.toString(null) }
        assertEquals("none", Guarded.toString(null, "none"))
    }

    @Test
    fun `the text of a composite stops at the limit, however it nests`() {
        val big = "x".repeat(chars)
        assertFailsWith<ForgeLimit> { Guarded.valueOf(listOf(big)) }
        val shared = java.util.ArrayList<Any>()
        repeat(elements) { shared.add(java.util.ArrayList<Any>()) }
        var nested: Any = shared
        repeat(3) { nested = java.util.ArrayList(java.util.Collections.nCopies(elements, nested)) }
        assertEquals("a text over $chars characters", assertFailsWith<ForgeLimit> { Guarded.valueOf(nested) }.message)
        var deep: Any = listOf<Any>()
        repeat(Limits.MAX_DEPTH + 1) { deep = listOf(deep) }
        assertFailsWith<ForgeLimit> { Guarded.valueOf(deep) }
    }

    @Test
    fun `arrays have the JDK's text`() {
        assertEquals(java.util.Arrays.toString(intArrayOf(3, 1)), Guarded.arrayToString(intArrayOf(3, 1)))
        assertEquals(java.util.Arrays.toString(doubleArrayOf(1.5)), Guarded.arrayToString(doubleArrayOf(1.5)))
        assertEquals(java.util.Arrays.toString(charArrayOf('a')), Guarded.arrayToString(charArrayOf('a')))
        assertEquals(java.util.Arrays.toString(booleanArrayOf(true)), Guarded.arrayToString(booleanArrayOf(true)))
        assertEquals(java.util.Arrays.toString(longArrayOf()), Guarded.arrayToString(longArrayOf()))
        assertEquals(java.util.Arrays.toString(null as IntArray?), Guarded.arrayToString(null as IntArray?))
        val objects = arrayOf<Any?>(listOf(1), null, intArrayOf(1))
        assertEquals(java.util.Arrays.toString(objects), Guarded.arrayToString(objects))
        val cyclic = arrayOfNulls<Any>(3)
        cyclic[0] = cyclic
        cyclic[1] = intArrayOf(1, 2)
        cyclic[2] = arrayOf<Any>("x", listOf(1))
        assertEquals(java.util.Arrays.deepToString(cyclic), Guarded.deepToString(cyclic))
        assertFailsWith<ForgeLimit> { Guarded.arrayToString(IntArray(Limits.MAX_ARRAY)) }
    }

    @Test
    fun `format and formatted give the JDK's text, with widths of three digits at most`() {
        val cases = listOf(
            "%5.2f|%-6s|%03d|%,d|%x|%%|%n|%b|%c|%10.3e" to arrayOf<Any?>(Math.E, "ok", 7, 1234567, 255, null, 'z', 12345.678),
            "%2\$s %1\$s %<s" to arrayOf<Any?>("a", listOf(1, 2)),
            "%s and %S" to arrayOf<Any?>(mapOf("k" to listOf(1)), Optional.of("v")),
            "%999s|%.999f" to arrayOf<Any?>("x", 1.0),
        )
        for ((format, args) in cases) {
            assertEquals(String.format(format, *args), Guarded.format(format, *args), format)
            assertEquals(String.format(Locale.FRANCE, format, *args), Guarded.format(Locale.FRANCE, format, *args), format)
            assertEquals(String.format(format, *args), Guarded.formatted(format, *args), format)
        }
        for (wide in listOf("%1000s", "%.1000f", "%01000d", "%-1000s", "%1\$1000s", "%,1000d")) {
            assertEquals("a format width or precision over 3 digits", assertFailsWith<ForgeLimit> { Guarded.format(wide, 1) }.message, wide)
        }
        assertFailsWith<ForgeLimit> { Guarded.format("%999s".repeat(70), *Array(70) { "x" }) }
        assertFailsWith<java.util.UnknownFormatConversionException> { Guarded.format("%q", 1) }
    }

    @Test
    fun `joins and joiners give the JDK's text within the limit`() {
        val parts = listOf("a", "bb", "", "c")
        assertEquals(java.lang.String.join(", ", parts), Guarded.join(", ", parts))
        assertEquals(java.lang.String.join("-", "x", null, "z"), Guarded.join("-", "x", null, "z"))
        assertEquals(parts.stream().collect(Collectors.joining()), parts.stream().collect(Guarded.joining()))
        assertEquals(parts.stream().collect(Collectors.joining("/")), parts.stream().collect(Guarded.joining("/")))
        assertEquals(parts.stream().collect(Collectors.joining(",", "<", ">")), parts.stream().collect(Guarded.joining(",", "<", ">")))
        val joiner = StringJoiner(",", "{", "}")
        Guarded.add(Guarded.add(joiner, "a"), "b")
        Guarded.merge(joiner, StringJoiner("+").add("c").add("d"))
        assertEquals("{a,b,c+d}", joiner.toString())
        val half = "y".repeat(chars / 2)
        assertFailsWith<ForgeLimit> { Guarded.join("", listOf(half, half, "z")) }
        assertFailsWith<ForgeLimit> { Stream.of(half, half, "z").collect(Guarded.joining()) }
        assertFailsWith<ForgeLimit> { Guarded.add(Guarded.add(StringJoiner("x".repeat(chars)), ""), "y") }
    }

    @Test
    fun `texts of the JDK give its result below the limit and throw above`() {
        for (text in listOf("", "a", "a\nb", "a\r\nb\rc\n", "\n\n", "  x\n y")) {
            for (n in listOf(-3, 0, 2, Int.MIN_VALUE)) assertEquals((text as java.lang.String).indent(n), Guarded.indent(text, n), "« $text » $n")
        }
        assertFailsWith<ForgeLimit> { Guarded.indent("a\n".repeat(chars / 4), 3) }
        assertEquals("abc".replace("bc", "bcbc"), Guarded.replace("abc", "bc", "bcbc"))
        assertEquals("a-b".replace("", "."), Guarded.replace("a-b", "", "."))
        assertEquals("x".repeat(chars), Guarded.replace("y".repeat(chars), "y", "x"))
        assertFailsWith<ForgeLimit> { Guarded.replace("y".repeat(chars), "y", "xx") }
        assertEquals("aa", Guarded.repeat("a", 2))
        assertFailsWith<IllegalArgumentException> { Guarded.repeat("a", -1) }
        assertFailsWith<ForgeLimit> { Guarded.repeat("ab", chars / 2 + 1) }
        assertEquals("ab", Guarded.concat("a", "b"))
        assertFailsWith<ForgeLimit> { Guarded.concat("x".repeat(chars), "y") }
    }

    @Test
    fun `a builder grows to the limit, not one character past it`() {
        val builder = StringBuilder("x".repeat(chars - 1))
        Guarded.append(builder, 'y')
        assertEquals(chars, builder.length)
        for (append in listOf<(StringBuilder) -> Unit>(
            { Guarded.append(it, 'z') }, { Guarded.append(it, 1) }, { Guarded.append(it, 1L) }, { Guarded.append(it, 1f) }, { Guarded.append(it, 1.0) },
            { Guarded.append(it, true) }, { Guarded.append(it, "s") }, { Guarded.append(it, null as String?) }, { Guarded.append(it, listOf(1) as Any) },
            { Guarded.append(it, StringBuilder("c") as CharSequence) }, { Guarded.append(it, "abc" as CharSequence, 0, 1) }, { Guarded.append(it, charArrayOf('c')) },
            { Guarded.appendCodePoint(it, 0x1F600) }, { Guarded.insert(it, 0, "s") }, { Guarded.insert(it, 0, 7) }, { Guarded.insert(it, 0, 'c') },
            { Guarded.insert(it, 0, listOf(1) as Any) }, { Guarded.replace(it, 0, 0, "s") }, { Guarded.repeat(it, 'c'.code, 1) }, { Guarded.repeat(it, "ab", 1) },
            { Guarded.setLength(it, chars + 1) },
        )) {
            assertFailsWith<ForgeLimit> { append(builder) }
            assertEquals(chars, builder.length, "nothing changed")
        }
        val numbers = StringBuilder("x".repeat(chars - 11))
        Guarded.append(numbers, Int.MIN_VALUE)
        assertEquals(chars, numbers.length, "an int's eleven characters fit exactly")
        Guarded.replace(builder, 0, 2, "ab")
        assertFailsWith<StringIndexOutOfBoundsException> { Guarded.insert(StringBuilder(), 5, "x") }
        assertFailsWith<IndexOutOfBoundsException> { Guarded.append(StringBuilder(), "abc" as CharSequence, 2, 9) }
    }

    @Test
    fun `a CharSequence whose length lies is read once, within the limit`() {
        val lying = object : CharSequence {
            override val length: Int get() = 500_000_000
            override fun get(index: Int) = 'x'
            override fun subSequence(startIndex: Int, endIndex: Int) = this
            override fun toString() = "lie"
        }
        assertFailsWith<ForgeLimit> { Guarded.trusted(lying) }
        assertFailsWith<ForgeLimit> { Guarded.append(StringBuilder(), lying) }
        assertFailsWith<ForgeLimit> { Guarded.insert(StringBuilder(), 0, lying) }
        val honest = object : CharSequence {
            override val length: Int get() = 3
            override fun get(index: Int) = "abc"[index]
            override fun subSequence(startIndex: Int, endIndex: Int) = "abc".subSequence(startIndex, endIndex)
            override fun toString() = "not the content"
        }
        assertEquals("abc", Guarded.append(StringBuilder(), honest).toString(), "read through charAt, as the JDK reads it")
    }

    @Test
    fun `collections grow to their limit, and a set or a map past it only by what it already holds`() {
        val list = full(elements - 1)
        assertTrue(Guarded.add(list, 0))
        assertEquals("a collection over $elements elements", assertFailsWith<ForgeLimit> { Guarded.add(list, 1) }.message)
        assertFailsWith<ForgeLimit> { Guarded.add(list, 0, 1) }
        assertFailsWith<ForgeLimit> { Guarded.addAll(list, listOf(1)) }
        assertFailsWith<ForgeLimit> { Guarded.addFirst(list as java.util.SequencedCollection<Int>, 1) }
        assertEquals(elements, list.size)

        val set = java.util.LinkedHashSet<Int>((0 until elements).toList())
        assertTrue(!Guarded.add(set, 5), "already held: no growth")
        assertTrue(!Guarded.addAll(set, listOf(1, 2, 3)))
        Guarded.addFirst(set, 7)
        assertFailsWith<ForgeLimit> { Guarded.addAll(set, listOf(1, -1)) }
        assertFailsWith<ForgeLimit> { Guarded.add(set, -1) }

        val map = java.util.HashMap<Int, Int>((0 until elements).associateWith { it })
        Guarded.put(map, 1, 2)
        Guarded.merge(map, 1, 1) { a, b -> a + b }
        Guarded.compute(map, 2) { _, v -> v }
        Guarded.putAll(map, mapOf(3 to 3, 4 to 4))
        assertFailsWith<ForgeLimit> { Guarded.put(map, -1, 0) }
        assertFailsWith<ForgeLimit> { Guarded.putAll(map, mapOf(3 to 3, -1 to 0)) }
        assertFailsWith<ForgeLimit> { Guarded.computeIfAbsent(map, -1) { 0 } }
        assertEquals(elements, map.size)

        val queue = ArrayDeque((0 until elements).toList())
        assertFailsWith<ForgeLimit> { Guarded.push(queue, 1) }
        assertFailsWith<ForgeLimit> { Guarded.offer(queue, 1) }
        assertFailsWith<ForgeLimit> { Guarded.addAll(java.util.ArrayList<Int>(), *Array(elements + 1) { it }) }
        assertEquals(10, Guarded.nCopies(10, "x").size)
    }

    @Test
    fun `the hash and equality of a shared structure are bounded, in one call, before the JDK does them`() {
        // L1 = 10,000 references to one list of 10,000: a hashCode walks 10^8 element slots in one JDK call.
        val l0 = java.util.ArrayList<Any?>((0 until 10_000).toList())
        val l1 = java.util.ArrayList<Any?>().apply { repeat(10_000) { add(l0) } }
        val l1b = java.util.ArrayList<Any?>(l1)
        // Explicit hash and equality, and the collection operations the design names.
        assertFailsWith<ForgeLimit> { Guarded.hashCode(l1) }
        assertFailsWith<ForgeLimit> { Guarded.equals(l1, l1b) }
        assertFailsWith<ForgeLimit> { Guarded.hash(l1) }
        assertFailsWith<ForgeLimit> { Guarded.containsAll(l1, l1b) }
        assertFailsWith<ForgeLimit> { Guarded.removeAll(java.util.ArrayList(l1), l1b) }
        assertFailsWith<ForgeLimit> { Guarded.retainAll(java.util.ArrayList(l1), l1b) }
        assertFailsWith<ForgeLimit> { Guarded.contains(java.util.HashSet<Any?>(), l1) }
        // The implicit paths: a list as a key of a map, or an element of a set.
        assertFailsWith<ForgeLimit> { Guarded.put(java.util.HashMap<Any, Int>(), l1, 1) }
        assertFailsWith<ForgeLimit> { Guarded.add(java.util.HashSet<Any>(), l1) }
        assertFailsWith<ForgeLimit> { Guarded.containsKey(java.util.HashMap<Any, Int>(), l1) }
        // Arrays: deepHashCode and deepEquals descend into nested arrays.
        val a0 = IntArray(10_000)
        val a1 = Array<Any>(10_000) { a0 }
        val a2 = Array<Any>(10_000) { a1 }
        assertFailsWith<ForgeLimit> { Guarded.deepHashCode(a2) }
        assertFailsWith<ForgeLimit> { Guarded.objectsDeepEquals(a2, a2.clone()) }

        // A plain structure, non-shared and within what a module can build, is hashed and compared as the JDK does.
        val small = java.util.ArrayList<Any?>((0 until 1_000).toList())
        assertEquals(small.hashCode(), Guarded.hashCode(small))
        assertTrue(Guarded.equals(small, java.util.ArrayList<Any?>(small)))
        assertEquals(0, Guarded.hashCode(null))
        assertEquals("x".hashCode(), Guarded.hashCode("x"))
        assertTrue(Guarded.add(java.util.HashSet<Any?>(), listOf(1, 2, 3)), "a small list is a fine set element")
    }

    @Test
    fun `the copy constructors, immutable factories and lookups bound a shared structure before the JDK hashes it`() {
        // L1 = 10,000 references to one list of 10,000: hashing it whole walks 10^8 slots in one JDK call (M5-FIX-2).
        val l0 = java.util.ArrayList<Any?>((0 until 10_000).toList())
        fun l1() = java.util.ArrayList<Any?>().apply { repeat(10_000) { add(l0) } }
        val l1 = l1()
        // Copy constructors: the collection or the map's keys, bounded before the ctor re-hashes them.
        assertFailsWith<ForgeLimit> { Guarded.hashedElements(l1) }
        assertFailsWith<ForgeLimit> { Guarded.hashedKeys(java.util.Collections.singletonMap<Any?, Int>(l1, 1)) }
        assertFailsWith<ForgeLimit> { Guarded.clone(java.util.HashSet<Any?>().apply { add(l1()) }) }
        // Immutable factories: Set.of / Map.of / copyOf, each element or key bounded.
        assertFailsWith<ForgeLimit> { Guarded.setOf(l1, "a", "b") }
        assertFailsWith<ForgeLimit> { Guarded.setOf(*arrayOf<Any?>(l1, "a")) }
        assertFailsWith<ForgeLimit> { Guarded.setCopyOf(java.util.List.of(l1, java.util.List.of(1))) }
        assertFailsWith<ForgeLimit> { Guarded.mapOf(l1, 1, "a", 2) }
        assertFailsWith<ForgeLimit> { Guarded.mapOfEntries(java.util.Map.entry(l1 as Any, 1 as Any)) }
        assertFailsWith<ForgeLimit> { Guarded.mapCopyOf(java.util.Collections.singletonMap<Any?, Int>(l1, 1)) }
        // Lookups and removals: one probe hashed or compared, like contains/get (M5-18).
        assertFailsWith<ForgeLimit> { Guarded.remove(java.util.HashSet<Any?>(), l1) }
        assertFailsWith<ForgeLimit> { Guarded.remove(java.util.HashMap<Any?, Int>(), l1) }
        assertFailsWith<ForgeLimit> { Guarded.getOrDefault(java.util.HashMap<Any?, Int>(), l1, 0) }
        assertFailsWith<ForgeLimit> { Guarded.computeIfPresent(java.util.HashMap<Any?, Int>(), l1) { _, v -> v } }
        assertFailsWith<ForgeLimit> { Guarded.containsValue(java.util.Collections.singletonMap(1, l1 as Any?), l1) }
        assertFailsWith<ForgeLimit> { Guarded.indexOf(java.util.ArrayList(listOf(l1b())), l1) }
        assertFailsWith<ForgeLimit> { Guarded.frequency(java.util.ArrayList(listOf(l1b())), l1) }
        assertFailsWith<ForgeLimit> { Guarded.disjoint(java.util.ArrayList(listOf(l1)), java.util.HashSet<Any?>(listOf(1))) }

        // Normal use: a small set, map or lookup is the JDK's own, cheap.
        assertEquals(java.util.Set.of("a", "b"), Guarded.setOf("a", "b"))
        assertEquals(java.util.Map.of("a", 1, "b", 2), Guarded.mapOf("a", 1, "b", 2))
        assertEquals(java.util.Set.copyOf(listOf(1, 2, 3)), Guarded.setCopyOf(listOf(1, 2, 3)))
        assertEquals(setOf(listOf(1), listOf(2)), Guarded.setOf(listOf(1), listOf(2)), "small composites are fine elements")
        val table = java.util.HashMap<Any?, Int>().apply { put("k", 1) }
        assertEquals(1, Guarded.remove(table, "k"))
        assertEquals(2, (Guarded.clone(java.util.HashSet<Any?>(listOf(1, 2))) as java.util.HashSet<*>).size)
        assertEquals(7, Guarded.getOrDefault(java.util.HashMap<Any?, Int>(), listOf(1), 7))
        // A duplicate composite: refused as the JDK refuses it, its text within the limit.
        assertEquals("duplicate element: [1]", assertFailsWith<IllegalArgumentException> { Guarded.setOf(listOf(1), listOf(1)) }.message)
        assertEquals("duplicate key: [1]", assertFailsWith<IllegalArgumentException> { Guarded.mapOf(listOf(1), 1, listOf(1), 2) }.message)
        // The JDK's message for a duplicate holds its whole text: 1,000 texts of 65,000 characters would make one of
        // 65 million in one call. Here the text is made within the limit, so the refusal is the limit's.
        val chunk = "x".repeat(65_000)
        val texts = java.util.ArrayList<String>().apply { repeat(1_000) { add(chunk) } }
        val same = java.util.ArrayList(texts)
        assertEquals("a text over $chars characters", assertFailsWith<ForgeLimit> { Guarded.setOf(texts, same) }.message)
        assertEquals("a text over $chars characters", assertFailsWith<ForgeLimit> { Guarded.mapOf(texts, 1, same, 2) }.message)
    }

    @Test
    fun `many small walks in one call are metered together`() {
        // 65,536 references to one list of 2,000: each walk is under the meter's sampling stride, and no code of the
        // module runs between them, so only the checkpoint that ends each composite walk sees their sum (M5-FIX-2).
        val small = java.util.ArrayList<Any?>((0 until 2_000).toList())
        val many = Array<Any?>(Limits.MAX_ARRAY) { small }
        val meter = Meter(javaClass.classLoader)
        meter.arm(20_000_000L, Long.MAX_VALUE)
        try {
            assertTrue("time budget" in assertFailsWith<ForgeLimit> { Guarded.arraysHashCode(many) }.message!!)
        } finally {
            meter.disarm()
        }
        assertEquals(java.util.Arrays.hashCode(many), Guarded.arraysHashCode(many), "unmetered, the same hash as the JDK's")
    }

    private fun l1b() = java.util.ArrayList<Any?>().apply { val l0 = java.util.ArrayList<Any?>((0 until 10_000).toList()); repeat(10_000) { add(l0) } }

    @Test
    fun `a sub-list and a list iterator count against their list`() {
        val list = java.util.ArrayList((0 until 10).toList())
        val view = Guarded.subList(list, 2, 5)
        view.clear()
        assertEquals(listOf(0, 1, 5, 6, 7, 8, 9), list)
        Guarded.subList(view, 0, 0).add(42)
        assertEquals(listOf(0, 1, 42, 5, 6, 7, 8, 9), list)
        val cursor = Guarded.listIterator(list, 1)
        cursor.next()
        cursor.add(-1)
        assertEquals(listOf(0, 1, -1, 42, 5, 6, 7, 8, 9), list)

        val packed = full()
        assertFailsWith<ForgeLimit> { Guarded.subList(packed, 0, 0).add(1) }
        assertFailsWith<ForgeLimit> { Guarded.subList(Guarded.subList(packed, 0, 5), 1, 1).addAll(listOf(1)) }
        assertFailsWith<ForgeLimit> { Guarded.listIterator(packed).add(1) }
        assertEquals(elements, packed.size)
    }

    @Test
    fun `capacities, copies, ranges and builders are bounded`() {
        assertEquals(Limits.MAX_ARRAY, Guarded.capacity(Limits.MAX_ARRAY))
        assertFailsWith<ForgeLimit> { Guarded.capacity(Limits.MAX_ARRAY + 1) }
        assertEquals(-1, Guarded.array(-1), "a negative length is the JVM's to refuse")
        assertFailsWith<ForgeLimit> { Guarded.newHashMap<Int, Int>(500_000_000) }
        assertFailsWith<ForgeLimit> { Guarded.ensureCapacity(java.util.ArrayList<Int>(), 500_000_000) }
        assertFailsWith<ForgeLimit> { Guarded.ensureCapacity(StringBuilder(), 500_000_000) }
        assertEquals(listOf(2, 3), Guarded.copyOfRange(intArrayOf(1, 2, 3), 1, 3).toList())
        assertFailsWith<ArrayIndexOutOfBoundsException> { Guarded.copyOfRange(intArrayOf(1), 5, 6) }
        assertFailsWith<ForgeLimit> { Guarded.copyOfRange(intArrayOf(1), 0, Limits.MAX_ARRAY + 1) }
        assertEquals(0, Guarded.range(5, 0).count())
        assertEquals(Limits.MAX_ARRAY.toLong(), Guarded.range(0, Limits.MAX_ARRAY).count())
        assertFailsWith<ForgeLimit> { Guarded.rangeClosed(Int.MIN_VALUE, Int.MAX_VALUE) }
        assertFailsWith<ForgeLimit> { Guarded.range(Long.MIN_VALUE, Long.MAX_VALUE) }
        assertFailsWith<ForgeLimit> { Guarded.rangeClosed(0L, Long.MAX_VALUE) }
        assertEquals(3L, Guarded.rangeClosed(1L, 3L).count())
        val builder = Guarded.builder<Int>()
        repeat(elements) { builder.add(it) }
        assertFailsWith<ForgeLimit> { builder.add(0) }
        assertEquals(elements.toLong(), builder.build().count())
        val ints = Guarded.intBuilder()
        repeat(elements) { ints.add(it) }
        assertFailsWith<ForgeLimit> { ints.accept(0) }
    }
}
