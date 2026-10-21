package io.github.clemsix6.narrator2.web

import io.github.clemsix6.narrator2.command.TraceText
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.journal.JournalTimeoutException
import io.github.clemsix6.narrator2.journal.JournalUnavailableException
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.trace.Prices
import io.github.clemsix6.narrator2.web.pages.ConversationsPage
import io.github.clemsix6.narrator2.web.pages.CostsPage
import io.github.clemsix6.narrator2.web.pages.DecisionsPage
import io.github.clemsix6.narrator2.web.pages.ForgePage
import io.github.clemsix6.narrator2.web.pages.MechanicsPage
import io.github.clemsix6.narrator2.web.pages.MemoryPage
import io.github.clemsix6.narrator2.web.pages.OverviewPage
import io.github.clemsix6.narrator2.web.pages.PlayersPage
import io.github.clemsix6.narrator2.web.pages.SqlPage
import io.github.clemsix6.narrator2.web.pages.StylePage
import io.github.clemsix6.narrator2.web.pages.TasksPage
import io.github.clemsix6.narrator2.web.pages.TurnsPage
import kotlinx.coroutines.runBlocking
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** What the dashboard receives of an HTTP request, before any check: the method, the raw path and query, the `Host` and `Cookie` headers. */
data class WebCall(val method: String, val rawPath: String, val rawQuery: String?, val host: String?, val cookies: List<String> = emptyList())

/** What it answers: [status], [contentType] (null without a body), [body], and [headers], the security headers included. */
class WebResponse(val status: Int, val contentType: String?, val body: ByteArray, val headers: Map<String, String> = emptyMap())

/**
 * A request a page serves, once signed in: its path as decoded segments (`/players/B%C3%A9a` → `players`, `Béa`) and its
 * query, the first value of each parameter.
 */
class WebRequest(val segments: List<String>, val query: Map<String, String> = emptyMap()) {
    /** The page's root, the first segment; empty for `/`. */
    val root: String get() = segments.firstOrNull().orEmpty()

    /** The segments after the root: `Béa` for `/players/Béa`. */
    val path: List<String> get() = segments.drop(1)

    /** The parameter [name]; null when absent or empty (an empty field of a form). */
    fun param(name: String): String? = query[name]?.takeIf { it.isNotEmpty() }

    fun int(name: String, default: Int): Int = param(name)?.toIntOrNull() ?: default

    fun long(name: String): Long? = param(name)?.toLongOrNull()

    /** This page with [changes] to its query: a null value removes the parameter (`request.with("page" to 2)`). */
    fun with(vararg changes: Pair<String, Any?>): Link {
        val parameters = LinkedHashMap<String, Any?>(query)
        changes.forEach { (name, value) -> if (value == null) parameters.remove(name) else parameters[name] = value }
        return link(*segments.toTypedArray(), query = parameters)
    }
}

/**
 * A page rendered: its [title] (the `<h1>` and the window's title), its [status], [refresh] seconds when it reloads itself
 * (`<meta http-equiv="refresh">`), and its [body].
 */
class Page(val title: String, val status: Int = 200, val refresh: Int? = null, val body: Html.() -> Unit)

/** A wrong parameter of a request (`?page=x` is not one: it falls back): the page answers 400 with [message]. */
class BadRequest(message: String) : RuntimeException(message)

/**
 * One page, or one family of pages, of the dashboard (M6 §8.4), served under `/<root>`. Each lives in its own file of
 * `web/pages/`, owned by its task; [Routes.PAGES] lists them all.
 */
interface WebPage {
    /** The first segment of its paths (`turns` serves `/turns` and `/turns/42`); empty for the overview, `/`. */
    val root: String

    /** Its label in the navigation bar. */
    val label: String

    /**
     * Renders [request]; null when there is no such page (404). Runs on a thread of the dashboard, never the server
     * thread: it reads the live state from snapshots and the Journal through [PageContext.read]. A Journal that is
     * unavailable or slow need not be handled: the page then says so, with a 503.
     */
    fun render(request: WebRequest, context: PageContext): Page?
}

