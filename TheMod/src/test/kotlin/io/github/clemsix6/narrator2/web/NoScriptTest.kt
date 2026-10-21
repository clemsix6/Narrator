package io.github.clemsix6.narrator2.web

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.observer.LivePlayer
import io.github.clemsix6.narrator2.testing.FakeMechanics
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import io.github.clemsix6.narrator2.trace.TraceDataset
import io.github.clemsix6.narrator2.trace.TurnRecord
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * No page of the dashboard can run a script (M6 §8.1, §8.6): every page, rendered on a Journal of test with a player, a
 * line of the situation and a wake-up holding traps, has no `<script`, no `on…=` nor `style=` attribute, no `javascript:`,
 * only links inside the dashboard, and the security headers; the traps come out as text. A page added to
 * [Routes.PAGES] is checked at its root without a change here.
 */
class NoScriptTest {
    private val dataset = TraceDataset()
    private val token = DashboardToken()

    @AfterEach
    fun close() = dataset.close()

    private fun routes(services: TestServices) = Routes(services, token, PORT)

    private fun services(): TestServices {
        val journal = dataset.fixture.journal
        val clock = dataset.fixture.clock
        clock.now = TraceDataset.NOW
        val turn = TurnRecord(44, TraceDataset.CHAPTER, clock.instant(), clock.instant(), TurnRecord.Trigger.CHAT, emptyList(), emptyList(), 0, 0, TurnRecord.Outcome.IDLE)
        journal.turn(turn)
        Decisions(journal, clock).record(Log.director, DecisionRecord.Kind.WAKE, "woke", "turn 44", "chat $SCRIPT_TRAP", conversationId = TraceDataset.CHAPTER, turnId = 44)
        dataset.fixture.sync()
        return TestServices(
            clock = TestClock(TraceDataset.NOW),
            journal = journal,
            mechanics = FakeMechanics().apply { lines = listOf("Challenge C-50: $QUOTE_TRAP") },
        ).also { it.players.put(player(NAME_TRAP)) }
    }

    @Test
    fun `no page runs a script, and the traps come out as text`() {
        val routes = routes(services())
        val paths = PATHS + Routes.PAGES.map { "/${it.root}" }
        paths.forEach { path ->
            val response = get(routes, path)
            assertTrue(response.status in setOf(200, 404), "$path: ${response.status}")
            assertSafe(path, response)
        }

        val overview = bodyText(get(routes, "/"))
        assertContains(overview, "&lt;img src=x onerror=alert(1)&gt;", message = "the player's name")
        assertContains(overview, "href=\"/players/%3Cimg%20src%3Dx%20onerror%3Dalert%281%29%3E\"", message = "the name in a link, encoded")
        assertContains(overview, "Challenge C-50: &quot;&gt;&lt;b&gt;bold&lt;/b&gt;", message = "a line of the situation")
        assertContains(overview, "chat &lt;script&gt;alert(1)&lt;/script&gt;", message = "a wake-up of the Journal")
        assertContains(overview, "#42</a>")
        assertContains(overview, "<svg class=\"chart\"")
        assertFalse("<b>bold" in overview)
    }

    @Test
    fun `refusals and errors are pages without a script too`() {
        val routes = routes(services())
        val refused = listOf(
            routes.respond(WebCall("GET", "/", null, "127.0.0.1:$PORT")),
            routes.respond(WebCall("GET", "/login", "key=wrong", "127.0.0.1:$PORT")),
            routes.respond(WebCall("GET", "/", null, "evil.example:$PORT", listOf(cookie()))),
            routes.respond(WebCall("POST", "/", null, "127.0.0.1:$PORT", listOf(cookie()))),
            routes.respond(WebCall("GET", "/players/%zz", null, "127.0.0.1:$PORT", listOf(cookie()))),
            get(routes, "/nope"),
        )
        assertEquals(listOf(403, 403, 421, 405, 400, 404), refused.map { it.status })
        refused.forEach { assertSafe("refusal ${it.status}", it) }
    }

