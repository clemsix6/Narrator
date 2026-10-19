package io.github.clemsix6.narrator2.gametest

import com.anthropic.models.messages.ToolResultBlockParam
import io.github.clemsix6.narrator2.event.Envelope
import io.github.clemsix6.narrator2.event.NarratorChannel
import io.github.clemsix6.narrator2.event.NarratorSpoke
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolRunner
import io.github.clemsix6.narrator2.tool.ToolSet
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.tool.of
import io.github.clemsix6.narrator2.tool.speech.ActionbarArgs
import io.github.clemsix6.narrator2.tool.speech.ActionbarTool
import io.github.clemsix6.narrator2.tool.speech.GiveBookArgs
import io.github.clemsix6.narrator2.tool.speech.GiveBookTool
import io.github.clemsix6.narrator2.tool.speech.PlaySoundArgs
import io.github.clemsix6.narrator2.tool.speech.PlaySoundTool
import io.github.clemsix6.narrator2.tool.speech.SayArgs
import io.github.clemsix6.narrator2.tool.speech.SayTool
import io.github.clemsix6.narrator2.tool.speech.ShowTitleArgs
import io.github.clemsix6.narrator2.tool.speech.ShowTitleTool
import io.github.clemsix6.narrator2.tool.speech.Speech
import io.github.clemsix6.narrator2.tool.speech.SpeechLimiter
import io.github.clemsix6.narrator2.tool.toolUse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.component.DataComponents
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.world.item.Items
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * In-game tests of the speech tools (M1_M2 §9.1, §17.3): what the players get, the [NarratorSpoke]
 * of each line and its row in the Journal's `chat` table.
 *
 * Each test has its own [TestPlayer]s (players with unique names, who record the packets they get) and
 * its own [SpeechLimiter]: tests run side by side, and the server's limiter is shared.
 */
class SpeechGameTests {

    /** The acceptance of T14: `say` journals its line, which the Journal writes to `chat`. */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun sayPublishesNarratorSpokeAndTheLineReachesTheChatTable(helper: GameTestHelper) {
        val runtime = helper.narrator()
        val listener = TestPlayer(helper)
        val spoken = Spoken(helper) { it.recipients?.any { ref -> ref.uuid == listener.uuid } == true }
        val (line, envelopeId, toolUseId) = try {
            val toolUseId = "toolu_speech_${UUID.randomUUID().toString().take(8)}"
            val input = """{"text":"§6Oyez, oyez !\nBernard est condamné.","to":["${listener.name}"]}"""
            val result = runBlocking {
                ToolRunner(runtime).runAll(listOf(toolUse(toolUseId, "say", input)), ToolSet.of(listOf(SayTool(SpeechLimiter(runtime.clock)))), CALLER, 0)
            }.single()
            helper.assertEquals(
                "ok: whispered to ${listener.name} (§ formatting codes were removed: text is plain)",
                result.text(),
                "tool result",
            )

            val envelope = spoken.envelopes.single()
            val event = envelope.event as NarratorSpoke
            helper.assertEquals(NarratorChannel.NARRATOR, event.channel, "channel")
            helper.assertEquals("Oyez, oyez !\nBernard est condamné.", event.text, "the cleaned text")
            helper.assertEquals(listOf(listener.ref), event.recipients, "recipients")
            helper.assertEquals(CALLER.turnId, event.turnId, "turn")
            helper.assertEquals(null, event.operator, "operator")
            val chat = listener.chat().single()
            helper.assertEquals("[Narrateur → toi] Oyez, oyez !\nBernard est condamné.", chat.content().string, "what the player reads")
            helper.assertFalse(chat.overlay(), "in the chat, not above the hotbar")
            Triple(event.text, envelope.id, toolUseId)
        } finally {
            spoken.close()
            listener.close()
        }

        val rows = AtomicReference<Any>()
        CoroutineScope(Dispatchers.IO).launch {
            rows.set(
                try {
                    runtime.journal.barrier()
                    runtime.journal.read(5_000) { connection ->
                        val chat = connection.prepareStatement(
                            "SELECT channel, player, CAST(recipients AS VARCHAR), text, turn_id, operator FROM chat WHERE id = ?",
                        ).use { statement ->
                            statement.setLong(1, envelopeId)
                            statement.executeQuery().use { r -> if (r.next()) listOf(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getLong(5), r.getString(6)) else null }
                        }
                        val call = connection.prepareStatement("SELECT status, effect FROM tool_calls WHERE tool_use_id = ?").use { statement ->
                            statement.setString(1, toolUseId)
                            statement.executeQuery().use { r -> if (r.next()) listOf(r.getString(1), r.getString(2)) else null }
                        }
                        listOf(chat, call)
                    }
                } catch (e: Exception) {
                    "read failed: $e"
                },
            )
        }
        helper.awaitAsync {
            helper.assertEquals(
                listOf(listOf("narrator", null, "[${listener.name}]", line, CALLER.turnId, null), listOf("ok", "speech")),
                rows.get(),
                "the chat row of event $envelopeId and the tool_calls row",
            )
        }
    }

