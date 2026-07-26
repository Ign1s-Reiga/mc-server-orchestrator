package mcorch.core

import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Exponential backoff with jitter, for requeueing a server after a retryable
 * failure.
 *
 * Jitter is not decoration: without it, a containerd restart makes every server
 * retry in lockstep forever, and the first retry after recovery is a thundering
 * herd against the runtime. [random] is injectable so tests are deterministic.
 */
public class Backoff(
    private val base: Duration = DEFAULT_BASE,
    private val factor: Double = DEFAULT_FACTOR,
    private val max: Duration = DEFAULT_MAX,
    /** Fraction of the computed delay that is randomised away, in `0.0..1.0`. */
    private val jitter: Double = DEFAULT_JITTER,
    private val random: Random = Random.Default,
) {
    init {
        require(base.isPositive()) { "base must be positive, got: $base" }
        require(factor >= 1.0) { "factor must be at least 1.0, got: $factor" }
        require(max >= base) { "max ($max) must not be below base ($base)" }
        require(jitter in 0.0..1.0) { "jitter must be in 0.0..1.0, got: $jitter" }
    }

    /**
     * How long to wait before attempt number [attempt].
     *
     * [attempt] is 1-based: the first retry waits [base] (minus jitter), the
     * second [base] × [factor], and so on up to [max].
     */
    public fun delayFor(attempt: Int): Duration {
        require(attempt >= 1) { "attempt must be at least 1, got: $attempt" }
        val exponent = (attempt - 1).coerceAtMost(MAX_EXPONENT)
        val scaled = base * Math.pow(factor, exponent.toDouble())
        val capped = if (scaled > max) max else scaled
        if (jitter == 0.0) return capped
        val reduction = capped * (jitter * random.nextDouble())
        return capped - reduction
    }

    public companion object {
        public val DEFAULT_BASE: Duration = 1.seconds
        public const val DEFAULT_FACTOR: Double = 2.0
        public val DEFAULT_MAX: Duration = 5.minutes
        public const val DEFAULT_JITTER: Double = 0.2

        /** Beyond this the delay is capped anyway, and `2^n` stops being representable. */
        private const val MAX_EXPONENT: Int = 32
    }
}