    @Test
    fun `without the Journal the overview still shows what runs, and says why the rest is missing`() {
        val routes = routes(TestServices().also { it.players.put(player("Bartok")) })
        val response = get(routes, "/")
        assertEquals(200, response.status)
        assertSafe("/ without a Journal", response)
        val page = bodyText(response)
        assertContains(page, ">Bartok</a>")
        assertContains(page, "Not shown: the Journal is unavailable: unit test.")
        Routes.PAGES.forEach { page -> assertSafe("/${page.root} without a Journal", get(routes, "/${page.root}")) }
    }

    private fun assertSafe(what: String, response: WebResponse) = assertSafePage(what, response)

    private fun get(routes: Routes, path: String): WebResponse =
        routes.respond(WebCall("GET", path.substringBefore('?'), path.substringAfter('?', "").ifEmpty { null }, "127.0.0.1:$PORT", listOf(cookie())))

    private fun cookie() = "${Routes.COOKIE}=${token.value}"

    private fun player(name: String) = LivePlayer(
        ref = PlayerRef(UUID.nameUUIDFromBytes(name.toByteArray()), name), sessionId = 1, joinedAt = 0, isOp = false, gameMode = "survival",
        pos = Pos("minecraft:overworld", 1, 64, 2), biome = null, structure = null, underground = false,
        health = 20f, maxHealth = 20f, food = 20, vehicle = null, lastDeathAt = null, lastMoveAt = 0, idle = false,
    )

    private companion object {
        const val PORT = 8770

        const val NAME_TRAP = "<img src=x onerror=alert(1)>"
        const val QUOTE_TRAP = "\"><b>bold</b>"
        const val SCRIPT_TRAP = "<script>alert(1)</script>"

        /** Pages beyond the roots, with the dataset's ids and a trapped name. */
        val PATHS = listOf(
            "/", "/?refresh=15", "/?refresh=1", "/turns/42", "/turns?trigger=chat&page=2", "/conversations/${TraceDataset.CHAPTER}",
            "/tasks/7", "/decisions?kind=guard", "/costs", "/memory/M-1", "/players/${encodeSegment(NAME_TRAP)}", "/players/Bartok",
            "/mechanics", "/forge/parade/1", "/style", "/sql?q=SELECT%201", "/style.css", "/nope",
        )
    }
}

/** The body of [response] as text. */
internal fun bodyText(response: WebResponse) = String(response.body, StandardCharsets.UTF_8)

/**
 * What every answer of the dashboard must hold (M6 §8.1, §8.3, §8.6), for the tests of any page ([what] names it in a
 * failure): the security headers; and for a page, no `<script`, no `javascript:`, no `on…` nor `style` attribute, and
 * links that stay inside the dashboard.
 */
internal fun assertSafePage(what: String, response: WebResponse) {
    assertEquals(Routes.CSP, response.headers["Content-Security-Policy"], what)
    assertEquals("nosniff", response.headers["X-Content-Type-Options"], what)
    assertEquals("no-store", response.headers["Cache-Control"], what)
    assertEquals("no-referrer", response.headers["Referrer-Policy"], what)
    if (response.contentType?.startsWith("text/html") != true) return
    val html = bodyText(response)
    assertFalse("<script" in html.lowercase(), "$what: <script")
    assertFalse("javascript:" in html.lowercase(), "$what: javascript:")
    TAG.findAll(html).forEach { match ->
        val tag = match.value
        val names = VALUE.replace(tag, "\"\"")
        assertFalse(EVENT_ATTRIBUTE.containsMatchIn(names), "$what: an on… attribute in $tag")
        assertFalse(STYLE_ATTRIBUTE.containsMatchIn(names), "$what: a style attribute in $tag")
        URL.findAll(tag).forEach { url ->
            val target = url.groupValues[1]
            assertTrue(target.startsWith("/") && !target.startsWith("//") || target.startsWith("#"), "$what: a link out of the dashboard in $tag")
        }
    }
}

/** A tag as written by the dashboard: text never holds a raw `<`. */
private val TAG = Regex("<[a-zA-Z][^>]*>")

/** An attribute's value, removed before the names are checked. */
private val VALUE = Regex("\"[^\"]*\"")

private val EVENT_ATTRIBUTE = Regex("\\son[a-z-]*\\s*=", RegexOption.IGNORE_CASE)
private val STYLE_ATTRIBUTE = Regex("\\sstyle\\s*=", RegexOption.IGNORE_CASE)
private val URL = Regex("\\s(?:href|action|src)=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
