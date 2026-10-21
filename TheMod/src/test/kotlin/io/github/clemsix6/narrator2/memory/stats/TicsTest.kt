package io.github.clemsix6.narrator2.memory.stats

import io.github.clemsix6.narrator2.memory.MemoryDataset
import io.github.clemsix6.narrator2.memory.MemoryDataset.day2
import io.github.clemsix6.narrator2.memory.stats.Tics.Line
import io.github.clemsix6.narrator2.memory.stats.Tics.Names
import io.github.clemsix6.narrator2.memory.stats.Tics.Tic
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Narrator's tics (M3 §6.4, normalized by M6 §3.4) on fabricated lines: openers, phrases, ellipsis; names and numbers
 * normalized; the corpora of the M4 and M5 acceptances replayed; and the lines and names read from the Journal.
 */
class TicsTest {
    private val start = Instant.parse("2026-10-03T18:00:00Z")

    /** [texts] said one minute apart from [start]. */
    private fun lines(vararg texts: String) = texts.mapIndexed { i, text -> Line(text, start.plusSeconds(60L * i)) }

    private fun keys(text: String, names: Names = Names.NONE) = Tics.tokens(text, names).map { it.key }

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
        // The latest form is shown. « Bureau des » (three lines) is no opener; its lines share a phrase once the
        // ordinals are numbers, « Bureau des plaintes, <n> étage », which holds « Bureau des plaintes ».
        assertEquals(listOf(Tic("Ministère de", 4, start.plusSeconds(180))), tics.filter { it.text.startsWith("Minist") })
        assertEquals(listOf("Ministère de", "Bureau des plaintes, premier étage"), tics.map { it.text })
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
    fun `of tics nested in one another, one is shown, the most frequent, the longest at equal count`() {
        // M3-19: « Ministère de l'Aviation » ×5, « Ministère de l'Aviation Divine » ×5 and « … Divine guichet » ×3 were all listed.
        val tics = Tics.find(
            lines(
                "Ministère de l'Aviation Divine, guichet 3 : vol refusé, Bartok.",
                "Ministère de l'Aviation Divine, guichet des chutes : dossier classé.",
                "Ministère de l'Aviation Divine, guichet fermé pour la nuit.",
                "Ministère de l'Aviation Divine : altitude 68 avant, 68 après.",
                "Ministère de l'Aviation Divine. Atterrissage noté, Mimosa.",
            ),
        )
        assertEquals(listOf(Tic("Ministère de l'Aviation Divine", 5, start.plusSeconds(240))), tics)

        val always = Tics.find(lines(*listOf("Pomme", "Poire", "Prune", "Pêche").map { "Ministère de l'Aviation Divine guichet $it : refusé." }.toTypedArray()))
        assertEquals(listOf(Tic("Ministère de l'Aviation Divine guichet", 4, start.plusSeconds(180))), always)
    }

    @Test
    fun `the ellipsis is a tic past 40 percent of the lines`() {
        val many = Tics.find(lines("Tiens…", "Voyons... non.", "Hmm… peut-être.", "Non.", "Bon."))
        assertEquals(listOf(Tic("…", 3, start.plusSeconds(120), 0.6)), many)
        assertEquals(emptyList(), Tics.find(lines("Tiens…", "Voyons.", "Non.", "Bon.", "Bien… Non.")))
    }

    @Test
    fun `at most eight, the most frequent first, rendered with their age`() {
        val texts = (1..10).flatMap { n -> List(4 + n) { "Formule$n numéro$n et quelques mots de plus." } }
        val tics = Tics.find(lines(*texts.toTypedArray()))
        assertEquals(Tics.MAX, tics.size)
        assertEquals("Formule10 numéro10 et quelques mots", tics.first().text)
        val now = start.plusSeconds(60L * texts.size + 7_200)
        assertEquals("« Formule10 numéro10 et quelques mots » ×14 (last 2h01m ago)", Tics.render(tics, now).first())
        assertEquals(
            "« … » ×3 (60% of lines, last 2h00m ago)",
            Tics.render(listOf(Tic("…", 3, now.minusSeconds(7_200), 0.6)), now).single(),
        )
    }

    // ── Normalizing (M6 §3.4) ───────────────────────────────────────────────────────────────────────

