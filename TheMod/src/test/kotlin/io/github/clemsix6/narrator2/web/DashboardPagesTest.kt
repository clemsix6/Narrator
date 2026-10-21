package io.github.clemsix6.narrator2.web

import io.github.clemsix6.narrator2.config.JournalConfig
import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.journal.DisabledJournal
import io.github.clemsix6.narrator2.journal.Journal
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.web.DashboardDataset.Companion.BARTOK
import io.github.clemsix6.narrator2.web.DashboardDataset.Companion.PRIVATE_SECRET
import io.github.clemsix6.narrator2.web.DashboardDataset.Companion.TRAPPED
import io.github.clemsix6.narrator2.web.pages.SqlPage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pages of M6-12 (M6 §8.4): the memory, the players, the mechanics, the Forge, the style and SQL, rendered on the
 * real Journal of [DashboardDataset], where every text holds a trap. Each answers, with its content, its pagination
 * and its filters; none runs a script ([assertSafePage]); none touches the world (the services have no Minecraft
 * server); the dashboard holds one read of the Journal at a time, the sheets and the SQL page included; without the
 * Journal, the pages of what runs still show it. The privacy of every page: [DashboardPrivacyTest].
 */
class DashboardPagesTest {
    private val dataset = DashboardDataset()
    private val token = DashboardToken()

    @AfterEach
    fun close() = dataset.close()

    private fun routes(services: TestServices = dataset.services()) = Routes(services, token, DATASET_PORT)

    private val routes = routes()

    /** [path]'s page, which must answer 200 without a script; its body. */
    private fun page(path: String, routes: Routes = this.routes): String {
        val response = pageOf(routes, token, path)
        assertEquals(200, response.status, "$path: ${bodyText(response)}")
        assertSafePage(path, response)
        return bodyText(response)
    }

    @Test
    fun `every page answers without a script, the traps written as text, and none touches the world`() {
        assertFailsWith<IllegalStateException>("the services have no Minecraft server") { dataset.services().server }
        val sheet = encodeSegment("sheet:${BARTOK.uuid}:recap")
        val pages = listOf(
            "/memory", "/memory/canon", "/memory/canon?status=open&kind=challenge", "/memory/canon?status=closed", "/memory/memories",
            "/memory/memories?state=archived&kind=episode", "/memory/interactions", "/memory/interactions?player=bartok&kind=insult",
            "/memory/notes?status=open", "/memory/ideas", "/memory/ideas?status=dropped&kind=season", "/memory/chronicle?level=session",
            "/memory/docs", "/memory/docs/grimoire", "/memory/docs/grimoire/1", "/memory/docs/grimoire/v2", "/memory/docs/review",
            "/memory/docs/$sheet", "/memory/jobs", "/memory/jobs?kind=dream&status=failed", "/memory/M-501", "/memory/M-502",
            "/memory/C-510", "/memory/c510", "/memory/C-512", "/memory/I-520", "/memory/N-530", "/memory/H-541", "/memory/P-550", "/memory/P-553",
            "/players", "/players?name=imo", "/players/Bartok", "/players/bartok?show=chat", "/players/Bartok?show=events", "/players/Mimosa",
            "/players/${encodeSegment(TRAPPED.name)}", "/players/${BARTOK.uuid}",
            "/mechanics", "/mechanics?mechanic=module&status=open", "/mechanics/decisions", "/mechanics/decisions?decision=started&subject=c-511",
            "/forge", "/forge?page=2", "/forge/tag_game", "/forge/tag_game/1", "/forge/trap_module/v1",
            "/style", "/style?period=day", "/style?period=month&page=2", "/sql", "/sql?q=SELECT%201",
        )
        pages.forEach { path ->
            val body = page(path)
            assertFalse("<script>alert" in body || "<b onmouseover" in body, "$path: a trap went through")
        }
        val notFound = listOf(
            "/memory/C-999", "/memory/X-1", "/memory/nope", "/memory/canon/more", "/memory/docs/nope", "/memory/docs/grimoire/9",
            "/memory/docs/grimoire/x", "/memory/docs/grimoire/1/2", "/players/Nobody", "/players/a/b", "/mechanics/nope", "/forge/nope",
            "/forge/tag_game/9", "/forge/tag_game/x", "/forge/a/b/c", "/style/x", "/sql/x",
        )
        notFound.forEach { path -> pageOf(routes, token, path).let { assertEquals(404, it.status, path); assertSafePage(path, it) } }
        val wrong = listOf("/memory/canon?kind=nope", "/memory/ideas?status=nope", "/style?period=year", "/players/Bartok?show=all", "/mechanics?mechanic=x")
        wrong.forEach { path -> pageOf(routes, token, path).let { assertEquals(400, it.status, path); assertSafePage(path, it) } }

        assertContains(page("/players/Bartok"), "le chat &lt;script&gt;alert(1)&lt;/script&gt;")
        assertContains(page("/players"), "href=\"/players/${encodeSegment(TRAPPED.name)}\"")
        assertContains(page("/players"), "Zed&quot;&gt;&lt;b onmouseover=alert(2)&gt;bold&lt;/b&gt;")
    }

