package io.github.clemsix6.narrator2.guard

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtectedPlayersTest {
    @TempDir
    lateinit var dir: Path

    private val bob = UUID.fromString("00000000-0000-4000-8000-0000000000b0")
    private val alice = UUID.fromString("00000000-0000-4000-8000-0000000000a1")
    private val now = Executor { it.run() }

    private fun players(configured: List<String> = emptyList()) = ProtectedPlayers(dir.resolve("protected.json"), { configured }, now)

    @Test
    fun `a protection is saved and read back by the next server`() {
        val first = players()
        assertTrue(first.set(bob, "Bob", on = true))
        assertFalse(first.set(bob, "Bob", on = true), "already protected: no change")
        assertContains(Files.readString(dir.resolve("protected.json")), bob.toString())

        val next = players()
        assertTrue(next.isProtected(bob))
        assertFalse(next.isProtected(alice))

        assertTrue(next.set(bob, "Bob", on = false))
        assertFalse(players().isProtected(bob), "lifted, and saved")
    }

    @Test
    fun `configured names match the players seen, ignoring case`() {
        val configured = players(configured = listOf("alice"))
        assertFalse(configured.isProtected(alice), "never seen: the name is unknown")
        configured.seen(alice, "Alice")
        assertTrue(configured.isProtected(alice))
        assertTrue(configured.isProtected(bob, "ALICE"), "by the name given with the check")
        assertFalse(configured.isProtected(bob, "Bob"))
    }

    @Test
    fun `an unreadable file is kept aside, and nobody is protected by it`() {
        Files.writeString(dir.resolve("protected.json"), "{ not json")
        val loaded = players()
        assertFalse(loaded.isProtected(bob))
        assertTrue(Files.exists(dir.resolve("protected.json.bad")), "kept for the operator")
        assertFalse(Files.exists(dir.resolve("protected.json")))
    }
}