/**
 * What a page renders with: the [services], the time of the request ([now]) in the Journal's zone ([zone]), the
 * [prices] in force, and reads of the Journal ([read]).
 */
class PageContext internal constructor(val services: NarratorServices, private val reads: Semaphore) {
    val now: Long = services.clock.millis()

    val zone: ZoneId = zoneOf(services)

    val prices: Prices = Prices.of(services.settings.get().config)

    private val text = TraceText(zone, now)

    /** [at] as `21:30:12` today, else `10-01 23:05`. */
    fun time(at: Instant): String = text.time(at)

    /** How long before [now]: `42 s ago`, `3 min ago`. */
    fun ago(at: Instant): String = text.ago(at)

    /**
     * Runs [query] on a read connection of the Journal. The dashboard reads one query at a time: of the Journal's three
     * read connections, two always stay with the Director and the memory (M6 §8.5). Waits [READ_TIMEOUT_MS] at most for
     * its turn, then as much for the read; statements should stop as soon ([rows] does). Fails with
     * [JournalUnavailableException] or [JournalTimeoutException], which the page shows.
     */
    fun <T> read(query: (Connection) -> T): T {
        if (!reads.tryAcquire(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)) throw JournalTimeoutException(READ_TIMEOUT_MS)
        try {
            return runBlocking { services.journal.read(READ_TIMEOUT_MS, query) }
        } finally {
            reads.release()
        }
    }

    private companion object {
        /** The zone of `journal.timeZone`, or the server's. */
        fun zoneOf(services: NarratorServices): ZoneId =
            services.settings.get().config.journal.timeZone.ifBlank { null }?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: services.clock.zone
    }
}

/** The longest a read of the dashboard waits for its turn, then for the Journal; a statement's own limit too ([rows]). */
const val READ_TIMEOUT_MS = 5_000L

/** Every row of [sql] with its [params] (`Long`, `Int`, `String`), each read by [row]; the statement stops after [READ_TIMEOUT_MS]. */
fun <T> Connection.rows(sql: String, vararg params: Any, row: ResultSet.() -> T): List<T> =
    prepareStatement(sql).use { statement ->
        statement.queryTimeout = (READ_TIMEOUT_MS / 1_000).toInt()
        params.forEachIndexed { i, value ->
            when (value) {
                is Long -> statement.setLong(i + 1, value)
                is Int -> statement.setInt(i + 1, value)
                is String -> statement.setString(i + 1, value)
                else -> error("unsupported parameter type ${value.javaClass}")
            }
        }
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.row()) } }
    }

/**
 * The dashboard's answers (M6 §8.2, §8.3), whatever serves them: [DashboardServer] or a test. In order:
 * 1. the `Host` must be `127.0.0.1`, `localhost` or `[::1]` with the dashboard's [port], else 421 (DNS rebinding);
 * 2. only `GET` and `HEAD`, else 405: nothing here writes anything;
 * 3. `/style.css` and `/login` are served to anyone (the stylesheet holds no data; the login checks its key);
 * 4. any other path needs the cookie the login set, holding the [token], else 403 and no data;
 * 5. the page of the path's root ([PAGES]), or 404.
 *
 * Every response carries the security headers ([SECURITY_HEADERS]): a CSP that allows no script at all. Refusals are
 * logged in a `WARN`, one line a minute at most; pages served in a `DEBUG` line, without their parameters. The token,
 * the cookie and the query never reach the log.
 */
class Routes internal constructor(private val services: NarratorServices, private val token: DashboardToken, private val port: Int) {
    private val reads = Semaphore(1, true)
    private val refusals = Refusals()

    private val nav: List<NavEntry> = PAGES.map { NavEntry(it.label, link(*rootSegments(it))) }

    /** The answer to [call]. Any thread; reads the Journal on the caller's thread (a dashboard thread). */
    fun respond(call: WebCall): WebResponse {
        val response = try {
            route(call)
        } catch (e: Exception) {
            log.error("the dashboard failed on /${firstSegment(call.rawPath)}", e)
            html(Page("Error", status = 500) { p("The dashboard failed: ${e.javaClass.simpleName}: ${e.message}. See the server log.", "failure") }, signedIn = false)
        }
        return WebResponse(response.status, response.contentType, response.body, response.headers + SECURITY_HEADERS)
    }

