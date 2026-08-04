package mcorch.core.proxy

/**
 * Reads the control protocol's responses, and writes its four-field requests.
 *
 * ## Why `:core` parses its own JSON
 *
 * The plugin writes its own for a reason that does not apply here — it must not
 * be pinned to whichever Gson the *proxy image* happens to bundle — and `:core`
 * has no such constraint. What it has instead is that its `Json` is `internal` to
 * `:velocity-plugin`, and widening it would put a parser in the module whose
 * whole discipline is that only `mcorch.velocity.control` crosses the boundary,
 * and only as constants. A page of reader with tests is cheaper than either
 * loosening that or adding a serialisation library to the module that runs the
 * drain.
 *
 * ## It refuses rather than coerces
 *
 * A missing field, a field of the wrong type, trailing content, over-deep
 * nesting: all [ControlMalformed]. The values read here decide whether a backend
 * is sealed and whether a transfer is finished, and a lenient reader turns a
 * truncated body into a confident wrong answer. In particular nothing defaults a
 * missing boolean — a body whose `admitsNewPlayers` was dropped must not read as
 * "admits".
 */
internal object ControlJson {
    /** Deeper than the protocol has any use for is a malformed body, not a document. */
    private const val MAX_DEPTH = 8

    fun parse(raw: String): ControlObject {
        val parser = Parser(raw)
        val value = parser.readValue(0)
        parser.skipWhitespace()
        if (!parser.exhausted) parser.fail("trailing content after the top-level value")
        return value as? ControlObject ?: throw ControlMalformed("the response body must be a JSON object")
    }

    /** A request body, built field by field. The protocol never needs nesting on the way out. */
    fun body(vararg fields: Pair<String, Any?>): String =
        fields.joinToString(prefix = "{", postfix = "}") { (name, value) ->
            val rendered =
                when (value) {
                    null -> "null"
                    is Boolean -> value.toString()
                    is Int -> value.toString()
                    else -> "\"${escape(value.toString())}\""
                }
            "\"${escape(name)}\":$rendered"
        }

