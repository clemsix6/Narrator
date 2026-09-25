package io.github.clemsix6.narrator2.live

import com.anthropic.models.messages.ContentBlockParam
import io.github.clemsix6.narrator2.agent.AgentLoop
import io.github.clemsix6.narrator2.agent.Pending
import io.github.clemsix6.narrator2.agent.TurnLimits
import io.github.clemsix6.narrator2.agent.TurnOutcome
import io.github.clemsix6.narrator2.config.GatewayConfig
import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.director.Briefing
import io.github.clemsix6.narrator2.director.Chapters
import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.NarratorChannel
import io.github.clemsix6.narrator2.event.NarratorSpoke
import io.github.clemsix6.narrator2.event.PlayerJoined
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.journal.JournalFixture
import io.github.clemsix6.narrator2.llm.ClaudeClient
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.observer.LivePlayer
import io.github.clemsix6.narrator2.observer.PlayerStates
import io.github.clemsix6.narrator2.oracle.Enclosure
import io.github.clemsix6.narrator2.oracle.PlayerSnapshot
import io.github.clemsix6.narrator2.oracle.WorldSnapshot
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.testing.testSettings
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.Doc
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolRunner
import io.github.clemsix6.narrator2.tool.ToolSet
import io.github.clemsix6.narrator2.tool.of
import io.github.clemsix6.narrator2.trace.ConversationRecord
import io.github.clemsix6.narrator2.trace.ConversationRecord.EndReason
import io.github.clemsix6.narrator2.trace.Transcripts
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A real chapter against the owner's LLMGW server (M1_M2 §12.10, §12.11): the real `director` prompt,
 * a briefing built from a real Journal, two wake-ups answered by Opus 5.5 with a `say` tool, then the
 * closing request with the real `chapter_summary` prompt; the next chapter's briefing must hold the
 * summary. Run by `gradlew liveTest` only (a few cents); skipped without a key or a tunnel. Everything
 * the model got and wrote goes to `build/reports/liveTest/chapter.md`, for a human to judge the tone:
 * factual, third person, never the Narrator's voice.
 */