    private fun route(call: WebCall): WebResponse {
        if (!hostAllowed(call.host)) {
            return refused(call, 421, "host ${call.host?.take(80) ?: "missing"}", "Misdirected request", "This dashboard answers only at http://127.0.0.1:$port/.")
        }
        if (call.method != "GET" && call.method != "HEAD") {
            val response = refused(call, 405, "method ${call.method.take(16)}", "Method not allowed", "The dashboard is read only: GET and HEAD only.")
            return WebResponse(response.status, response.contentType, response.body, mapOf("Allow" to "GET, HEAD"))
        }
        val request = parse(call) ?: return html(Page("Bad request", status = 400) { p("This address cannot be read.", "failure") }, signedIn = false)
        when (request.root) {
            STYLESHEET -> return stylesheet()
            LOGIN -> return login(call, request)
        }
        if (call.cookies.none { header -> cookieValues(header).any(token::matches) }) {
            return refused(call, 403, "not signed in", "Not signed in", "Run /narrator dashboard in game (operators only) for a login link.")
        }
        val page = PAGES.firstOrNull { it.root == request.root } ?: return notFound(signedIn = true)
        val started = System.nanoTime()
        val rendered = try {
            page.render(request, PageContext(services, reads)) ?: return notFound(signedIn = true)
        } catch (e: JournalUnavailableException) {
            Page(page.label, status = 503) { p("${e.message}. This page reads the Journal: it comes back with it (see /narrator status).", "failure") }
        } catch (e: JournalTimeoutException) {
            Page(page.label, status = 503) { p("${e.message}: the Journal is busy. Reload the page in a moment.", "failure") }
        } catch (e: BadRequest) {
            Page(page.label, status = 400) { p(e.message.orEmpty(), "failure") }
        }
        val response = html(rendered, signedIn = true, current = link(*rootSegments(page)))
        log.debug { "served /${request.root}${if (request.path.isEmpty()) "" else "/…"} (${response.status}) in ${(System.nanoTime() - started) / 1_000_000} ms" }
        return response
    }

    /** `/login?key=<token>`: the cookie, then `/` (303), the token out of the address bar; a wrong key gets 403. */
    private fun login(call: WebCall, request: WebRequest): WebResponse {
        if (!token.matches(request.query[KEY])) {
            return refused(call, 403, "wrong login key", "Wrong login link", "This link is wrong or expired: run /narrator dashboard in game for a new one.")
        }
        log.info("a browser signed in to the dashboard")
        return WebResponse(
            303, null, ByteArray(0),
            mapOf("Location" to "/", "Set-Cookie" to "$COOKIE=${token.value}; HttpOnly; SameSite=Strict; Path=/"),
        )
    }

    private fun stylesheet(): WebResponse = STYLE?.let { WebResponse(200, "text/css; charset=utf-8", it) } ?: notFound(signedIn = false)

    private fun notFound(signedIn: Boolean) = html(Page("Not found", status = 404) { p("There is no such page.", "muted") }, signedIn)

    /** A refusal: its page, without any data, and a rate-limited `WARN` saying [why] (never the query nor the cookie). */
    private fun refused(call: WebCall, status: Int, why: String, title: String, message: String): WebResponse {
        refusals.warn("refused ${call.method.take(16)} /${firstSegment(call.rawPath)} ($status): $why")
        return html(Page(title, status = status) { p(message, "failure") }, signedIn = false)
    }

    /** [page] in the frame of the dashboard; the navigation bar only for a browser [signedIn]. */
    private fun html(page: Page, signedIn: Boolean, current: Link? = null): WebResponse {
        val text = document(page.title, if (signedIn) nav else emptyList(), current, page.refresh, page.body)
        return WebResponse(page.status, "text/html; charset=utf-8", text.toByteArray(StandardCharsets.UTF_8))
    }

