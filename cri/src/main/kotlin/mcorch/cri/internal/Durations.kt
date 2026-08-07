package mcorch.cri.internal

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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

/**
 * Whole milliseconds, rounded **up**.
 *
 * A transport deadline is installed on a gRPC call from
 * [Duration.inWholeMilliseconds], which truncates anything finer: a deadline of
 * `300.9ms` is a call that actually gives up at `300ms`. On its own that is
 * nothing. It is not nothing for the two deadlines this module goes on to
 * *measure an elapsed time against* — a stop's and an `ExecSync`'s — because
 * there the comparison would be made against a bound the call never ran under,
 * and a call that gave up on its own deadline would read as one that came back
 * early for some other reason. Which is the whole distinction those two
 * comparisons exist to draw.
 *
 * So the deadline is brought to the resolution it is going to be installed at
 * before it is used for anything, and there is one number rather than two. The
 * gap this closes is under a millisecond and is unreachable with a whole-second
 * configuration, which is every configuration this project ships; it is reachable
 * by anyone who writes a sub-millisecond [mcorch.cri.CriTimeouts.deadlineSlack].
 *
 * Rounded up rather than down for [roundUpToWholeSeconds]'s reason: the value
 * derives from a wait a caller asked for, and no rounding here may shorten it.
 */
internal fun Duration.roundUpToWholeMilliseconds(): Duration {
    val whole = inWholeMilliseconds.milliseconds
    return if (this > whole) whole + 1.milliseconds else whole
}
