package io.github.clemsix6.narrator2.core

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * A subsystem logger. Minecraft's log pattern hides logger names, so every line carries its
 * subsystem tag, e.g. `[Directeur] réveil : chat de Thawyn`. Levels are set per subsystem from
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

        /** Applies `logging.levels` (subsystem tag → level name); unknown names are ignored. */
        fun applyLevels(levels: Map<String, String>) {
            loggers.values.forEach { it.level = Level.INFO }
            levels.forEach { (tag, name) ->
                val level = Level.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return@forEach
                of(tag).level = level
            }
        }

        val main = of("Narrateur")
        val llm = of("LLM")
        val config = of("Config")
    }
}
