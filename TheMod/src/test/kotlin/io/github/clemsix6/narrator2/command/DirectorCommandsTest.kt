package io.github.clemsix6.narrator2.command

import io.github.clemsix6.narrator2.config.ConfigException
import io.github.clemsix6.narrator2.config.ConfigStore
import io.github.clemsix6.narrator2.director.DirectorControl
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.DirectorState
import io.github.clemsix6.narrator2.testing.FakeDirector
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.trace.ConversationRecord
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The operators' hold on the Director (pause, resume, whisper, chapter close) against a fake Director,
 * `/narrator chaos` (the configuration file it edits, what it shows) and the rate of `/pray`.
 */
class DirectorCommandsTest {

    @Test
    fun `chaos is set in the operator's file, every other setting kept in its order`() {
        val text = """
            {
              "gateway": { "baseUrl": "http://127.0.0.1:8088", "maxRetries": 0 },
              "chaos": 3,
              "director": { "enabled": false }
            }
        """.trimIndent()
        assertEquals(
            """
            {
              "gateway": {
                "baseUrl": "http://127.0.0.1:8088",
                "maxRetries": 0
              },
              "chaos": 5,
              "director": {
                "enabled": false
              }
            }

            """.trimIndent(),
            DirectorCommands.withChaos(text, 5),
        )
        assertEquals("{\n  \"language\": \"fr\",\n  \"chaos\": 0\n}\n", DirectorCommands.withChaos("""{"language": "fr"}""", 0), "a missing setting is added")
        assertFailsWith<ConfigException> { DirectorCommands.withChaos("{ not json", 2) }
        assertFailsWith<ConfigException> { DirectorCommands.withChaos("[1, 2]", 2) }
    }

    @Test
    fun `the file is written whole, and loads with the new level`(@TempDir dir: Path) {
        val file = dir.resolve(ConfigStore.FILE_NAME)
        Files.writeString(file, """{ "chaos": 3, "safety": { "protectedPlayers": ["Mimosa"] } }""")
        DirectorCommands.writeChaos(file, 1)
        val config = ConfigStore.load(dir)
        assertEquals(1, config.chaos)
        assertEquals(listOf("Mimosa"), config.safety.protectedPlayers)
        assertEquals(listOf(ConfigStore.FILE_NAME), Files.list(dir).use { files -> files.map { it.fileName.toString() }.toList() }, "no temporary file left")

        val missing = dir.resolve("fresh").resolve(ConfigStore.FILE_NAME)
        DirectorCommands.writeChaos(missing, 4)
        assertEquals(4, ConfigStore.load(missing.parent).chaos)
    }

    @Test
    fun `chaos shows what the engine allows at a level`() {
        val three = DirectorCommands.chaosLines(3).map { it.string }
        assertEquals("[Narrator] chaos 3 of 5", three[0])
        assertEquals(
            "Harmful effects: up to 300 s, level 2 · creatures: 6 per action, 16 alive · explosions: power 3 · lightning: 3 hearts",
            three[1],
        )
        assertEquals("Launch: strength 2 · teleport: 1000 blocks · confiscation: 32 items · deaths caused: 1 per played hour", three[2])
        assertTrue(DirectorCommands.chaosLines(1)[1].string.endsWith("lightning: 1 heart"))
        assertTrue("strength 1.5" in DirectorCommands.chaosLines(2)[2].string)
        assertEquals(
            listOf("[Narrator] chaos 0 of 5", "No hostile action at all: no harmful effect, no punishment, no death caused by the Narrator."),
            DirectorCommands.chaosLines(0).map { it.string },
        )
    }

    private val director = FakeDirector()
    private val services = TestServices(director = director)

    private fun decisions() = services.traces.decisions.map { listOf(it.kind.id, it.decision, it.subject) }

    private fun DirectorCommands.Reply.text() = lines.map { it.string }

