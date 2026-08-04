package mcorch.api

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import mcorch.schema.BackendRegistration
import mcorch.schema.BackendRoutingStatus
import mcorch.schema.BackendStatus
import mcorch.schema.ConditionStatus
import mcorch.schema.ConditionType
import mcorch.schema.ControlEndpointStatus
import mcorch.schema.DrainBlock
import mcorch.schema.DrainBlockReason
import mcorch.schema.DrainState
import mcorch.schema.DrainStatus
import mcorch.schema.PlayerOccupancy
import mcorch.schema.ResourceName
import mcorch.schema.ServerPhase
import mcorch.schema.StatusCondition
import mcorch.schema.VelocityProxyStatus
import mcorch.schema.fixtures.ExampleDefinitions
import mcorch.schema.getOrThrow
import mcorch.store.getOrThrow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import java.time.Instant

/**
 * A `VelocityProxy` over the wire, end to end.
 *
 * These branches used to throw. That was defensible while `:store` refused the
 * kind — nothing readable could be one — but the moment a proxy can be stored, a
 * single declared proxy takes out the fleet list and every open event stream,
 * because both read every row. So the first thing this file establishes is that
 * a proxy renders at all; the rest is what it renders.
 *
 * The four shapes covered are the ones an operator actually meets: a proxy
 * nothing has looked at yet, one routing to a mix of backends, one whose control
 * endpoint is not answering, and one mid-drain.
 */
class VelocityProxyRenderingTest {
    private lateinit var api: TestApi

    @BeforeEach
    fun start() {
        api = TestApi.start()
    }

    @AfterEach
    fun stop() {
        api.close()
    }

    private val at: Instant = Instant.parse("2026-08-05T10:20:00Z")

    private fun createProxy(example: String = "proxy-minimal.yaml"): TestApi.Reply =
        api.call("POST", "/api/v1/servers", ExampleDefinitions.valid(example))

    @Test
    fun `a proxy definition round-trips through create, read and replace`() {
        val created = createProxy("proxy-full.yaml")
        created.status shouldBe 201
        val etag = created.header("ETag").shouldNotBeNull()

        val document = api.call("GET", "/api/v1/servers/proxy-02").json()
        document["kind"] shouldBe "VelocityProxy"
        document["name"] shouldBe "proxy-02"

        val spec = (document["definition"] as Map<*, *>)["spec"] as Map<*, *>
        spec["maxPlayers"] shouldBe 2000
        (spec["forwarding"] as Map<*, *>)["mode"] shouldBe "modern"
        ((spec["backends"] as Map<*, *>)["selector"] as Map<*, *>)["matchLabels"] shouldBe
            mapOf("mcorch.dev/fleet" to "main", "tier" to "survival")
        (spec["backends"] as Map<*, *>)["fallback"] shouldBe listOf("lobby-01", "lobby-02")
        (spec["control"] as Map<*, *>)["port"] shouldBe 8375
        (spec["lifecycle"] as Map<*, *>)["stopGracePeriod"] shouldBe "1m30s"

        // No `storage`, and not as an omission: a proxy holds no world, and a null
        // storage block would invite "not persistent yet" from an absence.
        spec.containsKey("storage") shouldBe false

        // What comes out is valid input, unchanged — the same property the Paper
        // kind has. An unchanged generation is the proof: the store only moves one
        // when the spec differs.
        val replayed = Dump(DumpSettings.builder().build()).dumpToString(document["definition"])
        val replaced =
            api.call("PUT", "/api/v1/servers/proxy-02", replayed, headers = listOf("If-Match" to etag))
        replaced.status shouldBe 200
        ((replaced.json()["metadata"] as Map<*, *>)["generation"]) shouldBe 1
    }

    @Test
    fun `a proxy nothing has looked at yet is PENDING, not UNKNOWN`() {
        // It rendered as UNKNOWN before, and not because anything was unknown: the
        // renderer cast to PaperServerStatus and a failed cast is indistinguishable
        // from no observation.
        createProxy().status shouldBe 201

        val document = api.call("GET", "/api/v1/servers/proxy-01").json()
        val display = document["display"] as Map<*, *>
        display["state"] shouldBe "PENDING"
        document["neverObserved"] shouldBe true
        document["status"] shouldBe null

        // The declared maximum, which used to be reachable only for a Paper spec.
        display["playersMax"] shouldBe 500
        display["proxy"] shouldBe null
    }

