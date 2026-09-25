package io.github.clemsix6.narrator2.observer

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.arguments.StringArgumentType.greedyString
import com.mojang.brigadier.arguments.StringArgumentType.word
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import io.github.clemsix6.narrator2.config.ObserverConfig
import io.github.clemsix6.narrator2.event.CommandIssued
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.journal.Journal
import io.github.clemsix6.narrator2.journal.JournalFixture
import io.github.clemsix6.narrator2.journal.SqlLimits
import io.github.clemsix6.narrator2.journal.SqlOutcome
import io.github.clemsix6.narrator2.journal.SqlRunner
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the Journal keeps of a typed command (M2 review, finding 8), on a dispatcher shaped like the
 * game's: `execute` forks back to itself and `run` redirects to the root, `tell` and `w` redirect to
 * `msg`, `tm` to `teammsg`; plus another mod's private messages (`r`, `mail`), an alias of `msg` that no
 * list names, and an alias into the middle of `msg`. The real dispatcher is tested in game
 * (`ObserverMixinGameTests`).
 */
class CommandPrivacyTest {
    private val dispatcher = CommandDispatcher<Any>().apply {
        fun literal(name: String) = LiteralArgumentBuilder.literal<Any>(name)
        fun <T> argument(name: String, type: ArgumentType<T>) = RequiredArgumentBuilder.argument<Any, T>(name, type)
        val ok = Command<Any> { 1 }

        val msg = register(literal("msg").then(argument("targets", word()).then(argument("message", greedyString()).executes(ok))))
        register(literal("tell").redirect(msg))
        register(literal("w").redirect(msg))
        val teammsg = register(literal("teammsg").then(argument("message", greedyString()).executes(ok)))
        register(literal("tm").redirect(teammsg))
        register(literal("say").then(argument("message", greedyString()).executes(ok)))
        register(literal("gamemode").then(literal("creative").executes(ok)).then(literal("survival").executes(ok)))
        register(literal("list").executes(ok).then(literal("uuids").executes(ok)))
        register(literal("return").then(literal("run").redirect(root)))
        val execute = register(literal("execute"))
        register(
            literal("execute")
                .then(literal("run").redirect(root))
                .then(literal("as").then(argument("who", word()).redirect(execute))),
        )
        register(literal("narrator").then(literal("whisper").then(argument("text", greedyString()).executes(ok))))
        // Other mods: private messages, a command of their own, an alias of msg, an alias into msg's arguments.
        register(literal("r").then(argument("message", greedyString()).executes(ok)))
        register(literal("mail").then(literal("send").then(argument("to", word()).then(argument("message", greedyString()).executes(ok)))))
        register(literal("sethome").then(argument("name", word()).executes(ok)))
        register(literal("psst").redirect(msg))
        register(literal("secretly").redirect(msg.getChild("targets")))
    }

    private val withheld = ObserverConfig().let { it.privateMessageCommands + it.redactedCommands }.map(CommandPrivacy::bare).toSet()
    private var parses = 0

    private fun text(line: String, withheld: Set<String> = this.withheld): String? =
        CommandPrivacy.text(line, withheld) { parses++; dispatcher.parse(line, Any()) }

    @Test
    fun `a private message keeps its text out, wherever it hides`() {
        ATTACKS.forEach { line -> assertNull(text(line), line) }
    }

    @Test
    fun `the vanilla commands without a private message keep their text`() {
        listOf("gamemode creative", "list uuids", "list", "say hello all", "execute as Bob run gamemode survival", "return run list")
            .forEach { line -> assertEquals(line, text(line), line) }
    }

    @Test
    fun `a line that does not parse to its end is withheld`() {
        assertNull(text("gamemode creatve"))
        assertNull(text("execute as Bob run nosuchcommand $SECRET"))
        assertNull(text("list uuids $SECRET"))
    }

    @Test
    fun `a withheld root is never parsed, and the configuration withholds more`() {
        assertNull(text("msg Bob $SECRET"))
        assertNull(text("sethome $SECRET"))
        assertEquals(0, parses, "parsed only when the root may keep its text")
        assertNull(text("execute run say hello", withheld + "say"), "a kept root the operators withhold, behind execute")
        assertEquals("execute run say hello", text("execute run say hello"))
    }

