package io.github.clemsix6.narrator2.forge.sdk

import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.api.ForgeRefusal
import io.github.clemsix6.narrator2.forge.api.Storage
import java.util.Collections

/**
 * One deployment's [Storage] (M5 §4.2, §6.7): texts, written at once, in the order their keys were first put, from
 * [initial] (its `ModuleRun.storage` after a restart). The engine copies it into the state when it changed ([changes],
 * once a second, and after `stop`). Limits: `ForgeLimits.STORAGE_KEYS` keys of `ForgeLimits.STORAGE_KEY_CHARS`
 * characters at most, values of `ForgeLimits.STORAGE_VALUE_CHARS`, `ForgeLimits.STORAGE_CHARS` characters in all (keys
 * and values); a write beyond throws [ForgeRefusal] and changes nothing. Writes work during `stop` (a module saves what
 * it will need after a restart); once the deployment ended ([close]) every method throws. Keys and values are
 * `String`s, a final class of the JDK: nothing of the module runs here. Server thread (the bench's worker at the bench).
 */
class StorageMap(initial: Map<String, String> = emptyMap()) : Storage {
    private val values = LinkedHashMap(initial)
    private var chars = values.entries.sumOf { it.key.length + it.value.length }
    private var changed = false
    private var closed = false

    override fun get(key: String?): String? = values[key(key, "get")]

    override fun put(key: String?, value: String?) {
        val checked = key(key, "put")
        if (value == null) return remove(checked)
        if (value.length > ForgeLimits.STORAGE_VALUE_CHARS) {
            throw ForgeRefusal("Storage.put: a value has ${ForgeLimits.STORAGE_VALUE_CHARS} characters at most (Limits.MAX_STORAGE_VALUE_CHARS; got ${value.length})")
        }
        val old = values[checked]
        if (old == null && values.size >= ForgeLimits.STORAGE_KEYS) {
            throw ForgeRefusal("Storage.put: ${ForgeLimits.STORAGE_KEYS} keys at most (Limits.MAX_STORAGE_KEYS); remove one first")
        }
        val total = chars - (old?.let { checked.length + it.length } ?: 0) + checked.length + value.length
        if (total > ForgeLimits.STORAGE_CHARS) {
            throw ForgeRefusal("Storage.put: ${ForgeLimits.STORAGE_CHARS} characters at most in all, keys and values (Limits.MAX_STORAGE_CHARS; would be $total)")
        }
        if (old == value) return
        values[checked] = value
        chars = total
        changed = true
    }

    override fun remove(key: String?) {
        val checked = key(key, "remove")
        val old = values.remove(checked) ?: return
        chars -= checked.length + old.length
        changed = true
    }

    override fun keys(): Set<String> {
        open()
        return Collections.unmodifiableSet(LinkedHashSet(values.keys))
    }

    override fun getInt(key: String?, fallback: Int): Int = get(key)?.trim()?.toIntOrNull() ?: fallback

    override fun putInt(key: String?, value: Int) = put(key, value.toString())

    override fun getDouble(key: String?, fallback: Double): Double = get(key)?.trim()?.toDoubleOrNull() ?: fallback

    override fun putDouble(key: String?, value: Double) = put(key, value.toString())

    /** A copy of what it holds now, in order. */
    fun snapshot(): Map<String, String> = LinkedHashMap(values)

    /** What it holds, when it changed since the last call; null otherwise. */
    fun changes(): Map<String, String>? {
        if (!changed) return null
        changed = false
        return snapshot()
    }

    /** The deployment ended: every method throws [ForgeRefusal] from now on ([snapshot] still reads). */
    fun close() {
        closed = true
    }

    private fun open() {
        if (closed) throw ForgeRefusal("the deployment has ended: its SDK objects no longer work")
    }

    private fun key(key: String?, method: String): String {
        open()
        if (key.isNullOrEmpty()) throw ForgeRefusal("Storage.$method: a key is a text of 1 to ${ForgeLimits.STORAGE_KEY_CHARS} characters (got ${key?.let { "\"\"" }})")
        if (key.length > ForgeLimits.STORAGE_KEY_CHARS) {
            throw ForgeRefusal("Storage.$method: a key has ${ForgeLimits.STORAGE_KEY_CHARS} characters at most (Limits.MAX_STORAGE_KEY_CHARS; got ${key.length})")
        }
        return key
    }
}
