package io.github.clemsix6.narrator2.forge.host

import io.github.clemsix6.narrator2.forge.ForgeLimits
import org.junit.jupiter.api.Test
import java.util.Optional
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The gauge of retained memory (M5 §3.7, design review finding 9), on objects of this test's own classes standing for
 * a module's: each object once (a cycle ends), hard ceilings in objects and in estimated bytes — work, not time
 * (M5-LOAD): the same graph gives the same measure on any machine, busy or not —, never a `hashCode`, `equals` or
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

    private fun gauge(maxObjects: Int = ForgeLimits.GAUGE_MAX_OBJECTS, maxBytes: Long = ForgeLimits.RETAINED_BYTES) = RetainedGauge(loader, maxObjects, maxBytes)

    @Test
    fun `a cycle is walked once, and each shared object counted once`() {
        val first = Node(null, "x".repeat(100))
        var at = first
        repeat(999) { at = Node(at, first.payload).also { node -> at.next = node } }
        at.next = first
        val shared = List(10_000) { first }
        val measure = gauge().measure(listOf(first, shared, shared))
        assertTrue(measure.complete)
        assertEquals(1_000 + 1 + 1, measure.objects, "1,000 nodes, their one text, the list")
    }

    @Test
    fun `its estimate follows the design's rule`() {
        val measure = gauge().measure(listOf("x".repeat(1_000), IntArray(1_000), LongArray(10), arrayOfNulls<Any>(4)))
        assertEquals((16 + 2_000) + (16 + 4_000) + (16 + 80) + (16 + 32).toLong(), measure.bytes)
        val box = gauge().measure(listOf(Node(null, null)))
        assertEquals(16L + 8 * 2, box.bytes, "an object: 16 bytes and 8 a field")
        val containers = gauge().measure(listOf(listOf(1_001, 1_002, 1_003), mapOf(2_001 to 2_002), Optional.of(3_001), AtomicReference(4_001)))
        assertTrue(containers.complete)
        assertEquals(4 + 3 + 2 + 1 + 1, containers.objects, "the containers and what they hold")
    }

    @Test
    fun `the module's objects and the JDK's collections are walked without calling them`() {
        val hostile = Hostile(Hostile(mutableListOf(Hostile(null), hashMapOf("k" to Hostile(null)))))
        val measure = gauge().measure(listOf(hostile, setOf(Hostile(null))))
        assertTrue(measure.complete)
        assertTrue(measure.objects >= 8, "${measure.objects}")
    }

    @Test
    fun `the walk stops at its ceiling in objects, and at its ceiling in bytes, the same on any machine`() {
        val wide = List(1_000) { i -> List(100) { Node(null, i) } }
        val capped = gauge(maxObjects = 5_000).measure(listOf(wide))
        assertFalse(capped.complete)
        assertTrue(capped.objects in 5_001..5_200, "it stops just past 5,000: ${capped.objects}")
        assertEquals(capped.objects, gauge(maxObjects = 5_000).measure(listOf(wide)).objects, "work, not time: the same stop again")

        // A hundred arrays of 65,536 references to one object: each costs 512 KiB of the estimate, so a ceiling of 1 MiB
        // stops the walk at the second, its references followed bounded by the bytes they were paid (8 each).
        val one = Node(null, null)
        val arrays = List(100) { arrayOfNulls<Any>(65_536).apply { fill(one) } }
        val heavy = gauge(maxBytes = 1L shl 20).measure(listOf(arrays))
        assertFalse(heavy.complete)
        assertTrue(heavy.bytes > 1L shl 20 && heavy.bytes <= (1L shl 20) + 16 + 8 * 65_536, "it stops just past 1 MiB: ${heavy.bytes}")
        assertEquals(1 + 100 + 1, heavy.objects, "the list, its arrays (seen as the list was walked), their one node")
        val whole = gauge(maxBytes = Long.MAX_VALUE).measure(listOf(arrays))
        assertTrue(whole.complete && whole.bytes > 50L shl 20, "${whole.bytes}")
    }

    @Test
    fun `static fields of the module's classes are roots, other classes' are not`() {
        Statics.kept += "y".repeat(10_000)
        val gauge = gauge()
        val statics = gauge.statics(listOf(Statics::class.java, String::class.java))
        assertTrue(statics.none { it is String && it.isEmpty() } && statics.any { it === Statics.kept }, "the module's classes only: $statics")
        val measure = gauge.measure(statics)
        assertTrue(measure.bytes >= 20_000, "${measure.bytes}")
        Statics.kept.clear()
    }

    private object Statics {
        @JvmField
        val kept = mutableListOf<String>()
    }
}
