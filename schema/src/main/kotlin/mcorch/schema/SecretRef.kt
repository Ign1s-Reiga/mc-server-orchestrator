package mcorch.schema

/**
 * A pointer into the secret store. Coordinates only.
 *
 * There is no field anywhere in this module that can hold secret material. An
 * RCON password, and later the Velocity forwarding secret, are named here and
 * resolved by whoever needs them at the moment they need them. That keeps them
 * out of definition files, out of the store's spec column, out of API responses
 * and out of every `toString` in the system — this type only ever prints the
 * name and the key.
 */
public data class SecretRef(
    val name: ResourceName,
    val key: String,
) {
    public companion object {
        public const val MAX_KEY_LENGTH: Int = 253

        private val KEY = Regex("^[A-Za-z0-9]([-A-Za-z0-9_.]*[A-Za-z0-9])?$")

        /** The key rule in words, the counterpart of [ResourceName.SYNTAX] and public for the same reason. */
        public val KEY_SYNTAX: String =
            "letters, digits, `-`, `_` and `.`, starting and ending alphanumeric, " +
                "at most $MAX_KEY_LENGTH characters"

        /**
         * Appended to a rejection of either coordinate, in place of the usual
         * `` found `…` ``.
         *
         * Both fields are a name and neither should ever hold material — but the
         * mistake that puts material in one of them is exactly the mistake that
         * makes it invalid, and these messages are rendered into log lines and
         * API response bodies. The violation still carries the field path and the
         * source location, so the operator is pointed at their own value without
         * this system repeating it.
         */
        private const val NOT_QUOTED: String =
            "what was written is not repeated here: a coordinate of a secret reference is where " +
                "secret material lands when someone abbreviates the reference away"

        /** Why a name's *syntax* is wrong, without saying what was written. */
        internal val NAME_PROBLEM: String = "must be ${ResourceName.SYNTAX}. $NOT_QUOTED"

        /**
         * Why a name is not usable. Split the same way as [keyProblem]: what an
         * operator wrote is never repeated, but "must not be empty" and a length
         * are facts *about* it that cost nothing to say and are the two answers
         * that save a reader from re-reading the syntax rule.
         */
        internal fun nameProblem(raw: String): String? =
            when {
                raw.isEmpty() -> {
                    "must not be empty"
                }

                raw.length > ResourceName.MAX_LENGTH -> {
                    "must be at most ${ResourceName.MAX_LENGTH} characters, found ${raw.length}"
                }

                ResourceName.problemWith(raw) != null -> {
                    NAME_PROBLEM
                }

                else -> {
                    null
                }
            }

        internal fun keyProblem(raw: String): String? =
            when {
                raw.isEmpty() -> "must not be empty"
                raw.length > MAX_KEY_LENGTH -> "must be at most $MAX_KEY_LENGTH characters, found ${raw.length}"
                !KEY.matches(raw) -> "must match ${KEY.pattern}. $NOT_QUOTED"
                else -> null
            }

        public fun of(
            name: String,
            key: String,
        ): Result<SecretRef> {
            nameProblem(name)?.let { return invalidValue("name $it") }
            keyProblem(key)?.let { return invalidValue("key $it") }
            val resolvedName = ResourceName.of(name).getOrNull() ?: return invalidValue("name $NAME_PROBLEM")
            return Result.success(SecretRef(resolvedName, key))
        }
    }
}
