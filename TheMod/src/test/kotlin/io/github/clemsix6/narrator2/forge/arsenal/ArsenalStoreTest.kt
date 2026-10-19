package io.github.clemsix6.narrator2.forge.arsenal

import io.github.clemsix6.narrator2.forge.ArsenalEntry
import io.github.clemsix6.narrator2.forge.ForgeRejected
import io.github.clemsix6.narrator2.forge.ModuleStatus
import io.github.clemsix6.narrator2.forge.ParamSpec
import io.github.clemsix6.narrator2.forge.api.ParamType
import io.github.clemsix6.narrator2.testing.ForgeSamples
import io.github.clemsix6.narrator2.testing.LogCapture
import io.github.clemsix6.narrator2.testing.TestClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.apache.logging.log4j.Level
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.name
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The arsenal on disk (M5 §6.1): versions immutable and numbered in turn, writes whole or not at all, `broken` and its
 * hash, the built-in modules, the limits, and nothing a model names ever leaving `world/narrator2/forge/`.
 */
class ArsenalStoreTest {
    @TempDir
    lateinit var temp: Path

    private val clock = TestClock(Instant.parse("2026-10-19T21:06:12Z"))
    private val forge: Path get() = temp.resolve("world").resolve("narrator2").resolve("forge")

    @Test
    fun `a save writes the source and its manifest, and the next save the next version, never touching the first`() = runBlocking<Unit> {
        val store = store()
        val first = store.save(manifest(), SOURCE, " The first statues. ", "forger T-31", ModuleStatus.READY)

        assertEquals(manifest().entry(1, ModuleStatus.READY, false, clock.instant(), "forger T-31", "The first statues.", Manifest.hash(SOURCE)), first)
        val v1 = forge.resolve("statues").resolve("v1.java")
        val m1 = forge.resolve("statues").resolve("v1.json")
        assertContentEquals(SOURCE.toByteArray(), Files.readAllBytes(v1))
        assertEquals(first, Manifest.decode(Files.readString(m1)))
        val written = Files.readAllBytes(v1) to Files.readAllBytes(m1)
        val modified = Files.getLastModifiedTime(v1) to Files.getLastModifiedTime(m1)

        clock.advance(Duration.ofMinutes(5))
        val second = store.save(manifest(), "$SOURCE// v2\n", "Slower.", "forger T-32", ModuleStatus.READY)

        assertEquals(2, second.version)
        assertContentEquals(written.first, Files.readAllBytes(v1), "a version is never written again")
        assertContentEquals(written.second, Files.readAllBytes(m1))
        assertEquals(modified, Files.getLastModifiedTime(v1) to Files.getLastModifiedTime(m1))
        assertEquals(listOf(second, first), store.list(), "the newest first")
        assertEquals(second, store.entry("statues"))
        assertEquals(first, store.entry("statues", 1))
        assertEquals(SOURCE, store.source("statues", 1))
        assertEquals(forge.toRealPath().resolve("statues").resolve("v2.java"), store.file("statues", 2))
        assertEquals(setOf("v1.java", "v1.json", "v2.java", "v2.json"), files("statues"), "no temporary file left")
    }

    @Test
    fun `the source is kept byte for byte`() = runBlocking<Unit> {
        val source = "// Les poules — « é », 🐔\r\nimport java.util.*;\tclass X {}\n"
        val store = store()
        val entry = store.save(manifest(), source, "note", "forger T-31", ModuleStatus.READY)

        assertContentEquals(source.toByteArray(Charsets.UTF_8), Files.readAllBytes(forge.resolve("statues").resolve("v1.java")))
        assertEquals(Manifest.hash(source), entry.hash)
        assertEquals(source, store.checkedSource("statues", 1))
        assertEquals(source, store().also { it.load() }.source("statues", 1))
    }