    @Test
    fun `the memory pages list each table, and a row shows its fields, its source, its audit and its recalls`() {
        val canon = page("/memory")
        listOf("C-510", "C-511", "C-512", "C-513").forEach { assertContains(canon, "href=\"/memory/$it\"") }
        assertContains(canon, "<td>law (module)</td>")
        assertContains(canon, "Le Narrateur a promis une couronne à Mimosa. &lt;script&gt;")
        assertContains(canon, "<tr class=\"muted\"><td><a href=\"/memory/C-512\">", message = "a closed entry")
        assertFalse("C-512" in page("/memory/canon?status=open"))

        val row = page("/memory/C-510")
        assertContains(row, "<title>C-510 · canon promise · active · Narrator</title>")
        assertContains(row, "« Je tiendrai parole &quot;&gt;&lt;b onmouseover=alert(2)&gt;bold&lt;/b&gt; »")
        assertContains(row, "<a href=\"/turns/42\">turn #42</a>")
        assertContains(row, "e${dataset.death} · <a href=\"/sql?q=SELECT+*+FROM+timeline+WHERE+id+IN+%28${dataset.death}%29+ORDER+BY+ts%2C+id\">read them</a>")
        assertContains(row, "<td>operator:Clement</td><td></td><td>status planned → active</td><td>started &quot;&gt;&lt;b onmouseover")
        assertContains(row, "2 times, the latest first")
        assertContains(row, "<td>recall_tool</td><td><a href=\"/conversations/director-aaaa\">director-aaaa</a></td><td class=\"num\"><a href=\"/turns/44\">#44</a></td>")

        val ideas = page("/memory/ideas")
        assertContains(ideas, "dropped 20:30:00: replaced by the season P-552")
        assertContains(ideas, "expired 20:30:00: its hours of world play are over")
        assertContains(ideas, "open · 48h00m of play left", message = "the fake memory's world clock stands at 0")
        assertContains(ideas, "<td class=\"num\">2<small> last 20:30:00</small></td>")
        assertContains(page("/memory/P-553"), "<a href=\"/forge/tag_game\">tag_game</a>")

        val docs = page("/memory/docs")
        assertContains(docs, "<a href=\"/memory/docs/sheet%3A${BARTOK.uuid}%3Arecap\">sheet of Bartok: recap</a>")
        val grimoire = page("/memory/docs/grimoire")
        assertContains(grimoire, "<title>grimoire v2 · Narrator</title>")
        assertContains(grimoire, "La lave revient. &lt;script&gt;")
        assertContains(grimoire, "<a href=\"/memory/docs/grimoire/1\">v1</a>")
        assertContains(page("/memory/docs/grimoire/1"), "Premier jet.")

        val jobs = page("/memory/jobs")
        assertContains(jobs, "<a href=\"/conversations/chronicler-1\">chronicler-1</a>")
        assertContains(jobs, "<tr class=\"failure\"><td class=\"num\">2</td><td>dream</td>")
        assertContains(jobs, "LLMGW refused &lt;script&gt;")
        assertContains(page("/memory/H-541"), "superseded by <a href=\"/memory/H-542\">H-542</a>")
        assertContains(page("/memory/memories"), "archived")
        assertFalse("M-502" in page("/memory/memories?state=kept"))
    }

