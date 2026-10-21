package io.github.clemsix6.narrator2.web

import io.github.clemsix6.narrator2.journal.Journal
import io.github.clemsix6.narrator2.web.DashboardDataset.Companion.FILE_SECRET
import io.github.clemsix6.narrator2.web.DashboardDataset.Companion.PRIVATE_SECRET
import io.github.clemsix6.narrator2.web.pages.OverviewPage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What no page of the dashboard may show (M6 §8.3, ARCHITECTURE §12, §17): the private messages between players, the
 * server's files and its secrets. [DashboardDataset] holds them all, on a Journal on disk (whose own files hold the
 * private messages in raw bytes): a private message, a key file next to the Journal and in the data folder, the
 * dashboard's token. Every page reachable from the navigation bar, by any link, shows none of them; no statement the
 * pages run names `private_messages`; the SQL page, with the model's access, refuses every query out of its white list.
 */
class DashboardPrivacyTest {
    private val folder: Path = Files.createTempDirectory("m6-12-privacy")
    private val dataset = DashboardDataset(folder.resolve("narrator.duckdb"))
    private val token = DashboardToken()
    private val journal = RecordingJournal(dataset.fixture.journal)
    private val services = dataset.services(journal = journal)
    private val routes = Routes(services, token, DATASET_PORT)
    private val keyFile = folder.resolve("llmgw.key").also { Files.writeString(it, FILE_SECRET) }

    init {
        Files.writeString(services.dataDir.resolve("llmgw.key"), FILE_SECRET)
    }

    @AfterEach
    fun close() = dataset.close()

    /** What no answer may hold: the secrets, the token, and where the server keeps its files. */
    private val secrets = listOf(PRIVATE_SECRET, FILE_SECRET, token.value, folder.toAbsolutePath().toString(), services.dataDir.toAbsolutePath().toString())

    private fun assertHidden(what: String, response: WebResponse) {
        val body = bodyText(response)
        secrets.forEach { secret -> assertFalse(secret in body, "$what shows $secret") }
        assertFalse("private_messages" in body && response.status == 200 && !what.startsWith("/sql"), "$what names the private messages")
    }

    @Test
    fun `no page reachable by a link shows a private message, a file, a secret or a path of the server`() {
        val seeds = Routes.PAGES.map { "/${it.root}" } + listOf(
            "/memory/canon", "/memory/memories", "/memory/interactions", "/memory/notes", "/memory/ideas", "/memory/chronicle", "/memory/docs",
            "/memory/jobs", "/players/Bartok?show=events", "/players/Mimosa?show=chat", "/mechanics/decisions", "/style?period=month",
            "/sql?q=SELECT+*+FROM+timeline+WHERE+id+%3D+${dataset.whisper}", "/sql?q=SELECT+*+FROM+chat_log",
        )
        val seen = LinkedHashSet<String>()
        val queue = ArrayDeque(seeds)
        while (queue.isNotEmpty() && seen.size < MAX_PAGES) {
            val path = queue.removeFirst()
            if (!seen.add(path)) continue
            val response = pageOf(routes, token, path)
            assertTrue(response.status in setOf(200, 400, 404), "$path: ${response.status} ${bodyText(response).take(300)}")
            assertSafePage(path, response)
            assertHidden(path, response)
            LINK.findAll(bodyText(response)).map { it.groupValues[1].replace("&amp;", "&") }
                .filter { it != "/style.css" && !it.startsWith("/login") && it !in seen }
                .forEach { queue.addLast(it) }
        }
        assertTrue(seen.size > 60, "the crawl reached ${seen.size} pages")
        listOf("/memory/C-510", "/players/Bartok", "/players/Mimosa", "/forge/tag_game/1", "/memory/docs/grimoire/1").forEach {
            assertTrue(it in seen, "the crawl reached $it")
        }
    }

    @Test
    fun `no statement of the dashboard names the private messages`() {
        val pages = Routes.PAGES.map { "/${it.root}" } + listOf(
            "/memory/canon", "/memory/memories", "/memory/interactions?player=Bartok", "/memory/notes", "/memory/ideas", "/memory/chronicle",
            "/memory/docs", "/memory/docs/grimoire", "/memory/jobs", "/memory/C-510", "/memory/P-550", "/players/Bartok", "/players/Bartok?show=chat",
            "/players/Mimosa?show=events", "/mechanics/decisions", "/forge/tag_game", "/style?period=month", "/sql?q=SELECT+count%28*%29+FROM+chat",
        )
        pages.forEach { path -> assertEquals(200, pageOf(routes, token, path).status, path) }
        val statements = journal.statements.toList()
        assertTrue(statements.size > 40, "${statements.size} statements recorded")
        (statements + DashboardQueries.STATEMENTS + OverviewPage.COST_BY_ROLE).forEach { sql ->
            assertFalse("private_messages" in sql.lowercase(), "a statement names the private messages: $sql")
        }
    }

