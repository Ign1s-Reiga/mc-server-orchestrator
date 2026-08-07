package mcorch.schema.yaml

import mcorch.schema.DurationFormat
import mcorch.schema.MemoryQuantity
import mcorch.schema.ResourceName
import mcorch.schema.SchemaViolation
import mcorch.schema.SecretRef
import mcorch.schema.SourceLocation
import org.snakeyaml.engine.v2.nodes.MappingNode
import org.snakeyaml.engine.v2.nodes.Node
import org.snakeyaml.engine.v2.nodes.ScalarNode
import org.snakeyaml.engine.v2.nodes.SequenceNode
import kotlin.time.Duration

/**
 * Collects violations instead of throwing on the first one.
 *
 * Readers keep going after a bad field — substituting a default and carrying on —
 * so that one parse reports everything wrong with a file. Nothing is
 * constructed while the sink has grown, so a "carry on with the default" never
 * escapes into a real definition.
 */
internal class ViolationSink(
    private val source: String,
) {
    private val collected = mutableListOf<SchemaViolation>()

    val violations: List<SchemaViolation> get() = collected.toList()

    val size: Int get() = collected.size

    fun add(
        field: String,
        problem: String,
        node: Node?,
    ) {
        collected += SchemaViolation(field, problem, locationOf(node))
    }

    fun addAt(
        field: String,
        problem: String,
        location: SourceLocation?,
    ) {
        collected += SchemaViolation(field, problem, location)
    }

    fun locationOf(node: Node?): SourceLocation? {
        val mark = node?.startMark?.orElse(null) ?: return null
        return SourceLocation(source, mark.line + 1, mark.column + 1)
    }
}

/** What a node is, in words an operator recognises from their own file. */
internal fun describe(node: Node): String =
    when (node) {
        is MappingNode -> "a mapping"
        is SequenceNode -> "a list"
        is ScalarNode -> if (isNullScalar(node)) "an empty value" else "the value `${node.value}`"
        else -> "an unsupported node"
    }

internal fun isNullScalar(node: Node): Boolean =
    node is ScalarNode && node.isPlain && node.value.trim() in setOf("", "~", "null", "Null", "NULL")

/**
 * Reads one YAML mapping, tracking which keys were consumed.
 *
 * Unknown keys are an error, not a shrug. A file that says `persistance:` has
 * to fail loudly: an operator who believes they configured persistence and did
 * not is exactly the failure this orchestrator exists to prevent. Ignoring a
 * key means the definition on disk and the server that runs disagree, silently
 * and permanently.
 */
