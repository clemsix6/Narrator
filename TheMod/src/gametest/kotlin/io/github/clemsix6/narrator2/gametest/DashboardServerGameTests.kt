package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.config.DashboardConfig
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.runtime.Settings
import io.github.clemsix6.narrator2.runtime.SettingsHolder
import io.github.clemsix6.narrator2.web.Dashboard
import io.github.clemsix6.narrator2.web.createDashboard
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * The dashboard's server in the test server (M6 §8.2, §15.2): `com.sun.net.httpserver` loads under Knot, and a dashboard
 * on port 0 answers. The runtime's own dashboard is off in the test servers (off by default, and `build.gradle.kts`
 * seeds `dashboard.enabled = false` too, so that the servers of several worktrees never fight over port 8770): these
 * tests start their own, through the runtime's factory.
 */
class DashboardServerGameTests {

    /** A browser without the token gets nothing; signed in, it gets the overview; once stopped, the port is closed. */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aDashboardOnPortZeroServesTheOverviewOnceSignedIn(helper: GameTestHelper) {
        val runtime = helper.narrator()
        val current = runtime.settings.get()
        val services = object : NarratorServices by runtime {
            override val settings = SettingsHolder(
                Settings(current.config.copy(dashboard = DashboardConfig(enabled = true, port = 0)), current.prompts, current.ui, claude = null, reflex = null),
            )
        }
        val dashboard = createDashboard(services)
        helper.assertTrue(dashboard.url != null, "no dashboard: ${dashboard.unavailableReason}")
        helper.closingOnFailure(AutoCloseable { dashboard.stop() }, ASYNC_MAX_TICKS) {
            // Off the server thread: the dashboard never needs it, and a test must not block it on the network.
            val visit = CompletableFuture.supplyAsync { browse(dashboard) }
            helper.awaitAsync {
                helper.assertTrue(visit.isDone, "the visit is still running")
                val steps = visit.join()
                helper.assertEquals(listOf("403 no data", "303 cookie", "200 overview", "closed"), steps, "the visit")
            }
        }
    }

    /** The test servers start without a dashboard of their own, and `/narrator dashboard` says why and how to have one. */
    @GameTest
    fun theTestServersRunWithoutADashboard(helper: GameTestHelper) {
        val runtime = helper.narrator()
        helper.assertEquals(null, runtime.dashboard.url, "the runtime's dashboard")
        helper.assertEquals("dashboard.enabled is false", runtime.dashboard.unavailableReason, "why")
        val output = CommandOutput()
        helper.level.server.commands.performPrefixedCommand(helper.level.server.createCommandSourceStack().withSource(output), "narrator dashboard")
        helper.assertTrue(output.lines.any { "no dashboard: dashboard.enabled is false." in it }, "reply: ${output.lines}")
        helper.assertTrue(output.lines.any { "\"enabled\": true" in it && "/narrator restart" in it }, "how to turn it on: ${output.lines}")
        helper.succeed()
    }

    /** What a browser gets: without the token, at the login, signed in, after the stop. */
    private fun browse(dashboard: Dashboard): List<String> {
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build()
        fun get(url: String, cookie: String? = null): HttpResponse<String> {
            val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET()
            cookie?.let { request.header("Cookie", it) }
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
        }
        val url = dashboard.url!!
        val steps = mutableListOf<String>()
        val anonymous = get(url)
        val csp = anonymous.headers().firstValue("Content-Security-Policy").orElse("")
        if (anonymous.statusCode() == 403 && "Overview" !in anonymous.body() && csp.startsWith("default-src 'none'")) steps += "403 no data"
        val login = get(dashboard.newLoginUrl()!!)
        val cookie = login.headers().firstValue("Set-Cookie").orElse("").substringBefore(';')
        if (login.statusCode() == 303 && cookie.startsWith("narrator2=")) steps += "303 cookie"
        val overview = get(url, cookie)
        if (overview.statusCode() == 200 && "<h1>Overview</h1>" in overview.body()) steps += "200 overview" else steps += "overview ${overview.statusCode()}"
        dashboard.stop()
        steps += if (runCatching { get(url, cookie) }.isFailure) "closed" else "still open"
        return steps
    }
}