    fun escape(value: String): String {
        val out = StringBuilder(value.length + 8)
        for (character in value) {
            when {
                character == '"' -> out.append("\\\"")
                character == '\\' -> out.append("\\\\")
                character == '\n' -> out.append("\\n")
                character == '\r' -> out.append("\\r")
                character == '\t' -> out.append("\\t")
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

        fun fail(problem: String): Nothing = throw ControlMalformed("malformed JSON at offset $at: $problem")

        fun skipWhitespace() {
            while (at < raw.length && (raw[at] == ' ' || raw[at] == '\t' || raw[at] == '\n' || raw[at] == '\r')) {
                at++
            }
        }

        fun readValue(depth: Int): ControlValue {
            if (depth > MAX_DEPTH) fail("nested more than $MAX_DEPTH levels deep")
            skipWhitespace()
            if (exhausted) fail("expected a value")
            return when (raw[at]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> ControlString(readString())
                't' -> {
                    expectLiteral("true")
                    ControlBoolean(true)
                }

                'f' -> {
                    expectLiteral("false")
                    ControlBoolean(false)
                }

                'n' -> {
                    expectLiteral("null")
                    ControlNull
                }

                else -> readNumber()
            }
        }

        private fun readObject(depth: Int): ControlObject {
            expect('{')
            val fields = LinkedHashMap<String, ControlValue>()
            skipWhitespace()
            if (peek() == '}') {
                at++
                return ControlObject(fields)
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
                    ',' -> at++
                    '}' -> {
                        at++
                        return ControlObject(fields)
                    }

                    else -> fail("expected `,` or `}`")
                }
            }
        }

        private fun readArray(depth: Int): ControlArray {
            expect('[')
            val items = mutableListOf<ControlValue>()
            skipWhitespace()
            if (peek() == ']') {
                at++
                return ControlArray(items)
            }
            while (true) {
                items += readValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> at++
                    ']' -> {
                        at++
                        return ControlArray(items)
                    }

                    else -> fail("expected `,` or `]`")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (exhausted) fail("unterminated string")
                when (val character = raw[at++]) {
                    '"' -> return out.toString()
                    '\\' -> out.append(readEscape())
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
                '"', '\\', '/' -> marker
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'b' -> '\b'
                'f' -> '\u000C'
                'u' -> {
                    if (at + 4 > raw.length) fail("truncated \\u escape")
                    val hex = raw.substring(at, at + 4)
                    at += 4
                    // Digit by digit, not `toIntOrNull(16)`, which accepts a sign.
                    if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                        fail("`$hex` is not a hex escape")
                    }
                    hex.toInt(16).toChar()
                }

                else -> fail("unknown escape `\\$marker`")
            }
        }

        private fun readNumber(): ControlNumber {
            val start = at
            if (peek() == '-') at++
            while (at < raw.length && (raw[at] in '0'..'9' || raw[at] in ".eE+-")) at++
            if (at == start) fail("expected a value")
            val text = raw.substring(start, at)
            return ControlNumber(text.toDoubleOrNull() ?: fail("`$text` is not a number"))
        }

        private fun expectLiteral(literal: String) {
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

/** A body this build cannot read. Always a failure of the *channel*, never of the drain's own logic. */
internal class ControlMalformed(
    problem: String,
) : RuntimeException(problem)

internal sealed interface ControlValue

internal data object ControlNull : ControlValue

internal data class ControlBoolean(
    val value: Boolean,
) : ControlValue

internal data class ControlNumber(
    val value: Double,
) : ControlValue

internal data class ControlString(
    val value: String,
) : ControlValue

internal data class ControlArray(
    val items: List<ControlValue>,
) : ControlValue

/** A parsed object, with reads that refuse rather than coerce. See [ControlJson]. */
internal data class ControlObject(
    val fields: Map<String, ControlValue>,
) : ControlValue {
    fun string(name: String): String =
        when (val value = fields[name]) {
            is ControlString -> value.value
            null -> refuse("field `$name` is required")
            else -> refuse("field `$name` must be a string")
        }

    fun boolean(name: String): Boolean =
        when (val value = fields[name]) {
            is ControlBoolean -> value.value
            null -> refuse("field `$name` is required")
            else -> refuse("field `$name` must be a boolean")
        }

    fun int(name: String): Int =
        when (val value = fields[name]) {
            is ControlNumber -> value.value.toInt()
            null -> refuse("field `$name` is required")
            else -> refuse("field `$name` must be a number")
        }

    fun long(name: String): Long =
        when (val value = fields[name]) {
            is ControlNumber -> value.value.toLong()
            null -> refuse("field `$name` is required")
            else -> refuse("field `$name` must be a number")
        }

    fun optionalLong(name: String): Long? =
        when (val value = fields[name]) {
            is ControlNumber -> value.value.toLong()
            null, ControlNull -> null
            else -> refuse("field `$name` must be a number when present")
        }

    fun objects(name: String): List<ControlObject> =
        when (val value = fields[name]) {
            is ControlArray -> value.items.map { it as? ControlObject ?: refuse("`$name` must hold objects") }
            null -> refuse("field `$name` is required")
            else -> refuse("field `$name` must be an array")
        }

    fun strings(name: String): List<String> =
        when (val value = fields[name]) {
            is ControlArray ->
                value.items.map {
                    (it as? ControlString)?.value ?: refuse("`$name` must hold strings")
                }

            null -> refuse("field `$name` is required")
            else -> refuse("field `$name` must be an array")
        }

    fun objectAt(name: String): ControlObject =
        when (val value = fields[name]) {
            is ControlObject -> value
            null -> refuse("field `$name` is required")
            else -> refuse("field `$name` must be an object")
        }

    fun optionalObject(name: String): ControlObject? =
        when (val value = fields[name]) {
            is ControlObject -> value
            null, ControlNull -> null
            else -> refuse("field `$name` must be an object when present")
        }

    private fun refuse(problem: String): Nothing = throw ControlMalformed(problem)
}