    private val hosts = setOf("127.0.0.1:$port", "localhost:$port", "[::1]:$port")

    private fun hostAllowed(host: String?): Boolean = host != null && host.lowercase() in hosts

    /** The rate limit of the refusals' log lines: the first of each minute, with how many were left out before it. */
    private inner class Refusals {
        private var lastAt: Long? = null
        private var skipped = 0

        @Synchronized
        fun warn(line: String) {
            val now = services.clock.millis()
            val last = lastAt
            if (last != null && now - last < REFUSAL_LOG_INTERVAL_MS) {
                skipped++
                return
            }
            log.warn(line + if (skipped > 0) " ($skipped more refused since the last line)" else "")
            lastAt = now
            skipped = 0
        }
    }

    companion object {
        private val log = Log.web

        /**
         * Every page, in the order of the navigation bar. A page's file is owned by its task (M6 §16.2): M6-10 the
         * overview, M6-11 the traces, M6-12 the memory, the mechanics, the Forge, the style and SQL.
         */
        val PAGES: List<WebPage> = listOf(
            OverviewPage, TurnsPage, ConversationsPage, TasksPage, DecisionsPage, CostsPage,
            MemoryPage, PlayersPage, MechanicsPage, ForgePage, StylePage, SqlPage,
        )

        /** The policy that forbids any script (M6 §8.1): only the dashboard's stylesheet, its forms and inline SVG. */
        const val CSP = "default-src 'none'; style-src 'self'; img-src 'self' data:; form-action 'self'; frame-ancestors 'none'; base-uri 'none'"

        /** On every response, refusals and the stylesheet included (M6 §8.3). */
        val SECURITY_HEADERS: Map<String, String> = mapOf(
            "Content-Security-Policy" to CSP,
            "X-Content-Type-Options" to "nosniff",
            "Cache-Control" to "no-store",
            "Referrer-Policy" to "no-referrer",
        )

        /** The cookie that holds the token once signed in. */
        const val COOKIE = "narrator2"

        /** The login's parameter: the token. */
        const val KEY = "key"

        private const val LOGIN = "login"
        private const val STYLESHEET = "style.css"
        private const val REFUSAL_LOG_INTERVAL_MS = 60_000L

        /** The stylesheet of the jar; null if it is missing. */
        private val STYLE: ByteArray? = Routes::class.java.getResourceAsStream("/narrator2/web/style.css")?.use { it.readAllBytes() }

        private fun rootSegments(page: WebPage): Array<String> = if (page.root.isEmpty()) emptyArray() else arrayOf(page.root)

        /** The request's path and query, decoded; null when they cannot be (a bad `%` escape). */
        internal fun parse(call: WebCall): WebRequest? = try {
            val segments = call.rawPath.split('/').filter { it.isNotEmpty() }.map { decode(it.replace("+", "%2B")) }
            val query = LinkedHashMap<String, String>()
            call.rawQuery?.split('&')?.filter { it.isNotEmpty() }?.forEach { pair ->
                val name = decode(pair.substringBefore('='))
                val value = decode(pair.substringAfter('=', ""))
                query.putIfAbsent(name, value)
            }
            WebRequest(segments, query)
        } catch (_: IllegalArgumentException) {
            null
        }

        private fun decode(text: String): String = URLDecoder.decode(text, StandardCharsets.UTF_8)

        /** The values of the cookie [COOKIE] in one `Cookie` header (`a=1; narrator2=…`). */
        private fun cookieValues(header: String): List<String> =
            header.split(';').map { it.trim() }.filter { it.startsWith("$COOKIE=") }.map { it.substringAfter('=') }

        /** The first segment of a raw path, for a log line: never the rest, nor the query. */
        private fun firstSegment(rawPath: String): String = rawPath.trimStart('/').substringBefore('/').take(40)
    }
}

/** A page that its task has not built yet (M6-11, M6-12): its title, and a line saying so. */
internal fun notBuiltYet(title: String): Page = Page(title) { p("This page is not built yet.", "muted") }
