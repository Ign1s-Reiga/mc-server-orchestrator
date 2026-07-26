package mcorch.cri.internal

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Whole seconds, rounded **up**.
 *
 * CRI carries both the container stop grace period and the `ExecSync` command
 * timeout as whole seconds. Truncating would silently shorten a save window or a
 * kill deadline below what the caller asked for, so this always rounds away from
 * zero.
 */
internal fun Duration.roundUpToWholeSeconds(): Long {
    val whole = inWholeSeconds
    return if (this > whole.seconds) whole + 1 else whole
}