    @GameTest
    fun aPublicLineReachesEveryoneWithThePrefixAndTheConsole(helper: GameTestHelper) {
        val marker = "Marqueur ${UUID.randomUUID().toString().take(8)}"
        withTestPlayers(helper, 2) { (first, second) ->
            val spoken = Spoken(helper) { marker in it.text }
            try {
                val result = run(helper, SayTool(SpeechLimiter(helper.narrator().clock)), SayArgs("Oyez, mortels. $marker"))
                helper.assertTrue(result.text.startsWith("ok: said to everyone ("), result.text)
                helper.assertEquals(null, (spoken.envelopes.single().event as NarratorSpoke).recipients, "said to everyone")
            } finally {
                spoken.close()
            }
            for (listener in listOf(first, second)) {
                val chat = listener.chat().single { marker in it.content().string }
                helper.assertEquals("[Narrateur] Oyez, mortels. $marker", chat.content().string, "public prefix")
                helper.assertEquals(Speech.color(helper.narrator().settings.get().ui), chat.content().siblings.first().style.color, "prefix color")
            }
        }
    }

    @GameTest
    fun aPrivateLineReachesItsRecipientsOnly(helper: GameTestHelper) = withTestPlayers(helper, 2) { (bob, alice) ->
        val result = run(helper, SayTool(SpeechLimiter(helper.narrator().clock)), SayArgs("Rien que pour toi.", to = listOf(bob.name.uppercase())))

        helper.assertEquals("ok: whispered to ${bob.name}", result.text, "names ignore case")
        helper.assertEquals(listOf("[Narrateur → toi] Rien que pour toi."), bob.chat().map { it.content().string }, "Bob")
        helper.assertEquals(emptyList<String>(), alice.chat().map { it.content().string }, "Alice")
    }

    @GameTest
    fun sayNamesWhoIsOnlineWhenAPlayerIsNot(helper: GameTestHelper) = withTestPlayers(helper, 1) { (bob) ->
        val result = run(helper, SayTool(SpeechLimiter(helper.narrator().clock)), SayArgs("Allô ?", to = listOf("nobody_here")))

        helper.assertEquals(ToolStatus.INVALID, result.status, result.text)
        helper.assertTrue(result.text.startsWith("Invalid arguments: nobody_here is not online; online: ") && bob.name in result.text, result.text)
        helper.assertEquals(emptyList<ClientboundSystemChatPacket>(), bob.chat(), "nothing said")
    }