    @Test
    fun `no private message is among the roots that keep their text`() {
        val private = ObserverConfig().privateMessageCommands.toSet() + CommandIssued.PRIVATE_MESSAGE_COMMANDS
        assertEquals(emptySet(), CommandIssued.TEXT_KEPT_ROOTS intersect private)
        listOf("tellraw", "title", "dialog", "bossbar", "kick", "ban", "narrator").forEach { root ->
            assertTrue(root !in CommandIssued.TEXT_KEPT_ROOTS, "$root: a text for chosen players or for the operators")
        }
        assertEquals(CommandIssued.PRIVATE_MESSAGE_COMMANDS, ObserverConfig().privateMessageCommands.take(5).toSet(), "the vanilla ones first")
    }

    /** The attacks as the Observer journals them, then every table and view the model may read: the secret is in none. */
    @Test
    fun `the secret is in no table or view the model can read`() {
        JournalFixture().use { fixture ->
            val player = PlayerRef(UUID.fromString("00000000-0000-4000-8000-0000000000d1"), "Bartok")
            (ATTACKS + "gamemode creative").forEach { line ->
                fixture.publish(CommandIssued(player, Pos("minecraft:overworld", 0, 64, 0), line.substringBefore(' '), text(line)))
            }
            fixture.sync()
            val relations = fixture.values(
                "SELECT table_name FROM duckdb_tables() WHERE schema_name = 'main' AND NOT internal " +
                    "UNION ALL SELECT view_name FROM duckdb_views() WHERE schema_name = 'main' AND NOT internal",
            ).map { it.single() as String }.filterNot { it in Journal.HIDDEN_FROM_MODEL }
            assertTrue("events" in relations && "timeline" in relations, "$relations")
            val runner = SqlRunner(fixture.journal, fixture.clock)
            val leaks = relations.associateWith { relation ->
                val sql = "SELECT count(*) FROM $relation AS r WHERE CAST(r AS VARCHAR) ILIKE '%$SECRET_WORD%'"
                val outcome = runBlocking { runner.query(sql, SqlLimits(maxRows = 1, timeoutSeconds = 5)) }
                assertIs<SqlOutcome.Rows>(outcome, "$sql → $outcome").text.lines().last()
            }.filterValues { it != "0" }
            assertEquals(emptyMap(), leaks)
            // The commands are there, without their text; the harmless one keeps it.
            assertEquals(ATTACKS.size + 1L, fixture.count("events WHERE type = 'command'"))
            assertEquals(listOf(listOf<Any?>("gamemode creative")), fixture.values("SELECT detail ->> 'text' FROM events WHERE (detail ->> 'text') IS NOT NULL"))
        }
    }

    private companion object {
        const val SECRET_WORD = "waterfall"
        const val SECRET = "the base is behind the $SECRET_WORD"

        /** Every way a private message may be typed: vanilla, behind execute, redirected, another mod's, an alias. */
        val ATTACKS = listOf(
            "msg Bob $SECRET",
            "tell Bob $SECRET",
            "w Bob $SECRET",
            "teammsg $SECRET",
            "tm $SECRET",
            "execute as Alice run msg Bob $SECRET",
            "execute run tell Bob $SECRET",
            "execute as Alice run w Bob $SECRET",
            "execute as Alice run teammsg $SECRET",
            "execute as Alice as Bob run execute run tm $SECRET",
            "return run msg Bob $SECRET",
            "execute as Alice run msg",
            "Minecraft:MSG Bob $SECRET",
            // Another mod's private messages, alone and behind execute.
            "r $SECRET",
            "execute as Alice run r $SECRET",
            "mail send Bob $SECRET",
            "reply $SECRET",
            "whisper Bob $SECRET",
            "pm Bob $SECRET",
            "dm Bob $SECRET",
            // Aliases that no list names: into msg, and into msg's arguments.
            "psst Bob $SECRET",
            "execute as Alice run psst Bob $SECRET",
            "secretly Bob $SECRET",
            "execute run secretly Bob $SECRET",
            // Another mod's command, a login, and the operators' instructions to the Narrator.
            "sethome $SECRET",
            "login $SECRET",
            "narrator whisper $SECRET",
        )
    }
}