    @Test
    fun `a list shows 50 rows a page, keeps its filters from page to page, and stays bounded`() {
        val first = page("/memory/interactions")
        assertEquals(PAGE_SIZE, Regex("href=\"/memory/I-").findAll(first).count())
        assertContains(first, "<a href=\"/memory/interactions?page=2\">older →</a>")
        val second = page("/memory/interactions?page=2")
        assertEquals(DashboardDataset.EXTRA_INTERACTIONS + 1 - PAGE_SIZE, Regex("href=\"/memory/I-").findAll(second).count())
        assertContains(second, "<a href=\"/memory/interactions\">← newer</a>")
        assertContains(page("/memory/interactions?kind=prayer"), "href=\"/memory/interactions?kind=prayer&amp;page=2\"")
        assertContains(page("/memory/interactions?page=999999"), "No interaction.")
    }

    @Test
    fun `a player's page shows the player, the Narrator's effects, the full sheet and the timeline, never a private message`() {
        val players = page("/players")
        assertContains(players, "<a href=\"/players/Bartok\">Bartok</a></td><td>online, active</td>")
        assertContains(players, "<a href=\"/players/Mimosa\">Mimosa</a></td><td>offline</td>")

        val bartok = page("/players/Bartok")
        assertContains(bartok, "Recap (10-21): Bartok creuse sans relâche.")
        assertContains(bartok, "Bartok creuse sans relâche.", message = "the sheet, as DuckPlayerSheets renders it")
        assertContains(bartok, "<td>slowness II</td><td>21:01:00</td>")
        assertContains(bartok, "<td>2x minecraft:emerald staked on C-511: a bet that Mimosa wins</td><td></td>", message = "nothing after the purpose")
        assertContains(bartok, "<td>chat:public</td><td>mdr</td>")
        assertContains(bartok, "Bartok, ta pioche pleure en silence", message = "a line to him alone")
        assertContains(bartok, "Et Bartok s&#39;élance", message = "a line that names him")
        assertContains(bartok, "<td>advancement</td><td>story/mine_diamond")
        assertContains(bartok, "<td>command</td><td>msg</td>")
        assertFalse("diamond_ore" in bartok, "blocks broken are left out by default")
        assertFalse("Mimosa remporte" in bartok)
        assertFalse(PRIVATE_SECRET in bartok)
        assertContains(page("/players/Bartok?show=events"), "diamond_ore")
        assertFalse("advancement" in page("/players/Bartok?show=chat").substringAfter("<h2>Timeline</h2>"))

        val mimosa = page("/players/Mimosa")
        assertContains(mimosa, "<td>chat:pray</td><td>pitié Narrateur</td>")
        assertContains(mimosa, "<td>death</td><td>lava</td>")
        assertContains(mimosa, "Mimosa remporte la manche")
        assertContains(mimosa, "offline")
        assertFalse(PRIVATE_SECRET in mimosa)
        assertContains(page("/players/${BARTOK.uuid}"), "<h1>Bartok</h1>")
    }

    @Test
    fun `the mechanics page shows what runs from the snapshots, then its history in the canon or the engine's decisions`() {
        val mechanics = page("/mechanics")
        assertContains(mechanics, "Mechanics: on · 1 challenge · 1 rule")
        assertContains(mechanics, "challenge obtain<small> « Œuf &quot;&gt;&lt;b onmouseover")
        assertContains(mechanics, "<a href=\"/players/Bartok\">Bartok</a><small> (1)</small>")
        assertContains(mechanics, "block_break where grief → punish")
        assertContains(mechanics, "<a href=\"/forge/tag_game/1\">tag_game v1</a>")
        assertContains(mechanics, "Challenges: C-511 obtain « Œuf &lt;script&gt;")
        val history = mechanics.substringAfter("Their history in the canon")
        listOf("C-511", "C-512", "C-513").forEach { assertContains(history, "href=\"/memory/$it\"") }
        assertFalse("C-510" in history, "a promise is not the engine's")
        assertContains(history, "removed by an operator")

        val decisions = page("/mechanics/decisions")
        assertContains(decisions, "<td>started</td><td>C-511</td><td>C-511 challenge started: obtain &lt;script&gt;")
        assertContains(decisions, "<a href=\"/turns/42\">#42</a>")
        assertContains(decisions, "C-513 fired on Bartok")
        assertFalse("C-513" in page("/mechanics/decisions?decision=started").substringAfter("The engine&#39;s decisions"))
    }

