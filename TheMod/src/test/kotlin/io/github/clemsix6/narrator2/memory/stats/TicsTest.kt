package io.github.clemsix6.narrator2.memory.stats

import io.github.clemsix6.narrator2.memory.MemoryDataset
import io.github.clemsix6.narrator2.memory.MemoryDataset.day2
import io.github.clemsix6.narrator2.memory.stats.Tics.Line
import io.github.clemsix6.narrator2.memory.stats.Tics.Tic
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.Statement
import java.time.Instant
import kotlin.test.assertEquals

/** The Narrator's tics (M3 §6.4) on fabricated lines: openers, phrases, ellipsis; and the lines read from the Journal. */
class TicsTest {
    private val start = Instant.parse("2026-10-03T18:00:00Z")

    /** [texts] said one minute apart from [start]. */
    private fun lines(vararg texts: String) = texts.mapIndexed { i, text -> Line(text, start.plusSeconds(60L * i)) }

    @Test
    fun `an opener seen four times is a tic, three times is not`() {
        val tics = Tics.find(
            lines(
                "ministère de la Pioche : ta demande est rejetée.",
                "Ministère de la Pluie, bureau des orages.",
                "Ministère de la Lave, service des chutes.",
                "Ministère de la Honte, guichet fermé.",
                "Bureau des plaintes, troisième étage.",
                "Bureau des plaintes, deuxième étage.",
                "Bureau des plaintes, premier étage.",
            ),
        )
        // The latest form is shown; « Bureau des plaintes » (three lines) is no opener, but a phrase in three lines.
        assertEquals(listOf(Tic("Ministère de la", 4, start.plusSeconds(180))), tics.filter { it.text.startsWith("Minist") })
        assertEquals(listOf("Ministère de la", "Bureau des plaintes"), tics.map { it.text })
    }

    @Test
    fun `phrases must start and end with a content word, in three distinct lines, the longest kept`() {
        val tics = Tics.find(
            lines(
                "Bartok, ta pioche pleure encore ce soir.",
                "Ce soir, ta pioche pleure encore, Mimosa.",
                "Rien ne va : ta pioche pleure encore.",
                "Rien ne va : ta pioche pleure encore et encore.",
                "Une ligne sans rapport de la de la de la.",
                "Une autre ligne de la de la.",
                "Encore une ligne de la de la.",
            ),
        )
        // « pioche pleure encore » (4 lines) wins over its shorter parts; « ta pioche pleure » starts with a stop word.
        assertEquals("pioche pleure encore", tics.first().text)
        assertEquals(4, tics.first().count)
        assertEquals(1, tics.count { "pioche" in it.text })
        assertEquals(0, tics.count { it.text.startsWith("de ") || it.text.endsWith(" la") })
    }

    @Test
    fun `the ellipsis is a tic past 40 percent of the lines`() {
        val many = Tics.find(lines("Tiens…", "Voyons... non.", "Hmm… peut-être.", "Non.", "Bon."))
        assertEquals(listOf(Tic("…", 3, start.plusSeconds(120), 0.6)), many)
        assertEquals(emptyList(), Tics.find(lines("Tiens…", "Voyons.", "Non.", "Bon.", "Bien… Non.")))
    }

    @Test
    fun `at most eight, the most frequent first, rendered with their age`() {
        val texts = (1..10).flatMap { n -> List(4 + n) { "Formule$n numéro $n." } }
        val tics = Tics.find(lines(*texts.toTypedArray()))
        assertEquals(Tics.MAX, tics.size)
        assertEquals("Formule10 numéro 10", tics.first().text)
        val now = start.plusSeconds(60L * texts.size + 7_200)
        assertEquals("« Formule10 numéro 10 » ×14 (last 2h01m ago)", Tics.render(tics, now).first())
        assertEquals(
            "« … » ×3 (60% of lines, last 2h00m ago)",
            Tics.render(listOf(Tic("…", 3, now.minusSeconds(7_200), 0.6)), now).single(),
        )
    }

    @Test
    fun `the lines are the Narrator's latest, oldest first, without the operator's`() {
        MemoryDataset.journal().use { fixture ->
            val lines = MemoryDataset.read(fixture) { Tics.lines(it) }
            assertEquals(
                listOf("Bartok, ta pioche pleure.", "Kelvin, tu dors ?", "Ta prière est refusée.", "Mimosa, reviens.", "Bartok et kelvin, au travail."),
                lines.map { it.text },
            )
            assertEquals(day2("20:20"), lines.first().ts)
        }
    }

    @Test
    fun `the lines are read within the stats' time limit`() {
        MemoryDataset.journal().use { fixture ->
            val timeouts = mutableListOf<Int>()
            val lines = MemoryDataset.read(fixture) { Tics.lines(recordingTimeouts(it, timeouts)) }
            assertEquals(5, lines.size)
            assertEquals(listOf(SheetStats.QUERY_TIMEOUT_SECONDS), timeouts)
        }
    }

    /** [connection], whose statements add the time limit they are given to [timeouts]. */
    private fun recordingTimeouts(connection: Connection, timeouts: MutableList<Int>): Connection = proxy(connection) { method, args ->
        val result = method.invoke(connection, *args)
        if (result !is Statement) return@proxy result
        proxy<Statement>(result) { call, callArgs ->
            if (call.name == "setQueryTimeout") timeouts += callArgs.single() as Int
            call.invoke(result, *callArgs)
        }
    }

    private inline fun <reified T : Any> proxy(target: T, crossinline handle: (Method, Array<Any?>) -> Any?): T =
        Proxy.newProxyInstance(TicsTest::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            try {
                handle(method, args ?: emptyArray())
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as T
}