    /** The subtitle goes before the title (the title packet shows the last subtitle received); then 30 s per player. */
    @GameTest
    fun aTitleSendsItsSubtitleFirstThenWaitsThirtySeconds(helper: GameTestHelper) = withTestPlayers(helper, 1) { (bob) ->
        val tool = ShowTitleTool(SpeechLimiter(helper.narrator().clock))
        val spoken = Spoken(helper) { it.recipients == listOf(bob.ref) }
        try {
            val result = run(helper, tool, ShowTitleArgs("§lJUGEMENT", subtitle = "Bernard\nest condamné", to = listOf(bob.name), seconds = 4))

            helper.assertEquals("ok: title shown to ${bob.name}", result.text, "result")
            val packets = bob.packets.filter { it is ClientboundSetTitlesAnimationPacket || it is ClientboundSetSubtitleTextPacket || it is ClientboundSetTitleTextPacket }
            helper.assertEquals(
                listOf("animation 10/80/20", "subtitle Bernard est condamné", "title JUGEMENT"),
                packets.map(::describe),
                "packets in order",
            )
            val title = packets.last() as ClientboundSetTitleTextPacket
            helper.assertEquals(Speech.color(helper.narrator().settings.get().ui), title.text().style.color, "the Narrator's color")
            helper.assertEquals("[title] JUGEMENT / Bernard est condamné", (spoken.envelopes.single().event as NarratorSpoke).text, "journaled")

            val again = run(helper, tool, ShowTitleArgs("ENCORE", to = listOf(bob.name)))
            helper.assertEquals(ToolStatus.DENIED, again.status, again.text)
            helper.assertTrue(again.text.startsWith("DENIED (speech_limit): one title per player every 30s: ${bob.name} got one"), again.text)
            helper.assertEquals(1, bob.packets.count { it is ClientboundSetTitleTextPacket }, "no second title")
        } finally {
            spoken.close()
        }
    }

    /** A per-player limit holds back only the players who must wait; the others get the title, and only they are journaled. */
    @GameTest
    fun aTitleSkipsOnlyThePlayersWhoMustWait(helper: GameTestHelper) = withTestPlayers(helper, 2) { (bob, alice) ->
        val tool = ShowTitleTool(SpeechLimiter(helper.narrator().clock))
        run(helper, tool, ShowTitleArgs("Premier", to = listOf(bob.name)))
        val spoken = Spoken(helper) { it.text == "[title] Second" }
        try {
            val result = run(helper, tool, ShowTitleArgs("Second", to = listOf(bob.name, alice.name)))

            helper.assertEquals(ToolStatus.OK, result.status, result.text)
            helper.assertTrue(
                result.text.startsWith("ok: title shown to ${alice.name}; skipped (one title per player every 30s): ${bob.name} got one "),
                result.text,
            )
            helper.assertEquals(listOf(alice.ref), (spoken.envelopes.single().event as NarratorSpoke).recipients, "journaled for Alice only")
            helper.assertEquals(1, bob.packets.count { it is ClientboundSetTitleTextPacket }, "Bob keeps his first title only")
            helper.assertEquals(1, alice.packets.count { it is ClientboundSetTitleTextPacket }, "Alice gets hers")
        } finally {
            spoken.close()
        }
    }

    @GameTest
    fun aTitleWithoutSubtitleClearsTheLastOne(helper: GameTestHelper) = withTestPlayers(helper, 1) { (bob) ->
        run(helper, ShowTitleTool(SpeechLimiter(helper.narrator().clock)), ShowTitleArgs("Silence", to = listOf(bob.name)))

        val subtitle = bob.packets.filterIsInstance<ClientboundSetSubtitleTextPacket>().single()
        helper.assertEquals("", subtitle.text().string, "an empty subtitle")
    }

    @GameTest
    fun actionbarShowsAColoredLineAboveTheHotbar(helper: GameTestHelper) = withTestPlayers(helper, 1) { (bob) ->
        val tool = ActionbarTool(SpeechLimiter(helper.narrator().clock))
        val result = run(helper, tool, ActionbarArgs("Psst… derrière toi.", to = listOf(bob.name)))

        helper.assertEquals("ok: shown to ${bob.name}", result.text, "result")
        val line = bob.packets.filterIsInstance<ClientboundSystemChatPacket>().single()
        helper.assertTrue(line.overlay(), "above the hotbar")
        helper.assertEquals("Psst… derrière toi.", line.content().string, "text")
        helper.assertEquals(Speech.color(helper.narrator().settings.get().ui), line.content().style.color, "the Narrator's color")
        helper.assertEquals(ToolStatus.DENIED, run(helper, tool, ActionbarArgs("Encore.", to = listOf(bob.name))).status, "5 s per player")
    }

