package io.github.clemsix6.narrator2.web

import io.github.clemsix6.narrator2.config.DashboardConfig
import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.journal.DuckDatabase
import io.github.clemsix6.narrator2.journal.JournalFixture
import io.github.clemsix6.narrator2.observer.LivePlayer
import io.github.clemsix6.narrator2.testing.LogCapture
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.testing.testSettings
import org.apache.logging.log4j.Level
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The dashboard's server over real sockets (M6 §8.2, §8.3): bound to `127.0.0.1` only, the token (constant time, cookie,
 * reset), the `Host`, the methods, the security headers on every answer, the token out of the log, a port taken.
 */
class DashboardServerTest {
    private val clock = TestClock()
    private val services = TestServices(clock = clock, settings = testSettings(NarratorConfig(dashboard = DashboardConfig(enabled = true, port = 0))))
    private val started = mutableListOf<Dashboard>()

    init {
        services.players.put(player("Bartok"))
    }

    @AfterEach
    fun stop() = started.forEach { it.stop() }

    private fun start(target: TestServices = services): DashboardServer = assertIs<DashboardServer>(createDashboard(target)).also { started += it }

    // ── What it listens on ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `it listens on 127,0,0,1 only, on the port the system gave`() {
        val server = start()
        assertTrue(server.port in 1..65_535)
        assertEquals("http://127.0.0.1:${server.port}/", server.url)
        assertEquals("127.0.0.1", server.address.address.hostAddress)
        assertEquals("on http://127.0.0.1:${server.port}/", dashboardState(server))
        assertFails("not on the IPv6 loopback") { Socket().use { it.connect(InetSocketAddress(InetAddress.getByName("::1"), server.port), 1_000) } }
        val outside = NetworkInterface.networkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
        outside.forEach { address ->
            assertFails("not on $address") { Socket().use { it.connect(InetSocketAddress(address, server.port), 1_000) } }
        }
    }

    // ── The token ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `without the token, or with a wrong one, nothing is served`() {
        val server = start()
        val token = server.loginUrl().substringAfter("key=")
        listOf("/", "/turns", "/players/Bartok", "/sql?q=SELECT%201").forEach { path ->
            val answers = listOf(
                get(server, path),
                get(server, path, cookie = "narrator2=deadbeef"),
                get(server, path, cookie = "narrator2=${token.dropLast(1)}"),
                get(server, path, cookie = "narrator2=${token}0"),
                get(server, path, cookie = "narrator2=${token.uppercase()}"),
                get(server, path, cookie = "other=$token"),
            )
            answers.forEach { answer ->
                assertEquals(403, answer.status, "$path: ${answer.body}")
                assertFalse("Bartok" in answer.body || "Players online" in answer.body, "$path served data: ${answer.body}")
            }
        }
        val wrong = get(server, "/login?key=${token.dropLast(1)}")
        assertEquals(403, wrong.status)
        assertNull(wrong.header("set-cookie"), "no cookie for a wrong key")
        assertEquals(403, get(server, "/login").status, "no key")
    }

    @Test
    fun `the login sets the cookie and goes to the overview, the token out of the address bar`() {
        val server = start()
        val login = get(server, "/" + server.loginUrl().substringAfter(server.url))
        assertEquals(303, login.status)
        assertEquals("/", login.header("location"))
        val cookie = login.header("set-cookie")!!
        assertEquals("narrator2=${token(server)}; HttpOnly; SameSite=Strict; Path=/", cookie)
        assertEquals("", login.body)

        val overview = get(server, "/", cookie = cookie.substringBefore(';'))
        assertEquals(200, overview.status, overview.body)
        assertEquals("text/html; charset=utf-8", overview.header("content-type"))
        assertContains(overview.body, "<h1>Overview</h1>")
        assertContains(overview.body, ">Bartok</a>")
        assertEquals(200, get(server, "/", cookie = "a=1; ${cookie.substringBefore(';')}; b=2").status, "among other cookies")
    }

    @Test
    fun `the token is drawn at random and compared whole`() {
        val token = DashboardToken()
        assertTrue(Regex("[0-9a-f]{64}").matches(token.value), "32 random bytes in hexadecimal")
        assertNotEquals(token.value, DashboardToken().value)
        assertTrue(token.matches(token.value))
        listOf(null, "", token.value.dropLast(1), token.value + "0", token.value.uppercase(), "0".repeat(64), token.value.repeat(1_000)).forEach { candidate ->
            assertFalse(token.matches(candidate), "matched ${candidate?.take(70)}")
        }
        val before = token.value
        token.reset()
        assertNotEquals(before, token.value)
        assertFalse(token.matches(before))
        assertTrue(token.matches(token.value))
    }

    @Test
    fun `a reset signs every browser out and voids the links given before`() {
        val server = start()
        val oldLink = "/" + server.loginUrl().substringAfter(server.url)
        val oldCookie = get(server, oldLink).header("set-cookie")!!.substringBefore(';')
        assertEquals(200, get(server, "/", cookie = oldCookie).status)

        server.resetToken()
        assertEquals(403, get(server, "/", cookie = oldCookie).status, "the old cookie")
        assertEquals(403, get(server, oldLink).status, "the old link")
        val newCookie = get(server, "/" + server.loginUrl().substringAfter(server.url)).header("set-cookie")!!.substringBefore(';')
        assertEquals(200, get(server, "/", cookie = newCookie).status)
    }

    // ── Host, methods, headers ──────────────────────────────────────────────────────────────────────

    @Test
    fun `only the dashboard's own host is answered`() {
        val server = start()
        val cookie = signIn(server)
        val port = server.port
        assertEquals(421, get(server, "/", cookie = cookie, host = "evil.example:$port").status, "DNS rebinding")
        assertEquals(421, get(server, "/", cookie = cookie, host = "evil.example").status)
        assertEquals(421, get(server, "/", cookie = cookie, host = "127.0.0.1:${port + 1}").status, "another port")
        assertEquals(421, get(server, "/", cookie = cookie, host = null, version = "HTTP/1.0").status, "no host")
        assertEquals(421, get(server, "/login?key=${token(server)}", host = "evil.example:$port").status, "not even the login")
        assertFalse("Bartok" in get(server, "/", cookie = cookie, host = "evil.example:$port").body)
        assertEquals(200, get(server, "/", cookie = cookie, host = "localhost:$port").status)
        assertEquals(200, get(server, "/", cookie = cookie, host = "LOCALHOST:$port").status)
        assertEquals(200, get(server, "/", cookie = cookie, host = "[::1]:$port").status)
    }

    @Test
    fun `it only reads, any method but GET and HEAD is refused`() {
        val server = start()
        val cookie = signIn(server)
        listOf("POST", "PUT", "DELETE", "PATCH", "OPTIONS").forEach { method ->
            val answer = get(server, "/", cookie = cookie, method = method)
            assertEquals(405, answer.status, method)
            assertEquals("GET, HEAD", answer.header("allow"), method)
            assertFalse("Bartok" in answer.body, method)
        }
        assertEquals(405, get(server, "/login?key=${token(server)}", method = "POST").status)
        val head = get(server, "/", cookie = cookie, method = "HEAD")
        assertEquals(200, head.status)
        assertEquals("", head.body)
    }

    @Test
    fun `every answer carries the security headers`() {
        val server = start()
        val cookie = signIn(server)
        val answers = mapOf(
            "page" to get(server, "/", cookie = cookie),
            "stylesheet" to get(server, "/style.css"),
            "not signed in" to get(server, "/"),
            "wrong key" to get(server, "/login?key=x"),
            "login" to get(server, "/login?key=${token(server)}"),
            "not found" to get(server, "/nope", cookie = cookie),
            "method" to get(server, "/", cookie = cookie, method = "POST"),
            "host" to get(server, "/", cookie = cookie, host = "evil.example"),
            "head" to get(server, "/", cookie = cookie, method = "HEAD"),
        )
        answers.forEach { (what, answer) ->
            assertEquals(Routes.CSP, answer.header("content-security-policy"), what)
            assertEquals("nosniff", answer.header("x-content-type-options"), what)
            assertEquals("no-store", answer.header("cache-control"), what)
            assertEquals("no-referrer", answer.header("referrer-policy"), what)
        }
        assertEquals(404, answers.getValue("not found").status)
        val css = answers.getValue("stylesheet")
        assertEquals(200, css.status, "the stylesheet holds no data: served to anyone")
        assertEquals("text/css; charset=utf-8", css.header("content-type"))
        assertContains(css.body, "prefers-color-scheme: dark")
    }

    // ── The log ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the token never reaches the log`() {
        LogCapture().use { logs ->
            val server = start()
            val first = token(server)
            val cookie = signIn(server)
            get(server, "/", cookie = cookie)
            get(server, "/login?key=${first.dropLast(2)}")
            server.resetToken()
            val second = token(server)
            signIn(server)
            get(server, "/", cookie = cookie)
            server.stop()
            assertContains(logs.text, "dashboard on ${server.url}")
            assertContains(logs.text, "a browser signed in to the dashboard")
            assertContains(logs.text, "refused GET /login (403): wrong login key")
            listOf(first, second, first.dropLast(2), "key=").forEach { secret -> assertFalse(secret in logs.text, "the log shows $secret") }
        }
    }

    @Test
    fun `refusals are logged once a minute at most`() {
        LogCapture().use { logs ->
            val server = start()
            repeat(3) { get(server, "/turns") }
            assertEquals(1, logs.at(Level.WARN).count { "refused" in it }, logs.text)
            clock.advance(Duration.ofSeconds(61))
            get(server, "/", method = "DELETE")
            val refusals = logs.at(Level.WARN).filter { "refused" in it }
            assertEquals(2, refusals.size, logs.text)
            assertEquals("[Web] refused GET /turns (403): not signed in", refusals[0])
            assertEquals("[Web] refused DELETE / (405): method DELETE (2 more refused since the last line)", refusals[1])
        }
    }

    // ── Start and stop ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a port taken is a warning and no dashboard, never a failure`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { taken ->
            val port = taken.localPort
            val busy = TestServices(settings = testSettings(NarratorConfig(dashboard = DashboardConfig(enabled = true, port = port))))
            LogCapture().use { logs ->
                val dashboard = createDashboard(busy)
                assertIs<DisabledDashboard>(dashboard)
                assertNull(dashboard.url)
                assertNull(dashboard.loginUrl())
                assertContains(dashboard.unavailableReason!!, "port $port of 127.0.0.1 is taken")
                assertContains(dashboardState(dashboard), "off (port $port of 127.0.0.1 is taken")
                assertTrue(logs.at(Level.WARN).any { "no dashboard: port $port of 127.0.0.1 is taken" in it }, logs.text)
            }
        }
    }

    @Test
    fun `dashboard enabled false starts nothing`() {
        val off = createDashboard(TestServices(settings = testSettings(NarratorConfig(dashboard = DashboardConfig(enabled = false, port = 0)))))
        assertIs<DisabledDashboard>(off)
        assertEquals("off (dashboard.enabled is false)", dashboardState(off))
        off.resetToken()
        off.stop()
    }

    @Test
    fun `stop closes the port at once, and no thread of the server keeps the JVM alive`() {
        val server = start()
        assertEquals(200, get(server, "/style.css").status)
        val threads = Thread.getAllStackTraces().keys.filter { it.name == "HTTP-Dispatcher" || it.name.startsWith("narrator2-web-") }
        assertTrue(threads.any { it.name == "HTTP-Dispatcher" }, "the dispatcher runs")
        threads.forEach { assertTrue(it.isDaemon, "${it.name} is not a daemon") }
        val begin = System.nanoTime()
        server.stop()
        assertTrue(System.nanoTime() - begin < TimeUnit.SECONDS.toNanos(2), "stop waits for nothing")
        assertFails("the port is closed") { get(server, "/style.css") }
        server.stop()
    }

    // ── The Journal ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the Journal has a third read connection, and the dashboard reads one query at a time`() {
        assertEquals(3, DuckDatabase.Options().readers)
        JournalFixture().use { fixture ->
            assertEquals(3, fixture.db.readers.size)
            val context = PageContext(TestServices(journal = fixture.journal), Semaphore(1, true))
            val active = AtomicInteger()
            val most = AtomicInteger()
            val ready = CountDownLatch(1)
            val readers = (1..3).map {
                thread {
                    ready.await()
                    context.read {
                        most.accumulateAndGet(active.incrementAndGet()) { a, b -> maxOf(a, b) }
                        Thread.sleep(150)
                        active.decrementAndGet()
                    }
                }
            }
            ready.countDown()
            readers.forEach { it.join(10_000) }
            assertEquals(1, most.get(), "one read of the dashboard at a time")
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────

    private class Answer(val status: Int, private val headers: Map<String, String>, val body: String) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    /** One request over a raw socket, which lets a test write any `Host` (or none) and any method. */
    private fun get(
        server: DashboardServer,
        path: String,
        cookie: String? = null,
        host: String? = "127.0.0.1:${server.port}",
        method: String = "GET",
        version: String = "HTTP/1.1",
    ): Answer = Socket(InetAddress.getByName("127.0.0.1"), server.port).use { socket ->
        socket.soTimeout = 10_000
        val request = buildString {
            append("$method $path $version\r\n")
            host?.let { append("Host: $it\r\n") }
            cookie?.let { append("Cookie: $it\r\n") }
            if (method == "POST" || method == "PUT" || method == "PATCH") append("Content-Length: 0\r\n")
            append("Connection: close\r\n\r\n")
        }
        socket.getOutputStream().write(request.toByteArray(StandardCharsets.US_ASCII))
        val text = String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
        val head = text.substringBefore("\r\n\r\n")
        val lines = head.split("\r\n")
        val headers = lines.drop(1).associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        Answer(lines.first().split(' ')[1].toInt(), headers, text.substringAfter("\r\n\r\n", ""))
    }

    private fun token(server: DashboardServer): String = server.loginUrl().substringAfter("key=")

    /** Signs in through the login link; the cookie to send back. */
    private fun signIn(server: DashboardServer): String =
        get(server, "/login?key=${token(server)}").header("set-cookie")!!.substringBefore(';')

    private fun player(name: String) = LivePlayer(
        ref = PlayerRef(UUID.nameUUIDFromBytes(name.toByteArray()), name), sessionId = 1, joinedAt = 0, isOp = false, gameMode = "survival",
        pos = Pos("minecraft:overworld", 10, 64, -3), biome = "minecraft:plains", structure = null, underground = false,
        health = 18f, maxHealth = 20f, food = 20, vehicle = null, lastDeathAt = null, lastMoveAt = 0, idle = false,
    )
}
