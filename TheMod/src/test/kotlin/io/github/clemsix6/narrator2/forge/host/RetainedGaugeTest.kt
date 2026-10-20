package io.github.clemsix6.narrator2.forge.host

import org.junit.jupiter.api.Test
import java.util.Optional
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The gauge of retained memory (M5 §3.7, design review finding 9), on objects of this test's own classes standing for
 * a module's: each object once (a cycle ends), a hard ceiling in objects and in time, never a `hashCode`, `equals` or
 * `toString` called.
 */
class RetainedGaugeTest {
    private class Node(var next: Node?, val payload: Any?)

    /** Its `hashCode`, `equals` and `toString` throw: the gauge must never call them. */
    private class Hostile(val keep: Any?) {
        override fun hashCode(): Int = error("hashCode called")

        override fun equals(other: Any?): Boolean = error("equals called")

        override fun toString(): String = error("toString called")
    }

    private val loader = javaClass.classLoader

    @Test
    fun `a cycle is walked once, and each shared object counted once`() {
        val first = Node(null, "x".repeat(100))
        var at = first
        repeat(999) { at = Node(at, first.payload).also { node -> at.next = node } }
        at.next = first
        val shared = List(10_000) { first }
        val measure = RetainedGauge(loader).measure(listOf(first, shared, shared))
        assertTrue(measure.complete)
        assertEquals(1_000 + 1 + 1, measure.objects, "1,000 nodes, their one text, the list")
    }

    @Test
    fun `its estimate follows the design's rule`() {
        val measure = RetainedGauge(loader).measure(listOf("x".repeat(1_000), IntArray(1_000), LongArray(10), arrayOfNulls<Any>(4)))
        assertEquals((16 + 2_000) + (16 + 4_000) + (16 + 80) + (16 + 32).toLong(), measure.bytes)
        val box = RetainedGauge(loader).measure(listOf(Node(null, null)))
        assertEquals(16L + 8 * 2, box.bytes, "an object: 16 bytes and 8 a field")
        val containers = RetainedGauge(loader).measure(listOf(listOf(1_001, 1_002, 1_003), mapOf(2_001 to 2_002), Optional.of(3_001), AtomicReference(4_001)))
        assertTrue(containers.complete)
        assertEquals(4 + 3 + 2 + 1 + 1, containers.objects, "the containers and what they hold")
    }

    @Test
    fun `the module's objects and the JDK's collections are walked without calling them`() {
        val hostile = Hostile(Hostile(mutableListOf(Hostile(null), hashMapOf("k" to Hostile(null)))))
        val measure = RetainedGauge(loader).measure(listOf(hostile, setOf(Hostile(null))))
        assertTrue(measure.complete)
        assertTrue(measure.objects >= 8, "${measure.objects}")
    }

    @Test
    fun `the walk stops at its ceiling in objects, and at its ceiling in time`() {
        val wide = List(1_000) { i -> List(100) { Node(null, i) } }
        val capped = RetainedGauge(loader, maxObjects = 5_000).measure(listOf(wide))
        assertFalse(capped.complete)
        assertTrue(capped.objects in 5_001..5_200, "it stops just past 5,000: ${capped.objects}")

        val timed = RetainedGauge(loader, maxObjects = Int.MAX_VALUE, maxNanos = 1_000_000).measure(listOf(wide))
        assertFalse(timed.complete)
        assertTrue(timed.nanos < 20_000_000, "it stops near 1 ms: ${timed.nanos / 1e6} ms")
    }

    @Test
    fun `static fields of the module's classes are roots, other classes' are not`() {
        Statics.kept += "y".repeat(10_000)
        val measure = RetainedGauge(loader).measure(emptyList(), listOf(Statics::class.java, String::class.java))
        assertTrue(measure.bytes >= 20_000, "${measure.bytes}")
        Statics.kept.clear()
    }

    private object Statics {
        @JvmField
        val kept = mutableListOf<String>()
    }
}