    @GameTest
    fun playSoundChecksItsIdAndPlaysItPrivately(helper: GameTestHelper) = withTestPlayers(helper, 2) { (bob, alice) ->
        val tool = PlaySoundTool(SpeechLimiter(helper.narrator().clock))

        val unknown = run(helper, tool, PlaySoundArgs("entity.lightning_bolt.thuner", to = listOf(bob.name)))
        helper.assertEquals(ToolStatus.INVALID, unknown.status, unknown.text)
        helper.assertTrue(unknown.text.contains("closest ids: entity.lightning_bolt.thunder"), unknown.text)

        val played = run(helper, tool, PlaySoundArgs("minecraft:entity.lightning_bolt.thunder", to = listOf(bob.name), volume = 0.5, pitch = 1.5))
        helper.assertEquals("ok: entity.lightning_bolt.thunder played for ${bob.name}, privately", played.text, "result")
        val sound = bob.packets.filterIsInstance<ClientboundSoundPacket>().single()
        helper.assertEquals("minecraft:entity.lightning_bolt.thunder", sound.sound.registeredName, "sound")
        helper.assertEquals(0.5f to 1.5f, sound.volume to sound.pitch, "volume and pitch")
        helper.assertEquals(bob.player.x to bob.player.z, sound.x to sound.z, "at the player")
        helper.assertEquals(emptyList<ClientboundSoundPacket>(), alice.packets.filterIsInstance<ClientboundSoundPacket>(), "Alice hears nothing")
    }

    @GameTest
    fun giveBookGivesASignedBookOncePerFiveMinutes(helper: GameTestHelper) = withTestPlayers(helper, 1) { (bob) ->
        val ui = helper.narrator().settings.get().ui
        val tool = GiveBookTool(SpeechLimiter(helper.narrator().clock))
        val spoken = Spoken(helper) { it.recipients == listOf(bob.ref) }
        try {
            val long = (1..90).joinToString(" ") { "prophétie" }.take(600)
            val result = run(helper, tool, GiveBookArgs(bob.name, "§5Prophétie n°7", listOf("Il était une fois.\n\nFin ?", long)))

            val pages = Speech.layOut(listOf("Il était une fois.\n\nFin ?", long))
            helper.assertEquals("ok: book \"Prophétie n°7\" given to ${bob.name}, ${pages.size} pages", result.text, "result")
            helper.assertTrue(pages.size >= 3, "the long page continues: $pages")
            val book = (0 until bob.player.inventory.containerSize).map { bob.player.inventory.getItem(it) }.single { it.`is`(Items.WRITTEN_BOOK) }
            val content = book.get(DataComponents.WRITTEN_BOOK_CONTENT)!!
            helper.assertEquals("Prophétie n°7", content.title().raw(), "title")
            helper.assertEquals(ui["book.author"], content.author(), "author")
            helper.assertEquals(pages, content.pages().map { it.raw().string }, "pages")
            helper.assertEquals("Il était une fois.\n\nFin ?", content.pages().first().raw().string, "the first page as written")
            val mark = book.get(DataComponents.CUSTOM_DATA)!!.copyTag().getCompoundOrEmpty(Speech.CUSTOM_DATA_KEY)
            helper.assertEquals("book", mark.getStringOr("kind", ""), "marked as the Narrator's")
            helper.assertEquals(CALLER.turnId, mark.getLongOr("turn", -1), "with its turn")
            val record = (spoken.envelopes.single().event as NarratorSpoke).text
            helper.assertTrue(record.startsWith("[book \"Prophétie n°7\"] Il était une fois. Fin ? / prophétie"), record)

            val again = run(helper, tool, GiveBookArgs(bob.name, "Bis", listOf("Encore.")))
            helper.assertEquals(ToolStatus.DENIED, again.status, again.text)
            helper.assertTrue(again.text.contains("one book per player every 5m"), again.text)
        } finally {
            spoken.close()
        }
    }