@Tag("live")
class LiveChapterTest {
    private val start = Instant.now().truncatedTo(ChronoUnit.SECONDS)
    private val clock = TestClock(start.minus(Duration.ofMinutes(50)))
    private val hms = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC)
    private val bartok = PlayerRef(UUID.nameUUIDFromBytes("Bartok".toByteArray()), "Bartok")
    private val mimosa = PlayerRef(UUID.nameUUIDFromBytes("Mimosa".toByteArray()), "Mimosa")

    @Test
    fun `a real chapter closes with a factual summary that the next briefing holds`() {
        val key = requireGateway()
        val report = Path.of("build/reports/liveTest").toAbsolutePath().also { Files.createDirectories(it) }
        val config = NarratorConfig(gateway = GatewayConfig(baseUrl = BASE_URL, maxRetries = 1))
        ClaudeClient(config, key).use { client ->
            JournalFixture(clock = clock).use { fixture ->
                val players = PlayerStates()
                val services = TestServices(
                    clock = clock, settings = testSettings(config, client), bus = fixture.bus, journal = fixture.journal, trace = fixture.journal,
                    transcripts = Transcripts(report.resolve("transcripts"), enabled = true), players = players,
                )
                history(fixture)
                listOf(bartok, mimosa).forEach { players.put(live(it)) }
                val world = WorldSnapshot(
                    12, 13_500, "clear",
                    listOf(
                        PlayerSnapshot(bartok, "survival", "minecraft:overworld", 210, 71, -340, "minecraft:plains", null, Enclosure.OPEN_SKY, null, 18f, 20f, 17, null),
                        PlayerSnapshot(mimosa, "survival", "minecraft:overworld", 180, 64, -300, "minecraft:beach", null, Enclosure.OPEN_SKY, null, 20f, 20f, 20, null),
                    ),
                )
                val chapters = Chapters(services, { ToolSet.of(listOf(SayTool())) }, Briefing(services, { world }) { null })
                val loop = AgentLoop(services, ToolRunner(services))
                val out = StringBuilder()

                runBlocking {
                    val chapter = chapters.current()
                    val briefing = assertNotNull(chapters.briefing(chapter, clock.millis(), clock.millis(), emptyList()))
                    out.append("## Briefing of chapter ${chapter.number}\n\n```\n$briefing\n```\n\n")

                    val wakes = listOf(
                        ChatMessage(bartok, ChatChannel.PUBLIC, "narrateur je parie que t'es même pas capable de me faire voler", emptyList()) to
                            "(to you 0.94, mocking_playful, provocation)",
                        ChatMessage(mimosa, ChatChannel.PRAY, "narrateur protège moi des creepers cette nuit, je t'offrirai trois diamants demain", emptyList()) to
                            "(to you 0.97, polite, request)",
                    )
                    wakes.forEachIndexed { index, (message, annotation) ->
                        clock.advance(Duration.ofSeconds(40))
                        fixture.publish(message)
                        clock.advance(Duration.ofSeconds(2))
                        val kind = if (message.channel == ChatChannel.PRAY) "PRAY" else "CHAT"
                        val wake = "<events now=\"${hms.format(clock.instant())}\" since=\"${hms.format(clock.instant().minusSeconds(2))}\">\n" +
                            "[-2s] $kind ${message.player.name} $annotation: \"${message.text}\"\n</events>\n" +
                            "<situation now=\"${hms.format(clock.instant())}\" day=\"12\" time=\"night\" weather=\"clear\">\n" +
                            "Bartok: overworld y=71 plains · hp 18/20 food 17 · online 50m\nMimosa: overworld y=64 beach · hp 20/20 food 20 · online 50m\n" +
                            "Your active effects: none\nTasks: none · Reminders: none\nYour last actions: none\n</situation>"
                        val blocks = (if (index == 0) listOf(ContentBlockParam.ofText(briefing)) else emptyList()) + ContentBlockParam.ofText(wake)
                        out.append("## Wake-up ${index + 1}\n\n```\n$wake\n```\n\n")
                        val outcome = loop.runTurn(chapter.conversation, Pending(blocks), Caller(Role.DIRECTOR, chapter.conversation.id, index + 1L, null), TurnLimits(6))
                        println("[live] turn ${index + 1}: $outcome")
                        assertIs<TurnOutcome.Ended>(outcome)
                    }
                    out.append("## What the Narrator said\n\n").append(said.joinToString("\n") { "- $it" }).append("\n\n")

                    clock.advance(Duration.ofMinutes(3))
                    chapters.closeWithSummary(EndReason.OPERATOR, "live test")
                }
                val summary = fixture.rows("SELECT summary FROM conversations WHERE chapter = 5") { getString(1) }.single()
                println("[live] summary:\n$summary")
                out.append("## Summary of chapter 5\n\n```\n$summary\n```\n\n")
                assertNotNull(summary)
                listOf("Joueurs vus", "Engagements en cours", "Bartok", "Mimosa").forEach { assertContains(summary, it) }

                val next = runBlocking {
                    val chapter = chapters.current()
                    assertNotNull(chapters.briefing(chapter, clock.millis(), clock.millis(), emptyList()))
                }
                out.append("## Briefing of chapter 6\n\n```\n$next\n```\n")
                Files.writeString(report.resolve("chapter.md"), out.toString())
                println("[live] report written to ${report.resolve("chapter.md")}")
                assertContains(next, "#5 ")
                assertContains(next, summary.lines().first { it.isNotBlank() })
                assertTrue(said.isNotEmpty(), "the Narrator answered through say")
            }
        }
    }

    /** Two players online for 50 minutes, their chat and a line of the Narrator, and the previous chapter with an open challenge. */
    private fun history(fixture: JournalFixture) {
        fun at(minutesAgo: Long, event: GameEvent) {
            clock.now = start.minus(Duration.ofMinutes(minutesAgo))
            fixture.publish(event)
        }
        at(50, PlayerJoined(bartok, Pos("minecraft:overworld", 0, 64, 0), 1, firstTime = false, isOp = false))
        at(50, PlayerJoined(mimosa, Pos("minecraft:overworld", 5, 64, 5), 2, firstTime = false, isOp = false))
        at(20, ChatMessage(mimosa, ChatChannel.PUBLIC, "j'ai trouvé la plage, les tortues sont là", emptyList()))
        at(19, NarratorSpoke(NarratorChannel.NARRATOR, "Une plage, des tortues, et Mimosa qui les regarde avec l'appétit d'un chef étoilé. Le défi tient toujours.", null, 3, null))
        at(12, ChatMessage(bartok, ChatChannel.PUBLIC, "moi je cherche du fer, laissez moi tranquille", emptyList()))
        fixture.journal.conversation(
            ConversationRecord(
                "director-live4", Role.DIRECTOR, "Chapter 4", 4, null, start.minus(Duration.ofMinutes(48)), start.minus(Duration.ofMinutes(5)), EndReason.TOKEN_LIMIT,
                """
                Joueurs vus : Bartok, Mimosa.
                Faits marquants :
                - Bartok est mort dans la lave en minant (y=-40).
                Interventions du Narrateur :
                - Bartok — surnommé « le Plongeur » après sa chute dans la lave → Bartok a répondu « très drôle ».
                Engagements en cours :
                - défi — envers tous — « Le premier qui me rapporte un œuf de tortue gagne ma bénédiction » — avant la fin de la nuit.
                - surnom — envers Bartok — « le Plongeur ».
                Fils ouverts :
                - aucun
                Répertoire utilisé : commentaire sportif (chute de Bartok).
                État des joueurs :
                - Bartok : mine du fer ; avec le Narrateur : surnommé.
                - Mimosa : cherche des tortues pour le défi.
                """.trimIndent(),
                null,
            ),
        )
        clock.now = start
        fixture.sync()
    }

    private fun live(player: PlayerRef) = LivePlayer(
        ref = player, sessionId = 1, joinedAt = start.minus(Duration.ofMinutes(50)).toEpochMilli(), isOp = false, gameMode = "survival",
        pos = Pos("minecraft:overworld", 200, 70, -330), biome = "minecraft:plains", structure = null, underground = false,
        health = 20f, maxHealth = 20f, food = 20, vehicle = null, lastDeathAt = null, lastMoveAt = start.toEpochMilli(), idle = false,
    )

    private val said = mutableListOf<String>()

    @Serializable
    data class SayArgs(@Doc("What to say.") val text: String, @Doc("Names of online players; everyone when left out.") val to: List<String>? = null)

    /** `say`: journals the line like the real tool (a `NarratorSpoke`), and remembers it. */
    private inner class SayTool : NarratorTool<SayArgs>("say", "Speak to the players in the chat; `to` whispers to some of them.", SayArgs.serializer(), ToolEffect.SPEECH) {
        override suspend fun run(ctx: ToolContext, args: SayArgs): ToolResult {
            val targets = args.to?.map { name -> ctx.players.byName(name)?.ref ?: return ToolResult.invalid("$name is not online") }
            ctx.services.bus.publish(NarratorSpoke(NarratorChannel.NARRATOR, args.text, targets, ctx.caller.turnId, null))
            said += (targets?.joinToString(",") { it.name }?.let { "→$it: " } ?: "→all: ") + args.text
            return ToolResult.ok("ok: said to ${targets?.joinToString { it.name } ?: "everyone (2 online)"}", targets ?: ctx.players.online.map { it.ref })
        }
    }

    private companion object {
        const val BASE_URL = "http://localhost:8088"

        /** The LLMGW key, or skips the test without a key or a tunnel. */
        fun requireGateway(): String {
            val key = System.getenv("NARRATOR_LLMGW_KEY")?.trim()?.ifEmpty { null }
                ?: System.getProperty("narrator2.live.keyFile")?.let { Path.of(it) }?.takeIf { Files.isRegularFile(it) }
                    ?.let { Files.readString(it).trim().ifEmpty { null } }
            assumeTrue(key != null) { "no LLMGW key: set NARRATOR_LLMGW_KEY or create .secrets/llmgw.key" }
            val healthy = runCatching {
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
                    .send(HttpRequest.newBuilder(URI("$BASE_URL/healthz")).timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.discarding())
                    .statusCode() == 200
            }.getOrDefault(false)
            assumeTrue(healthy) { "LLMGW does not answer on $BASE_URL/healthz: open the SSH tunnel first" }
            return key!!
        }
    }
}
