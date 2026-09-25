package io.github.clemsix6.narrator2.config

import io.github.clemsix6.narrator2.testing.LogCapture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** The sections of M1 and M2 (§15 of the design): defaults, merging with a file, validation. */
class ConfigValidationTest {
    @TempDir
    lateinit var dir: Path

    private val defaults = NarratorConfig()

    private fun problems(config: NarratorConfig) = ConfigStore.problems(config)

    private fun assertRefused(config: NarratorConfig, message: String) {
        val problems = problems(config)
        assertEquals(1, problems.size, "exactly one problem expected for '$message', got $problems")
        assertContains(problems.single(), message)
    }

    @Test
    fun `the defaults are valid and match the design`() {
        assertEquals(emptyList(), problems(defaults))
        assertEquals("fr", defaults.language)
        assertEquals(3, defaults.chaos)
        assertEquals(JournalConfig(1_000, 65_536, 512, 2, "", 5, 4_096), defaults.journal)
        assertEquals(listOf("login", "l", "register", "reg", "changepassword", "narrator"), defaults.observer.redactedCommands)
        assertEquals(
            listOf("msg", "tell", "w", "teammsg", "tm", "r", "reply", "whisper", "pm", "dm", "mail"),
            defaults.observer.privateMessageCommands,
        )
        with(defaults.director) {
            assertEquals(3..8, heartbeatRange)
            assertEquals(OperatorChannelMode.SYSTEM_MESSAGE, channelMode)
            assertEquals(listOf(1_500L, 150_000L, 180_000L), listOf(chatDebounceMs, chapterTokenLimit, chapterHardLimit))
            assertEquals(listOf(12, 10), listOf(maxStepsPerTurn, emptyServerCloseMinutes))
        }
        assertEquals(WorkersConfig(3, 15, 180), defaults.workers)
        assertEquals(JevConfig(true, "https://api.typesafe.ai", "TYPESAFE_API_KEY", "jev-1.13.0", 1_500), defaults.jev)
    }

