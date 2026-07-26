package mcorch.core

import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal class BackoffTest {
    @Test
    fun `delays grow exponentially and stop at the cap`() {
        val backoff = Backoff(base = 1.seconds, factor = 2.0, max = 30.seconds, jitter = 0.0)

        backoff.delayFor(1) shouldBe 1.seconds
        backoff.delayFor(2) shouldBe 2.seconds
        backoff.delayFor(3) shouldBe 4.seconds
        backoff.delayFor(10) shouldBe 30.seconds
        backoff.delayFor(1000) shouldBe 30.seconds
    }

    @Test
    fun `jitter stays inside the computed delay`() {
        val backoff =
            Backoff(base = 10.seconds, factor = 2.0, max = 5.minutes, jitter = 0.2, random = Random(7))

        repeat(50) {
            val delay = backoff.delayFor(1)
            delay shouldBeGreaterThan 7.seconds
            delay shouldBeLessThanOrEqualTo 10.seconds
        }
    }

    @Test
    fun `a backoff without jitter is deterministic`() {
        val backoff = Backoff(base = 250.milliseconds, factor = 3.0, max = 1.minutes, jitter = 0.0)

        backoff.delayFor(4) shouldBe backoff.delayFor(4)
        backoff.delayFor(4) shouldBe 6750.milliseconds
    }
}
