package io.github.clemsix6.narrator2.web

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.runtime.NarratorServices
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.HexFormat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The web dashboard (M6 §8), off unless `dashboard.enabled`: a read-only HTTP server bound to `127.0.0.1`, without
 * JavaScript, behind a token drawn at each start. The runtime builds it at the end of its wiring (`createDashboard`) and
 * stops it first at `SERVER_STOPPING`; `/narrator dashboard` gives the operators a login link. Nothing in the Narrator
 * depends on it.
 */
interface Dashboard {
    /** Where it serves, e.g. `http://127.0.0.1:8770/`; null when there is no dashboard. */
    val url: String?

    /** Why there is no dashboard ([DisabledDashboard.SWITCHED_OFF], the port is taken…); null when it serves. */
    val unavailableReason: String?

    /**
     * A new login link, `<url>login?key=<code>`, with a code good for one sign-in within 10 minutes
     * ([DashboardToken.newCode]): given to an operator only, never logged. Null when there is no dashboard.
     */
    fun newLoginUrl(): String?

    /** Draws a new token: the browsers signed in are signed out, the links given before stop working. Any thread. */
    fun resetToken()

    /** Stops serving at once; a second call does nothing. Server thread; never waits. */
    fun stop()
}

/** No dashboard, and why ([reason]): nothing listens, nothing to stop. */
class DisabledDashboard(private val reason: String) : Dashboard {
    override val url: String? get() = null

    override val unavailableReason: String get() = reason

    override fun newLoginUrl(): String? = null

    override fun resetToken() = Unit

    override fun stop() = Unit

    companion object {
        /** The reason when the configuration leaves it off, the default. */
        const val SWITCHED_OFF = "dashboard.enabled is false"
    }
}

/**
 * The dashboard's secrets (M6 §8.3), from [SecureRandom], kept in memory only, never written to disk nor logged:
 * - the session token ([value]), 32 bytes in hexadecimal, drawn when the dashboard starts and again by [reset]: the
 *   cookie of a browser signed in holds it, and no address ever shows it;
 * - the login codes ([newCode]), 16 bytes (128 bits), each good for one sign-in within [CODE_TTL_MS] (10 minutes): the
 *   links of `/narrator dashboard` carry them. A link copied to `logs/latest.log`, a browser's history or a debug log is
 *   dead once it served. [reset] voids them too.
 *
 * Any thread. Comparisons run in constant time: both sides are hashed (SHA-256) and the two digests, always 32 bytes,
 * compared by [MessageDigest.isEqual], so the time depends neither on where they differ nor on the candidate's length.
 */
class DashboardToken(private val clock: Clock = Clock.systemUTC(), private val random: SecureRandom = SecureRandom()) {
    @Volatile
    private var current: String = draw(TOKEN_BYTES)

    /** The codes not used yet, oldest first: their digest, and when they expire (epoch milliseconds). */
    private val codes = ArrayList<Code>()

    private class Code(val digest: ByteArray, val expiresAt: Long)

    /** The session token itself, for the cookie only. */
    val value: String get() = current

    /** Whether [candidate] is the session token. */
    fun matches(candidate: String?): Boolean = candidate != null && MessageDigest.isEqual(digest(current), digest(candidate))

    /** A new login code, good for one [redeem] within [CODE_TTL_MS]; past [MAX_CODES] unused, the oldest is voided. */
    @Synchronized
    fun newCode(): String {
        dropExpired()
        if (codes.size >= MAX_CODES) codes.removeAt(0)
        val code = draw(CODE_BYTES)
        codes += Code(digest(code), clock.millis() + CODE_TTL_MS)
        return code
    }

    /** Whether [candidate] is a login code not used nor expired; if so, it is used up. */
    @Synchronized
    fun redeem(candidate: String?): Boolean {
        if (candidate == null) return false
        dropExpired()
        val digest = digest(candidate)
        return codes.removeIf { MessageDigest.isEqual(it.digest, digest) }
    }

    /** Draws a new session token and voids the login codes: the old token no longer [matches], no code redeems. */
    @Synchronized
    fun reset() {
        current = draw(TOKEN_BYTES)
        codes.clear()
    }

    private fun dropExpired() {
        val now = clock.millis()
        codes.removeIf { it.expiresAt <= now }
    }

    private fun draw(bytes: Int): String = HexFormat.of().formatHex(ByteArray(bytes).also(random::nextBytes))

