package mcorch.cri.logging

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

/**
 * Everything this module logs during a test run, kept in memory.
 *
 * Registered as the SLF4J provider for `:cri`'s tests (see
 * `META-INF/services/org.slf4j.spi.SLF4JServiceProvider`), mirroring what
 * `:store` does for the same reason. The point is [mcorch.cri.FailureLoggingTest]:
 * "the runtime's error text does not reach the log for a request holding a
 * secret" is worth nothing unless something reads back what was actually
 * logged — pattern, formatted arguments and stack trace included.
 *
 * Every level is enabled, deliberately. A redaction that holds at WARN and
 * leaks the same string at DEBUG is not a redaction, and this is the only thing
 * that would notice.
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

    /** Everything logged so far as one blob, for substring searching. */
    @Synchronized
    fun text(): String = lines.joinToString("\n")
}

/** Formats a call the way a real appender would, so nothing hides in an unrendered argument. */
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
        CapturedLogs.record("${level?.name.orEmpty()} $loggerName - $message${throwable?.let { " ex=$it" }.orEmpty()}")
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