    @Test
    fun `a source too long or holding half a surrogate pair is refused before anything is written`() = runBlocking<Unit> {
        val store = store()
        val lone = assertFailsWith<ForgeRejected> { store.save(manifest(), "class X { String s = \"\uD800\"; }", "note", "forger T-31", ModuleStatus.READY) }
        assertEquals("Invalid arguments: the source holds a broken character (half of a surrogate pair): write it again", lone.toResult().text)
        val long = assertFailsWith<ForgeRejected> { store.save(manifest(), "x".repeat(48_001), "note", "forger T-31", ModuleStatus.READY) }
        assertEquals("Invalid arguments: the source is 48001 characters long, at most 48000", long.toResult().text)
        assertFalse(Files.exists(forge.resolve("statues")))
    }

    @Test
    fun `a reload finds every version as it was saved`() = runBlocking<Unit> {
        val builtIns = mapOf(ForgeSamples.NAME to ForgeSamples.SOURCE)
        val store = store(builtIns)
        store.load()
        store.addBuiltIn(Manifest.read(ForgeSamples.NAME, ForgeSamples.classes))
        store.save(manifest(), SOURCE, "One.", "forger T-31", ModuleStatus.READY)
        clock.advance(Duration.ofSeconds(1))
        store.save(manifest(), "$SOURCE//2", "Two.", "forger T-32", ModuleStatus.PENDING)
        clock.advance(Duration.ofSeconds(1))
        store.save(manifest(ForgeSamples.NAME), "$SOURCE//tag", "Derived.", "forger T-33", ModuleStatus.READY)

        val again = store(builtIns)
        again.load()
        again.addBuiltIn(Manifest.read(ForgeSamples.NAME, ForgeSamples.classes))

        assertEquals(store.list(), again.list())
        assertEquals(4, again.list().size)
        assertEquals("$SOURCE//2", again.source("statues", 2))
        assertEquals(ModuleStatus.PENDING, again.entry("statues", 2)?.status)
    }

    @Test
    fun `the version deployed is the latest ready one, never a pending, rejected or broken one`() = runBlocking<Unit> {
        val store = store()
        store.save(manifest(), SOURCE, "One.", "forger T-31", ModuleStatus.READY)
        store.save(manifest(), "$SOURCE//2", "Two.", "forger T-32", ModuleStatus.READY)
        store.save(manifest(), "$SOURCE//3", "Three.", "forger T-33", ModuleStatus.PENDING)

        assertEquals(2, store.entry("statues")?.version)
        store.review("statues", 3, approve = false, operator = "Clement")
        assertEquals(2, store.entry("statues")?.version)
        store.markBroken("statues", 2, "cannot find symbol World.surface")
        assertEquals(1, store.entry("statues")?.version)
        assertNull(store.entry("nobody"))
        assertNull(store.entry("statues", 9))
    }

    @Test
    fun `built-in modules are version 0, read-only, listed once compiled, and a forged version follows them`() = runBlocking<Unit> {
        val store = ArsenalStore(forge, clock, listOf(ForgeSamples.NAME, "lost_module"), { if (it == ForgeSamples.NAME) ForgeSamples.SOURCE.toByteArray() else null })
        LogCapture().use { logs ->
            store.load()
            assertTrue(logs.at(Level.ERROR).any { "the built-in module lost_module is missing from the mod's jar" in it }, logs.text)
        }

        assertEquals(mapOf(ForgeSamples.NAME to ForgeSamples.SOURCE), store.builtInSources)
        assertEquals(emptyList(), store.list(), "listed once the Forge has compiled it")
        assertEquals(ForgeSamples.SOURCE, store.source(ForgeSamples.NAME, 0), "its source, to compile it")

        val builtIn = store.addBuiltIn(Manifest.read(ForgeSamples.NAME, ForgeSamples.classes))

        assertEquals(
            ForgeSamples.manifest.copy(version = 0, builtIn = true, savedAt = Instant.EPOCH, by = "built-in", note = null),
            builtIn,
        )
        assertEquals(builtIn, store.entry(ForgeSamples.NAME))
        assertNull(store.file(ForgeSamples.NAME, 0), "not a file of the world")
        assertEquals(ForgeSamples.SOURCE, store.checkedSource(ForgeSamples.NAME, 0))
        assertFailsWith<IllegalArgumentException> { store.addBuiltIn(manifest()) }

        val derived = store.save(manifest(ForgeSamples.NAME), "$SOURCE//derived", "Faster wolves.", "forger T-34", ModuleStatus.READY)

        assertEquals(1, derived.version)
        assertEquals(listOf(derived, builtIn), store.list())
        assertEquals(derived, store.entry(ForgeSamples.NAME))
        assertEquals(setOf("v1.java", "v1.json"), files(ForgeSamples.NAME), "version 0 stays in the jar")
    }

