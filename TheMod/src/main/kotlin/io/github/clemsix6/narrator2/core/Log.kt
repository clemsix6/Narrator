package io.github.clemsix6.narrator2.core

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * A subsystem logger. Minecraft's log pattern hides logger names, so every line carries its
 * subsystem tag, e.g. `[Director] woken up: chat from Steve`. Levels are set per subsystem from
 * the configuration (`logging.levels`).
 */
class Log private constructor(val tag: String) {
    private val logger: Logger = LoggerFactory.getLogger("narrator2.$tag")

    @Volatile
    var level: Level = Level.INFO

    enum class Level { DEBUG, INFO, WARN, ERROR, OFF }

    fun debug(message: () -> String) {
        if (level <= Level.DEBUG) logger.info("[$tag] ${message()}")
    }

    fun info(message: String) {
        if (level <= Level.INFO) logger.info("[$tag] $message")
    }

    fun warn(message: String, error: Throwable? = null) {
        if (level <= Level.WARN) logger.warn("[$tag] $message", error)
    }

    fun error(message: String, error: Throwable? = null) {
        if (level <= Level.ERROR) logger.error("[$tag] $message", error)
    }

    companion object {
        private val loggers = ConcurrentHashMap<String, Log>()

        fun of(tag: String): Log = loggers.computeIfAbsent(tag) { Log(it) }

        // Every subsystem is declared here, so that a tag in `logging.levels` can be checked (and set)
        // before the subsystem's first line.
        val main = of("Narrator")
        val llm = of("LLM")
        val config = of("Config")
        val command = of("Command")
        val trace = of("Trace")
        val bus = of("Bus")
        val observer = of("Observer")
        val journal = of("Journal")
        val sql = of("Sql")
        val director = of("Director")
        val tool = of("Tool")
        val guard = of("Guard")
        val reflex = of("Reflex")
        val worker = of("Worker")
        val memory = of("Memory")
        val mechanics = of("Mechanics")
        val forge = of("Forge")

        /** The watch of the Narrator's style: the line review, the tics, the notes of tone, the refused openers (M6 §14). */
        val style = of("Style")

        /** The web dashboard (M6 §8). */
        val web = of("Web")

        /** The storyteller and `inspire` (M6 §5, §6). */
        val story = of("Story")

        /** The permanent difficulty layer (M7-BASE). */
        val difficulty = of("Difficulty")

        /** The armorer's work: champions, relics, the chests' reserves (M7-RELICS). */
        val relics = of("Relics")

        /** Applies `logging.levels` (subsystem tag → level name). Unknown tags and levels are reported and skipped. */
        fun applyLevels(levels: Map<String, String>) {
            loggers.values.forEach { it.level = Level.INFO }
            levels.forEach { (tag, name) ->
                val log = loggers[tag]
                val level = Level.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                when {
                    log == null -> config.warn(
                        "logging.levels: unknown subsystem '$tag' is ignored (known: ${loggers.keys.sorted().joinToString()})",
                    )
                    level == null -> config.warn(
                        "logging.levels.$tag: unknown level '$name' is ignored (use ${Level.entries.joinToString()})",
                    )
                    else -> log.level = level
                }
            }
        }
    }
}