    @Test
    fun `the Forge page shows the arsenal and its decisions, and a version its manifest and its source`() {
        val forge = page("/forge")
        assertContains(forge, "<a href=\"/forge/tag_game/1\">tag_game v1</a>")
        assertContains(forge, "A trap &lt;script&gt;")
        assertContains(forge, "<td>tested</td><td>tag_game</td>")
        assertContains(forge, "<a href=\"/decisions?kind=forge\">decisions of the Forge</a>")

        val tag = page("/forge/tag_game")
        assertContains(tag, "<title>tag_game v1 · Narrator</title>")
        assertContains(tag, "<td>saved</td><td>tag_game v1</td>")
        assertContains(tag, "<a href=\"/memory/C-513\">C-513</a> v1 law: 3 acts, 0 faults")
        assertContains(tag, "<td>minutes</td><td>integer</td><td>5</td><td>2 to 15</td>")
        assertContains(tag, "<pre>public class TagGame { /* le loup */ }</pre>")
        val trap = page("/forge/trap_module/1")
        assertContains(trap, "<pre>public class Trap { String s = &quot;&lt;/pre&gt;&lt;script&gt;alert(1)&lt;/script&gt;&quot;; }</pre>")
        assertContains(trap, "world/narrator2/forge/trap_module/v1.java", message = "relative to the world, never a path of the server")
    }

    @Test
    fun `the style page shows the watch, the diversity by session, the registers grouped by family, the tics, the flags and the levers`() {
        val style = page("/style")
        assertContains(style, "<pre>Style: openers at rest « Vingt secondes » ×5 &lt;script&gt;")
        assertContains(style, "« Vingt secondes » ×5<small> (« Onze secondes &quot;&gt;")
        assertContains(style, "7 lines of the Director, 6 reviewed.")
        val sessions = style.substringAfter("Diversity by session").substringBefore("</table>")
        assertContains(sessions, "<tr class=\"warning\"><td>the whole period</td><td class=\"num\">7</td><td class=\"num\">6</td>")
        assertContains(sessions, "<td>20:00:00 → 21:00:00</td><td class=\"num\">5</td><td class=\"num\">4</td>")
        assertContains(sessions, "<td>10-20 18:00 → 10-20 19:00</td><td class=\"num\">2</td><td class=\"num\">2</td>")
        assertContains(sessions, "« céleste » 100%")
        assertContains(style, "<td>administration</td><td>divine_bureaucracy 1, customer_service 1</td><td class=\"num\">2</td><td class=\"num\">33%</td>")
        assertContains(style, "<td>show</td><td>sports_commentary 1, game_show 1</td>")
        assertContains(style, "<svg class=\"chart\"")
        assertContains(style, "<summary>Each register</summary>")
        assertContains(style, "<td>« céleste »</td><td class=\"num\">2</td><td class=\"num\">33%</td>")
        val flags = style.substringAfter("Lines flagged for their tone").substringBefore("</table>")
        assertContains(flags, "<tr class=\"failure\"><td>20:25:00</td><td>dark 0.90</td><td>courtroom</td><td>Bartok, ta pioche pleure en silence &quot;&gt;&lt;b")
        assertContains(flags, "<td>flat 0.80</td><td>plain</td>")
        assertTrue(flags.indexOf("dark 0.90") < flags.indexOf("flat 0.80"), "the most serious first")
        val levers = style.substringAfter("Interventions by lever")
        assertContains(levers, "<td>punish</td><td class=\"num\">1</td><td>tiny</td>")
        assertContains(levers, "<td>challenge</td><td class=\"num\">1</td><td>obtain</td>")
        assertContains(levers, "<td>say</td>")

        val day = page("/style?period=day")
        assertContains(day, "5 lines of the Director, 4 reviewed.")
        assertFalse("10-20 18:00" in day)
    }