    @Test
    fun `a review writes the manifest's status again and nothing else`() = runBlocking<Unit> {
        var crash = false
        val store = store(beforeMove = { if (crash) throw IOException("the disk is full") })
        store.save(manifest(), SOURCE, "One.", "forger T-31", ModuleStatus.PENDING)
        val second = store.save(manifest(), "$SOURCE//2", "Two.", "forger T-32", ModuleStatus.PENDING)
        val sources = Files.readAllBytes(forge.resolve("statues").resolve("v2.java"))

        crash = true
        val failed = assertFailsWith<ForgeRejected> { store.review("statues", null, approve = true, operator = "Clement") }
        assertTrue(failed.toResult().text.startsWith("FAILED: cannot record the review of statues v2: the disk failed"), failed.toResult().text)
        assertEquals(ModuleStatus.PENDING, store.entry("statues", 2)?.status)
        assertEquals(second, Manifest.decode(Files.readString(forge.resolve("statues").resolve("v2.json"))))
        crash = false

        val approved = store.review("statues", null, approve = true, operator = "Clement")

        assertEquals(second.copy(status = ModuleStatus.READY), approved, "the latest pending one")
        assertEquals(approved, Manifest.decode(Files.readString(forge.resolve("statues").resolve("v2.json"))))
        assertContentEquals(sources, Files.readAllBytes(forge.resolve("statues").resolve("v2.java")))
        assertEquals(ModuleStatus.PENDING, store.entry("statues", 1)?.status)
        assertEquals(setOf("v1.java", "v1.json", "v2.java", "v2.json"), files("statues"))

        assertEquals(
            "Invalid arguments: statues v2 is ready: only a pending version is approved or rejected",
            assertFailsWith<ForgeRejected> { store.review("statues", 2, approve = false, operator = "Clement") }.toResult().text,
        )
        assertEquals(ModuleStatus.REJECTED, store.review("statues", 1, approve = false, operator = "Clement").status)
        assertEquals(
            "Invalid arguments: statues has no version waiting for approval",
            assertFailsWith<ForgeRejected> { store.review("statues", null, approve = true, operator = "Clement") }.toResult().text,
        )
        assertEquals(
            "Invalid arguments: no module statues v9 in the arsenal",
            assertFailsWith<ForgeRejected> { store.review("statues", 9, approve = true, operator = "Clement") }.toResult().text,
        )

        val again = store().also { it.load() }
        assertEquals(listOf(ModuleStatus.READY, ModuleStatus.REJECTED), again.list().map { it.status })
    }

    @Test
    fun `a source changed on disk makes its version broken, and the one before is deployed`() = runBlocking<Unit> {
        val store = store()
        store.save(manifest(), SOURCE, "One.", "forger T-31", ModuleStatus.READY)
        store.save(manifest(), "$SOURCE//2", "Two.", "forger T-32", ModuleStatus.READY)
        Files.writeString(forge.resolve("statues").resolve("v2.java"), "$SOURCE//2 with a backdoor")

        val again = store()
        LogCapture().use { logs ->
            again.load()
            assertTrue(logs.at(Level.ERROR).any { "statues v2 is broken" in it }, logs.text)
        }

        val broken = again.entry("statues", 2)!!
        assertEquals(ModuleStatus.BROKEN, broken.status)
        assertEquals("its source v2.java no longer matches its hash: it was changed on disk", broken.problem)
        assertEquals(1, again.entry("statues")?.version)
        assertEquals(
            "FAILED: statues v2 is broken: its source v2.java no longer matches its hash: it was changed on disk",
            assertFailsWith<ForgeRejected> { again.checkedSource("statues", 2) }.toResult().text,
        )
        assertEquals(SOURCE, again.checkedSource("statues", 1))
        assertEquals("ready", Manifest.decode(Files.readString(forge.resolve("statues").resolve("v2.json"))).status.id, "broken is never saved")

        // Changed after the arsenal was read: found when it is built.
        Files.delete(forge.resolve("statues").resolve("v1.java"))
        assertEquals(
            "FAILED: statues v1 is broken: its source v1.java is missing",
            assertFailsWith<ForgeRejected> { again.checkedSource("statues", 1) }.toResult().text,
        )
        assertNull(again.entry("statues"), "nothing ready is left")
        assertEquals(listOf(ModuleStatus.BROKEN, ModuleStatus.BROKEN), again.list().map { it.status })
    }

