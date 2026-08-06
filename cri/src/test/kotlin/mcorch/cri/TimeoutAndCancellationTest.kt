package mcorch.cri

import io.grpc.Status
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A hung containerd must not hang the reconcile loop. Every outward call carries
 * a deadline, and cancelling the caller cancels the RPC.
 */
class TimeoutAndCancellationTest {
    @Test
    fun `a hung runtime fails the call with a retryable timeout rather than hanging`() =
        runCriTest {
            val runtime = FakeCriServer.RuntimeBehaviour(hang = true)
            FakeCriServer(runtime = runtime).use { fake ->
                val client = fake.clientWith(CriTimeouts(query = 200.milliseconds))

                val startedAt = System.nanoTime()
                val thrown = shouldThrow<CriException> { client.version() }
                val elapsed = (System.nanoTime() - startedAt) / 1_000_000L

                thrown.shouldBeInstanceOf<CriException.Timeout>()
                thrown.retryable.shouldBeTrue()
                thrown.code shouldBe CriStatusCode.DEADLINE_EXCEEDED
                // Comfortably below the 5s the whole test would otherwise take.
                elapsed shouldBeLessThan 5_000L
            }
        }

    @Test
    fun `every operation carries a deadline`() =
        runCriTest {
            val runtime = FakeCriServer.RuntimeBehaviour(hang = true)
            val images = FakeCriServer.ImageBehaviour()
            FakeCriServer(runtime = runtime, images = images).use { fake ->
                val client =
                    fake.clientWith(
                        CriTimeouts(
                            query = 150.milliseconds,
                            sandboxLifecycle = 150.milliseconds,
                            containerLifecycle = 150.milliseconds,
                            imagePull = 150.milliseconds,
                            imageLifecycle = 150.milliseconds,
                            deadlineSlack = 150.milliseconds,
                        ),
                    )

                // Each of these would block forever without a deadline. withTimeout
                // is the test's own backstop: if any call lacked a deadline the
                // test would fail with a TimeoutCancellationException instead.
                withTimeout(20.seconds) {
                    shouldThrow<CriException.Timeout> { client.version() }
                    shouldThrow<CriException.Timeout> { client.status() }
                    shouldThrow<CriException.Timeout> { client.runSandbox(sampleSandboxSpec()) }
                    shouldThrow<CriException.Timeout> { client.stopSandbox(SandboxId("s")) }
                    shouldThrow<CriException.Timeout> { client.removeSandbox(SandboxId("s")) }
                    shouldThrow<CriException.Timeout> { client.sandboxStatus(SandboxId("s")) }
                    shouldThrow<CriException.Timeout> { client.listSandboxes() }
                    shouldThrow<CriException.Timeout> {
                        client.createContainer(SandboxId("s"), sampleSandboxSpec(), sampleContainerSpec())
                    }
                    shouldThrow<CriException.Timeout> { client.startContainer(ContainerId("c")) }
                    shouldThrow<CriException.Timeout> {
                        client.stopContainer(ContainerId("c"), StopGracePeriod.ofSeconds(1).getOrThrow())
                    }
                    shouldThrow<CriException.Timeout> { client.removeContainer(ContainerId("c")) }
                    shouldThrow<CriException.Timeout> { client.containerStatus(ContainerId("c")) }
                    shouldThrow<CriException.Timeout> { client.listContainers() }
                    shouldThrow<CriException.Timeout> {
                        client.execSync(ContainerId("c"), listOf("true"), 100.milliseconds)
                    }
                    shouldThrow<CriException.Timeout> { client.execStreamUrl(ContainerId("c"), listOf("true")) }
                }
            }
        }

    @Test
    fun `cancelling the caller cancels the rpc and raises CancellationException, not CriException`() =
        runCriTest {
            val runtime = FakeCriServer.RuntimeBehaviour(hang = true)
            FakeCriServer(runtime = runtime).use { fake ->
                val client = fake.clientWith(CriTimeouts(query = 60.seconds))
                val entered = CompletableDeferred<Unit>()

                val outcome =
                    coroutineScope {
                        val call =
                            async {
                                runCatching {
                                    entered.complete(Unit)
                                    client.version()
                                }
                            }
                        entered.await()
                        // Give the RPC a moment to reach the server before cancelling.
                        withTimeout(10.seconds) { runtime.serverReached.await() }
                        call.cancel()
                        runCatching { call.await() }
                    }

                val error = outcome.exceptionOrNull()
                (error is CancellationException).shouldBeTrue()

                // ...and the cancellation reached the server, rather than the
                // client abandoning a call that keeps running.
                withTimeout(10.seconds) { runtime.hangCancelled.await() }
            }
        }

    @Test
    fun `execSync rejects a non-positive timeout instead of running forever`() =
        runCriTest {
            FakeCriServer().use { fake ->
                shouldThrow<IllegalArgumentException> {
                    fake.client.execSync(ContainerId("c"), listOf("save-all", "flush"), 0.seconds)
                }
                shouldThrow<IllegalArgumentException> {
                    fake.client.execSync(ContainerId("c"), listOf("save-all", "flush"), (-1).seconds)
                }
            }
        }

