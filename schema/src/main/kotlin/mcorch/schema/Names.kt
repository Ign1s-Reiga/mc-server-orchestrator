package mcorch.schema

/** Shared failure shape for every value-object factory in this module. */
internal fun <T> invalidValue(problem: String): Result<T> = Result.failure(IllegalArgumentException(problem))

/**
 * The name of a declared object. It becomes part of a container name and of a
 * volume name, so it is restricted to what every runtime we could plausibly
 * target accepts: lowercase alphanumerics and `-`, starting and ending
 * alphanumeric, at most 63 characters (RFC 1123 label).
 */
@JvmInline
public value class ResourceName private constructor(
    public val value: String,
) : Comparable<ResourceName> {
    override fun compareTo(other: ResourceName): Int = value.compareTo(other.value)

    override fun toString(): String = value

    public companion object {
        public const val MAX_LENGTH: Int = 63

        private val PATTERN = Regex("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$")

        /**
         * The rule in words, for the one caller that may not quote what was
         * written back at the operator: a coordinate of a secret reference
         * ([SecretRef.NAME_PROBLEM]). Stated here so it cannot drift from
         * [PATTERN].
         */
        internal val SYNTAX: String =
            "lowercase letters, digits and `-`, starting and ending alphanumeric, at most $MAX_LENGTH characters"

        public fun of(raw: String): Result<ResourceName> {
            val problem = problemWith(raw)
            return if (problem == null) Result.success(ResourceName(raw)) else invalidValue(problem)
        }

        internal fun problemWith(raw: String): String? =
            when {
                raw.isEmpty() -> {
                    "must not be empty"
                }

                raw.length > MAX_LENGTH -> {
                    "must be at most $MAX_LENGTH characters, found ${raw.length}"
                }

                raw != raw.lowercase() -> {
                    "must be lowercase, found `$raw`"
                }

                !PATTERN.matches(raw) -> {
                    "must match ${PATTERN.pattern} " +
                        "(lowercase letters, digits and `-`, starting and ending alphanumeric), found `$raw`"
                }

                else -> {
                    null
                }
            }
    }
}

/**
 * Identifies a node the orchestrator can place a container on. Distinct from
 * [ResourceName] on purpose: a node is infrastructure, not a declared object,
 * and nothing in this module may assume there is only one of them.
 */
@JvmInline
public value class NodeName private constructor(
    public val value: String,
) : Comparable<NodeName> {
    override fun compareTo(other: NodeName): Int = value.compareTo(other.value)

    override fun toString(): String = value

    public companion object {
        public const val MAX_LENGTH: Int = 253

        private val PATTERN = Regex("^[a-z0-9]([-a-z0-9.]*[a-z0-9])?$")

        public fun of(raw: String): Result<NodeName> {
            val problem =
                when {
                    raw.isEmpty() -> {
                        "must not be empty"
                    }

                    raw.length > MAX_LENGTH -> {
                        "must be at most $MAX_LENGTH characters, found ${raw.length}"
                    }

                    !PATTERN.matches(raw) -> {
                        "must match ${PATTERN.pattern} (a DNS-style host name), found `$raw`"
                    }

                    else -> {
                        null
                    }
                }
            return if (problem == null) Result.success(NodeName(raw)) else invalidValue(problem)
        }
    }
}

/** Free-form selector metadata. Validated so it can be used as a runtime label later. */
internal object LabelSyntax {
    private const val MAX_LENGTH = 63
    private val KEY = Regex("^[A-Za-z0-9]([-A-Za-z0-9_./]*[A-Za-z0-9])?$")
    private val VALUE = Regex("^[A-Za-z0-9]([-A-Za-z0-9_.]*[A-Za-z0-9])?$")

    fun keyProblem(raw: String): String? =
        when {
            raw.isEmpty() -> "label keys must not be empty"
            raw.length > MAX_LENGTH -> "label keys must be at most $MAX_LENGTH characters, found ${raw.length}"
            !KEY.matches(raw) -> "label key `$raw` must match ${KEY.pattern}"
            else -> null
        }

    fun valueProblem(raw: String): String? =
        when {
            raw.length > MAX_LENGTH -> "label values must be at most $MAX_LENGTH characters, found ${raw.length}"
            raw.isNotEmpty() && !VALUE.matches(raw) -> "label value `$raw` must match ${VALUE.pattern}"
            else -> null
        }
}