internal class MappingReader private constructor(
    private val path: String,
    private val entries: Map<String, Node>,
    private val sink: ViolationSink,
) {
    private val consumed = mutableSetOf<String>()

    fun pathOf(name: String): String = if (path.isEmpty()) name else "$path.$name"

    /** Records a violation against a key of this mapping, located at that key's value. */
    fun violation(
        name: String,
        problem: String,
    ) {
        sink.add(pathOf(name), problem, entries[name])
    }

    // --- raw access -------------------------------------------------------

    /**
     * Consumes [name]. Returns null when absent, or when present but explicitly
     * null — an explicit null is reported rather than treated as "unset",
     * because `storage:` with nothing under it must not quietly mean
     * "whatever the default is".
     */
    fun node(name: String): Node? {
        consumed += name
        val node = entries[name] ?: return null
        if (isNullScalar(node)) {
            sink.add(pathOf(name), "must not be null; omit the field entirely to use its default", node)
            return null
        }
        return node
    }

    fun isPresent(name: String): Boolean = entries.containsKey(name)

    private fun scalar(
        name: String,
        expected: String,
    ): String? {
        val node = node(name) ?: return null
        if (node !is ScalarNode) {
            sink.add(pathOf(name), "expected $expected, found ${describe(node)}", node)
            return null
        }
        return node.value
    }

    private fun missing(
        name: String,
        required: Boolean,
    ) {
        if (required) sink.add(pathOf(name), "is required", entries[name])
    }

    // --- typed access -----------------------------------------------------

    fun mapping(
        name: String,
        required: Boolean = false,
    ): MappingReader? {
        val node = node(name)
        if (node == null) {
            missing(name, required)
            return null
        }
        return of(pathOf(name), node, sink)
    }

    fun string(
        name: String,
        required: Boolean = false,
        default: String? = null,
    ): String? {
        if (!entries.containsKey(name)) {
            consumed += name
            missing(name, required)
            return default
        }
        return scalar(name, "a string") ?: default
    }

    fun boolean(
        name: String,
        required: Boolean = false,
        default: Boolean? = null,
    ): Boolean? {
        if (!entries.containsKey(name)) {
            consumed += name
            missing(name, required)
            return default
        }
        val raw = scalar(name, "a boolean") ?: return default
        when (raw) {
            "true" -> {
                return true
            }

            "false" -> {
                return false
            }

            in YAML_1_1_BOOLEANS -> {
                violation(
                    name,
                    "expected a boolean `true` or `false`; `$raw` is a plain string in YAML 1.2, not a boolean",
                )
            }

            in CAPITALISED_BOOLEANS -> {
                violation(name, "expected a boolean written lowercase as `true` or `false`, found `$raw`")
            }

            else -> {
                violation(name, "expected a boolean `true` or `false`, found `$raw`")
            }
        }
        return default
    }

    fun int(
        name: String,
        required: Boolean = false,
        default: Int? = null,
        min: Int = Int.MIN_VALUE,
        max: Int = Int.MAX_VALUE,
    ): Int? {
        if (!entries.containsKey(name)) {
            consumed += name
            missing(name, required)
            return default
        }
        val raw = scalar(name, "a whole number") ?: return default
        val parsed = raw.trim().toLongOrNull()
        if (parsed == null) {
            violation(name, "expected a whole number, found `$raw`")
            return default
        }
        if (parsed < min || parsed > max) {
            violation(name, "must be between $min and $max, found $parsed")
            return default
        }
        return parsed.toInt()
    }

    fun port(
        name: String,
        required: Boolean = false,
        default: Int? = null,
    ): Int? = int(name, required = required, default = default, min = MIN_PORT, max = MAX_PORT)

    fun <T : Any> value(
        name: String,
        required: Boolean = false,
        default: T? = null,
        parse: (String) -> Result<T>,
    ): T? {
        if (!entries.containsKey(name)) {
            consumed += name
            missing(name, required)
            return default
        }
        val raw = scalar(name, "a string") ?: return default
        return parse(raw).fold(
            onSuccess = { it },
            onFailure = {
                violation(name, it.message ?: "is not valid")
                default
            },
        )
    }

    fun <T : Any> enum(
        name: String,
        required: Boolean = false,
        default: T? = null,
        supported: List<String>,
        lookup: (String) -> T?,
    ): T? =
        value(name, required = required, default = default) { raw ->
            val resolved = lookup(raw)
            if (resolved != null) {
                Result.success(resolved)
            } else {
                Result.failure(
                    IllegalArgumentException(
                        "must be one of ${supported.joinToString(", ") { "`$it`" }}, found `$raw`",
                    ),
                )
            }
        }

    fun memory(
        name: String,
        required: Boolean = false,
        default: MemoryQuantity? = null,
        min: MemoryQuantity? = null,
        max: MemoryQuantity? = null,
    ): MemoryQuantity? {
        val parsed = value(name, required = required, default = default, parse = MemoryQuantity::parse) ?: return null
        if (min != null && parsed < min) {
            violation(name, "must be at least ${min.render()}, found ${parsed.render()}")
            return default
        }
        if (max != null && parsed > max) {
            violation(name, "must be at most ${max.render()}, found ${parsed.render()}")
            return default
        }
        return parsed
    }

    fun duration(
        name: String,
        required: Boolean = false,
        default: Duration? = null,
        min: Duration,
        max: Duration,
    ): Duration? {
        val parsed = value(name, required = required, default = default, parse = DurationFormat::parse) ?: return null
        if (parsed < min || parsed > max) {
            violation(
                name,
                "must be between ${DurationFormat.render(min)} and ${DurationFormat.render(max)}, " +
                    "found ${DurationFormat.render(parsed)}",
            )
            return default
        }
        return parsed
    }

    /**
     * A list of scalars, each parsed and each reported on its own index.
     *
     * The index is in the field path (`spec.backends.fallback[2]`) so an
     * operator reading the violation can count down their own file. A bad entry
     * is dropped and the rest are still checked, on the same "report everything
     * in one pass" rule as the rest of this reader.
     */
    fun <T : Any> valueList(
        name: String,
        max: Int,
        parse: (String) -> Result<T>,
    ): List<T> {
        val node = node(name) ?: return emptyList()
        if (node !is SequenceNode) {
            sink.add(pathOf(name), "expected a list, found ${describe(node)}", node)
            return emptyList()
        }
        if (node.value.size > max) {
            sink.add(pathOf(name), "must have at most $max entries, found ${node.value.size}", node)
            return emptyList()
        }
        val result = mutableListOf<T>()
        node.value.forEachIndexed { index, entry ->
            val at = "${pathOf(name)}[$index]"
            if (entry !is ScalarNode || isNullScalar(entry)) {
                sink.add(at, "expected a string, found ${describe(entry)}", entry)
                return@forEachIndexed
            }
            parse(entry.value).fold(
                onSuccess = { value ->
                    if (value in result) {
                        sink.add(at, "is listed more than once, found `${entry.value}`", entry)
                    } else {
                        result += value
                    }
                },
                onFailure = { sink.add(at, it.message ?: "is not valid", entry) },
            )
        }
        return result
    }

    /**
     * A pointer into the secret store: `{name: ..., key: ...}`, coordinates only.
     *
     * The scalar case is the one that matters. `forwarding.secret: "abc123"` is
     * syntactically a perfectly good YAML string, and a generic "expected a
     * mapping" would tell an operator to add braces rather than to stop putting
     * the material in the file. Every field in this module that names a secret
     * goes through here, so there is one message and one place to change it.
     *
     * The complementary rule lives in [done]: a key that *looks* like a secret
     * and was never claimed by a reader is rejected too, so `forwardingSecret:`
     * at any depth is caught whether or not this kind has such a field.
     */
    fun secretRef(
        name: String,
        required: Boolean = false,
    ): SecretRef? {
        if (!entries.containsKey(name)) {
            consumed += name
            missing(name, required)
            return null
        }
        val node = node(name) ?: return null
        if (node is ScalarNode) {
            sink.add(pathOf(name), INLINE_SECRET_PROBLEM, node)
            return null
        }
        val reader = of(pathOf(name), node, sink) ?: return null
        val secretName = reader.value("name", required = true, parse = ResourceName::of)
        val key = reader.string("key", required = true)
        if (key != null) {
            SecretRef.keyProblem(key)?.let { reader.violation("key", it) }
        }
        reader.done()
        return SecretRef(secretName ?: return null, key ?: return null)
    }

    /** A mapping of plain string keys to plain string values, validated per entry. */
    fun stringMap(
        name: String,
        keyProblem: (String) -> String?,
        valueProblem: (String) -> String?,
    ): Map<String, String> {
        val reader = mapping(name) ?: return emptyMap()
        val result = mutableMapOf<String, String>()
        for (key in reader.entries.keys) {
            val problem = keyProblem(key)
            if (problem != null) {
                reader.violation(key, problem)
                reader.consumed += key
                continue
            }
            val text = reader.scalar(key, "a string")
            if (text == null) {
                continue
            }
            val issue = valueProblem(text)
            if (issue != null) {
                reader.violation(key, issue)
                continue
            }
            result[key] = text
        }
        reader.done()
        return result
    }

    /**
     * Call once every field of this mapping has been read. Reports keys nobody
     * claimed, and rejects anything that looks like an inline secret with a
     * message pointing at the secret store.
     */
    fun done() {
        val known = consumed.toList()
        for ((key, node) in entries) {
            if (key in consumed) continue
            val secretish = key.lowercase() in SECRET_LIKE_KEYS
            if (secretish) {
                sink.add(pathOf(key), INLINE_SECRET_PROBLEM, node)
                continue
            }
            val suggestion = closestMatch(key, known)
            val hint = if (suggestion != null) "did you mean `$suggestion`? " else ""
            sink.add(
                pathOf(key),
                "unknown field. ${hint}known fields here: ${known.sorted().joinToString(", ") { "`$it`" }}",
                node,
            )
        }
    }

    companion object {
        private const val MIN_PORT = 1
        private const val MAX_PORT = 65535

        private val YAML_1_1_BOOLEANS = setOf("yes", "no", "on", "off", "Yes", "No", "On", "Off")
        private val CAPITALISED_BOOLEANS = setOf("True", "False", "TRUE", "FALSE")

        /**
         * Keys that would be holding secret material if anything in this module
         * accepted it, so an unclaimed one gets [INLINE_SECRET_PROBLEM] rather
         * than a generic "unknown field".
         *
         * `secret` and `forwardingsecret` are both listed. A reader that
         * legitimately consumes a key named `secret` — `spec.forwarding.secret`
         * on a proxy — takes it through [MappingReader.secretRef], which enforces
         * the mapping shape, so the literal spelling
         * `forwarding.secret: "<value>"` is still refused, by that path rather
         * than this one. Anything nobody claims lands here.
         */
        private val SECRET_LIKE_KEYS =
            setOf(
                "password",
                "passwd",
                "rconpassword",
                "forwardingsecret",
                "secret",
                "secretvalue",
                "token",
                "apikey",
                "credentials",
            )

        internal const val INLINE_SECRET_PROBLEM: String =
            "inline secrets are not supported anywhere in a definition. Put the value in the secret store " +
                "and reference it by coordinates — `{name: <secret name>, key: <key within it>}` — the way " +
                "`network.rcon.passwordSecret` and `forwarding.secret` do"

        /** Reports "expected a mapping" and returns null when [node] is anything else. */
        fun of(
            path: String,
            node: Node,
            sink: ViolationSink,
        ): MappingReader? {
            if (node !is MappingNode) {
                sink.add(
                    path.ifEmpty { "<document>" },
                    "expected a mapping, found ${describe(node)}",
                    node,
                )
                return null
            }
            val entries = LinkedHashMap<String, Node>()
            for (tuple in node.value) {
                val keyNode = tuple.keyNode
                if (keyNode !is ScalarNode) {
                    sink.add(
                        path.ifEmpty { "<document>" },
                        "expected string keys, found ${describe(keyNode)} used as a key",
                        keyNode,
                    )
                    continue
                }
                val key = keyNode.value
                if (entries.containsKey(key)) {
                    sink.add(
                        if (path.isEmpty()) key else "$path.$key",
                        "is declared more than once",
                        keyNode,
                    )
                    continue
                }
                entries[key] = tuple.valueNode
            }
            return MappingReader(path, entries, sink)
        }

        private fun closestMatch(
            candidate: String,
            known: List<String>,
        ): String? {
            val limit = maxOf(1, candidate.length / 3)
            return known
                .map { it to editDistance(candidate.lowercase(), it.lowercase()) }
                .filter { it.second <= limit }
                .minByOrNull { it.second }
                ?.first
        }

        private fun editDistance(
            left: String,
            right: String,
        ): Int {
            var previous = IntArray(right.length + 1) { it }
            for (i in 1..left.length) {
                val current = IntArray(right.length + 1)
                current[0] = i
                for (j in 1..right.length) {
                    val substitution = previous[j - 1] + if (left[i - 1] == right[j - 1]) 0 else 1
                    current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
                }
                previous = current
            }
            return previous[right.length]
        }
    }
}
