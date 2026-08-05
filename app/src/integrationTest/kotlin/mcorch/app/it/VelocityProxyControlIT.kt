package mcorch.app.it

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import mcorch.core.EndpointRequest
import mcorch.core.HttpVerb
import mcorch.core.WorkloadObservation
import mcorch.core.WorkloadState
import mcorch.schema.ServerPhase
import mcorch.schema.VelocityProxyDefaults
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * A proxy the reconcile loop created, with a control endpoint that answers only
 * its orchestrator.
 *
 * ## Why a unit test is not enough for this one
 *
 * Two defects reached this repo that every unit test in it agreed with. The
 * planner asked for the control plugin and the node dropped the request one layer
 * down; the plugin's bearer token was sent by `:core` on every call and never put
 * in the container at all. Neither is a logic error — both are something
 * *absent* — and both produce a proxy that comes up perfectly well, serves
 * players, and has no working control channel. The cost is fleet-wide and lands
 * on the day somebody drains a backend: steps 2, 4 and 6 go through that
 * endpoint.
 *
 * So the assertions here are the ones a fake cannot make. A real Velocity, from
 * an image nobody in this repo controls, loading a JAR that arrived only because
 * the loop asked a node for it — and refusing a request that does not carry the
 * token the loop was never previously required to deliver.
 *
 * A unit test asserting "the planner emits a mount" is necessary and was not
 * sufficient: that assertion passed throughout.
 */
@Timeout(value = 12, unit = TimeUnit.MINUTES)
internal class VelocityProxyControlIT {
    @TempDir
    lateinit var root: Path

    private lateinit var harness: ContainerdHarness

    @BeforeEach
    fun open() {
        harness = ContainerdHarness(root)
    }

    @AfterEach
    fun cleanUp() {
        harness.close()
    }

    @Test
    fun `a proxy the loop created comes up with its control plugin loaded and authenticated`() =
        integrationTest {
            val definition = velocityProxy(name = "it-proxy")
            val name = definition.metadata.name
            val token = generatedSecret()
            // Both are material this run generated. Nothing in the repository
            // holds either, and the definition carries only coordinates.
            harness.putSecret(forwardingSecret("it-proxy"), generatedSecret())
            harness.putSecret(controlToken("it-proxy"), token)
            harness.declare(definition)
            harness.start(this)

            harness.await("the proxy container to be running") {
                (harness.observe(name) as? WorkloadObservation.Present)?.state == WorkloadState.RUNNING
            }

            // **Defect 1, end to end.** `control.reachable` is written from a real
            // `GET /v1/version` against the plugin's own HTTP server, which exists
            // only if a JAR arrived in the image's plugin directory — and the only
            // thing that put one there is the asset the planner asked a node for.
            // Nothing in this test mounts anything by hand; the previous run of
            // this scenario had to, which is how the defect was found.
            harness.await("the control endpoint to answer") {
                harness.proxyStatus(name)?.control?.reachable == true
            }

            val status = harness.proxyStatus(name).shouldNotBeNull()
            val control = status.control.shouldNotBeNull()
            control.reachable.shouldBeTrue()
            // And it speaks a protocol this build knows, which is the plugin
            // reporting its own version rather than anything inferred here.
            control.compatible.shouldBeTrue()
            control.pluginApiVersion.shouldNotBeNull()

            // **Defect 2.** The endpoint refuses a request with no credential.
            // That is only true if `MCORCH_CONTROL_TOKEN` reached the container:
            // the plugin treats an absent token as "no authentication required"
            // and answers 200 to anyone, which is exactly what a proxy created by
            // this loop used to do while `:core` politely sent a bearer token it
            // ignored.
            val handle = harness.observe(name).shouldBeInstanceOf<WorkloadObservation.Present>().handle
            val port = definition.spec.control.port
            val node = harness.node()

            val anonymous =
                node.callEndpoint(
                    handle,
                    EndpointRequest(
                        port = port,
                        verb = HttpVerb.GET,
                        path = "/v1/state",
                        bearerToken = null,
                        timeout = CALL_TIMEOUT,
                    ),
                )
            anonymous.status shouldBe UNAUTHENTICATED
            anonymous.successful.shouldBeFalse()

            // The control for that negative: the *same* request with the token the
            // definition names is served. Without this pair, a 401 could mean the
            // route does not exist, or the port is wrong, or the plugin is a
            // different build — none of which is the claim.
            val authenticated =
                node.callEndpoint(
                    handle,
                    EndpointRequest(
                        port = port,
                        verb = HttpVerb.GET,
                        path = "/v1/state",
                        bearerToken = definition.spec.control.tokenSecret,
                        timeout = CALL_TIMEOUT,
                    ),
                )
            authenticated.status shouldBe OK
            // A real routing table, read out of the running proxy.
            authenticated.body shouldContain "backends"

            // And the third correction, which the two above cannot see: readiness
            // is a Server List Ping against `spec.network.port`, so a proxy that
            // reports ready is a proxy whose declared player port is the one
            // Velocity actually bound. The default used to be BungeeCord's 25577,
            // against which this never becomes ready.
            harness.await("the proxy to answer a Server List Ping") {
                harness.proxyStatus(name)?.ready == true
            }
            val ready = harness.proxyStatus(name).shouldNotBeNull()
            ready.phase shouldBe ServerPhase.RUNNING
            ready.endpoint.shouldNotBeNull().port shouldBe VelocityProxyDefaults.PLAYER_PORT
        }

    private companion object {
        /** Generous: this crosses a container boundary to a JDK HTTP server. */
        private val CALL_TIMEOUT = 15.seconds

        private const val OK: Int = 200
        private const val UNAUTHENTICATED: Int = 401
    }
}