    @Test
    fun `pause and resume reach the Director and are recorded as the operator's`() {
        val paused = DirectorCommands.pause(services, "Clement")
        assertTrue(paused.done)
        assertEquals(listOf("[Narrator] paused: the Director ends its current turn, if any, then stays silent until /narrator resume."), paused.text())
        assertEquals(listOf<DirectorSignal>(DirectorSignal.Pause), director.signals)

        director.state = DirectorState.PAUSED
        assertEquals(listOf("[Narrator] the Director is already paused; /narrator resume wakes it."), DirectorCommands.pause(services, "Clement").text())
        val resumed = DirectorCommands.resume(services, "Clement")
        assertEquals(listOf("[Narrator] resumed: the Director wakes up with what waited."), resumed.text())
        assertEquals(listOf(DirectorSignal.Pause, DirectorSignal.Resume), director.signals)
        assertEquals(listOf(listOf("operator", "paused", "Clement"), listOf("operator", "resumed", "Clement")), decisions())
    }

    @Test
    fun `a disabled Director is neither paused nor resumed nor whispered to`() {
        director.state = DirectorState.DISABLED
        val disabled = object : DirectorControl by director {
            override fun status() = director.status().copy(reason = "no LLMGW key")
        }
        val off = TestServices(director = disabled)
        for (reply in listOf(DirectorCommands.pause(off, "Clement"), DirectorCommands.resume(off, "Clement"), DirectorCommands.whisper(off, "Clement", "Be nice."))) {
            assertFalse(reply.done)
            assertTrue(reply.text().single().startsWith("[Narrator] the Director is disabled (no LLMGW key): "), reply.text().toString())
        }
        assertEquals(emptyList(), director.signals, "nothing waits in a disabled Director")
        assertEquals(emptyList(), off.traces.decisions)
    }

    @Test
    fun `a whisper goes to the Director with its author and time`() {
        val reply = DirectorCommands.whisper(services, "Clement", "  Be nicer to Bartok.  ")
        assertEquals(listOf("[Narrator] sent: the Director wakes up to read it."), reply.text())
        assertEquals(listOf<DirectorSignal>(DirectorSignal.OperatorWhisper("Clement", "Be nicer to Bartok.", services.clock.millis())), director.signals)
        val decision = services.traces.decisions.single()
        assertEquals(listOf("operator", "whispered", "Clement", "\"Be nicer to Bartok.\""), listOf(decision.kind.id, decision.decision, decision.subject, decision.reason))
        assertEquals("Be nicer to Bartok.", decision.detail?.get("text")?.jsonPrimitive?.content)

        director.state = DirectorState.PAUSED
        assertEquals(listOf("[Narrator] queued: the Director is paused and reads it when it wakes up."), DirectorCommands.whisper(services, "Clement", "Later.").text())
    }

    @Test
    fun `chapter close needs an open chapter`() {
        assertFalse(DirectorCommands.closeChapter(services, "Clement").done)
        val open = object : DirectorControl by director {
            override fun status() = director.status().copy(chapter = 3)
        }
        val reply = DirectorCommands.closeChapter(TestServices(director = open, trace = services.traces), "Clement")
        assertEquals(listOf("[Narrator] chapter 3 closes at the next quiet moment; the next wake-up opens a new one."), reply.text())
        assertEquals(listOf<DirectorSignal>(DirectorSignal.CloseChapter(ConversationRecord.EndReason.OPERATOR)), director.signals)
        assertEquals(listOf(listOf("operator", "chapter_close", "Clement")), decisions())
    }

    @Test
    fun `a player prays at most once every 10 seconds`() {
        assertEquals(0, PrayCommand.waitBefore(null, 1_000))
        assertEquals(10_000, PrayCommand.waitBefore(5_000, 5_000))
        assertEquals(1, PrayCommand.waitBefore(5_000, 14_999))
        assertEquals(0, PrayCommand.waitBefore(5_000, 15_000))
    }
}