    @Test
    fun `a proxy routing to a mix of backends renders every one of them`() {
        createProxy().status shouldBe 201
        observe(
            backends =
                BackendRoutingStatus(
                    observedAt = at,
                    backends =
                        listOf(
                            backend("survival-01", BackendRegistration.REGISTERED, online = 12),
                            backend("survival-02", BackendRegistration.SEALED, online = 3, draining = true),
                            backend("lobby-01", BackendRegistration.DEREGISTERED),
                            backend("survival-03", BackendRegistration.UNREACHABLE),
                            backend("survival-04", BackendRegistration.PENDING),
                        ),
                ),
            control = ControlEndpointStatus(reachable = true, pluginApiVersion = "1", compatible = true),
            backendsResolved = true,
        )

        val document = api.call("GET", "/api/v1/servers/proxy-01").json()
        val routing = (document["status"] as Map<*, *>)["backends"] as Map<*, *>

        routing["matched"] shouldBe 5
        // REGISTERED and SEALED are both in the routing table.
        routing["registered"] shouldBe 2
        // Only one may receive a transfer: the sealed one is draining.
        routing["destinations"] shouldBe 1

        @Suppress("UNCHECKED_CAST")
        val backends = routing["backends"] as List<Map<String, Any?>>
        backends.map { it["registration"] } shouldContainAll
            listOf("REGISTERED", "SEALED", "DEREGISTERED", "UNREACHABLE", "PENDING")

        val sealed = backends.single { it["server"] == "survival-02" }
        sealed["drainInitiated"] shouldBe true
        sealed["eligibleAsDestination"] shouldBe false
        // Counts, and only counts. A proxy sees every player in the fleet.
        (sealed["players"] as Map<*, *>).keys.map { it.toString() }.sorted() shouldBe
            listOf("max", "observedAt", "online")

        val display = document["display"] as Map<*, *>
        display["state"] shouldBe "READY"
        (display["proxy"] as Map<*, *>)["backendsRegistered"] shouldBe 2
        (display["proxy"] as Map<*, *>)["controlReachable"] shouldBe true
    }

    @Test
    fun `a selector that matched nothing is not the same as never having looked`() {
        // The distinction `:store` established and this must not flatten. One is
        // "no observation yet" and resolves itself; the other is a live condition
        // an operator has to fix, and it is the answer to "why can nobody join".
        createProxy().status shouldBe 201
        observe(
            backends = BackendRoutingStatus(observedAt = at, backends = emptyList()),
            control = ControlEndpointStatus(reachable = true, pluginApiVersion = "1", compatible = true),
            backendsResolved = false,
        )

        val document = api.call("GET", "/api/v1/servers/proxy-01").json()
        val routing = (document["status"] as Map<*, *>)["backends"] as Map<*, *>
        routing["matched"] shouldBe 0
        routing["backends"] shouldBe emptyList<Any>()

        val display = document["display"] as Map<*, *>
        // Accepting players and routing them nowhere. READY would be a green badge
        // on a front door with nothing behind it.
        display["state"] shouldBe "DEGRADED"
        display["ready"] shouldBe true
        (display["proxy"] as Map<*, *>)["backendsObserved"] shouldBe true
        (display["proxy"] as Map<*, *>)["backendsMatched"] shouldBe 0
        (display["detail"] as String) shouldContain "not able to do its job"

        // Control: the never-observed case, same endpoint, different answer.
        observe(backends = null, control = null, backendsResolved = null)
        val fresh = api.call("GET", "/api/v1/servers/proxy-01").json()
        (fresh["status"] as Map<*, *>)["backends"] shouldBe null
        ((fresh["display"] as Map<*, *>)["proxy"] as Map<*, *>)["backendsObserved"] shouldBe false
    }

    @Test
    fun `an unreachable control endpoint is DEGRADED and says which capability is missing`() {
        createProxy().status shouldBe 201
        observe(
            backends =
                BackendRoutingStatus(
                    observedAt = at,
                    backends = listOf(backend("survival-01", BackendRegistration.REGISTERED, online = 4)),
                ),
            control = ControlEndpointStatus(reachable = false),
            backendsResolved = true,
            controlReady = false,
            controlMessage = "the control endpoint did not answer within 5s",
        )

        val document = api.call("GET", "/api/v1/servers/proxy-01").json()
        val control = (document["status"] as Map<*, *>)["control"] as Map<*, *>
        control["reachable"] shouldBe false
        // Separate fields: "did not answer" and "answered, wrong version" have
        // different remedies and only one of them is "change the image".
        control["compatible"] shouldBe false
        control["pluginApiVersion"] shouldBe null

        val display = document["display"] as Map<*, *>
        display["state"] shouldBe "DEGRADED"
        (display["detail"] as String) shouldContain "the control endpoint did not answer within 5s"
        (display["proxy"] as Map<*, *>)["controlReachable"] shouldBe false

        // Control: with the endpoint answering and compatible, the same proxy is
        // READY. Without this the badge could be DEGRADED for everything.
        observe(
            backends =
                BackendRoutingStatus(
                    observedAt = at,
                    backends = listOf(backend("survival-01", BackendRegistration.REGISTERED, online = 4)),
                ),
            control = ControlEndpointStatus(reachable = true, pluginApiVersion = "1", compatible = true),
            backendsResolved = true,
            controlReady = true,
        )
        ((api.call("GET", "/api/v1/servers/proxy-01").json()["display"]) as Map<*, *>)["state"] shouldBe "READY"
    }

