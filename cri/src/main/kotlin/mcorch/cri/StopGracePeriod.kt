package mcorch.cri

import mcorch.cri.internal.roundUpToWholeSeconds
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How long containerd waits after the stop signal before it kills the container.
 *
 * There is deliberately no default and no unchecked constructor. Every call to
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
 *
 * ## Bigger is not always safer
 *
 * The intuition the drain protocol reasons with — a longer grace period is the
 * conservative choice — stops holding above [MAX_SECONDS], where the runtime's
 * own arithmetic wraps and a very long grace period becomes a very short one or
 * none at all. That is why construction is checked and why it is checked *here*,
 * at the boundary that renders the value onto the wire, rather than wherever a
 * caller happens to read it from. See [MAX_SECONDS].
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
         * The largest grace period containerd carries out as asked: 9223372036
         * seconds, about 292 years.
         *
         * CRI sends the grace period as `int64` *seconds*
         * (`StopContainerRequest.timeout`) and containerd multiplies it by a
         * billion to get a Go `time.Duration`, which is `int64` *nanoseconds*.
         * Above `(2^63 - 1) / 1e9 = 9223372036.85…` that product does not fit,
         * so it wraps — and containerd neither checks nor reports it. A wrapped
         * value that lands negative makes containerd skip the stop signal
         * entirely and `SIGKILL` immediately; one that lands positive produces a
         * grace period of a few arbitrary seconds. Either way the RPC succeeds:
         * `StopContainerResponse` has no fields, so `{}` is the whole answer and
         * the caller cannot tell the difference.
         *
         * **Measured against containerd 2.3.3** (the release
         * `scripts/dev/containerd-env.sh` pins), on a container whose init
         * process ignores `SIGTERM`, with a client deadline of 12s so a grace
         * period that is really being served shows up as the client giving up
         * first:
         *
         * | `timeout` sent        | what containerd did                       |
         * |-----------------------|-------------------------------------------|
         * | 2147483647            | still waiting at 12s, container `RUNNING`  |
         * | **9223372036**        | still waiting at 12s, container `RUNNING`  |
         * | **9223372037**        | killed it, RPC returned `{}` in 359 ms     |
         * | 18446744073           | killed it, RPC returned `{}` in 1.9 s      |
         * | 18446744083           | **584 years asked, 9.7 s served**          |
         * | 9223372036854775807   | killed it, RPC returned `{}` in 414 ms     |
         *
         * The 18446744083 row is the one to keep in mind, because it is the shape
         * that passes review: the wrap lands 9.29 s past zero
         * (18446744083e9 − 2^64 = 9290448384 ns), the container is signalled,
         * waited for and killed exactly as a stop is supposed to look, and a save
         * that needed longer than nine seconds is gone. `cri/src/integrationTest`
         * re-measures both sides of the boundary against the runtime that is
         * actually installed; if that test fails, containerd's behaviour has
         * changed and this number is what to re-derive.
         *
         * This is a bound on what can be *expressed*, not a policy. How long a
         * given server may take to save is a `:schema` question, and the caps
         * there are far below this.
         */
        public const val MAX_SECONDS: Long = 9_223_372_036L

        /**
         * A grace period of [duration], rounded up to whole seconds.
         *
         * Fails when [duration] is not strictly positive, is not finite, or
         * exceeds [MAX_SECONDS]. Zero is not reachable through this function on
         * purpose; it is spelled [IMMEDIATE_KILL].
         */
        public fun of(duration: Duration): Result<StopGracePeriod> =
            if (!duration.isFinite()) {
                // Checked before rounding: `roundUpToWholeSeconds` on an infinite
                // duration would add one to `Long.MAX_VALUE` and hand the range
                // check a negative number.
                rejected(
                    "must be finite, got: $duration. An unbounded wait is not a safer stop — CRI has no way " +
                        "to express one, and the largest value it can carry is $MAX_SECONDS seconds",
                )
            } else {
                checked(duration.roundUpToWholeSeconds(), "$duration")
            }

        /** A grace period of [wholeSeconds] seconds. Fails under the same conditions as [of]. */
        public fun ofSeconds(wholeSeconds: Long): Result<StopGracePeriod> = checked(wholeSeconds, "${wholeSeconds}s")

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

        /**
         * The one place the range is decided.
         *
         * [asWritten] is the caller's own spelling of the value rather than the
         * rounded seconds, so a rejection names what was passed in.
         */
        private fun checked(
            seconds: Long,
            asWritten: String,
        ): Result<StopGracePeriod> =
            when {
                seconds <= 0L -> {
                    rejected(
                        "must be positive, got: $asWritten. For a zero-grace kill use " +
                            "StopGracePeriod.IMMEDIATE_KILL and be sure the container holds no unsaved world data",
                    )
                }

                seconds > MAX_SECONDS -> {
                    rejected(
                        "must be at most $MAX_SECONDS seconds, got: $asWritten. Above that, containerd's " +
                            "conversion to nanoseconds overflows and the runtime kills the container with little " +
                            "or no grace while still reporting success — a longer grace period is not a safer " +
                            "one past this point. See StopGracePeriod.MAX_SECONDS",
                    )
                }

                else -> {
                    Result.success(StopGracePeriod(seconds))
                }
            }

        private fun rejected(reason: String): Result<StopGracePeriod> =
            Result.failure(IllegalArgumentException("stop grace period $reason"))
    }
}
