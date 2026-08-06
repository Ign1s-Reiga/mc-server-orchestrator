package mcorch.cri.it

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mcorch.cri.ContainerState
import mcorch.cri.CriException
import mcorch.cri.CriTimeouts
import mcorch.cri.StopGracePeriod
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What containerd does with a stop whose caller gives up part-way through the
 * grace period.
 *
 * [CriTimeouts.stopDeadlineCap] makes that a routine event rather than a
 * pathology: a grace period longer than the cap is deadlined at the cap, so the
 * call ends while containerd is still waiting. Everything the wrapper says about
 * that — that the runtime is not implicated, that the stop is worth re-issuing,
 * that the cap can only leave a container running *longer* — rests on what the
 * runtime does next, and a fake server cannot answer it: it would simply agree
 * with whatever this module believes.
 *
 * The answer is in containerd's own source and is not the obvious one.
 * `internal/cri/server/container_stop.go` waits out the grace period on a
 * context derived from the request's, and between that wait and the `SIGKILL`
 * that follows it sits `if ctx.Err() != nil { return ctx.Err() }`. So the kill
 * is reached only when the *inner* wait is what expired. A client that gave up
 * first gets no kill at all — the stop signal stands, and nothing escalates.
 *
 * That is the safe direction, and the reason it is safe is that no world is at
 * risk from a container that keeps running. It is also the direction that
 * surprises: a container that ignores the stop signal survives its own grace
 * period, and the thing that finishes it is the caller re-issuing the stop, not
 * the runtime. Both halves are measured below.
 *
 * Not wired into `check`: this needs `scripts/dev/containerd-up.sh` first.
 */
internal class StopDeadlineCapIT {
    @Test
    fun `a stop that outruns its capped deadline is not escalated to a kill by the runtime`() {
        runBlocking {
            RuntimeHarness(CriTimeouts(stopDeadlineCap = CAP, deadlineSlack = SLACK)).use { harness ->
                val container = harness.startSigtermIgnoringContainer()
                val grace = StopGracePeriod.ofSeconds(GRACE.inWholeSeconds).getOrThrow()

                val startedAt = System.nanoTime()
                val thrown =
                    shouldThrow<CriException.Timeout> { harness.client.stopContainer(container, grace) }
                val elapsed = (System.nanoTime() - startedAt).toDouble() / 1_000_000_000.0

                println(
                    "grace=${grace.seconds}s cap=$CAP slack=$SLACK: the call gave up after " +
                        "${"%.2f".format(elapsed)}s",
                )
                // It ended at the cap, nowhere near the grace period.
                withClue("the call outlasted the $GRACE grace period, so the cap did not apply") {
                    (elapsed < GRACE.inWholeSeconds.toDouble()) shouldBe true
                }
                // Retryable, which is what lets the caller re-issue rather than
                // treat a healthy stop as a permanent failure.
                thrown.retryable shouldBe true
                // And the runtime is not implicated by it.
                withClue("the failure does not say the grace period was still running: ${thrown.message}") {
                    thrown.message.contains("grace period this stop asked for") shouldBe true
                }
                harness.state(container) shouldBe ContainerState.RUNNING

                // Past the whole grace period the runtime was asked for, with a
                // margin. Under the old shape — deadline equal to the grace
                // period — containerd would have killed it at the end of this
                // wait. Here the request context died first, so it did not.
                delay(GRACE + KILL_MARGIN)
                val afterGrace = harness.state(container)
                println(
                    "grace=${grace.seconds}s: ${GRACE + KILL_MARGIN} after the stop was issued the container is " +
                        "$afterGrace",
                )
                withClue(
                    "containerd escalated to SIGKILL after the request context expired; the note on " +
                        "CriTimeouts.stopDeadlineCap about container_stop.go is out of date",
                ) {
                    afterGrace shouldBe ContainerState.RUNNING
                }

                // The re-issue is what finishes it, and it is finished by a stop
                // whose grace period fits inside the cap: deadline outlasts the
                // grace period, the inner wait expires first, the kill happens.
                val reissuedAt = System.nanoTime()
                harness.client.stopContainer(container, StopGracePeriod.ofSeconds(1).getOrThrow())
                val reissueElapsed = (System.nanoTime() - reissuedAt).toDouble() / 1_000_000_000.0
                println("re-issued with a 1s grace period: returned after ${"%.2f".format(reissueElapsed)}s")
                harness.state(container) shouldBe ContainerState.EXITED
            }
        }
    }

    internal companion object {
        /**
         * A stand-in for the shipped [CriTimeouts.stopDeadlineCap] of two hours.
         * The property under test is "the deadline is the cap rather than the
         * grace period", which does not depend on the cap's magnitude; waiting
         * out the real one would be a two-hour test.
         */
        private val CAP: Duration = 2.seconds
        private val SLACK: Duration = 2.seconds

        /** Comfortably above `CAP + SLACK`, so the call cannot reach the end of it. */
        private val GRACE: Duration = 12.seconds

        /**
         * How long after the grace period a kill is still waited for. containerd
         * reaching the kill is a signal, a wait and a state update; if it were
         * going to happen it would be inside this.
         */
        private val KILL_MARGIN: Duration = 5.seconds

        @JvmStatic
        @BeforeAll
        fun requireRuntime() {
            RuntimeHarness.requireContainerd()
        }
    }
}