    @Test
    fun `the SQL page refuses every query out of the model's white list, and none reaches a private message or a file`() {
        val db = folder.resolve("narrator.duckdb").toString().replace('\\', '/')
        val key = keyFile.toString().replace('\\', '/')
        val refused = listOf(
            "SELECT text FROM private_messages",
            "SELECT * FROM PRIVATE_MESSAGES",
            "SELECT * FROM main.private_messages",
            "WITH x AS (SELECT * FROM private_messages) SELECT * FROM x",
            "SELECT * FROM range(3) a, (SELECT text FROM private_messages) b",
            "SELECT * FROM chat_log WHERE text IN (SELECT text FROM private_messages)",
            "SELECT * FROM query('SELECT text FROM private_messages')",
            "SELECT * FROM query_table('private_' || 'messages')",
            "SUMMARIZE private_messages",
            "DESCRIBE private_messages",
            "SELECT 1; SELECT text FROM private_messages",
            "SELECT * FROM duckdb_tables()",
            "SELECT * FROM information_schema.tables",
            "SHOW TABLES",
            "SELECT current_setting('allowed_paths')",
            "SELECT * FROM read_text('$key')",
            "SELECT * FROM read_blob('$db')",
            "SELECT * FROM read_csv('$key')",
            "SELECT * FROM '$db'",
            "SELECT * FROM glob('*')",
            "PRAGMA database_list",
            "ATTACH '$db' AS other",
            "COPY chat TO 'out.csv'",
            "INSERT INTO chat (id, ts, channel, text) VALUES (1, now(), 'public', 'forged')",
            "CREATE TABLE forged (a INTEGER)",
            "SELECT nextval('forged')",
        )
        refused.forEach { sql ->
            val response = pageOf(routes, token, link("sql", query = mapOf("q" to sql)).href)
            assertEquals(200, response.status, sql)
            assertSafePage(sql, response)
            val body = bodyText(response)
            assertContains(body, "Refused: ", message = sql)
            assertFalse(PRIVATE_SECRET in body || FILE_SECRET in body || token.value in body, sql)
        }
        assertContains(bodyText(pageOf(routes, token, "/sql?q=SELECT+text+FROM+private_messages")), "private messages between players stay private")
        val whisper = bodyText(pageOf(routes, token, "/sql?q=SELECT+*+FROM+timeline+WHERE+id+%3D+${dataset.whisper}"))
        assertContains(whisper, "0 rows", message = "a private message is in no table the model reads")
        assertContains(bodyText(pageOf(routes, token, "/sql?q=SELECT+count%28*%29+AS+n+FROM+chat")), "\nn\n", message = "the white list reads the chat")
        journal.statements.forEach { sql -> assertFalse("private_messages" in sql.lowercase(), "it reached DuckDB: $sql") }
        assertEquals(1L, dataset.fixture.count("private_messages"), "the private message is there, out of reach")
    }

    @Test
    fun `an address never becomes a path of the server`() {
        listOf(
            "/forge/..%2F..%2Fllmgw.key/1", "/forge/%2E%2E/1", "/forge/tag_game/..%2F..%2Fllmgw.key", "/memory/docs/..%2F..%2Fllmgw.key",
            "/memory/docs/grimoire/..%2F1", "/players/..%2F..%2Fllmgw.key", "/memory/..%2Fllmgw.key",
        ).forEach { path ->
            val response = pageOf(routes, token, path)
            assertEquals(404, response.status, path)
            assertSafePage(path, response)
            assertHidden(path, response)
        }
    }

    /** The Journal of the dataset, recording the text of every statement its read connections prepare or execute. */
    private class RecordingJournal(private val inner: Journal) : Journal by inner {
        val statements = ConcurrentLinkedQueue<String>()

        override suspend fun <T> read(timeoutMs: Long, query: (Connection) -> T): T = inner.read(timeoutMs) { query(recording(it)) }

        private fun recording(connection: Connection): Connection = proxy(Connection::class.java) { method, args ->
            if (method.name in PREPARE) (args?.firstOrNull() as? String)?.let { statements += it }
            val result = call(connection, method, args)
            if (result is Statement && result !is PreparedStatement) recording(result) else result
        }

        private fun recording(statement: Statement): Statement = proxy(Statement::class.java) { method, args ->
            if (method.name.startsWith("execute") || method.name == "addBatch") (args?.firstOrNull() as? String)?.let { statements += it }
            call(statement, method, args)
        }

        private fun <T> proxy(type: Class<T>, handle: (java.lang.reflect.Method, Array<out Any?>?) -> Any?): T =
            type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args -> handle(method, args) })

        private fun call(target: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? = try {
            method.invoke(target, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

        companion object {
            val PREPARE = setOf("prepareStatement", "prepareCall", "nativeSQL")
        }
    }

    private companion object {
        /** The pages the crawl reads at most. */
        const val MAX_PAGES = 400

        /** A link of a page: every `href` the dashboard writes points inside it. */
        val LINK = Regex("href=\"(/[^\"]*)\"")
    }
}
