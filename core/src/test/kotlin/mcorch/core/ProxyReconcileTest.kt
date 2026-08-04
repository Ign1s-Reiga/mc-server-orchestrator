package mcorch.core

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import mcorch.core.paper.PaperImageContract
import mcorch.core.proxy.VelocityWorkloadPlanner
import mcorch.schema.BackendRegistration
import mcorch.schema.ConditionStatus
import mcorch.schema.ConditionType
import mcorch.schema.FailureClass
import mcorch.schema.ServerPhase
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Bringing a `VelocityProxy` up, and what it does to the servers behind it.
 *
 * The assertions here are on **side effects** rather than on returned statuses:
 * what was created, what was asserted at the proxy, what was registered. An
 * idempotency test that reads a status can pass while a second container exists.
 */
internal class ProxyReconcileTest {
    /**
     * The label that decides whether a proxy can ever be stopped.
     *
     * Without `Labels.WORLD_DATA` on the workload, `contractOf` defaults
     * `holdsWorldData` to `true` on the safe side; Velocity has no RCON so
     * `saveConfirmable` is false; the drain then asks for a save nothing can
     * confirm, gets `Unconfirmable`, classifies it **permanent**, and the container
     * can never be stopped by the orchestrator — for ever, because a permanent
     * abort freezes the status.
     *
     * Nothing else in the suite would notice: the proxy comes up, serves players
     * and looks perfect until somebody deletes it. So the assertion is on the
     * workload the planner produced *and* on the drain reaching a stop.
     */
    @Test
    fun `a proxy workload declares that it holds no world, and can therefore be stopped`() =
        coreTest {
            val planned = VelocityWorkloadPlanner.plan(proxyDefinition())
            planned.labels[Labels.WORLD_DATA] shouldBe "false"
            // Derived from the spec rather than written as a literal here, which is
            // what `ServerSpec.holdsWorldData` being abstract is for.
            Labels.booleanValue(planned.labels, Labels.WORLD_DATA) shouldBe proxyDefinition().spec.holdsWorldData

            // And the consequence, end to end: an empty proxy that is deleted
            // reaches a stop rather than an unconfirmable save.
            val harness = ProxyHarness()
            val name = harness.proxyDefinition.metadata.name
            harness.bringUp()
            harness.store.deleteDefinition(name)
            repeat(8) { harness.pass(name) }

            harness.proxyNode.stops shouldHaveSize 1
            harness.proxyNode.saves.shouldBeEmpty()
        }

    /**
     * The negative half, and it is what makes the assertion above mean something.
     *
     * A workload with no `WORLD_DATA` label is the state a planner that forgot it
     * produces, and this pins the cost: the drain refuses to stop, permanently.
     * Without this the test above would pass against a planner that wrote the label
     * for a reason unrelated to the drain.
     */
    @Test
    fun `a proxy workload with no world-data label becomes unstoppable`() =
        coreTest {
            val harness = ProxyHarness()
            val name = harness.proxyDefinition.metadata.name
            harness.bringUp()

            // Exactly the sabotage: the label goes off the running container, the
            // way a planner that forgot it would have left it.
            val present = harness.proxyNode.workload.shouldBeInstanceOf<WorkloadObservation.Present>()
            harness.proxyNode.workload = present.copy(labels = present.labels - Labels.WORLD_DATA)

            harness.store.deleteDefinition(name)
            repeat(8) { harness.pass(name) }

            harness.proxyNode.stops.shouldBeEmpty()
            val status = harness.proxyStatus().shouldNotBeNull()
            status.drain
                .shouldNotBeNull()
                .failure
                .shouldNotBeNull()
                .failureClass shouldBe FailureClass.PERMANENT
            status.drain
                .shouldNotBeNull()
                .failure
                .shouldNotBeNull()
                .message shouldContain "no world"
        }

    /**
     * Two passes over a settled fleet, asserted on side effects.
     *
     * The interesting ones are the proxy's: a second `PUT /v1/backends/{name}` that
     * *registered* something would mean the level trigger is not idempotent, and a
     * second transfer or seal would mean the drain is issuing rather than
     * asserting. Re-asserting an unchanged admission is expected and is not a side
     * effect — the whole design rests on it being free — so the assertion is on
     * `registrations`, `sweepsStarted` and `deregistrations`, which count only work
     * that changed something.
     */
    @Test
    fun `a second pass over a settled fleet creates nothing and registers nothing new`() =
        coreTest {
            val backend = backendDefinition("survival-01")
            val harness = ProxyHarness(backends = listOf(backend))
            harness.bringUp()

            val creates = harness.proxyNode.creates.size + harness.nodeOf(backend).creates.size
            val pulls = harness.proxyNode.pulls.size + harness.nodeOf(backend).pulls.size
            val registrations = harness.plugin.registrations.size
            val writes = harness.store.statusWrites

            harness.sweep()
            harness.sweep()

            (harness.proxyNode.creates.size + harness.nodeOf(backend).creates.size) shouldBe creates
            (harness.proxyNode.pulls.size + harness.nodeOf(backend).pulls.size) shouldBe pulls
            harness.plugin.registrations shouldHaveSize registrations
            harness.plugin.sweepsStarted.shouldBeEmpty()
            harness.plugin.deregistrations.shouldBeEmpty()
            harness.proxyNode.stops.shouldBeEmpty()
            harness.nodeOf(backend).stops.shouldBeEmpty()
            // The store is quiet too: a settled fleet produces no observation
            // traffic beyond the heartbeat, which has not elapsed.
            harness.store.statusWrites shouldBe writes
        }