    @Test
    fun `broken and faults are kept in memory, never saved`() = runBlocking<Unit> {
        val store = store()
        store.save(manifest(), SOURCE, "One.", "forger T-31", ModuleStatus.READY)

        val faulted = store.noteFault("statues", 1, "IllegalStateException at Statues.java:42")!!
        assertEquals(ModuleStatus.READY, faulted.status)
        assertEquals("IllegalStateException at Statues.java:42", faulted.problem)

        val broken = store.markBroken("statues", 1, "[1] error Statues.java:31:15: World.surface is undefined")!!
        assertEquals(ModuleStatus.BROKEN, broken.status)
        assertEquals("[1] error Statues.java:31:15: World.surface is undefined", broken.problem)
        assertEquals(broken, store.noteFault("statues", 1, "another fault"), "broken says why it is broken")
        assertNull(store.markBroken("nobody", 1, "x"))
        assertNull(store.noteFault("statues", 2, "x"))

        val again = store().also { it.load() }
        assertEquals(ModuleStatus.READY, again.entry("statues", 1)?.status)
        assertNull(again.entry("statues", 1)?.problem)
    }

    @Test
    fun `a save that fails before its source is in place leaves nothing, and the next one takes its number`() = runBlocking<Unit> {
        var crash = true
        val store = store(beforeMove = { if (crash) throw IOException("the disk is full") })

        val failed = assertFailsWith<ForgeRejected> { store.save(manifest(), SOURCE, "One.", "forger T-31", ModuleStatus.READY) }

        assertEquals("FAILED: cannot save statues v1: the disk failed (IOException: the disk is full)", failed.toResult().text)
        assertEquals(emptySet(), files("statues"), "no source, no manifest, no temporary file")
        assertEquals(emptyList(), store.list())
        crash = false
        assertEquals(1, store.save(manifest(), SOURCE, "One.", "forger T-31", ModuleStatus.READY).version)
    }

    @Test
    fun `a save that fails between its source and its manifest leaves no version`() = runBlocking<Unit> {
        var crash = true
        val store = store(beforeMove = { if (crash && it.name.startsWith(".v1.json.")) throw IOException("the disk is full") })

        assertFailsWith<ForgeRejected> { store.save(manifest(), SOURCE, "One.", "forger T-31", ModuleStatus.READY) }

        assertEquals(emptySet(), files("statues"), "the source of a version without a manifest goes too")
        assertEquals(emptyList(), store().also { it.load() }.list())
        crash = false
        assertEquals(1, store.save(manifest(), SOURCE, "One.", "forger T-31", ModuleStatus.READY).version)
    }

    @Test
    fun `while a save is under way no half version can be seen`() = runBlocking<Unit> {
        lateinit var store: ArsenalStore
        val seen = mutableListOf<String>()
        store = store(
            beforeMove = { temporary ->
                val folder = temporary.parent
                when {
                    temporary.name.startsWith(".v1.java.") -> {
                        assertFalse(Files.exists(folder.resolve("v1.java")))
                        assertContentEquals(SOURCE.toByteArray(), Files.readAllBytes(temporary), "the whole source, before the move")
                    }
                    temporary.name.startsWith(".v1.json.") -> {
                        assertTrue(Files.exists(folder.resolve("v1.java")))
                        assertFalse(Files.exists(folder.resolve("v1.json")))
                        assertEquals(1, Manifest.decode(Files.readString(temporary)).version, "the whole manifest, before the move")
                    }
                }
                assertEquals(emptyList(), store.list(), "no version until its manifest is in place")
                assertNull(store.entry("statues", 1))
                seen += temporary.name.split('.').let { "${it[1]}.${it[2]}" } // .v1.java.<random>.tmp
            },
        )

        store.save(manifest(), SOURCE, "One.", "forger T-31", ModuleStatus.READY)

        assertEquals(listOf("v1.java", "v1.json"), seen, "the source first, then the manifest")
        assertEquals(1, store.list().size)
    }