    @Test
    fun `every forbidden value is refused with a clear message`() {
        val cases: List<Pair<NarratorConfig, String>> = listOf(
            defaults.copy(language = "de") to "language must be a language the mod ships texts for",
            defaults.copy(language = "../fr") to "language must be a language the mod ships texts for",
            defaults.copy(chaos = -1) to "chaos must be between 0 and 5",
            defaults.copy(chaos = 6) to "chaos must be between 0 and 5",
            defaults.copy(journal = JournalConfig(flushIntervalMs = 199)) to "journal.flushIntervalMs must be between 200 and 10000",
            defaults.copy(journal = JournalConfig(flushIntervalMs = 10_001)) to "journal.flushIntervalMs must be between 200 and 10000",
            defaults.copy(journal = JournalConfig(queueCapacity = 1_000)) to "journal.queueCapacity must be between 1024 and 1048576",
            defaults.copy(journal = JournalConfig(memoryLimitMb = 9_000)) to "journal.memoryLimitMb must be between 64 and 8192",
            defaults.copy(journal = JournalConfig(threads = 0)) to "journal.threads must be between 1 and 8",
            defaults.copy(journal = JournalConfig(timeZone = "Mars/Olympus")) to "journal.timeZone must be empty",
            defaults.copy(journal = JournalConfig(sqlTimeoutSeconds = 61)) to "journal.sqlTimeoutSeconds must be between 1 and 60",
            defaults.copy(journal = JournalConfig(warnSizeMb = 0)) to "journal.warnSizeMb must be positive",
            defaults.copy(observer = ObserverConfig(listOf("login", "/register"))) to
                "observer.redactedCommands[1] must be a command root in lowercase",
            defaults.copy(observer = ObserverConfig(listOf("Login"))) to "observer.redactedCommands[0] must be a command root",
            defaults.copy(observer = ObserverConfig(privateMessageCommands = listOf("msg", "R"))) to
                "observer.privateMessageCommands[1] must be a command root in lowercase",
            defaults.copy(director = DirectorConfig(heartbeatMinutes = listOf(8, 3))) to "director.heartbeatMinutes must be [min, max]",
            defaults.copy(director = DirectorConfig(heartbeatMinutes = listOf(0, 3))) to "director.heartbeatMinutes must be [min, max]",
            defaults.copy(director = DirectorConfig(heartbeatMinutes = listOf(3, 61))) to "director.heartbeatMinutes must be [min, max]",
            defaults.copy(director = DirectorConfig(heartbeatMinutes = listOf(5))) to "director.heartbeatMinutes must be [min, max]",
            defaults.copy(director = DirectorConfig(chatDebounceMs = 100)) to "director.chatDebounceMs must be between 200 and 10000",
            defaults.copy(director = DirectorConfig(chapterTokenLimit = 10_000)) to "chapterTokenLimit <= chapterHardLimit",
            defaults.copy(director = DirectorConfig(chapterTokenLimit = 200_000)) to "chapterTokenLimit <= chapterHardLimit",
            defaults.copy(director = DirectorConfig(chapterHardLimit = 1_000_000)) to "chapterTokenLimit <= chapterHardLimit",
            defaults.copy(director = DirectorConfig(maxStepsPerTurn = 2)) to "director.maxStepsPerTurn must be between 3 and 30",
            defaults.copy(director = DirectorConfig(operatorChannel = "tags")) to "director.operatorChannel must be one of system_message, user_tag",
            defaults.copy(director = DirectorConfig(emptyServerCloseMinutes = 0)) to "director.emptyServerCloseMinutes must be between 1 and 120",
            defaults.copy(workers = WorkersConfig(maxConcurrent = 9)) to "workers.maxConcurrent must be between 1 and 8",
            defaults.copy(workers = WorkersConfig(maxSteps = 41)) to "workers.maxSteps must be between 3 and 40",
            defaults.copy(workers = WorkersConfig(timeoutSeconds = 29)) to "workers.timeoutSeconds must be between 30 and 1800",
            defaults.copy(safety = SafetyConfig(protectedPlayers = listOf("Steve", "not a name"))) to
                "safety.protectedPlayers[1] must be a player name",
            defaults.copy(safety = SafetyConfig(extraForbiddenCommands = listOf("scoreboard", "tell raw"))) to
                "safety.extraForbiddenCommands[1] must be a command root",
            defaults.copy(safety = SafetyConfig(extraAllowedCommands = listOf("sethome", "/home"))) to
                "safety.extraAllowedCommands[1] must be a command root",
            defaults.copy(safety = SafetyConfig(extraAllowedCommands = listOf("sethome", "give"))) to
                "safety.extraAllowedCommands[1]: give is forbidden by the built-in list and cannot be allowed",
            defaults.copy(safety = SafetyConfig(extraForbiddenCommands = listOf("home"), extraAllowedCommands = listOf("home"))) to
                "safety.extraAllowedCommands[0]: home is also in safety.extraForbiddenCommands",
            defaults.copy(jev = JevConfig(baseUrl = "api.typesafe.ai")) to "jev.baseUrl must be an http:// or https:// URL with a host",
            defaults.copy(jev = JevConfig(apiKeyEnv = " ")) to "jev.apiKeyEnv must name an environment variable",
            defaults.copy(jev = JevConfig(model = "")) to "jev.model must not be empty",
            defaults.copy(jev = JevConfig(timeoutMs = 200)) to "jev.timeoutMs must be between 300 and 10000",
        )
        cases.forEach { (config, message) -> assertRefused(config, message) }
    }

    @Test
    fun `bounds are inclusive`() {
        val edges = listOf(
            defaults.copy(chaos = 0),
            defaults.copy(chaos = 5),
            defaults.copy(journal = JournalConfig(200, 1_024, 64, 1, "Europe/Paris", 1, 1)),
            defaults.copy(journal = JournalConfig(10_000, 1_048_576, 8_192, 8, "UTC", 60, 1)),
            defaults.copy(director = DirectorConfig(heartbeatMinutes = listOf(1, 1), chatDebounceMs = 200, maxStepsPerTurn = 3)),
            defaults.copy(director = DirectorConfig(chapterTokenLimit = 20_000, chapterHardLimit = 20_000, operatorChannel = "user_tag")),
            defaults.copy(director = DirectorConfig(chapterTokenLimit = 900_000, chapterHardLimit = 900_000, emptyServerCloseMinutes = 120)),
            defaults.copy(workers = WorkersConfig(1, 3, 30)),
            defaults.copy(workers = WorkersConfig(8, 40, 1_800)),
            defaults.copy(safety = SafetyConfig(listOf("Steve", "a_b_c_1234567890"), listOf("scoreboard", "minecraft:tell"), listOf("sethome", "farmersdelight:cook"))),
            defaults.copy(jev = JevConfig(baseUrl = "http://localhost:9000", timeoutMs = 300)),
            defaults.copy(jev = JevConfig(timeoutMs = 10_000)),
        )
        edges.forEach { assertEquals(emptyList(), problems(it), "$it") }
    }

