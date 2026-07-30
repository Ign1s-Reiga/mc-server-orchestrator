package mcorch.api.json

import java.time.Instant

/**
 * The JSON this API emits.
 *
 * A value model rather than a streaming writer, because responses are small and
 * every one of them is also asserted on structurally by a test. Nothing here
 * reads JSON: see `api/build.gradle.kts` for why the request side needs no
 * parser.
 *
 * ## Absent renders as `null`, never as an omitted key
 *
 * Every optional field appears in every response with an explicit `null`. A
 * TypeScript client then gets one stable object shape per resource instead of a
 * union of "with the field" and "without it", and `Object.keys` on a response is
 * a fixed set. The exceptions are collections, which render as `[]` and `{}` —
 * an empty list is not an absent one.
 *
 * One deliberate exception, and it is documented where it applies: the
 * `definition` document in [mcorch.api.render.ServerJson] *omits* absent
 * optional fields, because it has to remain valid input to the schema parser and
 * the schema treats an explicit `null` as a violation rather than as "unset".
 */
internal sealed interface Json {
    fun write(out: StringBuilder)

    data object Null : Json {
        override fun write(out: StringBuilder) {
            out.append("null")
        }
    }

    data class Bool(
        val value: Boolean,
    ) : Json {
        override fun write(out: StringBuilder) {
            out.append(if (value) "true" else "false")
        }
    }

    /**
     * A number, already rendered. Held as text so that nothing between here and
     * the socket can reformat it: a `Long` beyond 2^53 survives, and a value the
     * schema renders exactly (`2500m` is not a number, but a port is) never
     * acquires a decimal point it did not have.
     */
    data class Num(
        val literal: String,
    ) : Json {
        override fun write(out: StringBuilder) {
            out.append(literal)
        }
    }

    data class Str(
        val value: String,
    ) : Json {
        override fun write(out: StringBuilder) {
            escape(value, out)
        }
    }

    data class Arr(
        val items: List<Json>,
    ) : Json {
        override fun write(out: StringBuilder) {
            out.append('[')
            items.forEachIndexed { index, item ->
                if (index > 0) out.append(',')
                item.write(out)
            }
            out.append(']')
        }
    }

    data class Obj(
        val fields: List<Pair<String, Json>>,
    ) : Json {
        override fun write(out: StringBuilder) {
            out.append('{')
            fields.forEachIndexed { index, (key, value) ->
                if (index > 0) out.append(',')
                escape(key, out)
                out.append(':')
                value.write(out)
            }
            out.append('}')
        }
    }

    fun render(): String = StringBuilder().also { write(it) }.toString()

    companion object {
        fun of(value: String?): Json = if (value == null) Null else Str(value)

        fun of(value: Boolean?): Json = if (value == null) Null else Bool(value)

        fun of(value: Int?): Json = if (value == null) Null else Num(value.toString())

        fun of(value: Long?): Json = if (value == null) Null else Num(value.toString())

        /** ISO-8601 with a `Z`, which is what `Instant.toString` produces and what `Date` parses. */
        fun of(value: Instant?): Json = if (value == null) Null else Str(value.toString())

        fun of(value: Enum<*>?): Json = if (value == null) Null else Str(value.name)

        fun strings(values: Iterable<String>): Arr = Arr(values.map(::Str))

        fun map(values: Map<String, String>): Obj =
            Obj(values.entries.sortedBy { it.key }.map { it.key to Str(it.value) })

        /**
         * Escapes for JSON *and* for being embedded in a `<script>` tag or an SSE
         * `data:` line. U+2028 and U+2029 are legal raw in JSON and illegal raw in
         * JavaScript source, and are line terminators to an SSE parser; escaping
         * them costs nothing and removes a whole class of surprise.
         */
        private fun escape(
            raw: String,
            out: StringBuilder,
        ) {
            out.append('"')
            for (character in raw) {
                when {
                    character == '"' -> {
                        out.append("\\\"")
                    }

                    character == '\\' -> {
                        out.append("\\\\")
                    }

                    character == '\n' -> {
                        out.append("\\n")
                    }

                    character == '\r' -> {
                        out.append("\\r")
                    }

                    character == '\t' -> {
                        out.append("\\t")
                    }

                    character == '\b' -> {
                        out.append("\\b")
                    }

                    character == '\u000C' -> {
                        out.append("\\f")
                    }

                    character < ' ' || character == '\u2028' || character == '\u2029' -> {
                        out.append("\\u")
                        out.append(character.code.toString(16).padStart(4, '0'))
                    }

                    else -> {
                        out.append(character)
                    }
                }
            }
            out.append('"')
        }
    }
}

/** Accumulates fields in declaration order. Order is stable so responses diff cleanly. */
internal class JsonObjectBuilder {
    private val fields = mutableListOf<Pair<String, Json>>()

    fun put(
        key: String,
        value: Json,
    ) {
        fields += key to value
    }

    fun put(
        key: String,
        value: String?,
    ): Unit = put(key, Json.of(value))

    fun put(
        key: String,
        value: Boolean?,
    ): Unit = put(key, Json.of(value))

    fun put(
        key: String,
        value: Int?,
    ): Unit = put(key, Json.of(value))

    fun put(
        key: String,
        value: Long?,
    ): Unit = put(key, Json.of(value))

    fun put(
        key: String,
        value: Instant?,
    ): Unit = put(key, Json.of(value))

    fun put(
        key: String,
        value: Enum<*>?,
    ): Unit = put(key, Json.of(value))

    /** [render] applied to [value], or `null` when it is absent. */
    fun <T : Any> putOrNull(
        key: String,
        value: T?,
        render: (T) -> Json,
    ): Unit = put(key, if (value == null) Json.Null else render(value))

    /** Nested object, or `null` when [value] is absent. */
    fun <T : Any> putObject(
        key: String,
        value: T?,
        block: JsonObjectBuilder.(T) -> Unit,
    ) {
        if (value == null) {
            put(key, Json.Null)
        } else {
            put(key, jsonObject { block(value) })
        }
    }

    fun <T> putArray(
        key: String,
        values: Iterable<T>,
        block: (T) -> Json,
    ): Unit = put(key, Json.Arr(values.map(block)))

    fun build(): Json.Obj = Json.Obj(fields.toList())
}

internal fun jsonObject(block: JsonObjectBuilder.() -> Unit): Json.Obj = JsonObjectBuilder().apply(block).build()