    @Test
    fun `what a crash leaves is cleaned or ignored, and its number never used again`() = runBlocking<Unit> {
        store().save(manifest(), SOURCE, "One.", "forger T-31", ModuleStatus.READY)
        val folder = forge.resolve("statues")
        Files.writeString(folder.resolve(".v2.java.8812736452.tmp"), "public final class Sta")
        Files.writeString(folder.resolve("v2.java"), "$SOURCE//2, whose manifest never came")
        Files.writeString(folder.resolve(".v2.json.991.tmp"), "{\"name\": \"stat")
        val orphan = Files.readAllBytes(folder.resolve("v2.java"))

        val store = store()
        LogCapture().use { logs ->
            store.load()
            assertTrue(logs.messages.any { "removed 2 temporary files" in it }, logs.text)
            assertTrue(logs.at(Level.WARN).any { "statues v2 has a source but no manifest" in it }, logs.text)
        }

        assertEquals(listOf(1), store.list().map { it.version })
        assertEquals(setOf("v1.java", "v1.json", "v2.java"), files("statues"))
        assertEquals(3, store.save(manifest(), "$SOURCE//3", "Three.", "forger T-33", ModuleStatus.READY).version)
        assertContentEquals(orphan, Files.readAllBytes(folder.resolve("v2.java")), "never written over")
        assertNull(store.source("statues", 2), "not a version")
    }

    @Test
    fun `a version on disk is never written over, even one the arsenal could not read`() = runBlocking<Unit> {
        store().save(manifest(), SOURCE, "One.", "forger T-31", ModuleStatus.READY)
        val folder = forge.resolve("statues")
        Files.writeString(folder.resolve("v2.json"), "{ not a manifest")
        val store = store()
        LogCapture().use { logs ->
            store.load()
            assertTrue(logs.at(Level.ERROR).any { "statues v2 is skipped (its number stays taken)" in it }, logs.text)
        }

        assertEquals(3, store.save(manifest(), "$SOURCE//3", "Three.", "forger T-33", ModuleStatus.READY).version)
        assertEquals("{ not a manifest", Files.readString(folder.resolve("v2.json")))

        // A file put there behind the arsenal's back: the save fails rather than replace it.
        Files.writeString(folder.resolve("v4.java"), "someone else's")
        val failed = assertFailsWith<ForgeRejected> { store.save(manifest(), "$SOURCE//4", "Four.", "forger T-34", ModuleStatus.READY) }
        assertContains(failed.toResult().text, "FAILED: cannot save statues v4: the disk failed (FileAlreadyExistsException")
        assertEquals("someone else's", Files.readString(folder.resolve("v4.java")))
        assertFalse(Files.exists(folder.resolve("v4.json")))
        assertEquals(5, store.save(manifest(), "$SOURCE//5", "Five.", "forger T-35", ModuleStatus.READY).version)

        val misnamed = manifest().entry(6, ModuleStatus.READY, false, clock.instant(), "x", null, Manifest.hash(SOURCE))
        Files.writeString(folder.resolve("v6.java"), SOURCE)
        Files.writeString(folder.resolve("v6.json"), Manifest.encode(misnamed.copy(name = "other")))
        assertEquals(listOf(5, 3, 1), store().also { it.load() }.list().map { it.version }, "a manifest of another module is skipped")
    }

