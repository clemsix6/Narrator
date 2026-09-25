package io.github.clemsix6.narrator2.testing

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.Logger
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Records every line the mod logs (loggers `narrator2.*`) while open, stack traces included, through
 * the log4j backend that Minecraft ships. The appender sits on the root logger, next to the usual
 * ones, so the lines still reach the console and the log file.
 *
 * ```
 * LogCapture().use { logs -> runCodeUnderTest(); assertContains(logs.text, "[LLM]") }
 * ```
 */
class LogCapture : AutoCloseable {
    private val lines = CopyOnWriteArrayList<String>()
    private val closed = AtomicBoolean(false)
    private val context = (LogManager.getLogger("narrator2") as Logger).context
    private val appender = object : AbstractAppender("narrator2-capture-${IDS.incrementAndGet()}", null, null, true, Property.EMPTY_ARRAY) {
        override fun append(event: LogEvent) {
            if (!event.loggerName.startsWith("narrator2.")) return
            lines += event.message.formattedMessage + (event.thrown?.let { "\n" + it.stackTraceToString() } ?: "")
        }
    }

    init {
        appender.start()
        context.configuration.rootLogger.addAppender(appender, null, null)
        context.updateLoggers()
    }

    /** Every line so far, stack traces included. */
    val text: String get() = lines.joinToString("\n")

    val messages: List<String> get() = lines.toList()

    /** Stops recording; calling it again does nothing. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        context.configuration.rootLogger.removeAppender(appender.name)
        context.updateLoggers()
        appender.stop()
    }

    private companion object {
        val IDS = AtomicInteger()
    }
}
