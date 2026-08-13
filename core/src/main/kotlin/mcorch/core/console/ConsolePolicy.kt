package mcorch.core.console

import mcorch.schema.ConsoleSpec
import mcorch.schema.Tier

/**
 * Whether a caller may run a command on a server.
 *
 * The second gate of `spec/03-command-policy.md`, sitting above
 * [ConsoleInvariants]. The order matters and is not interchangeable: the first
 * gate refuses what **nobody** may run, and no tier, ceiling or credential
 * reaches past it. This one decides what *this* caller may run of the rest.
 *
 * ## `Superuser` is not allow-listed
 *
 * `spec/04-output.md` settles that the console is general-purpose, and a general
 * console cannot be allow-listed — an allow-list is exactly what stops a Forge
 * mod's command from working. So `Superuser` is bounded by Gate 1 and by nothing
 * else.
 *
 * The two lower tiers keep explicit sets, and an unrecognised command is refused
 * there. Two reasons survive the output decision:
 *
 * - **Mods and plugins register arbitrary commands.** A deny-list develops a
 *   silent hole the moment somebody installs a mod; an allow-list refuses the new
 *   command until somebody adds it deliberately.
 * - **`Member` exists to be safe to hand out**, and one that inherited every mod
 *   command as it was installed would not be.
 *
 * An earlier draft required an allow-list at every tier, on the grounds that
 * output whose shape you have never seen cannot be safely handled. That reason
 * died with the typed-parser design — under a general console, unknown output is
 * the normal case and is returned as-is. What is left is about bounding a *tier*,
 * not about handling output.
 *
 * ## The ceiling clamps, it never grants
 *
 * A server's [ConsoleSpec.maxTier] can only lower what a caller may do. Somebody
 * holding `Member` does not become an `Operator` on a server that permits one.
 */
public object ConsolePolicy {
    /**
     * Commands a `Member` may run: no state changes, and nothing that names a
     * player in its arguments.
     *
     * Output is a different question and is not this gate's — the console returns
     * whatever the server says, so a `Member` running `list` sees names. That is
     * `spec/04-output.md`'s decision, and the tier this server accepts is how an
     * operator bounds it.
     */
    private val MEMBER_COMMANDS: Set<String> =
        setOf("list", "tps", "mspt", "seed", "version", "help", "whitelist")

    /** Everything a [MEMBER_COMMANDS] holder may run, plus gameplay and moderation. */
    private val OPERATOR_COMMANDS: Set<String> =
        MEMBER_COMMANDS +
            setOf(
                "say",
                "tell",
                "msg",
                "me",
                "kick",
                "ban",
                "ban-ip",
                "pardon",
                "pardon-ip",
                "banlist",
                "op",
                "deop",
                "gamemode",
                "give",
                "clear",
                "effect",
                "enchant",
                "experience",
                "xp",
                "kill",
                "spawnpoint",
                "setworldspawn",
                "teleport",
                "tp",
                "time",
                "weather",
                "difficulty",
                "gamerule",
                "save-all",
                "save-on",
            )

    /**
     * Whether [commandLine] may run, for a caller holding [held] against a server
     * declaring [ceiling].
     *
     * Gate 1 first, always. A caller at any tier — including one the ceiling has
     * not lowered — is refused a lifecycle command by the same rule.
     */
    public fun screen(
        commandLine: String,
        held: Tier,
        ceiling: ConsoleSpec,
    ): ConsoleDecision {
        when (val invariant = ConsoleInvariants.screen(commandLine)) {
            is ConsoleScreening.Refused -> return ConsoleDecision.RefusedOutright(invariant)
            ConsoleScreening.Permitted -> Unit
        }
        val effective = held.clampedTo(ceiling.maxTier)
        if (effective == Tier.SUPERUSER) return ConsoleDecision.Permitted(effective)

        val verb =
            verbOf(commandLine) ?: return ConsoleDecision.RefusedOutright(
                ConsoleScreening.Refused("", RefusalReason.UNSCREENABLE),
            )
        val permitted = if (effective == Tier.OPERATOR) OPERATOR_COMMANDS else MEMBER_COMMANDS
        if (verb in permitted) return ConsoleDecision.Permitted(effective)

        // Which tier *would* run it, so a caller is told what is missing rather
        // than only that they lack it. Null when nothing below Superuser holds it,
        // which is the honest answer for a mod's command.
        val required =
            when {
                verb in OPERATOR_COMMANDS -> Tier.OPERATOR
                else -> Tier.SUPERUSER
            }
        return ConsoleDecision.RefusedByTier(effective = effective, required = required, verb = verb)
    }

    /**
     * The commands [held] may run against [ceiling], or null when the effective
     * tier is unrestricted.
     *
     * Null rather than an enormous set: `Superuser` is bounded by Gate 1 alone, so
     * there is no finite list to render. A dashboard reads the null as "offer a
     * free-text prompt" and a set as "offer a picker".
     */
    public fun available(
        held: Tier,
        ceiling: ConsoleSpec,
    ): Set<String>? =
        when (held.clampedTo(ceiling.maxTier)) {
            Tier.SUPERUSER -> null
            Tier.OPERATOR -> OPERATOR_COMMANDS
            Tier.MEMBER -> MEMBER_COMMANDS
        }

    /** The same normalisation Gate 1 uses, so the two gates cannot disagree about what a verb is. */
    private fun verbOf(commandLine: String): String? {
        if (commandLine.any { it == '\n' || it == '\r' }) return null
        val first = commandLine.trim().takeWhile { !it.isWhitespace() }
        return first
            .trimStart('/')
            .substringAfterLast(':')
            .lowercase()
            .ifEmpty { null }
    }
}

/** What [ConsolePolicy] decided. */
public sealed interface ConsoleDecision {
    /** May run, at [effective] — the caller's tier after the server's ceiling. */
    public data class Permitted(
        public val effective: Tier,
    ) : ConsoleDecision

    /** Gate 1. Refused for every caller, whatever their tier. */
    public data class RefusedOutright(
        public val screening: ConsoleScreening.Refused,
    ) : ConsoleDecision

    /**
     * Gate 2. Permitted to somebody, but not to this caller here.
     *
     * [required] is what would run it. [effective] is what the caller has after
     * the server's ceiling, which may be lower than the tier they hold — so a
     * refusal can be the *server's* doing rather than the credential's, and a
     * caller told only "you lack the tier" would go looking in the wrong place.
     */
    public data class RefusedByTier(
        public val effective: Tier,
        public val required: Tier,
        public val verb: String,
    ) : ConsoleDecision
}