    @Test
    fun `the arsenal holds 64 modules and 20 versions of each`() = runBlocking<Unit> {
        val builtIns = (0 until 63).associate { "fake_%02d".format(it) to "class X {}" }
        val store = store(builtIns)
        store.save(manifest("one_more"), SOURCE, "64th.", "forger T-31", ModuleStatus.READY)

        val full = assertFailsWith<ForgeRejected> { store.save(manifest("two_more"), SOURCE, "65th.", "forger T-32", ModuleStatus.READY) }

        assertEquals(
            "DENIED (arsenal_full): the arsenal holds 64 modules, the most it may: make a new version of one of them rather than a new module",
            full.toResult().text,
        )
        assertEquals(2, store.save(manifest("one_more"), "$SOURCE//2", "Again.", "forger T-33", ModuleStatus.READY).version)
        assertEquals(1, store.save(manifest("fake_00"), SOURCE, "Derived.", "forger T-34", ModuleStatus.READY).version, "a built-in module's name is in")
        assertFalse(Files.exists(forge.resolve("two_more")))

        (1..20).forEach { plant("statues", it) }
        val versions = store()
        versions.load()
        assertEquals(
            "DENIED (arsenal_full): statues has 20 versions, the most a module may have: save it under a new name",
            assertFailsWith<ForgeRejected> { versions.save(manifest(), SOURCE, "21st.", "forger T-35", ModuleStatus.READY) }.toResult().text,
        )
        assertFalse(Files.exists(forge.resolve("statues").resolve("v21.java")))
    }

    @Test
    fun `no name from a model ever leaves the arsenal's folder`() = runBlocking<Unit> {
        val store = store()
        store.load()
        val before = tree()
        val names = listOf(
            "../evil", "..\\evil", "statues/../../evil", "/evil", "\\evil", "C:\\evil", "c:evil", "evil.java", "evil:stream", "ev il", "Evil",
            "EVIL", "évil", "evil\u0000", "evil\n", "", "ab", "e".repeat(33), "con", "nul", "aux", "prn", "com1", "com9", "lpt1", "lpt0",
            "_evil", "1evil", "evil-x", "evil\u200b", "ｅｖｉｌ", "evıl", "evil$", "evil~1", "evil*", "evil?", "evil\"", "evil<", "evil>", "evil|",
            "..", ".", "~", "%TEMP%", "\$HOME", "evil/", "evil\\", " evil",
        )

        names.forEach { name ->
            val saved = assertFailsWith<ForgeRejected>(name) { store.save(manifest().copy(name = name), SOURCE, "x", "forger T-66", ModuleStatus.READY) }
            assertTrue(saved.toResult().text.startsWith("Invalid arguments: "), "$name: ${saved.toResult().text}")
            assertFailsWith<ForgeRejected>(name) { store.review(name, 1, approve = true, operator = "Clement") }
            assertFailsWith<ForgeRejected>(name) { store.checkedSource(name, 1) }
            assertNull(store.source(name, 1), name)
            assertNull(store.source(name, 0), name)
            assertNull(store.entry(name), name)
            assertNull(store.file(name, 1), name)
            assertNull(store.markBroken(name, 1, "x"), name)
            assertNull(store.noteFault(name, 1, "x"), name)
        }
        listOf(-1, 0, Int.MAX_VALUE).forEach { version ->
            assertNull(store.source("statues", version))
            assertNull(store.file("statues", version))
        }

        assertEquals(before, tree(), "nothing written anywhere")
    }

    @Test
    fun `a module's folder that is a link is never followed`() = runBlocking<Unit> {
        val outside = Files.createDirectories(temp.resolve("outside").resolve("linked"))
        val planted = manifest("linked").entry(1, ModuleStatus.READY, false, clock.instant(), "an intruder", null, Manifest.hash(SOURCE))
        Files.writeString(outside.resolve("v1.java"), SOURCE)
        Files.writeString(outside.resolve("v1.json"), Manifest.encode(planted))
        Files.createDirectories(forge)
        assumeTrue(linkFolder(forge.resolve("linked"), outside), "neither a symbolic link nor a junction can be made here")

        val store = store()
        LogCapture().use { logs ->
            store.load()
            assertTrue(logs.at(Level.WARN).any { "linked is not a plain folder (a file, a link?): skipped, never followed" in it }, logs.text)
        }

        assertEquals(emptyList(), store.list())
        assertNull(store.source("linked", 1))
        assertNull(store.file("linked", 1))
        val refused = assertFailsWith<ForgeRejected> { store.save(manifest("linked"), SOURCE, "x", "forger T-66", ModuleStatus.READY) }
        assertTrue(refused.toResult().text.startsWith("FAILED: cannot save linked v1: the disk failed"), refused.toResult().text)
        assertEquals(setOf("v1.java", "v1.json"), Files.list(outside).use { files -> files.map { it.name }.toList().toSet() }, "nothing written through the link")
    }

