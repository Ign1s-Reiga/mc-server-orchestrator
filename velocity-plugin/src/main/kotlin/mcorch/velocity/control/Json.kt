package mcorch.velocity.control

/**
 * The JSON this protocol needs, and no more.
 *
 * Velocity does ship Gson, and using it would be one line. It is not used, for
 * the same reason `:api` writes its own: a plugin that compiles against a
 * library the *host* provides is pinned to whichever version that host happens
 * to bundle, across a boundary this build cannot see. The request bodies here
 * are four fields of string and boolean; the responses are two levels of object
 * and array. That is a page of code with tests, against a transitive version
 * dependency on a proxy image nothing here controls.
 *
 * It is deliberately strict. Trailing content, unterminated strings, bad escapes
 * and over-deep nesting are all refusals rather than best-effort reads — a
 * control plane that can seal every backend in a fleet is not the place for a
 * lenient parser.
 */
internal object Json {
    /** Nesting deeper than the protocol has any use for is a malformed body, not a document. */
    private const val MAX_DEPTH = 8

    fun parse(raw: String): JsonValue {
        val parser = Parser(raw)
        val value = parser.readValue(0)
        parser.skipWhitespace()
        if (!parser.exhausted) parser.fail("trailing content after the top-level value")
        return value
    }

    /** Parses and requires an object, which every request body in this protocol is. */
    fun parseObject(raw: String): JsonObject =
        parse(raw) as? JsonObject
            ?: throw ControlFailure(ControlErrorCode.MALFORMED_REQUEST, "the request body must be a JSON object")

    fun escape(value: String): String {
        val out = StringBuilder(value.length + 8)
        for (character in value) {
            when {
                character == '"' -> out.append("\\\"")
                character == '\\' -> out.append("\\\\")
                character == '\n' -> out.append("\\n")
                character == '\r' -> out.append("\\r")
                character == '\t' -> out.append("\\t")
                character == '\b' -> out.append("\\b")
                character == '\u000C' -> out.append("\\f")
                character < ' ' || character == '\u007F' -> out.append("\\u%04x".format(character.code))
                else -> out.append(character)
            }
        }
        return out.toString()
    }

    private class Parser(
        private val raw: String,
    ) {
        private var at = 0

        val exhausted: Boolean get() = at >= raw.length

        fun fail(problem: String): Nothing =
            throw ControlFailure(ControlErrorCode.MALFORMED_REQUEST, "malformed JSON at offset $at: $problem")

        // JSON's four, not Char.isWhitespace's Unicode set. A parser documented as
        // strict should not accept U+00A0 between tokens.
        private fun isJsonSpace(character: Char): Boolean =
            character == ' ' || character == '\t' || character == '\n' || character == '\r'

        fun skipWhitespace() {
            while (at < raw.length && isJsonSpace(raw[at])) at++
        }

        fun readValue(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) fail("nested more than $MAX_DEPTH levels deep")
            skipWhitespace()
            if (exhausted) fail("expected a value")
            return when (raw[at]) {
                '{' -> {
                    readObject(depth)
                }

                '[' -> {
                    readArray(depth)
                }

                '"' -> {
                    JsonString(readString())
                }

                't' -> {
                    readLiteral("true")
                    JsonBoolean(true)
                }

                'f' -> {
                    readLiteral("false")
                    JsonBoolean(false)
                }

                'n' -> {
                    readLiteral("null")
                    JsonNull
                }

                else -> {
                    readNumber()
                }
            }
        }

        private fun readObject(depth: Int): JsonObject {
            expect('{')
            val fields = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                at++
                return JsonObject(fields)
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("expected a field name")
                val name = readString()
                // A duplicate key is a document whose meaning depends on which one
                // the reader kept. Refuse rather than pick.
                if (fields.containsKey(name)) fail("duplicate field `$name`")
                skipWhitespace()
                expect(':')
                fields[name] = readValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        at++
                    }

                    '}' -> {
                        at++
                        return JsonObject(fields)
                    }

