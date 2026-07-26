package mcorch.cri

import io.grpc.Status
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import mcorch.cri.logging.CapturedLogs
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * What a failing CRI call is allowed to write to a log.
 *
 * A runtime's error description is a third party's free-form string. It is the
 * most useful thing this client can report and it is also the one thing here
 * that nobody controls the contents of — Go's `fmt.Errorf("...: %+v", config)`
 * habit means a rejected request can come back with the request inside it, and
 * for `CreateContainer` the request is where the RCON password and the Velocity
 * forwarding secret live (CLAUDE.md invariant 4).
 *
 * These tests read back what was actually logged rather than what the code
 * looks like it logs, so a redaction that is bypassed by a later refactor — or
 * that holds at WARN and leaks at DEBUG — fails here.
 */
class FailureLoggingTest {
    @BeforeEach
    fun clearLogs() {
        CapturedLogs.clear()
    }

    /** Shaped like a Go error that rendered the request it rejected. */
    private fun errorQuotingTheRequest(): Status =
        Status.INVALID_ARGUMENT.withDescription(
            "failed to create containerd container: invalid config: " +
                "&ContainerConfig{Envs:[]*KeyValue{&KeyValue{Key:EULA,Value:TRUE,}," +
                "&KeyValue{Key:RCON_PASSWORD,Value:$SECRET,},},}",
        )

    @Test
    fun `a create failure quoting the request does not put the request in the log`() =
        runCriTest {
            val runtime = FakeCriServer.RuntimeBehaviour(failWith = errorQuotingTheRequest())
            FakeCriServer(runtime = runtime).use { fake ->
                shouldThrow<CriException> {
                    fake.client.createContainer(SandboxId("s"), sampleSandboxSpec(), sampleContainerSpec())
                }

                val logged = CapturedLogs.text()
                logged shouldNotContain SECRET
                logged shouldNotContain "RCON_PASSWORD"
                // The failure is still reported, and still says which call and
                // how it was classified — only the runtime's prose is held back.
                logged shouldContain "op=CREATE_CONTAINER"
                logged shouldContain "code=INVALID_ARGUMENT"
                logged shouldContain "not logged"
            }
        }

    @Test
    fun `an image pull failure does not put registry credentials in the log`() =
        runCriTest {
            val images =
                FakeCriServer.ImageBehaviour(
                    failWith =
                        Status.UNAUTHENTICATED.withDescription(
                            "failed to pull: auth=&AuthConfig{Username:ci,Password:$SECRET,}",
                        ),
                )
            FakeCriServer(images = images).use { fake ->
                shouldThrow<CriException> {
                    fake.client.pullImage(ImageName("docker.io/itzg/minecraft-server:2026.6.1"))
                }

                val logged = CapturedLogs.text()
                logged shouldNotContain SECRET
                logged shouldContain "op=PULL_IMAGE"
            }
        }

    @Test
    fun `a sandbox failure does not put the sandbox config in the log`() =
        runCriTest {
            val runtime = FakeCriServer.RuntimeBehaviour(failWith = errorQuotingTheRequest())
            FakeCriServer(runtime = runtime).use { fake ->
                shouldThrow<CriException> { fake.client.runSandbox(sampleSandboxSpec()) }

                CapturedLogs.text() shouldNotContain SECRET
            }
        }

    /**
     * The counterpart. Withholding everything would have thrown away the line
     * that identified the real cause of a whole misdiagnosed integration stall,
     * so an operation whose request holds no secret still reports what the
     * runtime said.
     */
    @Test
    fun `an exec failure still says what the runtime said`() =
        runCriTest {
            val runtime =
                FakeCriServer.RuntimeBehaviour(
                    failWith =
                        Status.DEADLINE_EXCEEDED.withDescription(
                            "failed to exec in container: timeout 10s exceeded: context deadline exceeded",
                        ),
                )
            FakeCriServer(runtime = runtime).use { fake ->
                shouldThrow<CriException> {
                    fake.client.execSync(ContainerId("c"), listOf("mc-monitor", "status"), 10.seconds)
                }

                CapturedLogs.text() shouldContain "timeout 10s exceeded"
            }
        }

    @Test
    fun `a runtime that answers with an unbounded description is truncated`() =
        runCriTest {
            val flood = "x".repeat(20_000)
            val runtime =
                FakeCriServer.RuntimeBehaviour(
                    failWith = Status.INTERNAL.withDescription("snapshotter failed: $flood"),
                )
            FakeCriServer(runtime = runtime).use { fake ->
                shouldThrow<CriException> { fake.client.containerStatus(ContainerId("c")) }

                val logged = CapturedLogs.text()
                // Bounded, and honest about having been bounded...
                logged.length shouldBeLessThan 2_000
                logged shouldContain "more characters not logged"
                // ...while still leading with the part worth reading.
                logged shouldContain "snapshotter failed"
            }
        }

    /**
     * The redaction is driven off one list, and this is the list. If a new RPC
     * is added to [CriOperation] the `when` behind this stops compiling, which
     * is the point of writing it out longhand there.
     */
    @Test
    fun `only the operations whose requests hold secrets withhold their description`() {
        CriOperation.entries.filter { it.requestMayCarrySecrets } shouldBe
            listOf(CriOperation.PULL_IMAGE, CriOperation.RUN_SANDBOX, CriOperation.CREATE_CONTAINER)
    }

    private companion object {
        /**
         * Stands in for an RCON password. Not a real credential and never one:
         * a test fixture is exactly the kind of file a real secret gets
         * committed into and then lives in forever.
         */
        const val SECRET = "not-a-real-password-9f3c1e"
    }
}