    @Test
    fun `a version's file that is a link is never read`() = runBlocking<Unit> {
        val store = store()
        store.save(manifest(), SOURCE, "One.", "forger T-31", ModuleStatus.READY)
        val folder = forge.resolve("statues")
        val outside = Files.createDirectories(temp.resolve("outside"))
        val hidden = "$SOURCE// read through a link"
        Files.writeString(outside.resolve("secret.java"), hidden)
        Files.writeString(outside.resolve("v3.json"), Manifest.encode(manifest().entry(3, ModuleStatus.READY, false, clock.instant(), "x", null, Manifest.hash(SOURCE))))
        // The source of v2 and the manifest of v3 are links out of the arsenal (to the right content).
        Files.writeString(folder.resolve("v2.json"), Manifest.encode(manifest().entry(2, ModuleStatus.READY, false, clock.instant(), "x", null, Manifest.hash(hidden))))
        assumeTrue(linkFile(folder.resolve("v2.java"), outside.resolve("secret.java")), "no link can be made here")
        assumeTrue(linkFile(folder.resolve("v3.json"), outside.resolve("v3.json")), "no link can be made here")

        val again = store()
        again.load()

        val v2 = again.entry("statues", 2)!!
        assertEquals(ModuleStatus.BROKEN, v2.status)
        assertEquals("v2.java is not a plain file (a link?)", v2.problem)
        assertNull(again.source("statues", 2))
        assertFailsWith<ForgeRejected> { again.checkedSource("statues", 2) }
        assertNull(again.entry("statues", 3), "a manifest that is a link is not read")
        assertEquals(4, again.save(manifest(), "$SOURCE//4", "Four.", "forger T-34", ModuleStatus.READY).version)
        assertEquals(hidden, Files.readString(outside.resolve("secret.java")))
    }

    @Test
    fun `what is not a module's folder or file is ignored`() = runBlocking<Unit> {
        Files.createDirectories(forge)
        Files.writeString(forge.resolve("README.txt"), "notes")
        Files.createDirectories(forge.resolve("Tag"))
        Files.createDirectories(forge.resolve(".git"))
        Files.writeString(forge.resolve("tag_x"), "a file with a module's name")
        Files.createDirectories(forge.resolve("statues"))
        Files.writeString(forge.resolve("statues").resolve("notes.txt"), "notes")
        Files.writeString(forge.resolve("statues").resolve("v01.java"), "a number with a zero")
        Files.writeString(forge.resolve("statues").resolve("v1.java.orig"), "a copy")
        Files.writeString(forge.resolve("statues").resolve("x.json"), "{}")

        val store = store()
        LogCapture().use { logs ->
            store.load()
            val ignored = "not a module's file or folder: .git, README.txt, Tag, statues/notes.txt, statues/v01.java, statues/v1.java.orig, statues/x.json"
            assertTrue(logs.at(Level.WARN).any { ignored in it }, logs.text)
            assertTrue(logs.at(Level.WARN).any { "tag_x is not a plain folder (a file, a link?)" in it }, logs.text)
        }

        assertEquals(emptyList(), store.list())
        assertEquals(1, store.save(manifest(), SOURCE, "One.", "forger T-31", ModuleStatus.READY).version)
    }

    @Test
    fun `a file larger than any save is never read whole`() = runBlocking<Unit> {
        val huge = "/" + "*".repeat(1_000_000) + "/"
        plant("statues", 1, source = huge)
        val store = store()
        store.load()

        assertEquals(ModuleStatus.BROKEN, store.entry("statues", 1)?.status)
        assertEquals("v1.java is 1000002 bytes long, at most 192000", store.entry("statues", 1)?.problem)
    }