    /**
     * The backend learns the forwarding secret without its definition ever
     * mentioning a proxy.
     *
     * Coordinates only — the container carries a `SecretRef` in `secretEnv`, which
     * the node resolves at the moment it hands it to the runtime. The control
     * assertion is the pair: the *name* is findable and the material is not, so
     * this cannot pass for the reason a leak test passes when the needle was never
     * findable at all.
     */
    @Test
    fun `a matched backend is created with the proxy's forwarding secret as a coordinate`() =
        coreTest {
            val backend = backendDefinition("survival-01")
            val harness = ProxyHarness(backends = listOf(backend))
            harness.bringUp()

            val created = harness.nodeOf(backend).creates.single()
            val ref = created.secretEnv[PaperImageContract.FORWARDING_SECRET].shouldNotBeNull()
            ref shouldBe harness.proxyDefinition.spec.forwarding.secret
            // Modern forwarding: the proxy authenticates and the backend trusts it.
            created.env[PaperImageContract.ONLINE_MODE] shouldBe "false"

            // The control: the coordinate is findable in the rendered spec, and no
            // value is, because there is no value in this process to render.
            val rendered = created.toString()
            rendered shouldContain "secretEnv"
            rendered shouldContain PaperImageContract.FORWARDING_SECRET
            rendered shouldContain "<from secret store>"
        }

    /**
     * A server nothing claims is untouched.
     *
     * The control for the test above, and it guards a real hazard: the spec hash
     * gains a forwarding component, so a standalone server whose hash moved because
     * the proxy kind was added would be drained and recreated for nothing — on
     * every server in every fleet, at upgrade time.
     */
    @Test
    fun `a server no selector matches gets no forwarding secret and no new spec hash`() =
        coreTest {
            val unmatched = unmatchedDefinition("lobby-01", hostPort = 30002)
            val harness = ProxyHarness(backends = listOf(unmatched))
            harness.bringUp()

            val created = harness.nodeOf(unmatched).creates.single()
            created.secretEnv.keys shouldBe setOf(PaperImageContract.RCON_PASSWORD)
            created.env.containsKey(PaperImageContract.ONLINE_MODE).shouldBeFalse()
            // The hash a fleet with no proxy in it would have produced.
            created.specHash shouldBe
                mcorch.core.paper.PaperWorkloadPlanner
                    .specHash(unmatched)
        }

    /**
     * The routing table is asserted from the fleet, and the conditions say so.
     *
     * `BACKENDS_RESOLVED` false is not a failure — an operator may simply not have
     * labelled anything yet — but it is the answer to "why can nobody join", and it
     * cannot be caught at parse time because the selector is checked against
     * definitions the parse never sees.
     */
    @Test
    fun `the proxy registers what its selector matches and reports what it resolved`() =
        coreTest {
            val backend = backendDefinition("survival-01")
            val stranger = unmatchedDefinition("lobby-01", hostPort = 30002)
            val harness = ProxyHarness(backends = listOf(backend, stranger))
            harness.bringUp()

            harness.plugin.registrations shouldBe listOf("survival-01")
            harness.plugin.backend("lobby-01") shouldBe null

            val status = harness.proxyStatus().shouldNotBeNull()
            status.phase shouldBe ServerPhase.RUNNING
            status.ready.shouldBeTrue()
            status.backends.shouldNotBeNull().matched shouldBe 1
            status.backends
                .shouldNotBeNull()
                .backends
                .single()
                .registration shouldBe
                BackendRegistration.REGISTERED
            status.condition(ConditionType.BACKENDS_RESOLVED).status shouldBe ConditionStatus.TRUE
            status.condition(ConditionType.CONTROL_ENDPOINT_READY).status shouldBe ConditionStatus.TRUE
        }