    /**
     * The runtime enforcing the caller's command timeout is not the runtime
     * failing to answer, and the two must not be reported the same way.
     *
     * This is the failure a real containerd 2.3.3 returns when `ExecSync`
     * outruns its timeout: `DEADLINE_EXCEEDED`, promptly, with
     * `failed to exec in container: timeout 10s exceeded: context deadline
     * exceeded`. Reported as an ordinary transport timeout it became
     * `RUNTIME_UNREACHABLE` on a node that was answering perfectly — a Paper
     * server that was still generating its world took longer than ten seconds
     * to answer a Server List Ping, and the runtime got the blame.
     */
    @Test
    fun `a command that outruns its own timeout is not reported as an unreachable runtime`() =
        runCriTest {
            val runtime =
                FakeCriServer.RuntimeBehaviour(
                    failWith =
                        Status.DEADLINE_EXCEEDED.withDescription(
                            "failed to exec in container: timeout 10s exceeded: context deadline exceeded",
                        ),
                )
            FakeCriServer(runtime = runtime).use { fake ->
                // Slack far larger than anything this call can take, so an
                // elapsed time below the transport deadline is unambiguous.
                val client = fake.clientWith(CriTimeouts(deadlineSlack = 60.seconds))

                val thrown =
                    shouldThrow<CriException.Timeout> {
                        client.execSync(ContainerId("c"), listOf("mc-monitor", "status"), 10.seconds)
                    }

                thrown.commandTimeout.shouldBeTrue()
                thrown.retryable.shouldBeTrue()
                // The operator is told which timeout ran out, in seconds...
                thrown.message shouldContain "10s timeout"
                // ...that the node is not the problem...
                thrown.message shouldContain "reachable"
                // ...and what the runtime itself said, not a paraphrase of it.
                thrown.message shouldContain "timeout 10s exceeded"
            }
        }

    /**
     * The deadline of a stop is capped; the grace period it sends is not.
     *
     * Before this, the two were the same number, so a grace period nobody
     * bounded was a call nobody bounded — a definition carrying 30 days of grace
     * parked a reconcile worker for 30 days, and CLAUDE.md's "everything
     * crossing the `:cri` boundary has timeouts" had no owner above two hours.
     * Capping the *grace period* instead cannot fix it: the grace period is half
     * of a schema-validated pair with the save timeout, and shortening it below
     * the save inverts the pair and kills a container mid-save.
     *
     * So both halves are asserted here together, and the second is the one not
     * to relax: whatever the deadline does, `timeout` on the wire stays the whole
     * grace period.
     */
    @Test
    fun `a grace period past the cap is deadlined at the cap and still sends the whole grace period`() =
        runCriTest {
            val runtime = FakeCriServer.RuntimeBehaviour(hang = true)
            FakeCriServer(runtime = runtime).use { fake ->
                val client =
                    fake.clientWith(
                        CriTimeouts(stopDeadlineCap = 200.milliseconds, deadlineSlack = 100.milliseconds),
                    )
                val thirtyDays = 30L * 24 * 60 * 60

                val startedAt = System.nanoTime()
                val thrown =
                    shouldThrow<CriException.Timeout> {
                        client.stopContainer(ContainerId("c"), StopGracePeriod.ofSeconds(thirtyDays).getOrThrow())
                    }
                val elapsed = (System.nanoTime() - startedAt) / 1_000_000L

                elapsed shouldBeLessThan 5_000L
                // Retryable, and this is load-bearing rather than incidental: the
                // caller re-issues the stop, and a permanent classification would
                // turn a slow-but-working stop into a drain that aborts for good.
                thrown.retryable.shouldBeTrue()
                // This client gave up. The runtime did not report anything, so
                // the flag that means "the runtime answered promptly" is false.
                thrown.commandTimeout.shouldBeFalse()
                thrown.message shouldContain "${thirtyDays}s grace period"
                thrown.message shouldContain "nothing shortened it"

                // The half that must not move: containerd was asked for the
                // whole thirty days.
                runtime.lastStopContainer.shouldNotBeNull().timeout shouldBe thirtyDays
            }
        }

    @Test
    fun `a grace period inside the cap still waits the grace period out`() =
        runCriTest {
            val runtime = FakeCriServer.RuntimeBehaviour(hang = true)
            FakeCriServer(runtime = runtime).use { fake ->
                // Cap far above the grace period, so if the deadline were taken
                // from the cap this would wait an hour instead of a second.
                val client =
                    fake.clientWith(CriTimeouts(stopDeadlineCap = 1.hours, deadlineSlack = 200.milliseconds))

                val startedAt = System.nanoTime()
                val thrown =
                    shouldThrow<CriException.Timeout> {
                        client.stopContainer(ContainerId("c"), StopGracePeriod.ofSeconds(1).getOrThrow())
                    }
                val elapsed = (System.nanoTime() - startedAt) / 1_000_000L

                // It waited the grace period out before giving up...
                (elapsed >= 1_000L).shouldBeTrue()
                elapsed shouldBeLessThan 30_000L
                // ...and this is the alarming kind of stop timeout, not the
                // expected one: the deadline outlasted the grace period, so the
                // runtime should have killed the container and did not answer.
                thrown.message shouldNotContain "grace period this stop asked for"
            }
        }

    @Test
    fun `a runtime that stops answering an exec is still reported as a transport timeout`() =
        runCriTest {
            val runtime = FakeCriServer.RuntimeBehaviour(hang = true)
            FakeCriServer(runtime = runtime).use { fake ->
                val client = fake.clientWith(CriTimeouts(deadlineSlack = 100.milliseconds))

                val thrown =
                    shouldThrow<CriException.Timeout> {
                        client.execSync(ContainerId("c"), listOf("mc-monitor", "status"), 100.milliseconds)
                    }

                // Nothing came back before our own deadline, so this is the
                // call giving up — not the runtime reporting a slow command.
                thrown.commandTimeout.shouldBeFalse()
                thrown.retryable.shouldBeTrue()
            }
        }
}
