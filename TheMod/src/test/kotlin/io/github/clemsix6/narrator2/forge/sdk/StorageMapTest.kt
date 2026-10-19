package io.github.clemsix6.narrator2.forge.sdk

import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.api.ForgeRefusal
import io.github.clemsix6.narrator2.testing.ForgeSamples
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * A deployment's small saved state (M5 §4.2, §6.7; task M5-06): texts in the order their keys were first put, `null`
 * removes, numbers as text, the limits of keys, values and characters (a write beyond changes nothing), what the engine
 * copies into the state, and the end of the deployment.
 */
class StorageMapTest {
    @Test
    fun `it is back after a restart, and keeps its keys in the order they were first put`() {
        val storage = StorageMap(ForgeSamples.run.storage)
        assertEquals("Bartok", storage.get("wolf"))
        storage.put("round", "2")
        storage.put("wolf", "Mimosa")
        storage.putInt("score", 12)
        assertEquals(listOf("wolf", "round", "score"), storage.keys().toList())
        storage.put("round", null)
        storage.put("round", "3")
        assertEquals(listOf("wolf", "score", "round"), storage.keys().toList(), "a key removed then put again is put anew")
        assertFailsWith<UnsupportedOperationException> { (storage.keys() as MutableSet<String>).add("x") }
        storage.remove("score")
        storage.remove("never there")
        assertNull(storage.get("score"))
        assertEquals(mapOf("wolf" to "Mimosa", "round" to "3"), storage.snapshot())
    }

    @Test
    fun `numbers are texts, and a value that is no number reads the fallback`() {
        val storage = StorageMap()
        storage.putInt("int", -7)
        storage.putDouble("double", 2.5)
        storage.put("text", "loup")
        assertEquals(-7, storage.getInt("int", 0))
        assertEquals(2.5, storage.getDouble("double", 0.0))
        assertEquals(-7.0, storage.getDouble("int", 0.0))
        assertEquals(4, storage.getInt("text", 4))
        assertEquals(4, storage.getInt("double", 4))
        assertEquals(1.5, storage.getDouble("missing", 1.5))
        assertEquals("2.5", storage.get("double"))
    }

    @Test
    fun `keys, values and characters have their limits, and a write beyond changes nothing`() {
        val storage = StorageMap()
        assertContains(assertFailsWith<ForgeRefusal> { storage.put(null, "x") }.message.orEmpty(), "a key is a text of 1 to 40 characters")
        assertFailsWith<ForgeRefusal> { storage.put("", "x") }
        assertFailsWith<ForgeRefusal> { storage.get(null) }
        assertContains(assertFailsWith<ForgeRefusal> { storage.put("k".repeat(41), "x") }.message.orEmpty(), "40 characters at most")
        assertContains(assertFailsWith<ForgeRefusal> { storage.put("k", "v".repeat(513)) }.message.orEmpty(), "512 characters at most")
        storage.put("k".repeat(40), "v".repeat(512))

        val keys = StorageMap()
        repeat(ForgeLimits.STORAGE_KEYS) { keys.put("k$it", "v") }
        assertContains(assertFailsWith<ForgeRefusal> { keys.put("one too many", "v") }.message.orEmpty(), "64 keys at most")
        keys.put("k0", "a new value for a key it has")

        val chars = StorageMap()
        repeat(16) { chars.put("key$it", "v".repeat(500)) } // 10 × 504 + 6 × 505 = 8,070 characters
        val before = chars.snapshot()
        assertContains(assertFailsWith<ForgeRefusal> { chars.put("big", "v".repeat(512)) }.message.orEmpty(), "8192 characters at most in all")
        assertEquals(before, chars.snapshot(), "nothing changed")
        chars.put("key0", "v".repeat(512)) // 8,082: a value replaced counts once
        chars.put("small", "v".repeat(105)) // 8,192: the limit itself
        assertFailsWith<ForgeRefusal> { chars.put("more", "v") }
        chars.put("key1", null)
        chars.put("more", "v".repeat(60))
        assertEquals(8_192 - 504 + 64, chars.snapshot().entries.sumOf { it.key.length + it.value.length })
    }

    @Test
    fun `the engine copies it when it changed, and once the deployment ended every method refuses`() {
        val storage = StorageMap(mapOf("wolf" to "Bartok"))
        assertNull(storage.changes(), "nothing changed yet")
        storage.put("wolf", "Bartok")
        assertNull(storage.changes(), "the same value changes nothing")
        storage.put("wolf", "Mimosa")
        assertEquals(mapOf("wolf" to "Mimosa"), storage.changes())
        assertNull(storage.changes(), "copied once")
        storage.remove("wolf")
        assertEquals(emptyMap(), storage.changes())

        storage.close()
        assertContains(assertFailsWith<ForgeRefusal> { storage.get("wolf") }.message.orEmpty(), "has ended")
        assertFailsWith<ForgeRefusal> { storage.put("wolf", "x") }
        assertFailsWith<ForgeRefusal> { storage.keys() }
        assertFailsWith<ForgeRefusal> { storage.getInt("x", 0) }
        assertEquals(emptyMap(), storage.snapshot(), "the engine still reads it")
    }
}
