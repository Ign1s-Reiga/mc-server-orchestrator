package mcorch.cri

import mcorch.cri.internal.roundUpToWholeSeconds
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How long containerd waits after the stop signal before it kills the container.
 *
 * There is deliberately no default and no `Duration` overload. Every call to
 * [CriClient.stopContainer] has to name a grace period, because for a Minecraft
 * server the wrong one loses a world:
 *
 * - The grace period is the **last-resort safety net**, not the save path. The
 *   world is saved and confirmed saved *before* the stop is issued — see
 *   `.claude/skills/drain-protocol/`.
 * - It must still exceed the maximum expected save duration, so that a container
 *   which somehow reaches the stop with unsaved state gets a chance to flush
 *   rather than being killed mid-write. A kill during a region-file write
 *   corrupts the region.
 *
 * CRI carries this as whole seconds, so [of] rounds **up**: a 90.5s grace period
 * becomes 91s, never 90s.
 */
@JvmInline
public value class StopGracePeriod private constructor(
    /** The grace period in whole seconds, as CRI carries it. */
    public val seconds: Long,
) {
    /** The grace period as a [Duration]. Always a whole number of seconds. */
    public val duration: Duration get() = seconds.seconds

    override fun toString(): String = if (seconds == 0L) "IMMEDIATE_KILL" else "${seconds}s"

    public companion object {
        /**
         * A grace period of [duration], rounded up to whole seconds.
         *
         * @throws IllegalArgumentException if [duration] is not strictly
         *   positive. Zero is not reachable through this function on purpose;
         *   it is spelled [IMMEDIATE_KILL].
         */
        public fun of(duration: Duration): StopGracePeriod {
            require(duration.isPositive()) {
                "stop grace period must be positive; for a zero-grace kill use StopGracePeriod.IMMEDIATE_KILL " +
                    "and be sure the container holds no unsaved world data"
            }
            require(duration.isFinite()) { "stop grace period must be finite, got: $duration" }
            return StopGracePeriod(duration.roundUpToWholeSeconds())
        }

        /** A grace period of [wholeSeconds] seconds. @throws IllegalArgumentException if not positive. */
        public fun ofSeconds(wholeSeconds: Long): StopGracePeriod = of(wholeSeconds.seconds)

        /**
         * Kill immediately, with no grace at all (CRI timeout `0`).
         *
         * Only legitimate for a container that provably holds no unsaved state:
         * a sandbox scaffold, a container that never started, a disposable
         * minigame instance with no persistent world. Never for a server with
         * world data, and never as a way to make a stuck drain finish —
         * reaching a retry limit is not a reason to force-stop.
         *
         * Named rather than defaulted so that every use is greppable and shows
         * up in a drain audit.
         */
        public val IMMEDIATE_KILL: StopGracePeriod = StopGracePeriod(0)
    }
}
