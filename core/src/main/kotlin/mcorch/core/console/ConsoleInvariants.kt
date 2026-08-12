package mcorch.core.console

/**
 * The console commands no caller may run, whatever their tier.
 *
 * This is the first of the two gates in `spec/03-command-policy.md`, and it is
 * not a permission. The second gate — an allow-list per tier — decides what a
 * given operator may do; this one decides what *nobody* may do, and it is
 * checked first so that no tier, token or per-server ceiling can reach past it.
 *
 * ## Why a console needs this at all
 *
 * `RouteTableTest` asserts that no route pattern contains `stop`, `kill`,
 * `force` or `purge`, and says why: *"one that could stop a container directly
 * could stop one with players on it."* A route named `/console` satisfies that
 * test while carrying `stop` in its body, so the guard the route table provides
 * has to be re-established here or the console reintroduces exactly the thing it
 * was written to prevent — the same unconditional stop, spelled differently and
 * routed through an operator's keyboard.
 *
 * Two of the repository's non-negotiable invariants are at stake:
 *
 * - **`stop` ends the container with players on it.** Stopping goes through the
 *   drain protocol, always. The declarative path — deleting the definition, which
 *   makes the loop drain and *then* stop — is the only way a server ends.
 * - **`save-off` disables world saving silently.** Nothing surfaces it: the
 *   server keeps running and looks healthy, and the damage appears later when a
 *   drain's `save-all flush` confirms a save that wrote nothing.
 *
 * Note which command is *not* here. `save-all` is permitted, because it is what
 * the drain itself runs. Its dangerous neighbour sits at the same Minecraft op
 * level, which is why op level cannot express this gate — see
 * `spec/03-command-policy.md` §1.2.
 *
 * ## Defence in depth, not the primary guard
 *
 * The allow-list is the primary guard: a command that is not on it never runs,
 * so an unknown plugin or mod verb is refused by default. This set exists for
 * the case where something reaches execution anyway — an allow-list entry added
 * carelessly, a plugin aliasing a permitted verb onto a lifecycle command.
 *
 * It therefore refuses on the *normalised* verb and errs toward refusing too
 * much. Over-refusing costs an operator one command; under-refusing costs a
 * world.
 */
public object ConsoleInvariants {
    /** Verbs that end the server process. Vanilla `stop`, plus the Paper/Spigot restart family. */
    private val ENDS_THE_SERVER: Set<String> = setOf("stop", "restart", "shutdown", "halt")

    /** Verbs that stop the world reaching disk. `save-on` is absent on purpose: it is the repair. */
    private val DISABLES_WORLD_SAVING: Set<String> = setOf("save-off")

    /**
     * Whether [commandLine] may be dispatched at all.
     *
     * Fails closed: a line this cannot reduce to exactly one verb is
     * [RefusalReason.UNSCREENABLE] rather than permitted, because a guard that
     * waves through what it could not read is not a guard.
     */
    public fun screen(commandLine: String): ConsoleScreening {
        val verb = verbOf(commandLine) ?: return ConsoleScreening.Refused("", RefusalReason.UNSCREENABLE)
        return when (verb) {
            in ENDS_THE_SERVER -> ConsoleScreening.Refused(verb, RefusalReason.ENDS_THE_SERVER)
            in DISABLES_WORLD_SAVING -> ConsoleScreening.Refused(verb, RefusalReason.DISABLES_WORLD_SAVING)
            else -> ConsoleScreening.Permitted
        }
    }

    /**
     * The leading verb of [commandLine], normalised, or `null` when there is not
     * exactly one to read.
     *
     * Every step here closes a way of writing the same command:
     *
     * - a line carrying a newline is rejected outright — it may hold a second
     *   command, and this cannot know which one matters. The API rejects those
     *   before they arrive; this does not rely on that;
     * - leading `/` is stripped, so `/stop` and `stop` are one verb. All of them
     *   are stripped, which also folds `//stop`;
     * - the namespace is dropped, so Brigadier's `minecraft:stop` cannot walk
     *   past a set that only knows `stop`;
     * - case is folded. `String.lowercase()` is locale-independent, so this does
     *   not acquire the Turkish dotless-i bug that `lowercase(Locale.getDefault())`
     *   would.
     */
    private fun verbOf(commandLine: String): String? {
        if (commandLine.any { it == '\n' || it == '\r' }) return null
        val firstToken = commandLine.trim().takeWhile { !it.isWhitespace() }
        val verb = firstToken.trimStart('/').substringAfterLast(':').lowercase()
        return verb.ifEmpty { null }
    }
}

/** The outcome of [ConsoleInvariants.screen]. */
public sealed interface ConsoleScreening {
    /** Not refused *here*. The tier gate and the allow-list have not run yet. */
    public data object Permitted : ConsoleScreening

    /**
     * Refused for every caller.
     *
     * [verb] is the normalised form that matched, so a caller is told what was
     * read rather than what was typed — empty when nothing could be read.
     */
    public data class Refused(
        public val verb: String,
        public val reason: RefusalReason,
    ) : ConsoleScreening
}

/**
 * Why a command is refused to everyone.
 *
 * Deliberately carries no operator-facing message and no remedy: the remedy is
 * an HTTP path, and `:core` does not know about those. `:api` maps these onto a
 * message and the declarative endpoint to use instead.
 */
public enum class RefusalReason {
    /** Ends the server process. Stopping a server goes through the drain. */
    ENDS_THE_SERVER,

    /** Stops the world reaching disk, with nothing to surface that it happened. */
    DISABLES_WORLD_SAVING,

    /** The line could not be reduced to one verb, so nothing about it can be trusted. */
    UNSCREENABLE,
}