                    else -> {
                        fail("expected `,` or `}`")
                    }
                }
            }
        }

        private fun readArray(depth: Int): JsonArray {
            expect('[')
            val items = mutableListOf<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                at++
                return JsonArray(items)
            }
            while (true) {
                items += readValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        at++
                    }

                    ']' -> {
                        at++
                        return JsonArray(items)
                    }

                    else -> {
                        fail("expected `,` or `]`")
                    }
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (exhausted) fail("unterminated string")
                when (val character = raw[at++]) {
                    '"' -> {
                        return out.toString()
                    }

                    '\\' -> {
                        out.append(readEscape())
                    }

                    else -> {
                        if (character < ' ') fail("unescaped control character in a string")
                        out.append(character)
                    }
                }
            }
        }

        private fun readEscape(): Char {
            if (exhausted) fail("unterminated escape")
            return when (val marker = raw[at++]) {
                '"', '\\', '/' -> {
                    marker
                }

                'n' -> {
                    '\n'
                }

                'r' -> {
                    '\r'
                }

                't' -> {
                    '\t'
                }

                'b' -> {
                    '\b'
                }

                'f' -> {
                    '\u000C'
                }

                'u' -> {
                    if (at + 4 > raw.length) fail("truncated \\u escape")
                    val hex = raw.substring(at, at + 4)
                    at += 4
                    // Checked digit by digit rather than with toIntOrNull(16), which
                    // accepts a leading sign: `\u+041` would decode to `A` and
                    // `\u-001` to U+FFFF.
                    if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                        fail("`$hex` is not a hex escape")
                    }
                    hex.toInt(16).toChar()
                }

                else -> {
                    fail("unknown escape `\\$marker`")
                }
            }
        }

        private fun readNumber(): JsonNumber {
            val start = at
            if (peek() == '-') at++
            // ASCII digits only. Char.isDigit accepts every Unicode decimal digit,
            // and toDouble does not.
            while (at < raw.length && (raw[at] in '0'..'9' || raw[at] in ".eE+-")) at++
            if (at == start) fail("expected a value")
            val text = raw.substring(start, at)
            val value = text.toDoubleOrNull() ?: fail("`$text` is not a number")
            return JsonNumber(value)
        }

        private fun readLiteral(literal: String) {
            if (!raw.startsWith(literal, at)) fail("expected `$literal`")
            at += literal.length
        }

        private fun peek(): Char? = raw.getOrNull(at)

        private fun expect(character: Char) {
            if (peek() != character) fail("expected `$character`")
            at++
        }
    }
}

internal sealed interface JsonValue

internal data object JsonNull : JsonValue

internal data class JsonBoolean(
    val value: Boolean,
) : JsonValue

internal data class JsonNumber(
    val value: Double,
) : JsonValue

internal data class JsonString(
    val value: String,
) : JsonValue

internal data class JsonArray(
    val items: List<JsonValue>,
) : JsonValue

/**
 * A parsed object, with typed reads that refuse rather than coerce.
 *
 * Every accessor throws [ControlFailure] with [ControlErrorCode.MALFORMED_REQUEST],
 * so a handler reads fields in a straight line and the transport turns the
 * refusal into a 400. Nothing here defaults a missing field to a value: the seal
 * is asserted through one of these, and a body whose `admitsNewPlayers` was
 * dropped by a serialisation bug must not read as "admits".
 */
internal data class JsonObject(
    val fields: Map<String, JsonValue>,
) : JsonValue {
    fun string(name: String): String =
        when (val value = fields[name]) {
            is JsonString -> value.value
            null -> refuse("field `$name` is required")
            else -> refuse("field `$name` must be a string")
        }

    fun boolean(name: String): Boolean =
        when (val value = fields[name]) {
            is JsonBoolean -> value.value
            null -> refuse("field `$name` is required")
            else -> refuse("field `$name` must be a boolean")
        }

    fun optionalString(name: String): String? =
        when (val value = fields[name]) {
            is JsonString -> value.value
            null, JsonNull -> null
            else -> refuse("field `$name` must be a string when present")
        }

    private fun refuse(problem: String): Nothing = throw ControlFailure(ControlErrorCode.MALFORMED_REQUEST, problem)
}

/**
 * Builds a response body.
 *
 * A builder rather than a serialiser over data classes, because the whole point
 * of the identity rule is that no object graph reachable from a `Player` is ever
 * handed to something that walks it reflectively. Every field in every response
 * is written by a line of code somebody had to type.
 */
internal class JsonWriter {
    private val out = StringBuilder()

    fun obj(build: ObjectScope.() -> Unit): JsonWriter {
        out.append('{')
        ObjectScope(out).build()
        out.append('}')
        return this
    }

    override fun toString(): String = out.toString()

    class ObjectScope(
        private val out: StringBuilder,
    ) {
        private var written = 0

        private fun key(name: String) {
            if (written++ > 0) out.append(',')
            out.append('"').append(Json.escape(name)).append("\":")
        }

        fun field(
            name: String,
            value: String?,
        ) {
            key(name)
            if (value == null) out.append("null") else out.append('"').append(Json.escape(value)).append('"')
        }

        fun field(
            name: String,
            value: Boolean,
        ) {
            key(name)
            out.append(if (value) "true" else "false")
        }

        fun field(
            name: String,
            value: Int,
        ) {
            key(name)
            out.append(value)
        }

        fun field(
            name: String,
            value: Long?,
        ) {
            key(name)
            if (value == null) out.append("null") else out.append(value)
        }

        fun objectField(
            name: String,
            build: ObjectScope.() -> Unit,
        ) {
            key(name)
            out.append('{')
            ObjectScope(out).build()
            out.append('}')
        }

        fun nullField(name: String) {
            key(name)
            out.append("null")
        }

        fun stringArray(
            name: String,
            values: List<String>,
        ) {
            key(name)
            out.append('[')
            values.forEachIndexed { index, value ->
                if (index > 0) out.append(',')
                out.append('"').append(Json.escape(value)).append('"')
            }
            out.append(']')
        }

        fun <T> objectArray(
            name: String,
            items: List<T>,
            build: ObjectScope.(T) -> Unit,
        ) {
            key(name)
            out.append('[')
            items.forEachIndexed { index, item ->
                if (index > 0) out.append(',')
                out.append('{')
                ObjectScope(out).build(item)
                out.append('}')
            }
            out.append(']')
        }
    }
}
