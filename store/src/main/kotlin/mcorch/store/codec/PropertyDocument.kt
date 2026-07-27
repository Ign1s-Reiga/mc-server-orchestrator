package mcorch.store.codec

import mcorch.store.StoreException
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * The store's own on-disk encoding for a definition spec and a status: a sorted
 * list of `dotted.key=value` lines.
 *
 * ## Why a store-owned encoding rather than a column per field
 *
 * A column per schema field ties the on-disk layout to `:schema`'s Kotlin field
 * list, so every additive change to a status becomes a migration, and the layout
 * is unusable by any backend that is not a SQL database. A document is portable —
 * a distributed backend stores the same bytes — and lets migrations be about
 * *structure* (tables, projections, indices) rather than about every new
 * optional field.
 *
 * ## Why not YAML, given `:schema` already parses it
 *
 * Because a store row must stay readable even if validation gets stricter. If
 * rows were stored as YAML and read back through `ServerDefinitionParser`, then
 * tightening any rule in `:schema` would make already-accepted definitions
 * unloadable — the store would lose data because of an unrelated change. This
 * encoding reconstructs the objects directly and only has to satisfy their own
 * invariants.
 *
 * ## Properties this format has to have
 *
 * - **Lossless.** Every value is stored in its most primitive exact form: memory
 *   as bytes, CPU as millicores, durations as whole nanoseconds, instants as
 *   ISO-8601 with nanosecond precision. Nothing is stored in a rendered form that
 *   rounds.
 * - **Canonical.** Keys are sorted and absent values are omitted, so two equal
 *   objects encode to byte-identical text. `putDefinition` compares encoded specs
 *   to decide whether the generation moves, so this is load-bearing.
 * - **Absent is not empty.** A missing key is a null field; a key with an empty
 *   value is an empty string.
 */
internal object PropertyDocument {
    /**
     * Bumped only if this encoding changes shape. Stored next to every document so
     * an older binary refuses a newer document instead of misreading it.
     *
     * ## Before bumping this
     *
     * A bump needs a plan for the documents already on disk — a migration that
     * reads the old encoding and rewrites it — because nothing else converts them.
     * Until that plan exists `MigrationTest` fails wholesale, and **the failure is
     * the point rather than a bug in the tests**: its fixtures label legacy rows
     * with this constant, while schema version 3 is pinned to the literal `1`, so a
     * bump makes every legacy fixture a row that migration refuses to read. Do not
     * chase those failures one at a time. Note the asymmetry a bump has to keep:
     * the read path checks this live constant, because a binary should read only
     * what it understands, while a migration pins the literal it was written
     * against, because a shipped migration must keep asking the same question.
     *
     * One test will *not* fail and has to be moved by hand — the "an encoding
     * version 3 does not understand" fixture is pinned to `2`, which a bump to 2
     * turns into the current encoding rather than an unknown one. It keeps passing,
     * having quietly stopped testing what its name says. Move it to the next unused
     * value.
     *
     * That pin is only half-testable while this stays at 1: un-pinning version 3
     * today fails nothing, so the test suite read on its own suggests the pin is
     * untested and safe to delete. It is the bump that makes it bite, which is why
     * this is recorded here rather than left to the tests to say.
     */
    const val ENCODING_VERSION: Int = 1

    fun parse(
        text: String,
        what: String,
    ): DocumentReader {
        val fields = LinkedHashMap<String, String>()
        for (line in text.lineSequence()) {
            if (line.isEmpty()) continue
            val separator = line.indexOf('=')
            if (separator < 0) {
                throw StoreException.Corrupt("$what: encoded document has a line with no `=` separator")
            }
            val key = unescape(line.substring(0, separator), what)
            val value = unescape(line.substring(separator + 1), what)
            if (fields.put(key, value) != null) {
                throw StoreException.Corrupt("$what: encoded document repeats the key `$key`")
            }
        }
        return DocumentReader(fields, what)
    }

    fun escape(raw: String): String =
        buildString(raw.length) {
            for (character in raw) {
                when (character) {
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '=' -> append("\\e")
                    else -> append(character)
                }
            }
        }

    private fun unescape(
        raw: String,
        what: String,
    ): String =
        buildString(raw.length) {
            var index = 0
            while (index < raw.length) {
                val character = raw[index]
                if (character != '\\') {
                    append(character)
                    index++
                    continue
                }
                if (index + 1 >= raw.length) {
                    throw StoreException.Corrupt("$what: encoded document ends in a dangling escape")
                }
                when (val escaped = raw[index + 1]) {
                    '\\' -> append('\\')
                    'n' -> append('\n')
                    'r' -> append('\r')
                    'e' -> append('=')
                    else -> throw StoreException.Corrupt("$what: encoded document has an unknown escape `\\$escaped`")
                }
                index += 2
            }
        }
}

/** Accumulates fields and renders them canonically. Null values are simply not written. */
internal class DocumentWriter {
    private val fields = sortedMapOf<String, String>()

    fun put(
        key: String,
        value: String?,
    ) {
        if (value == null) return
        require(fields.put(key, value) == null) { "duplicate key `$key` in encoded document" }
    }

    fun put(
        key: String,
        value: Boolean?,
    ): Unit = put(key, value?.toString())

    fun put(
        key: String,
        value: Int?,
    ): Unit = put(key, value?.toString())

    fun put(
        key: String,
        value: Long?,
    ): Unit = put(key, value?.toString())

    fun put(
        key: String,
        value: Instant?,
    ): Unit = put(key, value?.toString())

    fun put(
        key: String,
        value: Enum<*>?,
    ): Unit = put(key, value?.name)

