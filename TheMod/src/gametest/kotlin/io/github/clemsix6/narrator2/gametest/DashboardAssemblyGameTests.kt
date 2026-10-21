package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.web.DisabledDashboard
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration

/**
 * The runtime's own dashboard, assembled (M6 §8, §15.2, task M6-17). Off by default (M6-FIX-2), it is turned on here as an
 * operator does it: `"dashboard": { "enabled": true }` in the configuration (on port 0: any free port), then
 * `/narrator restart`. The new Narrator's dashboard answers on `127.0.0.1` and refuses a browser without a session; the
 * link of `/narrator dashboard`, from the console, opens one session, then is worth nothing; every page of the menu
 * serves; `/narrator status` says where it listens. It stops with its Narrator: the restart that turns it off again runs
 * the first half of the server's stop (`ShutdownSequence.stopInGame`, the dashboard first), and its port is closed.
 *
 * It replaces the server's Narrator twice, so it runs alone, in a batch of its own ([ENVIRONMENT]), on an empty server;
 * the batches after it run on the last Narrator it made, without a dashboard. It puts the configuration file back as
 * it found it.
 */
class DashboardAssemblyGameTests {

    @GameTest(environment = ENVIRONMENT, maxTicks = MAX_TICKS)
    fun theRuntimesDashboardServesOnceSignedInAndStopsWithItsNarrator(helper: GameTestHelper) = helper.runScript(MAX_TICKS) {
        val first = helper.narrator()
        check(first.dashboard.url == null && first.dashboard.unavailableReason == DisabledDashboard.SWITCHED_OFF) {
            "off by default: ${first.dashboard.url} ${first.dashboard.unavailableReason}"
        }
        val config = Files.readString(NarratorRuntime.configFile)
        try {
            // On, as an operator turns it on: the configuration, then /narrator restart.
            Files.writeString(NarratorRuntime.configFile, """{ "dashboard": { "enabled": true, "port": 0 } }""")
            restart(helper)
            val runtime = NarratorRuntime.current ?: error("no Narrator after the restart")
            check(runtime !== first) { "a new Narrator" }
            val url = runtime.dashboard.url ?: error("no dashboard: ${runtime.dashboard.unavailableReason}")
            check(Regex("http://127\\.0\\.0\\.1:\\d+/").matches(url)) { "on 127.0.0.1 only: $url" }

            // Without a session: refused, nothing shown.
            val anonymous = get(url)
            check(anonymous.statusCode() == 403 && "<title>Not signed in" in anonymous.body() && "<h1>Overview</h1>" !in anonymous.body()) {
                "without a session: ${anonymous.statusCode()} ${anonymous.body().take(300)}"
            }

            // The link of /narrator dashboard, from the console: one session, then nothing.
            val reply = operatorCommands(helper, "narrator dashboard").lines.toList()
            check(reply.first() == "[Narrator] dashboard on $url (read only, on this machine only)") { "the reply: $reply" }
            val link = reply.firstNotNullOfOrNull { it.substringAfter("Sign in: ", "").takeIf(String::isNotEmpty) } ?: error("no link: $reply")
            check(link.startsWith("${url}login?key=")) { "the link: $link" }
            val login = get(link)
            val cookie = login.headers().firstValue("Set-Cookie").orElse("").substringBefore(';')
            check(login.statusCode() == 303 && cookie.startsWith("narrator2=")) { "the sign-in: ${login.statusCode()} ${login.headers().map()}" }
            check(!link.contains(cookie.substringAfter('='))) { "the link never carries the session's token" }
            val again = get(link)
            check(again.statusCode() == 403 && again.headers().firstValue("Set-Cookie").isEmpty) { "a link serves once: ${again.statusCode()}" }

            // Every page of the menu serves, signed in.
            for (page in PAGES) {
                val response = get(url + page.removePrefix("/"), cookie)
                check(response.statusCode() == 200 && "<h1>" in response.body() && "<script" !in response.body()) {
                    "$page: ${response.statusCode()} ${response.body().take(300)}"
                }
            }
            val status = operatorCommands(helper, "narrator status").lines.toList()
            check("Dashboard: on $url" in status) { "status: $status" }

            // Off again: the restart stops it with its Narrator, and its port closes.
            Files.writeString(NarratorRuntime.configFile, GAMETEST_CONFIG)
            restart(helper)
            check(runCatching { get(url, cookie) }.isFailure) { "the port of the stopped dashboard is closed" }
            val last = NarratorRuntime.current ?: error("no Narrator after the second restart")
            check(last.dashboard.url == null && last.dashboard.unavailableReason == DisabledDashboard.SWITCHED_OFF) { "off again: ${last.dashboard.url}" }
        } finally {
            // As found: no dashboard listens for the batches after this one.
            if (NarratorRuntime.current?.dashboard?.url != null) {
                Files.writeString(NarratorRuntime.configFile, GAMETEST_CONFIG)
                runCatching { restart(helper) }
            }
            Files.writeString(NarratorRuntime.configFile, config)
        }
    }

    /** `/narrator restart` from the console, once it answered that the Narrator restarted. */
    private suspend fun restart(helper: GameTestHelper) {
        val output = operatorCommands(helper, "narrator restart")
        awaitCondition("the restart", 60_000) { output.lines.any { it.startsWith(RESTARTED) || "restart failed" in it || it.startsWith("[Narrator] cannot restart") } }
        val done = output.lines.first { it.startsWith(RESTARTED) || "restart failed" in it || it.startsWith("[Narrator] cannot restart") }
        check(done.startsWith(RESTARTED) && done.endsWith(": Journal available · memory on · mechanics on · forge on")) { "the restart: ${output.lines}" }
    }

    /** A GET of [url], with the session [cookie] if any, without following a redirect. Off the server thread. */
    private fun get(url: String, cookie: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET()
        cookie?.let { request.header("Cookie", it) }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private companion object {
        /** The batch of this test: it replaces the server's Narrator twice. */
        const val ENVIRONMENT = "narrator2-gametest:dashboard_assembly"

        /** At least two minutes (see [runScript]): two restarts and thirteen pages. */
        const val MAX_TICKS = 120_000

        /** The configuration of the in-game runs (`seedGameTestConfig` in `build.gradle.kts`): no dashboard. */
        const val GAMETEST_CONFIG = """{ "dashboard": { "enabled": false } }"""

        const val RESTARTED = "[Narrator] Narrator restarted in "

        /** The pages of the menu (M6 §8.4). */
        val PAGES = listOf("/", "/turns", "/conversations", "/tasks", "/decisions", "/costs", "/memory", "/players", "/mechanics", "/forge", "/style", "/sql")

        val CLIENT: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build()
    }
}
