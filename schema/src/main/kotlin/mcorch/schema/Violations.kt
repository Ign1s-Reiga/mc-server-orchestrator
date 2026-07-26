package mcorch.schema

/**
 * Where in the source document a violation was found. Line and column are
 * 1-based so they line up with what an editor shows.
 */
public data class SourceLocation(
    val source: String,
    val line: Int,
    val column: Int,
) {
    override fun toString(): String = "$source:$line:$column"
}

/**
 * A single problem with a definition, always attributed to the field that
 * caused it.
 *
 * [field] is a dotted path into the document (`spec.resources.heap.max`), never
 * a class or property name — the operator reads YAML, not Kotlin.
 */
public data class SchemaViolation(
    val field: String,
    val problem: String,
    val location: SourceLocation? = null,
) {
    public fun render(): String =
        buildString {
            append(field)
            append(": ")
            append(problem)
            if (location != null) {
                append(" (at ")
                append(location)
                append(")")
            }
        }
}

/**
 * Thrown only by [getOrThrow]. Parsing itself never throws: it collects.
 */
public class SchemaValidationException(
    public val violations: List<SchemaViolation>,
) : IllegalArgumentException(renderViolations(violations))

private fun renderViolations(violations: List<SchemaViolation>): String =
    when (violations.size) {
        0 -> {
            "invalid definition"
        }

        1 -> {
            "invalid definition: ${violations[0].render()}"
        }

        else -> {
            violations.joinToString(
                separator = "\n  - ",
                prefix = "invalid definition (${violations.size} problems):\n  - ",
                transform = SchemaViolation::render,
            )
        }
    }

/**
 * The outcome of parsing. [Invalid] carries *every* problem found, not just the
 * first one, so an operator can fix a file in one pass.
 */
public sealed interface ParseResult<out T> {
    public data class Valid<out T>(
        val value: T,
    ) : ParseResult<T>

    public data class Invalid(
        val violations: List<SchemaViolation>,
    ) : ParseResult<Nothing>
}

public fun <T> ParseResult<T>.getOrThrow(): T =
    when (this) {
        is ParseResult.Valid -> value
        is ParseResult.Invalid -> throw SchemaValidationException(violations)
    }

public fun <T> ParseResult<T>.getOrNull(): T? =
    when (this) {
        is ParseResult.Valid -> value
        is ParseResult.Invalid -> null
    }

public fun <T> ParseResult<T>.violations(): List<SchemaViolation> =
    when (this) {
        is ParseResult.Valid -> emptyList()
        is ParseResult.Invalid -> violations
    }