    /**
     * The dry checks (`check`, M4 §4.3) of the speech tools a rule may use, with the game's registries: an
     * unknown sound gets its closest ids, as `run` answers; nobody needs to be online, and nothing is counted
     * against the limits. The engine runs them when a rule is created.
     */
    @GameTest
    fun theDryChecksTouchNothing(helper: GameTestHelper) = withTestPlayers(helper, 1) { (bob) ->
        val limiter = SpeechLimiter(helper.narrator().clock)
        val unknown = check(helper, PlaySoundTool(limiter), PlaySoundArgs("entity.lightning_bolt.thuner", to = listOf("nobody_here")))
        helper.assertEquals(ToolStatus.INVALID, unknown?.status, "$unknown")
        helper.assertTrue(unknown!!.text.contains("closest ids: entity.lightning_bolt.thunder"), unknown.text)
        helper.assertEquals(null, check(helper, PlaySoundTool(limiter), PlaySoundArgs("minecraft:block.bell.use", to = listOf(bob.name))), "a known sound")
        helper.assertEquals(null, check(helper, SayTool(limiter), SayArgs("La loi est la loi.", to = listOf(bob.name))), "say")
        helper.assertEquals(null, check(helper, ShowTitleTool(limiter), ShowTitleArgs("Châtiment", to = listOf(bob.name))), "show_title")
        helper.assertEquals(null, check(helper, ActionbarTool(limiter), ActionbarArgs("Psst.", to = listOf(bob.name))), "actionbar")

        // Other tests may speak to everyone meanwhile: Bob's packets prove nothing; the limiter is this test's own.
        helper.assertEquals(listOf(bob.ref), limiter.titles(listOf(bob.ref)).allowed, "no title was counted")
        helper.assertEquals(listOf(bob.ref), limiter.actionbars(listOf(bob.ref)).allowed, "no line above the hotbar was counted")
    }

    private companion object {
        val CALLER = Caller(Role.DIRECTOR, "speech-gametest", turnId = 4_242, taskId = null)

        /** The dry checks of [tool] for [args], as the engine runs them (`EngineActions.check`). */
        fun <A : Any> check(helper: GameTestHelper, tool: NarratorTool<A>, args: A): ToolResult? {
            val runtime = helper.narrator()
            return runBlocking { tool.check(ToolContext(runtime, CALLER, runtime.clock, 5_000), args) }
        }

        /** Runs [tool] on the server thread, where its world access runs inline. */
        fun <A : Any> run(helper: GameTestHelper, tool: NarratorTool<A>, args: A): ToolResult {
            val runtime = helper.narrator()
            return runBlocking { tool.run(ToolContext(runtime, CALLER, runtime.clock, 5_000), args) }
        }

        fun ToolResultBlockParam.text(): String = content().get().asString()

        fun describe(packet: Packet<*>): String = when (packet) {
            is ClientboundSetTitlesAnimationPacket -> "animation ${packet.fadeIn}/${packet.stay}/${packet.fadeOut}"
            is ClientboundSetSubtitleTextPacket -> "subtitle ${packet.text().string}"
            is ClientboundSetTitleTextPacket -> "title ${packet.text().string}"
            else -> packet.toString()
        }
    }
}

/** The [NarratorSpoke] events that [accept] picks, published from now until [close]. */
private class Spoken(helper: GameTestHelper, accept: (NarratorSpoke) -> Boolean) : AutoCloseable {
    private val bus = helper.narrator().bus
    private val owner = "speech-test-${UUID.randomUUID()}"
    val envelopes = CopyOnWriteArrayList<Envelope>()

    init {
        bus.subscribeSync("spoken", owner, { it is NarratorSpoke }) { envelope ->
            if (accept(envelope.event as NarratorSpoke)) envelopes += envelope
        }
    }

    override fun close() = bus.unsubscribeOwner(owner)
}