    /**
     * A plugin whose protocol this build cannot speak.
     *
     * Compatibility is set membership — `ControlProtocol.VERSION in supported` —
     * never `>=`, because the version's only meaning is "the wire changed" and
     * there is no ordering over that. The consequence is fleet-wide, so it is
     * reported rather than swallowed: **no backend behind this proxy can complete a
     * drain.**
     *
     * Retryable, deliberately. A permanent classification would freeze the proxy's
     * status and stop the routing sweep, which is the one thing that restores joins
     * to a backend whose drain has parked.
     */
    @Test
    fun `an incompatible plugin surfaces without freezing the proxy`() =
        coreTest {
            val harness = ProxyHarness()
            harness.plugin.supported = listOf("99")
            harness.declareAll()
            repeat(6) { harness.pass(harness.proxyDefinition.metadata.name) }

            val status = harness.proxyStatus().shouldNotBeNull()
            status.control
                .shouldNotBeNull()
                .reachable
                .shouldBeTrue()
            status.control
                .shouldNotBeNull()
                .compatible
                .shouldBeFalse()
            status.condition(ConditionType.CONTROL_ENDPOINT_READY).status shouldBe ConditionStatus.FALSE
            status.condition(ConditionType.CONTROL_ENDPOINT_READY).message shouldContain "complete a drain"
            status.failure.shouldNotBeNull().failureClass shouldBe FailureClass.RETRYABLE
        }

    /**
     * A transient failure requeues; a permanent one surfaces and the loop stops.
     *
     * Required by `add-server-kind` step 6 for every kind, and the two halves are
     * asserted in one test because the interesting property is that they *differ*:
     * the same call site produces a requeue or a stop depending only on how the
     * node classified it.
     */
    @Test
    fun `a proxy classifies a transient node failure as a requeue and a permanent one as a stop`() =
        coreTest {
            val harness = ProxyHarness()
            val name = harness.proxyDefinition.metadata.name
            harness.bringUp()

            harness.proxyNode.failAlways(NodeOperation.OBSERVE, harness.proxyNode.unreachable(NodeOperation.OBSERVE))
            harness.pass(name).shouldBeInstanceOf<ReconcileOutcome.Retry>()
            harness
                .proxyStatus()
                .shouldNotBeNull()
                .failure
                .shouldNotBeNull()
                .failureClass shouldBe FailureClass.RETRYABLE

            harness.proxyNode.stopFailing(NodeOperation.OBSERVE)
            harness.proxyNode.failAlways(NodeOperation.OBSERVE, harness.proxyNode.rejected(NodeOperation.OBSERVE))
            harness.pass(name).shouldBeInstanceOf<ReconcileOutcome.Failed>()

            val failed = harness.proxyStatus().shouldNotBeNull()
            failed.failure.shouldNotBeNull().failureClass shouldBe FailureClass.PERMANENT
            failed.attention().status shouldBe ConditionStatus.TRUE

            // The gate: after a permanent failure the loop stops touching the node
            // at all, which is what makes the flag load-bearing rather than
            // decorative.
            val calls = harness.proxyNode.calls.size
            harness.clock.advance(30.minutes)
            harness.pass(name).shouldBeInstanceOf<ReconcileOutcome.Failed>()
            harness.proxyNode.calls shouldHaveSize calls
            harness.proxyNode.stops.shouldBeEmpty()
        }

    /**
     * Two proxies claiming one backend with different secrets.
     *
     * A fleet-level conflict, so it cannot be a parse error — neither document is
     * wrong on its own and neither parse can see the other. The container is not
     * created, because bringing it up would mean choosing one of the two secrets
     * and choosing wrong means a backend that authenticates nobody.
     *
     * Retryable: fixing either selector resolves it without touching this server,
     * which is the definition of a failure that must not be classified permanent.
     */
    @Test
    fun `a backend claimed by two proxies with different secrets is not created`() =
        coreTest {
            val backend = backendDefinition("survival-01")
            val harness = ProxyHarness(backends = listOf(backend))
            harness.declareAll()
            harness.declare(proxyDefinition(name = "front-02", node = "proxy-node"))

            harness.pass(backend.metadata.name).shouldBeInstanceOf<ReconcileOutcome.Retry>()

            harness.nodeOf(backend).creates.shouldBeEmpty()
            val status = harness.status(backend.metadata.name).shouldNotBeNull()
            val failure = status.failure.shouldNotBeNull()
            failure.failureClass shouldBe FailureClass.RETRYABLE
            failure.message shouldContain "front-01"
            failure.message shouldContain "front-02"
            failure.message shouldContain "different forwarding secrets"
        }

    /**
     * A registration whose backend is gone is removed by the proxy's own sweep.
     *
     * The only thing that can repair it: the backend's definition is purged, so
     * nothing reconciles the backend any more. Safe *here and only here* because
     * the plugin refuses `DELETE` outright while anybody is connected, with no
     * force flag — so the sweep cannot disconnect a player however wrong it is.
     */
    @Test
    fun `the proxy lets go of a registration whose selector no longer matches`() =
        coreTest {
            val backend = backendDefinition("survival-01")
            val harness = ProxyHarness(backends = listOf(backend))
            harness.bringUp()
            harness.plugin.backend("survival-01").shouldNotBeNull()

            // The definition goes away entirely, the way a completed delete leaves
            // the fleet.
            harness.store.deleteDefinition(backend.metadata.name)
            repeat(12) { harness.pass(backend.metadata.name) }
            harness.pass(harness.proxyDefinition.metadata.name)

            harness.plugin.deregistrations shouldContain "survival-01"
            harness.plugin.backend("survival-01") shouldBe null
        }
}
