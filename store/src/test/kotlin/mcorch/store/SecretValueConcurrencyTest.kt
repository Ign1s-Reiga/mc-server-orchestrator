package mcorch.store

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A [SecretValue] is shared, and [SecretValue.destroy] is now on the hot path: the
 * reconcile loop resolves a secret, uses it, and destroys it in a `finally`. So a
 * `use` on one thread and a `destroy` on another overlap routinely rather than
 * theoretically.
 *
 * The property under test is that a caller is never handed material that is part
 * real and part wipe characters. A half-wiped copy would not throw — it would
 * quietly produce a wrong RCON password, and the failure would surface as an
 * authentication error with no explanation attached to it.
 */
class SecretValueConcurrencyTest {
    private val threads = Executors.newFixedThreadPool(THREADS)

    @AfterEach
    fun shutDown() {
        threads.shutdownNow()
        threads.awaitTermination(10, TimeUnit.SECONDS) shouldBe true
    }

    @Test
    fun `destroy is idempotent and safe to repeat`() {
        val value = SecretValue.random(32)

        value.destroy()
        value.destroy()
        value.destroy()

        value.isDestroyed shouldBe true
        runCatching { value.use { it.size } }.isFailure shouldBe true
    }

    @Test
    fun `destroying from many threads at once is safe`() {
        // Deterministic: whatever the interleaving, no call may throw and the value must
        // end up destroyed. `:core` destroys in a `finally`, and a pass that fails part
        // way through can reach that `finally` while another already has.
        repeat(ROUNDS) {
            val value = SecretValue.random(MATERIAL_LENGTH)
            val barrier = CyclicBarrier(THREADS)

            val failures =
                (1..THREADS)
                    .map {
                        threads.submit(
                            Callable<Throwable?> {
                                barrier.await()
                                runCatching { value.destroy() }.exceptionOrNull()
                            },
                        )
                    }.mapNotNull { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                    .map { it.toString() }

            failures shouldContainExactly emptyList()
            value.isDestroyed shouldBe true
        }
    }

    @Test
    fun `a use racing a destroy gets an intact copy or nothing at all`() {
        // Honest about its own nature: the *outcome* is deterministic — every assertion
        // here can only fail if a partly wiped copy really was handed out, so the test
        // cannot go flaky in the other direction — but *catching* a regression is
        // probabilistic, because the wipe has to land inside a copy. The shape below is
        // what makes that likely rather than lucky: material large enough that copying it
        // takes real time, readers hammering `use` in a loop rather than trying once, and
        // a destroy that fires after they are already running. Measured against the
        // unsynchronised implementation this replaces, it caught it in every run.
        val readers = THREADS - 1
        var intact = 0
        var refused = 0

        repeat(ROUNDS) {
            val value = SecretValue.random(MATERIAL_LENGTH)
            val barrier = CyclicBarrier(THREADS)

            // Uncontended, before anything can destroy it: this must come back whole. It
            // also keeps the count below from passing vacuously if every racing reader
            // happens to arrive after the destroy.
            wipeCharacters(value.use { it.copyOf() }) shouldBe 0
            intact++

            val reading =
                (1..readers).map {
                    threads.submit(
                        Callable {
                            barrier.await()
                            readUntilDestroyed(value)
                        },
                    )
                }
            val destroying =
                threads.submit(
                    Callable {
                        barrier.await()
                        // Long enough for the readers to be inside their loop, short enough
                        // that the round stays sub-millisecond.
                        spin(HEAD_START_NANOS)
                        value.destroy()
                    },
                )

            for (outcome in reading.map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }) {
                // A copy that was handed out has to be the whole thing. Any wipe character
                // in one is the defect: a few of them mean the copy overlapped a wipe in
                // progress, all of them mean `use` was allowed through after the wipe had
                // finished. Both hand a caller material that is not the secret.
                if (outcome.worstCopy != 0) {
                    throw AssertionError(
                        "use handed out a wiped copy: ${outcome.worstCopy} of $MATERIAL_LENGTH characters were zeroed",
                    )
                }
                intact += outcome.copies
                refused += outcome.refusals
            }
            destroying.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            value.isDestroyed shouldBe true
        }

        // Both paths were exercised: copies really were handed out, and calls really were
        // refused once the value was gone.
        (intact >= ROUNDS) shouldBe true
        (refused >= ROUNDS) shouldBe true
    }

    /**
     * Copies [value] over and over until it refuses, reporting the most wiped copy it
     * was ever handed.
     *
     * Bounded rather than `while (true)`: an implementation that never publishes the
     * destroy should fail this test, not hang it.
     */
    private fun readUntilDestroyed(value: SecretValue): Reading {
        var copies = 0
        var worst = 0
        repeat(MAX_ATTEMPTS) {
            try {
                worst = maxOf(worst, value.use { copy -> wipeCharacters(copy) })
                copies++
            } catch (refusal: IllegalStateException) {
                check(refusal.message?.contains("destroyed") == true) { "unexpected refusal: $refusal" }
                return Reading(copies = copies, refusals = 1, worstCopy = worst)
            }
        }
        return Reading(copies = copies, refusals = 0, worstCopy = worst)
    }

    private data class Reading(
        val copies: Int,
        val refusals: Int,
        val worstCopy: Int,
    )

    /**
     * How many characters of [copy] are wipe characters.
     *
     * `SecretValue.random` draws from an alphanumeric alphabet, so intact material
     * contains none of them and any count above zero means the copy overlapped a wipe.
     */
    private fun wipeCharacters(copy: CharArray): Int = copy.count { it.code == 0 }

    private fun spin(nanos: Long) {
        val until = System.nanoTime() + nanos
        while (System.nanoTime() < until) {
            Thread.onSpinWait()
        }
    }

    private companion object {
        const val THREADS: Int = 4

        /** Long enough that copying it and wiping it take long enough to overlap. */
        const val MATERIAL_LENGTH: Int = 32_768

        const val ROUNDS: Int = 200

        /** How long the destroyer lets the readers run before it wipes. */
        const val HEAD_START_NANOS: Long = 100_000

        /** A reader that is never refused is a bug; this bounds it instead of hanging. */
        const val MAX_ATTEMPTS: Int = 5_000

        const val TIMEOUT_SECONDS: Long = 30
    }
}