    @Test
    fun `names become one name, numbers one number, in digits or in letters`() {
        val names = Names.of(listOf("Bartok", "Mimosa", "Lisette", "Gaspard", "Firmin", "Pistache", "Opaline", "Client Mécontent"))
        assertEquals(listOf("bonsoir", Tics.NAME), keys("Bonsoir, Lisette, Gaspard, Firmin !", names))
        assertEquals(listOf(Tics.NAME, "les", "paris", "sont", "ouverts"), keys("Pistache et Opaline, les paris sont ouverts", names))
        assertEquals(listOf(Tics.NUMBER, "secondes", "le", "documentaire"), keys("Dix-sept secondes : le documentaire", names))
        assertEquals(listOf(Tics.NUMBER, "secondes", Tics.NUMBER), keys("Onze secondes. ONZE.", names))
        assertEquals(listOf("le", "guichet", "ferme", "à", Tics.NUMBER), keys("Le guichet ferme à 18h18.", names))
        assertEquals(listOf("ticket", "n", Tics.NUMBER, Tics.NAME), keys("ticket n°2 : Mimosa", names))
        assertEquals(listOf("notre", Tics.NAME, "reçoit"), keys("notre Client Mécontent reçoit", names), "a nickname of two words")
        assertEquals(listOf("un", "mouton", "ferait", "mieux"), keys("Un mouton ferait mieux", names), "« un » is an article far more often than a number")
        assertEquals(listOf("bartok", "et", "mimosa"), keys("Bartok et Mimosa"), "no name known, no name normalized")
        assertTrue(Names.of(listOf("Les", "123")).isEmpty, "a name made of a stop word or a number would hide the word")
    }

    /**
     * M6 review 1, finding 4: « d'Opaline » is a name after an elided article, written out (`d'` → `de`, `qu'` → `que`),
     * so that « le cadeau d'Opaline » and « le cadeau de Bartok » are one construction, and « Opaline » never a crutch
     * candidate.
     */
    @Test
    fun `an elided name is a name, its elided article written out`() {
        val names = Names.of(listOf("Bartok", "Mimosa", "Opaline", "Client Mécontent", "l'Ancien"))
        assertEquals(listOf("le", "cadeau", "de", Tics.NAME, "et", "de", Tics.NAME), keys("Le cadeau d'Opaline et de Bartok", names))
        assertEquals(listOf("que", Tics.NAME, "se", "méfie", Tics.NAME, "approche"), keys("Qu’Opaline se méfie, Bartok approche", names))
        assertEquals(listOf("lorsque", Tics.NAME, "parle"), keys("Lorsqu'Opaline parle", names))
        assertEquals(listOf("plainte", "de", Tics.NAME), keys("plainte d'Client Mécontent", names), "a name of two words")
        assertEquals(listOf("salut", "à", Tics.NAME), keys("Salut à l'Ancien", names), "a name with its own elision")
        assertEquals(listOf("le", "ministère", "de", "l'aviation"), keys("Le ministère de l'Aviation", names), "no name after the apostrophe: one word")
        assertEquals(listOf("aujourd'hui", Tics.NAME), keys("Aujourd'hui Bartok", names))

        val qu = Tics.said("Qu'Opaline se méfie : Bartok approche du désert.", start, names)
        assertEquals(Tics.Opener("que <nom>", "Qu'Opaline", hasWord = false), qu.opener)
        assertEquals(setOf("cadeau", "brille"), Tics.words(Tics.said("Le cadeau d'Opaline brille.", start, names)), "a name is no crutch candidate")

        val gifts = listOf("Le cadeau d'Opaline brille encore.", "Le cadeau de Bartok brille encore.", "Le cadeau de Mimosa brille encore.")
        assertEquals(
            listOf("cadeau de <nom> brille encore" to 3),
            Tics.phrases(gifts.map { Tics.said(it, start, names) }).map { it.key to it.count },
            "one construction",
        )
    }

    @Test
    fun `an opener is two tokens of a line said, long enough, with a content word`() {
        val names = Names.of(listOf("Bartok", "Mimosa", "Gaspard", "Firmin"))
        val vingt = Tics.said("Vingt secondes ! Bartok remporte le Grand Prix du Sable.", start, names).opener!!
        assertEquals(Tics.Opener("<n> secondes", "Vingt secondes", hasWord = true), vingt)
        assertEquals(Tics.Opener("bonsoir mortels", "Bonsoir, mortels", hasWord = true), Tics.said("Bonsoir, mortels ! Trois diamants en dix secondes.", start, names).opener)
        assertFalse(Tics.said("Gaspard, Firmin : vous vous chamaillez depuis le début.", start, names).opener!!.hasWord, "<nom> vous")
        assertNull(Tics.said("BARTOK vs MIMOSA", start, names).opener, "a title is shorter than 25 characters")
        assertNull(Tics.said("[title] GASPARD vs FIRMIN, LE GRAND DUEL", start, names).opener, "a title never opens")
        assertNull(Tics.said("[actionbar] Les poules ont atterri. Aucune n'a réclamé de pourboire.", start, names).opener, "nor a line above the hotbar")
        assertEquals(listOf(Tics.NAME, "vs", Tics.NAME), Tics.said("[title] GASPARD vs FIRMIN", start, names).tokens.map { it.key }, "the kind is left out")
        assertTrue(Tics.isStop("c'est") && Tics.isStop("qu’on") && !Tics.isStop("l'aviation") && !Tics.isStop("aujourd'hui"))
    }