    @Test
    fun `the SQL page runs a query with the model's access, within the Director's limits, never past 5 s`() {
        assertContains(page("/sql?q=SELECT+name+FROM+players+ORDER+BY+name"), "Mimosa")
        val refused = page("/sql?q=SELECT+text+FROM+private_messages")
        assertContains(refused, "Refused: this query names private_messages, which is never readable")
        assertFalse(PRIVATE_SECRET in refused)
        assertContains(page("/sql?q=SELECT+1+AS+%22%3Cb%3E%22"), "&lt;b&gt;", message = "a column name stays text")

        assertEquals(200, SqlPage.limits(NarratorConfig()).maxRows)
        assertEquals(5, SqlPage.limits(NarratorConfig()).timeoutSeconds)
        assertEquals(5, SqlPage.limits(NarratorConfig(journal = JournalConfig(sqlTimeoutSeconds = 30))).timeoutSeconds)
        assertEquals(2, SqlPage.limits(NarratorConfig(journal = JournalConfig(sqlTimeoutSeconds = 2))).timeoutSeconds)

        val heavy = "/sql?q=SELECT+sum%28a.range+%2B+b.range%29+FROM+range%28100000000%29+a%2C+range%28100000000%29+b"
        val quick = routes(dataset.services(config = NarratorConfig(journal = JournalConfig(sqlTimeoutSeconds = 1))))
        var started = System.nanoTime()
        assertContains(page(heavy, quick), "The query did not finish within 1 s")
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000, "a query of 1 s stopped in time")
        started = System.nanoTime()
        assertContains(page(heavy), "The query did not finish within 5 s")
        val elapsed = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsed in 4_500..15_000, "the default limit stops it at 5 s, not before: $elapsed ms")
        assertContains(page("/sql?q=SELECT+1+AS+n"), "\nn\n1", message = "the next query runs")
    }

    @Test
    fun `without the Journal, the pages of what runs still show it and say why the rest is missing`() {
        val off = routes(dataset.services(journal = DisabledJournal("unit test", TestClock())))
        listOf("/memory", "/memory/C-510", "/players", "/players/Bartok").forEach { path ->
            val response = pageOf(off, token, path)
            assertEquals(503, response.status, path)
            assertSafePage(path, response)
            assertContains(bodyText(response), "the Journal is unavailable: unit test", message = path)
        }
        val mechanics = page("/mechanics", off)
        assertContains(mechanics, "href=\"/memory/C-511\"")
        assertContains(mechanics, "Not shown: the Journal is unavailable: unit test.")
        val forge = page("/forge", off)
        assertContains(forge, "tag_game v1")
        assertContains(forge, "Not shown: the Journal is unavailable: unit test.")
        val style = page("/style", off)
        assertContains(style, "« Vingt secondes » ×5")
        assertContains(style, "Not shown: the Journal is unavailable: unit test.")
        assertContains(page("/sql?q=SELECT+1", off), "Failed: the Journal is unavailable: unit test")
    }

    @Test
    fun `the dashboard holds one read of the Journal at a time, the sheets and the SQL page included`() {
        val counting = CountingJournal(dataset.fixture.journal)
        val routes = routes(dataset.services(journal = counting))
        val paths = listOf(
            "/sql?q=SELECT+count%28*%29+FROM+range%283000000%29", "/players/Bartok", "/memory", "/style", "/memory/C-510", "/mechanics", "/forge/tag_game",
        )
        val statuses = ConcurrentLinkedQueue<Pair<String, Int>>()
        val ready = CountDownLatch(1)
        val threads = (1..3).flatMap { paths }.map { path ->
            thread {
                ready.await()
                statuses += path to pageOf(routes, token, path).status
            }
        }
        ready.countDown()
        threads.forEach { it.join(60_000) }
        assertEquals(paths.size * 3, statuses.size)
        statuses.forEach { (path, status) -> assertEquals(200, status, path) }
        assertTrue(counting.reads.get() >= paths.size * 3, "every page read the Journal")
        assertEquals(1, counting.most.get(), "one read of the dashboard at a time")
    }

    /** The Journal of the dataset, counting the reads that hold a connection at once. */
    private class CountingJournal(private val inner: Journal) : Journal by inner {
        val active = AtomicInteger()
        val most = AtomicInteger()
        val reads = AtomicInteger()

        override suspend fun <T> read(timeoutMs: Long, query: (Connection) -> T): T = inner.read(timeoutMs) { connection ->
            reads.incrementAndGet()
            most.accumulateAndGet(active.incrementAndGet()) { a, b -> maxOf(a, b) }
            try {
                query(connection)
            } finally {
                active.decrementAndGet()
            }
        }
    }
}