    @Test
    fun `the Journal's time zone must be a named zone, never an offset DuckDB would refuse`() {
        listOf("Europe/Paris", "UTC", "America/New_York", "Etc/GMT-2").forEach { zone ->
            assertEquals(emptyList(), problems(defaults.copy(journal = JournalConfig(timeZone = zone))), zone)
        }
        listOf("Z", "+02:00", "-05:00", "UTC+2", "UTC+02:00", "GMT+01:00", "utc", "europe/paris", "Mars/Olympus").forEach { zone ->
            assertRefused(
                defaults.copy(journal = JournalConfig(timeZone = zone)),
                "journal.timeZone must be empty (the server's zone) or a named time zone such as Europe/Paris or UTC, not an offset",
            )
        }
    }

    @Test
    fun `an existing file without the new sections gets their defaults`() {
        dir.resolve(ConfigStore.FILE_NAME).writeText("""{ "gateway": { "baseUrl": "http://localhost:8088" } }""")
        val config = ConfigStore.load(dir)
        assertEquals(defaults.copy(gateway = GatewayConfig(baseUrl = "http://localhost:8088")), config)
    }

    @Test
    fun `sections merge key by key and lists are replaced whole`() {
        dir.resolve(ConfigStore.FILE_NAME).writeText(
            """
            {
              "chaos": 1,
              "journal": { "timeZone": "Europe/Paris" },
              "director": { "heartbeatMinutes": [5, 12], "operatorChannel": "user_tag" },
              "safety": { "protectedPlayers": ["Mimosa"] },
              "jev": { "enabled": false, "model": null }
            }
            """.trimIndent(),
        )
        val config = ConfigStore.load(dir)

        assertEquals(1, config.chaos)
        assertEquals(JournalConfig(timeZone = "Europe/Paris"), config.journal)
        assertEquals(DirectorConfig(heartbeatMinutes = listOf(5, 12), operatorChannel = "user_tag"), config.director)
        assertEquals(OperatorChannelMode.USER_TAG, config.director.channelMode)
        assertEquals(listOf("Mimosa"), config.safety.protectedPlayers)
        assertEquals(JevConfig(enabled = false), config.jev, "a null value takes its default")
    }

    @Test
    fun `an invalid file lists every problem and quotes no value`() {
        dir.resolve(ConfigStore.FILE_NAME).writeText(
            """{ "chaos": 9, "director": { "operatorChannel": "secret-value-1" }, "workers": { "maxConcurrent": 0 } }""",
        )
        val message = assertFailsWith<ConfigException> { ConfigStore.load(dir) }.message.orEmpty()

        assertContains(message, "chaos must be between 0 and 5")
        assertContains(message, "director.operatorChannel must be one of system_message, user_tag")
        assertContains(message, "workers.maxConcurrent must be between 1 and 8")
        assertFalse("secret-value-1" in message)
    }

    @Test
    fun `a value of the wrong type names its setting`() {
        dir.resolve(ConfigStore.FILE_NAME).writeText("""{ "chaos": "high" }""")
        val message = assertFailsWith<ConfigException> { ConfigStore.load(dir) }.message.orEmpty()
        assertContains(message, "chaos")
        assertFalse("high" in message)
    }

    @Test
    fun `a Jev key in the file is ignored with a warning and never logged`() {
        dir.resolve(ConfigStore.FILE_NAME).writeText("""{ "jev": { "apiKey": "ts-leftover-7b7b" } }""")
        val logs = LogCapture()
        val config = logs.use { ConfigStore.load(dir) }

        assertEquals(JevConfig(), config.jev)
        assertContains(logs.text, "jev.apiKey is ignored, remove it from the file: the Jev key is read only from the environment variable named by jev.apiKeyEnv")
        assertFalse("ts-leftover-7b7b" in logs.text)
    }

    @Test
    fun `plain http to Jev on another machine is accepted with a warning`() {
        dir.resolve(ConfigStore.FILE_NAME).writeText("""{ "jev": { "baseUrl": "http://192.168.1.30:8080" } }""")
        val logs = LogCapture()
        logs.use { ConfigStore.load(dir) }
        assertContains(logs.text, "jev.baseUrl sends the Jev key unencrypted (http://) to 192.168.1.30")
    }
}