    @Test
    fun `a proxy mid-drain renders the drain and the badge a delete implies`() {
        createProxy().status shouldBe 201
        api.call("DELETE", "/api/v1/servers/proxy-01").status shouldBe 202
        observe(
            backends = BackendRoutingStatus(observedAt = at, backends = emptyList()),
            control = ControlEndpointStatus(reachable = true, pluginApiVersion = "1", compatible = true),
            backendsResolved = false,
            drain =
                DrainStatus(
                    state = DrainState.DRAIN_FAILED,
                    startedAt = at,
                    enteredStateAt = at,
                    sealRequestedAt = at,
                    blocked =
                        DrainBlock(
                            reason = DrainBlockReason.AWAITING_ZERO_PLAYERS,
                            message = "40 of 500 player slots are in use; the proxy waits rather than disconnecting",
                            since = at,
                            observations = 6,
                        ),
                ),
            drainBlocked = true,
        )

        val document = api.call("GET", "/api/v1/servers/proxy-01").json()
        val status = document["status"] as Map<*, *>
        val drain = status["drain"] as Map<*, *>

        // The same DrainStatus a server uses. A proxy drain never visits SAVING,
        // so these stay null — an honest gap rather than a placeholder.
        drain["saveRequestedAt"] shouldBe null
        drain["worldSavedAt"] shouldBe null
        (drain["blocked"] as Map<*, *>)["reason"] shouldBe "AWAITING_ZERO_PLAYERS"

        val display = document["display"] as Map<*, *>
        // TERMINATING outranks DEGRADED, and the delete is the more actionable
        // fact. The capability problem is still visible under `proxy`.
        display["state"] shouldBe "TERMINATING"
        display["drainBlocked"] shouldBe true
        (display["detail"] as String) shouldContain "waiting, not stuck"
    }

    @Test
    fun `a proxy and a server list together, and neither breaks the other`() {
        // The failure this whole change is about: one declared proxy used to make
        // the fleet list 500 and kill every open stream, because both read every
        // row.
        api.call("POST", "/api/v1/servers", ExampleDefinitions.valid("minimal.yaml")).status shouldBe 201
        createProxy().status shouldBe 201

        val listed = api.call("GET", "/api/v1/servers")
        listed.status shouldBe 200
        listed.json()["count"] shouldBe 2

        @Suppress("UNCHECKED_CAST")
        val items = listed.json()["items"] as List<Map<String, Any?>>
        items.map { it["kind"] } shouldBe listOf("VelocityProxy", "PaperServer")

        // And the stream opens and carries both.
        val events = api.stream(limit = 2)
        events[1].name shouldBe "snapshot"
        events[1].json()["count"] shouldBe 2
    }

    private fun backend(
        name: String,
        registration: BackendRegistration,
        online: Int? = null,
        draining: Boolean = false,
    ): BackendStatus =
        BackendStatus(
            server = ResourceName.of(name).getOrThrow(),
            registration = registration,
            players = online?.let { PlayerOccupancy(online = it, max = 20, observedAt = at) },
            drainInitiated = draining,
            lastTransitionAt = at,
        )

    /**
     * Writes the observation the reconciler would have written.
     *
     * The conditions are typed out because `:api` cannot call `:core` even in
     * tests — see `DrainBlockRenderingTest` for the same note. `:app`'s
     * `DisplayConformanceTest` is where the reconciler's rule meets this renderer.
     */
    @Suppress("LongParameterList")
    private fun observe(
        backends: BackendRoutingStatus?,
        control: ControlEndpointStatus?,
        backendsResolved: Boolean?,
        controlReady: Boolean? = control?.let { it.reachable && it.compatible },
        controlMessage: String = "",
        drain: DrainStatus? = null,
        drainBlocked: Boolean = false,
    ) {
        val conditions = mutableListOf<StatusCondition>()
        backendsResolved?.let {
            conditions +=
                StatusCondition(
                    ConditionType.BACKENDS_RESOLVED,
                    if (it) ConditionStatus.TRUE else ConditionStatus.FALSE,
                    if (it) "" else "the selector matched no server",
                    at,
                )
        }
        controlReady?.let {
            conditions +=
                StatusCondition(
                    ConditionType.CONTROL_ENDPOINT_READY,
                    if (it) ConditionStatus.TRUE else ConditionStatus.FALSE,
                    controlMessage,
                    at,
                )
        }
        conditions +=
            StatusCondition(
                ConditionType.DRAIN_BLOCKED,
                if (drainBlocked) ConditionStatus.TRUE else ConditionStatus.FALSE,
                "",
                at,
            )
        val status =
            VelocityProxyStatus(
                name = ResourceName.of("proxy-01").getOrThrow(),
                observedGeneration = 1,
                phase = ServerPhase.RUNNING,
                observedAt = at,
                lastTransitionAt = at,
                ready = true,
                players = PlayerOccupancy(online = 40, max = 500, observedAt = at),
                backends = backends,
                control = control,
                drain = drain,
                conditions = conditions,
            )
        runBlocking { api.store.putStatus(status).getOrThrow() }
    }
}
