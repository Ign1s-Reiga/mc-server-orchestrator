package mcorch.store.logging

import org.slf4j.ILoggerFactory
import org.slf4j.IMarkerFactory
import org.slf4j.Logger
import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.BasicMDCAdapter
import org.slf4j.helpers.BasicMarkerFactory
import org.slf4j.helpers.LegacyAbstractLogger
import org.slf4j.helpers.MessageFormatter
import org.slf4j.spi.MDCAdapter
import org.slf4j.spi.SLF4JServiceProvider
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Everything this module logs during a test run, kept in memory.
 *
 * Registered as the SLF4J provider for `:store`'s tests (see
 * `META-INF/services/org.slf4j.spi.SLF4JServiceProvider`). The point is
 * [mcorch.store.SecretLeakageTest]: "we do not log secrets" is only worth
 * anything if something actually reads back what was logged, formatted arguments
 * and stack traces included.
 */
object CapturedLogs {
    private val lines = mutableListOf<String>()

    @Synchronized
    fun record(line: String) {
        lines += line
    }

    @Synchronized
    fun clear() {
        lines.clear()
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    /** Everything logged so far as one blob, for substring searching. */
    @Synchronized
    fun text(): String = lines.joinToString("\n")
}

/** Formats a call the way a real appender would — pattern, arguments and stack trace all rendered. */
internal class CapturingLogger(
    private val loggerName: String,
) : LegacyAbstractLogger() {
    override fun getName(): String = loggerName

    override fun getFullyQualifiedCallerName(): String? = null

    override fun handleNormalizedLoggingCall(
        level: Level?,
        marker: Marker?,
        messagePattern: String?,
        arguments: Array<out Any>?,
        throwable: Throwable?,
    ) {
        val message = MessageFormatter.basicArrayFormat(messagePattern, arguments)
        val rendered =
            buildString {
                append(level?.name.orEmpty())
                append(' ')
                append(loggerName)
                append(" - ")
                append(message)
                if (throwable != null) {
                    append('\n')
                    val writer = StringWriter()
                    throwable.printStackTrace(PrintWriter(writer))
                    append(writer.toString())
                }
            }
        CapturedLogs.record(rendered)
    }

    override fun isTraceEnabled(): Boolean = true

    override fun isTraceEnabled(marker: Marker?): Boolean = true

    override fun isDebugEnabled(): Boolean = true

    override fun isDebugEnabled(marker: Marker?): Boolean = true

    override fun isInfoEnabled(): Boolean = true

    override fun isInfoEnabled(marker: Marker?): Boolean = true

    override fun isWarnEnabled(): Boolean = true

    override fun isWarnEnabled(marker: Marker?): Boolean = true

    override fun isErrorEnabled(): Boolean = true

    override fun isErrorEnabled(marker: Marker?): Boolean = true
}

internal class CapturingLoggerFactory : ILoggerFactory {
    private val loggers = mutableMapOf<String, Logger>()

    @Synchronized
    override fun getLogger(name: String): Logger = loggers.getOrPut(name) { CapturingLogger(name) }
}

/** Discovered through `META-INF/services`; test scope only. */
class CapturingServiceProvider : SLF4JServiceProvider {
    private val loggerFactory = CapturingLoggerFactory()
    private val markerFactory = BasicMarkerFactory()
    private val mdcAdapter = BasicMDCAdapter()

    override fun getLoggerFactory(): ILoggerFactory = loggerFactory

    override fun getMarkerFactory(): IMarkerFactory = markerFactory

    override fun getMDCAdapter(): MDCAdapter = mdcAdapter

    override fun getRequestedApiVersion(): String = "2.0.99"

    override fun initialize() = Unit
}