    /**
     * The essay of M6 (`M6.md`, « Essais »): the 66 lines of the M4 acceptance. Raw, no opener repeats; normalized,
     * `<n> secondes` ×5 (each challenge's end), `bonsoir mortels` ×3 (each evening's start), `décret divin` ×2, and
     * `<nom> vous` ×2, which is no tic. The titles (« BARTOK vs MIMOSA ») never open.
     */
    @Test
    fun `the M4 acceptance's corpus, replayed`() {
        val corpus = corpus("m4-recipe-lines.txt")
        assertEquals(66, corpus.size)
        val said = corpus.mapIndexed { i, text -> Tics.said(text, start.plusSeconds(60L * i), M4_NAMES) }
        assertEquals(61, said.count { it.opens }, "the lines of 25 characters or more")

        val raw = corpus.filter { Tics.opens(it) }.map { text -> Regex("[\\p{L}\\p{N}'’-]+").findAll(text).take(2).joinToString(" ") { it.value.lowercase() } }
        val rawSecondes = raw.filter { it.endsWith(" secondes") }
        assertEquals(5, rawSecondes.size)
        assertEquals(5, rawSecondes.toSet().size, "raw, « Onze secondes », « Douze secondes »… hide the construction: $rawSecondes")

        val openers = Tics.openers(said).associate { it.key to it.count }
        assertEquals(5, openers["<n> secondes"])
        assertEquals(3, openers["bonsoir mortels"])
        assertEquals(2, openers["décret divin"])
        assertEquals(2, said.count { it.opener?.key == "<nom> vous" }, "Gaspard, Firmin : vous… / Bartok, Mimosa : vous…")
        assertFalse("<nom> vous" in openers, "an opener without a content word is never a tic")
        assertTrue(openers.keys.none { it.endsWith(" vs") }, "no title opens: ${openers.keys}")

        val secondes = Tics.openers(said).single { it.key == "<n> secondes" }
        assertEquals(listOf("Dix-sept secondes", "Vingt secondes", "Douze secondes", "Onze secondes", "Seize secondes"), secondes.forms)
        val tics = Tics.tics(said)
        assertEquals(Tic("Dix-sept secondes", 5, said[64].ts), tics.first(), "the one tic of the evening's openers: ${Tics.render(tics, start)}")
    }

    /**
     * The 17 lines of the M5 acceptance (`M5-22`): « Mesdames et messieurs » opens two of them and closes a line of M4,
     * « poules en orbite » comes back three times; the lines above the hotbar never open.
     */
    @Test
    fun `the M5 acceptance's corpus, replayed`() {
        val m5 = corpus("m5-recipe-lines.txt")
        assertEquals(17, m5.size)
        val said = m5.mapIndexed { i, text -> Tics.said(text, start.plusSeconds(60L * i), M5_NAMES) }
        assertEquals(3, said.count { !it.opens }, "the three lines above the hotbar")
        val openers = Tics.openers(said).associate { it.key to it.count }
        assertEquals(2, openers["mesdames et"])
        assertEquals(2, openers["interruption des"])
        assertEquals(listOf("poules en orbite" to 3), Tics.phrases(said).map { it.key to it.count })

        val evenings = (corpus("m4-recipe-lines.txt") + m5).mapIndexed { i, text -> Tics.said(text, start.plusSeconds(60L * i), M4_NAMES) }
        val phrases = Tics.phrases(evenings).associate { it.key to it.count }
        assertEquals(3, phrases["mesdames et messieurs"], "two in M5, one in M4: $phrases")
        assertEquals(3, phrases["poules en orbite"])
    }

    // ── The Journal ─────────────────────────────────────────────────────────────────────────────────

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
    fun `the names are the players' and the active nicknames'`() {
        MemoryDataset.journal().use { fixture ->
            val names = MemoryDataset.read(fixture) { Tics.names(it) }
            assertTrue(names.containsAll(listOf("Bartok", "Mimosa", "Kelvin")), "$names")
            val tokens = Tics.tokens("Bartok et kelvin, au travail.", Names.of(names)).map { it.key }
            assertEquals(listOf(Tics.NAME, "au", "travail"), tokens)
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

    /** [connection], whose prepared statements add the time limit they are given to [timeouts]. */
    private fun recordingTimeouts(connection: Connection, timeouts: MutableList<Int>): Connection = proxy(connection) { method, args ->
        val result = method.invoke(connection, *args)
        if (result !is PreparedStatement) return@proxy result
        proxy<PreparedStatement>(result) { call, callArgs ->
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

    companion object {
        /** The players of the M4 acceptance's three passes, and those of M5. */
        val M4_NAMES = Names.of(listOf("Bartok", "Mimosa", "Pistache", "Opaline", "Lisette", "Gaspard", "Firmin"))
        val M5_NAMES = Names.of(listOf("Bartok", "Mimosa", "Gaspard", "Lisette", "Firmin"))

        /**
         * A corpus of `narrator2/style/`: one line of the Narrator a line, as the `chat` table records it (the kind before
         * a line above the hotbar), a line break written `\n`.
         */
        fun corpus(name: String): List<String> =
            TicsTest::class.java.getResourceAsStream("/narrator2/style/$name")!!.bufferedReader(Charsets.UTF_8).readLines()
                .filter { it.isNotBlank() }
                .map { it.replace("\\n", "\n") }
    }
}
