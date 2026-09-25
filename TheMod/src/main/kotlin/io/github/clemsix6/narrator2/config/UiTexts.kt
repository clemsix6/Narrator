package io.github.clemsix6.narrator2.config

import io.github.clemsix6.narrator2.core.Log
import net.minecraft.network.chat.TextColor
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * The texts players read that do not come from Claude: the Narrator's name and chat prefix, the
 * "thinking" indicator, replies to `/pray`… The code only uses keys; the texts ship in the jar as
 * `narrator2/ui/<language>.properties` (UTF-8), chosen by the `language` setting.
 *
 * A file with the same name in the override folder (`config/narrator2/ui/<language>.properties`)
 * replaces the bundled texts key by key. Administration messages (`/narrator …`) are not here: they
 * are English, in the code.
 *
 * Placeholders are written `{name}` and filled by [format].
 */
class UiTexts private constructor(val language: String, private val texts: Map<String, String>) {

    /** The text of [key], placeholders left as they are. Fails for a key missing from [KEYS]. */
    operator fun get(key: String): String = texts[key] ?: error("unknown UI text: $key")

    /** The text of [key] with its placeholders filled, e.g. `format("pray.cooldown", "seconds" to 7)`. */
    fun format(key: String, vararg values: Pair<String, Any>): String {
        val expected = KEYS[key] ?: error("unknown UI text: $key")
        val given = values.map { it.first }.toSet()
        require(given == expected) { "UI text $key takes ${expected.sorted()}, got ${given.sorted()}" }
        return values.fold(get(key)) { text, (name, value) -> text.replace("{$name}", value.toString()) }
    }

    companion object {
        /** Every key, with the placeholders its text must contain. */
        val KEYS: Map<String, Set<String>> = mapOf(
            // How the Narrator appears: its name, and the prefixes of its lines in the public chat and in private.
            "narrator.name" to emptySet(),
            "narrator.prefix" to emptySet(),
            "narrator.whisper_prefix" to emptySet(),
            // Color of the prefixes: a color name such as gold, or #rrggbb (TextColor.parseColor).
            "narrator.color" to emptySet(),
            // Action bar text shown while the Narrator prepares an answer.
            "narrator.thinking" to emptySet(),
            // Replies to /pray.
            "pray.sent" to emptySet(),
            "pray.cooldown" to setOf("seconds"),
            // Sent when confiscated items come back.
            "items.returned" to setOf("items"),
            // Sent once when confiscated items are due but the inventory is full: they wait in the ledger.
            "items.no_room" to setOf("items"),
            // Author of the books the Narrator gives.
            "book.author" to emptySet(),
        )

        /** Keys whose text is a color. */
        private val COLORS = setOf("narrator.color")

        private const val RESOURCES = "/narrator2/ui/"

        private val log = Log.config

        /** True when the jar ships texts for [language]. */
        fun isBundled(language: String): Boolean = UiTexts::class.java.getResource("$RESOURCES$language.properties") != null

        /**
         * The texts for [language]: the bundled file, overridden key by key by
         * `<overrideDir>/<language>.properties` when it exists. Fails when a key is missing or a text
         * lacks one of its placeholders; unknown keys of an override are logged and ignored.
         */
        fun load(language: String, overrideDir: Path? = null): UiTexts {
            val stream = UiTexts::class.java.getResourceAsStream("$RESOURCES$language.properties")
                ?: throw IllegalArgumentException("no UI texts for language '$language' in the jar")
            val texts = read(stream).toMutableMap()
            val override = overrideDir?.resolve("$language.properties")
            if (override != null && Files.isRegularFile(override)) {
                read(Files.newInputStream(override)).forEach { (key, text) ->
                    if (key in KEYS) texts[key] = text else log.warn("$override: unknown UI text '$key' is ignored")
                }
                log.info("UI texts $language: overridden by $override")
            }
            val problems = KEYS.flatMap { (key, placeholders) ->
                val text = texts[key]
                when {
                    text == null -> listOf("$key is missing")
                    key in COLORS && TextColor.parseColor(text).result().isEmpty -> listOf("$key must be a color name or #rrggbb")
                    else -> placeholders.filter { "{$it}" !in text }.map { "$key must contain {$it}" }
                }
            }
            require(problems.isEmpty()) { "invalid UI texts for '$language': ${problems.joinToString("; ")}" }
            return UiTexts(language, texts.filterKeys { it in KEYS })
        }

        private fun read(stream: InputStream): Map<String, String> = stream.use { input ->
            val properties = Properties()
            input.reader(Charsets.UTF_8).use { properties.load(it) }
            properties.stringPropertyNames().associateWith { properties.getProperty(it) }
        }
    }
}
