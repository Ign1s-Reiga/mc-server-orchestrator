package mcorch.schema

/**
 * What an operator is allowed to do.
 *
 * In `:schema` rather than in `:store` or `:api` because it is shared vocabulary:
 * an identity carries one, a route requires one, and `spec.console.maxTier` will
 * declare one on a server. A type that three modules name belongs where the other
 * three-module types are.
 *
 * ## Totally ordered, and compared rather than switched on
 *
 * The ordering is what makes `min(identity tier, server ceiling)` meaningful, and
 * it is why [atLeast] exists. **Call sites should ask `tier.atLeast(OPERATOR)`
 * rather than `when (tier)`**: `spec/auth/02-model.md` records Kubernetes' role
 * model as the direction for later work, and a comparison survives a tier becoming
 * a named set of verb-resource pairs where an exhaustive `when` over three
 * constants does not.
 *
 * ## The names
 *
 * [SUPERUSER] is a placeholder, used consistently so that renaming it is one
 * mechanical change rather than a hunt.
 */
public enum class Tier {
    /** Non-destructive operations. Read-only. */
    MEMBER,

    /** Non-destructive operations, plus limited creation and editing. */
    OPERATOR,

    /** Full access. */
    SUPERUSER,

    ;

    /** Whether this tier carries at least [required]'s authority. */
    public fun atLeast(required: Tier): Boolean = ordinal >= required.ordinal

    /**
     * The lesser of two tiers.
     *
     * The effective tier of a request is the identity's, clamped by whatever
     * ceiling applies to what it is acting on.
     */
    public fun clampedTo(ceiling: Tier): Tier = if (ordinal <= ceiling.ordinal) this else ceiling

    /** The wire and on-disk spelling. Lowercase, so a stored value is not a Kotlin detail. */
    public val wireValue: String get() = name.lowercase()

    public companion object {
        /**
         * Parses [raw], or null if it names no tier.
         *
         * Null rather than a default: a stored or declared value this build does
         * not recognise is a row to refuse, not one to guess a privilege level
         * for.
         */
        public fun parse(raw: String): Tier? = entries.firstOrNull { it.wireValue == raw.lowercase() }
    }
}