    /**
     * Whole nanoseconds. Rendering a duration the way an operator writes it (`5m`)
     * would round anything finer than a millisecond, and a store must not round.
     */
    fun putDuration(
        key: String,
        value: Duration?,
    ) {
        if (value == null) return
        require(value.isFinite()) { "cannot store a non-finite duration at `$key`" }
        put(key, value.inWholeNanoseconds)
    }

    /** Nested writer under a prefix, so encoders read like the object they encode. */
    fun scope(
        prefix: String,
        block: DocumentScope.() -> Unit,
    ) {
        DocumentScope(this, prefix).block()
    }

    fun render(): String =
        fields.entries.joinToString("\n") { (key, value) ->
            "${PropertyDocument.escape(key)}=${PropertyDocument.escape(value)}"
        }
}

/** A [DocumentWriter] with a key prefix applied. */
internal class DocumentScope(
    private val writer: DocumentWriter,
    private val prefix: String,
) {
    private fun key(suffix: String): String = "$prefix.$suffix"

    fun put(
        suffix: String,
        value: String?,
    ): Unit = writer.put(key(suffix), value)

    fun put(
        suffix: String,
        value: Boolean?,
    ): Unit = writer.put(key(suffix), value)

    fun put(
        suffix: String,
        value: Int?,
    ): Unit = writer.put(key(suffix), value)

    fun put(
        suffix: String,
        value: Long?,
    ): Unit = writer.put(key(suffix), value)

    fun put(
        suffix: String,
        value: Instant?,
    ): Unit = writer.put(key(suffix), value)

    fun put(
        suffix: String,
        value: Enum<*>?,
    ): Unit = writer.put(key(suffix), value)

    fun putDuration(
        suffix: String,
        value: Duration?,
    ): Unit = writer.putDuration(key(suffix), value)

    fun scope(
        suffix: String,
        block: DocumentScope.() -> Unit,
    ): Unit = writer.scope(key(suffix), block)
}

/**
 * Reads an encoded document back. Every failure is [StoreException.Corrupt]: a
 * row that cannot be decoded is a permanent problem, and guessing at it would be
 * exactly the silent reinterpretation the schema-version discipline exists to
 * prevent.
 */
internal class DocumentReader(
    private val fields: Map<String, String>,
    private val what: String,
) {
    fun has(key: String): Boolean = fields.containsKey(key)

    fun keys(): Set<String> = fields.keys

    fun string(key: String): String? = fields[key]

    fun requireString(key: String): String =
        fields[key] ?: throw StoreException.Corrupt("$what: encoded document is missing the required key `$key`")

    fun boolean(key: String): Boolean? =
        when (val raw = fields[key]) {
            null -> null
            "true" -> true
            "false" -> false
            else -> throw corrupt(key, raw, "a boolean")
        }

    fun requireBoolean(key: String): Boolean = boolean(key) ?: missing(key)

    fun int(key: String): Int? {
        val raw = fields[key] ?: return null
        return raw.toIntOrNull() ?: throw corrupt(key, raw, "an integer")
    }

    fun requireInt(key: String): Int = int(key) ?: missing(key)

    fun long(key: String): Long? {
        val raw = fields[key] ?: return null
        return raw.toLongOrNull() ?: throw corrupt(key, raw, "a long")
    }

    fun requireLong(key: String): Long = long(key) ?: missing(key)

    fun instant(key: String): Instant? {
        val raw = fields[key] ?: return null
        return try {
            Instant.parse(raw)
        } catch (failure: DateTimeParseException) {
            throw StoreException.Corrupt("$what: key `$key` is not an ISO-8601 instant", failure)
        }
    }

    fun requireInstant(key: String): Instant = instant(key) ?: missing(key)

    fun duration(key: String): Duration? = long(key)?.nanoseconds

    fun requireDuration(key: String): Duration = duration(key) ?: missing(key)

    inline fun <reified E : Enum<E>> enum(key: String): E? {
        val raw = string(key) ?: return null
        return enumValues<E>().firstOrNull { it.name == raw }
            ?: throw unknownEnum(key, raw, E::class.simpleName.orEmpty(), enumValues<E>().map { it.name })
    }

    inline fun <reified E : Enum<E>> requireEnum(key: String): E = enum<E>(key) ?: missing(key)

    /**
     * Rebuilds a value type through its `Result`-returning factory. A rejected value
     * on the way back in means the row no longer satisfies the schema's rules, which
     * is corruption and not something to paper over.
     */
    fun <T> value(
        key: String,
        parse: (String) -> Result<T>,
    ): T? {
        val raw = string(key) ?: return null
        return parse(raw).getOrElse {
            throw StoreException.Corrupt("$what: key `$key` holds a value the schema rejects: ${it.message}", it)
        }
    }

    fun <T> requireValue(
        key: String,
        parse: (String) -> Result<T>,
    ): T = value(key, parse) ?: missing(key)

    fun missing(key: String): Nothing =
        throw StoreException.Corrupt("$what: encoded document is missing the required key `$key`")

    fun unknownEnum(
        key: String,
        raw: String,
        type: String,
        supported: List<String>,
    ): StoreException.Corrupt =
        StoreException.Corrupt(
            "$what: key `$key` holds `$raw`, which is not a $type this build knows " +
                "(${supported.joinToString(", ")}). Refusing to guess",
        )

    private fun corrupt(
        key: String,
        raw: String,
        expected: String,
    ): StoreException.Corrupt = StoreException.Corrupt("$what: key `$key` holds `$raw`, which is not $expected")
}