    @Test
    fun `the files are read and written away from the caller's thread`() {
        val threads = mutableListOf<Thread>()
        val store = store(beforeMove = { threads += Thread.currentThread() })
        val caller = Thread.currentThread()

        runBlocking<Unit> { store.save(manifest(), SOURCE, "One.", "forger T-31", ModuleStatus.READY) }

        assertEquals(2, threads.size)
        assertTrue(threads.none { it == caller }, "${threads.map { it.name }}")
    }

    @Test
    fun `saves at the same time get each their own version`() = runBlocking<Unit> {
        val store = store()
        val saved = (1..8).map { async(Dispatchers.Default) { store.save(manifest(), "$SOURCE// $it", "Save $it.", "forger T-$it", ModuleStatus.READY) } }.awaitAll()

        assertEquals((1..8).toList(), saved.map { it.version }.sorted())
        saved.forEach { entry -> assertEquals(entry.hash, Manifest.hash(Files.readAllBytes(forge.resolve("statues").resolve("v${entry.version}.java")))) }
        assertEquals(16, files("statues").size)
    }

    // ── Helpers ────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun store(builtIns: Map<String, String> = emptyMap(), beforeMove: (Path) -> Unit = {}) =
        ArsenalStore(forge, clock, builtIns.keys.toList(), { builtIns[it]?.toByteArray() }, beforeMove)

    private fun manifest(name: String = "statues") = Manifest(
        name = name, mainClass = Manifest.mainClass(name), summary = "Red light, green light: whoever moves is sent back.",
        params = listOf(ParamSpec("players", ParamType.PLAYERS, "Who plays.", true, null, 2.0, 8.0, emptyList())),
        scenarios = listOf("movingIsCaught", "stillnessWins"), classes = 3, bytes = 14_210,
    )

    /** Writes version [version] of [name] by hand, as a store would have. */
    private fun plant(name: String, version: Int, source: String = "$SOURCE// $version"): ArsenalEntry {
        val entry = manifest(name).entry(version, ModuleStatus.READY, false, clock.instant(), "forger T-1", null, Manifest.hash(source))
        val folder = Files.createDirectories(forge.resolve(name))
        Files.writeString(folder.resolve("v$version.java"), source)
        Files.writeString(folder.resolve("v$version.json"), Manifest.encode(entry))
        return entry
    }

    private fun files(name: String): Set<String> {
        val folder = forge.resolve(name)
        if (!Files.exists(folder)) return emptySet()
        return Files.list(folder).use { files -> files.map { it.name }.toList().toSet() }
    }

    /** Every path under the test's folder, with its size. */
    private fun tree(): Map<String, Long> = Files.walk(temp).use { paths ->
        paths.toList().associate { temp.relativize(it).toString() to if (Files.isRegularFile(it)) Files.size(it) else -1L }
    }

    /** Makes [link] a link to the folder [target]: a symbolic link when the system allows it, else a junction of Windows. */
    private fun linkFolder(link: Path, target: Path): Boolean {
        if (runCatching { Files.createSymbolicLink(link, target) }.isSuccess) return links.add(link)
        return junction(link, target)
    }

    /** Makes [link] a link to the file [target]; without the right to make one (Windows), a junction to its folder in its place. */
    private fun linkFile(link: Path, target: Path): Boolean {
        if (runCatching { Files.createSymbolicLink(link, target) }.isSuccess) return links.add(link)
        return junction(link, target.parent)
    }

    private fun junction(link: Path, target: Path): Boolean {
        if (!System.getProperty("os.name").startsWith("Windows")) return false
        val process = ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString()).redirectErrorStream(true).start()
        process.inputStream.readAllBytes()
        return process.waitFor() == 0 && Files.exists(link, LinkOption.NOFOLLOW_LINKS) && links.add(link)
    }

    /** The links a test made, removed (never what they point to) before the test's folder is. */
    private val links = mutableListOf<Path>()

    @AfterEach
    fun removeLinks() {
        links.forEach { Files.deleteIfExists(it) }
    }

    private companion object {
        val SOURCE = ForgeSamples.SOURCE.replace("tag_game", "statues").replace("TagGame", "Statues")
    }
}