    private fun digest(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8))

    companion object {
        /** How long a login code stays good: 10 minutes. */
        const val CODE_TTL_MS = 10 * 60_000L

        /** Unused login codes kept at most (an operator who asks again and again voids the oldest). */
        const val MAX_CODES = 16

        private const val TOKEN_BYTES = 32
        private const val CODE_BYTES = 16
    }
}

/**
 * The dashboard's server (M6 §8.2): `com.sun.net.httpserver` (module `jdk.httpserver`) on `127.0.0.1` only, two daemon
 * threads (`narrator2-web-1`, `-2`) that serve the requests through [Routes]. Started by [start]; [stop] closes the
 * socket at once. The server thread never waits for a request: a page runs on a dashboard thread, which reads the
 * Journal there (`runBlocking`) and the live state from snapshots.
 */
internal class DashboardServer private constructor(
    private val server: HttpServer,
    private val threads: ExecutorService,
    /** Its secrets; tests read the session token. */
    internal val token: DashboardToken,
) : Dashboard {
    private val stopped = AtomicBoolean()

    /** Where it listens: `127.0.0.1`, on the configured port or the one the system gave for port 0. */
    val address: InetSocketAddress = server.address

    val port: Int = address.port

    override val url: String = "http://127.0.0.1:$port/"

    override val unavailableReason: String? get() = null

    override fun newLoginUrl(): String = "${url}login?${Routes.KEY}=${token.newCode()}"

    override fun resetToken() = token.reset()

    override fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        server.stop(0)
        threads.shutdownNow()
        log.info("dashboard stopped")
    }

    companion object {
        private val log = Log.web

        private val THREADS = AtomicInteger()

        /**
         * Binds `127.0.0.1:<port>` (0: any free port) and serves [services]' dashboard behind a new token. Throws when it
         * cannot listen (`BindException` for a port taken), and `NoClassDefFoundError` when the Java runtime has no
         * `jdk.httpserver`: the caller turns both into a [DisabledDashboard].
         */
        fun start(services: NarratorServices, port: Int): DashboardServer {
            val server = HttpServer.create(InetSocketAddress(InetAddress.getByAddress(LOOPBACK), port), BACKLOG)
            val threads = Executors.newFixedThreadPool(2) { task -> Thread(task, "narrator2-web-${THREADS.incrementAndGet()}").apply { isDaemon = true } }
            try {
                val token = DashboardToken(services.clock)
                val routes = Routes(services, token, server.address.port)
                server.executor = threads
                server.createContext("/") { exchange -> serve(exchange, routes) }
                startAsDaemon(server)
                return DashboardServer(server, threads, token)
            } catch (e: Throwable) {
                server.stop(0)
                threads.shutdownNow()
                throw e
            }
        }

        /**
         * Starts [server] from a daemon thread, whose daemon flag its dispatcher thread inherits: it never keeps the JVM
         * alive, even if a stop were missed. Takes a millisecond.
         */
        private fun startAsDaemon(server: HttpServer) {
            var failure: Throwable? = null
            thread(name = "narrator2-web-start", isDaemon = true) {
                try {
                    server.start()
                } catch (e: Throwable) {
                    failure = e
                }
            }.join()
            failure?.let { throw it }
        }

        /** One exchange: the call read from it, [routes]' answer written back (without a body for `HEAD`), then closed. */
        private fun serve(exchange: HttpExchange, routes: Routes) {
            try {
                val headers = exchange.requestHeaders
                val call = WebCall(
                    method = exchange.requestMethod,
                    rawPath = exchange.requestURI.rawPath ?: "/",
                    rawQuery = exchange.requestURI.rawQuery,
                    host = headers.getFirst("Host"),
                    cookies = headers["Cookie"].orEmpty(),
                )
                val response = routes.respond(call)
                response.headers.forEach { (name, value) -> exchange.responseHeaders.set(name, value) }
                response.contentType?.let { exchange.responseHeaders.set("Content-Type", it) }
                val head = call.method == "HEAD"
                val length = if (head || response.body.isEmpty()) -1L else response.body.size.toLong()
                exchange.sendResponseHeaders(response.status, length)
                if (length > 0) exchange.responseBody.write(response.body)
            } catch (e: Exception) {
                log.debug { "an exchange of the dashboard failed: ${e.javaClass.simpleName}: ${e.message}" }
            } finally {
                exchange.close()
            }
        }

        private const val BACKLOG = 16

        /** `127.0.0.1`, whatever the system prefers: never another interface, never IPv6. */
        private val LOOPBACK = byteArrayOf(127, 0, 0, 1)
    }
}
