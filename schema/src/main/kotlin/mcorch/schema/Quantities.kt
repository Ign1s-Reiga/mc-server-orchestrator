package mcorch.schema

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * An amount of memory in bytes, written with binary (`Ki`/`Mi`/`Gi`/`Ti`) or
 * decimal (`K`/`M`/`G`/`T`) suffixes, or as a plain byte count.
 *
 * Stored as bytes so comparisons — in particular "does the JVM heap fit inside
 * the container limit" — are exact and unit-free.
 */
@JvmInline
public value class MemoryQuantity private constructor(
    public val bytes: Long,
) : Comparable<MemoryQuantity> {
    public operator fun plus(other: MemoryQuantity): MemoryQuantity = MemoryQuantity(bytes + other.bytes)

    public operator fun minus(other: MemoryQuantity): MemoryQuantity = MemoryQuantity(maxOf(0L, bytes - other.bytes))

    override fun compareTo(other: MemoryQuantity): Int = bytes.compareTo(other.bytes)

    /** Renders with the largest binary unit that divides evenly, so `parse(render()) == this`. */
    public fun render(): String {
        val units = listOf(TIB to "Ti", GIB to "Gi", MIB to "Mi", KIB to "Ki")
        for ((factor, suffix) in units) {
            if (bytes >= factor && bytes % factor == 0L) return "${bytes / factor}$suffix"
        }
        return bytes.toString()
    }

    override fun toString(): String = render()

    public companion object {
        public const val KIB: Long = 1024L
        public const val MIB: Long = 1024L * 1024L
        public const val GIB: Long = 1024L * 1024L * 1024L
        public const val TIB: Long = 1024L * 1024L * 1024L * 1024L

        private val PATTERN = Regex("^([0-9]+(?:\\.[0-9]+)?)\\s*(Ki|Mi|Gi|Ti|K|M|G|T)?$")

        private val FACTORS =
            mapOf(
                "Ki" to KIB,
                "Mi" to MIB,
                "Gi" to GIB,
                "Ti" to TIB,
                "K" to 1_000L,
                "M" to 1_000_000L,
                "G" to 1_000_000_000L,
                "T" to 1_000_000_000_000L,
            )

        public fun ofBytes(bytes: Long): Result<MemoryQuantity> =
            if (bytes < 0) invalidValue("must not be negative, found $bytes") else Result.success(MemoryQuantity(bytes))

        public fun parse(raw: String): Result<MemoryQuantity> {
            val text = raw.trim()
            val match =
                PATTERN.matchEntire(text)
                    ?: return invalidValue(
                        "expected a memory quantity such as `4Gi`, `512Mi` or `2G`, found `$raw`",
                    )
            val factor = FACTORS[match.groupValues[2]] ?: 1L
            val bytes =
                BigDecimal(match.groupValues[1])
                    .multiply(BigDecimal.valueOf(factor))
                    .setScale(0, RoundingMode.DOWN)
            if (bytes.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
                return invalidValue("is too large to represent, found `$raw`")
            }
            return ofBytes(bytes.toLong())
        }
    }
}

/**
 * A CPU allowance in millicores. `1` and `1000m` are the same thing; `500m` is
 * half a core.
 */
@JvmInline
public value class CpuQuantity private constructor(
    public val millicores: Int,
) : Comparable<CpuQuantity> {
    override fun compareTo(other: CpuQuantity): Int = millicores.compareTo(other.millicores)

    public fun render(): String = if (millicores % 1000 == 0) "${millicores / 1000}" else "${millicores}m"

    override fun toString(): String = render()

    public companion object {
        private val PATTERN = Regex("^([0-9]+(?:\\.[0-9]+)?)(m)?$")

        public fun ofMillicores(millicores: Int): Result<CpuQuantity> =
            if (millicores <= 0) {
                invalidValue("must be greater than zero, found ${millicores}m")
            } else {
                Result.success(CpuQuantity(millicores))
            }

        public fun parse(raw: String): Result<CpuQuantity> {
            val text = raw.trim()
            val match =
                PATTERN.matchEntire(text)
                    ?: return invalidValue("expected a CPU quantity such as `2`, `1.5` or `500m`, found `$raw`")
            val scale = if (match.groupValues[2] == "m") BigDecimal.ONE else BigDecimal.valueOf(1000L)
            val millicores =
                BigDecimal(match.groupValues[1])
                    .multiply(scale)
                    .setScale(0, RoundingMode.DOWN)
            if (millicores.compareTo(BigDecimal.valueOf(Int.MAX_VALUE.toLong())) > 0) {
                return invalidValue("is too large to represent, found `$raw`")
            }
            return ofMillicores(millicores.toInt())
        }
    }
}

/**
 * Durations in definitions are written the way an operator says them out loud:
 * `30s`, `5m`, `1m30s`, `250ms`. ISO-8601 (`PT5M`) is deliberately not accepted —
 * one spelling means one thing to review.
 */
public object DurationFormat {
    private val TERM = Regex("([0-9]+)(ms|s|m|h)")
    private val WHOLE = Regex("^(?:[0-9]+(?:ms|s|m|h))+$")

    public fun parse(raw: String): Result<Duration> {
        val text = raw.trim()
        if (!WHOLE.matches(text)) {
            return invalidValue("expected a duration such as `30s`, `5m` or `1m30s`, found `$raw`")
        }
        var total = Duration.ZERO
        for (term in TERM.findAll(text)) {
            val amount = term.groupValues[1].toLongOrNull() ?: return invalidValue("is out of range, found `$raw`")
            total +=
                when (term.groupValues[2]) {
                    "ms" -> amount.milliseconds
                    "s" -> amount.seconds
                    "m" -> amount.minutes
                    else -> amount.hours
                }
        }
        return Result.success(total)
    }

    public fun render(duration: Duration): String {
        val millis = duration.inWholeMilliseconds
        if (millis == 0L) return "0s"
        if (millis % 1000L != 0L) return "${millis}ms"
        var seconds = millis / 1000L
        return buildString {
            val hours = seconds / 3600L
            if (hours > 0) {
                append(hours).append("h")
                seconds -= hours * 3600L
            }
            val minutes = seconds / 60L
            if (minutes > 0) {
                append(minutes).append("m")
                seconds -= minutes * 60L
            }
            if (seconds > 0) append(seconds).append("s")
        }
    }
}
