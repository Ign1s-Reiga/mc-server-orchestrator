package mcorch.core

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import mcorch.core.paper.PaperImageContract
import mcorch.core.proxy.VelocityWorkloadPlanner
import mcorch.schema.BackendRegistration
import mcorch.schema.ConditionStatus
import mcorch.schema.ConditionType
import mcorch.schema.ControlCredential
import mcorch.schema.FailureClass
import mcorch.schema.FailureReason
import mcorch.schema.SecretRef
import mcorch.schema.ServerPhase
import mcorch.store.getOrThrow
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

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
     * The container the *loop* creates carries the control channel, twice over.
     *
     * Both halves of this were missing at once and neither was visible from any
     * status: the plugin JAR was requested as storage and dropped by the node, and
     * the token the plugin authenticates with was never put in the container — so
     * `:core` sent a bearer token to an endpoint that had been told nothing and
     * would serve anyone who reached the port. The planner's own unit tests are
     * `VelocityWorkloadPlannerTest`; this asserts the workload that reached a node
     * through a real reconcile pass, which is what a planner nobody wired would
     * still fail.
     *
     * The second half is idempotency, on the same scenario rather than as a
     * separate one: the asset is not part of the spec hash — it is constant for
     * the kind — so a pass that recreated a container over it would be a silent
     * restart of a proxy with players on it.
     */
    @Test
    fun `the proxy container the loop creates carries the plugin and the token, once`() =
        coreTest {
            val token = SecretRef.of("front-01-control", "token").getOrThrow()
            val harness = ProxyHarness(proxy = proxyDefinition(tokenSecret = token))
            harness.bringUp()

            val created = harness.proxyNode.creates.single()
            created.assets.single().asset shouldBe WorkloadAsset.VELOCITY_CONTROL_PLUGIN
            created.assets.single().directory shouldBe VelocityWorkloadPlanner.PLUGIN_DIRECTORY
            // Coordinates, never material — and in `secretEnv`, so nothing that
            // renders a spec can print it.
            created.secretEnv[VelocityWorkloadPlanner.CONTROL_TOKEN] shouldBe token
            created.env.containsKey(VelocityWorkloadPlanner.CONTROL_TOKEN).shouldBeFalse()
            // The same reference the loop authenticates with. Two fields that could
            // disagree would be an endpoint refusing its own orchestrator.
            harness.proxyDefinition.spec.control.tokenSecret shouldBe token

            harness.sweep()
            harness.sweep()

            harness.proxyNode.creates shouldHaveSize 1
            harness.proxyNode.stops.shouldBeEmpty()
        }

    /**
     * A node that cannot supply the plugin refuses the proxy, permanently.
     *
     * This is the composition the fix rests on, and each half is pinned where it
     * lives: `WorkloadMountsTest` asserts that a node with no artefact throws
     * `Rejected` rather than mounting a hole, and this asserts what a `Rejected`
     * on a *proxy* create does — surfaces as `PERMANENT`, keeps the operator's
     * message, and stops being attempted. Starting a proxy without its control
     * endpoint would be the worse outcome: it serves players perfectly and every
     * backend behind it is undrainable.
     */
    @Test
    fun `a node that cannot supply the plugin refuses the proxy rather than starting one without it`() =
        coreTest {
            val harness = ProxyHarness()
            val name = harness.proxyDefinition.metadata.name
            harness.proxyNode.failAlways(
                NodeOperation.CREATE,
                NodeException.Rejected(
                    harness.proxyNode.name,
                    NodeOperation.CREATE,
                    "`front-01` needs the VELOCITY_CONTROL_PLUGIN artefact and node does not have it: every " +
                        "backend behind it would be undrainable",
                ),
            )
            harness.declareAll()

            harness.pass(name).shouldBeInstanceOf<ReconcileOutcome.Failed>()

            val status = harness.proxyStatus().shouldNotBeNull()
            val failure = status.failure.shouldNotBeNull()
            failure.failureClass shouldBe FailureClass.PERMANENT
            failure.reason shouldBe FailureReason.CONTAINER_CREATE_FAILED
            failure.message shouldContain "undrainable"
            harness.proxyNode.starts.shouldBeEmpty()

            // "Stops retrying" has to be a side effect, not an outcome: the node is
            // not asked again.
            val attempts = harness.proxyNode.calls.count { it == NodeOperation.CREATE }
            harness.pass(name)
            harness.pass(name)
            harness.proxyNode.calls.count { it == NodeOperation.CREATE } shouldBe attempts
        }

    /**
     * The same refusal, asked **before** the drain that would destroy the thing
     * being replaced.
     *
     * The twenty-fourth audit's warning, and the classification and the enforcement
     * point are not what was wrong with it — *when it was first asked* was. A proxy
     * running perfectly, a hash-bearing edit, then: drain to zero, stop, remove,
     * all correct — and only at `ensureWorkload` does the node discover it has no
     * plugin to mount. The front door is gone and the loop has just established
     * permanently that it cannot build another. Nothing stages that artefact for
     * `:app:run` or for any distribution, so it is the *default* state of a real
     * install rather than bad luck.
     *
     * ## The assertions, and which of them is not enough on its own
     *
     * "The replacement was not created" would pass against the broken build too —
     * it never got created there either. The discriminators are that the **old
     * container is still running and was never stopped**, and that the failure is
     * `RETRYABLE`: permanence here would freeze the proxy's passes, and with them
     * `assertBackends`, which is the level trigger that restores joins to a backend
     * whose own drain has parked.
     */
    @Test
    fun `a proxy that cannot be rebuilt is not drained, and keeps running`() =
        coreTest {
            val harness = ProxyHarness()
            val name = harness.proxyDefinition.metadata.name
            harness.bringUp()
            harness
                .proxyStatus()
                .shouldNotBeNull()
                .ready
                .shouldBeTrue()
            val handle = harness.proxyNode.stops.size

            // The artefact goes: staged for the first create, gone by the second.
            harness.proxyNode.failAlways(
                NodeOperation.CREATE,
                NodeException.Rejected(
                    harness.proxyNode.name,
                    NodeOperation.CREATE,
                    "`front-01` needs the VELOCITY_CONTROL_PLUGIN artefact and node `proxy-node` does not have it",
                ),
            )
            // A hash-bearing edit. `maxPlayers` is in the proxy's spec hash, so this
            // is a replacement rather than an in-place update.
            harness.declare(proxyDefinition(maxPlayers = 300))

            repeat(6) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }

            // Nothing was taken away on the strength of being able to build a
            // replacement that cannot be built.
            harness.proxyNode.stops shouldHaveSize handle
            harness.proxyNode.removals.shouldBeEmpty()

            val status = harness.proxyStatus().shouldNotBeNull()
            status.phase shouldBe ServerPhase.RUNNING
            status.ready.shouldBeTrue()
            // No drain was started at all — not started and parked, not started and
            // blocked. The question is asked before the protocol begins.
            status.drain.shouldBeNull()

            val failure = status.failure.shouldNotBeNull()
            failure.failureClass shouldBe FailureClass.RETRYABLE
            failure.message shouldContain "cannot build the replacement"
            failure.message shouldContain "keeps running and keeps routing"
        }

    /**
     * The blocker outranks "it exited", because only one of them can be acted on.
     *
     * The twenty-fifth audit's fourth warning. `convergeProxy` preferred the blocker
     * over the routing failure in the `RUNNING` branch and dropped it everywhere
     * else — so a proxy that is *down* and cannot be rebuilt reported
     * `CONTAINER_EXITED`, permanently, and the sentence naming the artefact that is
     * missing went to a log line. An operator reading the dashboard is told the
     * container exited and nothing about why nothing replaces it.
     *
     * The class matters as much as the message: `CONTAINER_EXITED` is `PERMANENT`,
     * which freezes the passes. Staging the artefact would then change nothing until
     * somebody also edited the definition.
     */
    @Test
    fun `a proxy that exited and cannot be rebuilt reports the artefact, not the exit`() =
        coreTest {
            val harness = ProxyHarness()
            val name = harness.proxyDefinition.metadata.name
            harness.bringUp()

            harness.proxyNode.failAlways(
                NodeOperation.CREATE,
                NodeException.Rejected(
                    harness.proxyNode.name,
                    NodeOperation.CREATE,
                    "`front-01` needs the VELOCITY_CONTROL_PLUGIN artefact and node `proxy-node` does not have it",
                ),
            )
            // The container is gone and the definition has moved on: both problems
            // at once, which is the state that has one status field between them.
            val running = harness.proxyNode.workload.shouldBeInstanceOf<WorkloadObservation.Present>()
            harness.proxyNode.workload =
                running.copy(state = WorkloadState.EXITED, exitCode = 1, reason = "Error")
            harness.declare(proxyDefinition(maxPlayers = 300))

            harness.pass(name)

            val status = harness.proxyStatus().shouldNotBeNull()
            status.phase shouldBe ServerPhase.STOPPED
            val failure = status.failure.shouldNotBeNull()
            failure.reason shouldBe FailureReason.CONTAINER_CREATE_FAILED
            failure.failureClass shouldBe FailureClass.RETRYABLE
            failure.message shouldContain "cannot build the replacement"

            // And the loop is still trying, which the recorded class promises: a
            // frozen pass would make staging the artefact do nothing.
            harness.pass(name).shouldBeInstanceOf<ReconcileOutcome.Retry>()
        }

    /**
     * A proxy that is answering, but not to us.
     *
     * The spec hash carries the control token's **coordinates** and never its
     * value — deliberately, and correctly, because that is what stops a secret
     * rotation restarting the whole fleet at once. For the *forwarding* secret the
     * mismatch that follows is loud: nobody can log in. For the *control* token it
     * was silent. The container keeps the token it was created with, `:core` starts
     * sending the new one, and `GET /v1/version` needs no token at all — so the
     * handshake kept reporting `reachable = true, compatible = true` while every
     * seal, transfer and deregistration in the fleet was refused.
     *
     * The state became reachable in the change that first delivered the token to
     * the container. Before it, the plugin required no credential and accepted
     * anything.
     *
     * ## Where the answer has to show up
     *
     * On `status.failure`, which is what `NEEDS_ATTENTION` and every "is anything
     * wrong here" surface reads. Retryable, because re-aligning the token is an
     * operator action needing no definition change — and because a permanent
     * failure on a proxy freezes its passes and with them the routing sweep, which
     * is the one thing that restores joins to a backend whose drain has parked.
     *
     * **And on `control` itself.** It used to reach only `failure`, and this test
     * used to assert that `reachable` and `compatible` were both true with a
     * remark that a reader of `control` alone learns nothing is wrong. That was
     * the defect stated as a property: a dashboard drawing a control badge from
     * those two fields renders green on a proxy behind which nothing can be
     * sealed, transferred or deregistered. `credential` is the third observation
     * and it is what the badge has to read — through `usable`, which is the one
     * derivation of the three.
     *
     * The proxy is not stopped and not recreated: it is serving players perfectly
     * well, and this build cannot know whether the operator would rather fix the
     * secret than restart their front door.
     */
    @Test
    fun `a proxy that refuses this orchestrator's control token says so rather than looking healthy`() =
        coreTest {
            val backend = backendDefinition("survival-01")
            val harness = ProxyHarness(backends = listOf(backend))
            harness.bringUp()
            val healthy = harness.proxyStatus().shouldNotBeNull()
            healthy.failure.shouldBeNull()

            // The control of the control: a working proxy records the accepted
            // verdict, so `REJECTED` below is a change this pass produced rather
            // than the value the field always holds.
            val before = healthy.control.shouldNotBeNull()
            before.credential shouldBe ControlCredential.ACCEPTED
            before.usable.shouldBeTrue()

            // The secret behind the reference is rotated. Nothing in the definition
            // changed, so nothing recreates the container, and the token it holds is
            // no longer the one being sent.
            harness.plugin.rejectsCredential = true
            harness.sweep()

            val status = harness.proxyStatus().shouldNotBeNull()
            val failure = status.failure.shouldNotBeNull()
            failure.reason shouldBe FailureReason.PROXY_CONTROL_UNREACHABLE
            failure.failureClass shouldBe FailureClass.RETRYABLE
            failure.message shouldContain "rejecting this orchestrator's credential"

            // The handshake still says the endpoint is there and speaks our
            // protocol, and both are still true — the version route needs no token.
            // What has changed is the fact those two cannot express.
            val control = status.control.shouldNotBeNull()
            control.reachable.shouldBeTrue()
            control.compatible.shouldBeTrue()
            control.credential shouldBe ControlCredential.REJECTED
            control.usable.shouldBeFalse()

            // And the condition an alerting channel quotes moves with it, naming
            // the remedy rather than the version or the network.
            val condition = status.conditions.single { it.type == ConditionType.CONTROL_ENDPOINT_READY }
            condition.status shouldBe ConditionStatus.FALSE
            condition.message shouldContain "refused this orchestrator's credential"

            harness.proxyNode.stops.shouldBeEmpty()
            harness.proxyNode.removals.shouldBeEmpty()
        }

    /**
     * The same verdict arriving by the other door.
     *
     * `:core` learns whether the plugin accepts its credential from the pass's
     * *first* authenticated call, `GET /v1/self`, and that call being first is an
     * ordering rather than a guarantee: the token is re-read per call, so a
     * rotation can land between it and the backend assertions that follow. A
     * verdict only one call site can write is one that goes missing the day the
     * order changes — and what goes missing here is a control endpoint reading
     * `usable` while every registration it is being sent is refused.
     */
    @Test
    fun `a credential refused after the first call still lands on the control record`() =
        coreTest {
            val harness = ProxyHarness(backends = listOf(backendDefinition("survival-01")))
            harness.bringUp()

            harness.plugin.rejectsCredentialExceptState = true
            harness.sweep()

            val status = harness.proxyStatus().shouldNotBeNull()
            val control = status.control.shouldNotBeNull()
            // The handshake answered and so did `/v1/self`, so nothing before the
            // backend assertions saw anything wrong.
            control.reachable.shouldBeTrue()
            control.compatible.shouldBeTrue()
            control.credential shouldBe ControlCredential.REJECTED
            control.usable.shouldBeFalse()
            status.failure.shouldNotBeNull().reason shouldBe FailureReason.PROXY_CONTROL_UNREACHABLE
        }

    /**
     * A pass that authenticates nothing may not erase what a pass that did
     * established.
     *
     * The handshake needs no token, so `readControl` cannot establish a verdict —
     * and it builds the record. Built from scratch, `credential` re-enters
     * `UNTESTED` every pass and only the routing sweep refines it, so any pass
     * that does not reach the sweep **deletes** the verdict. The reachable one is
     * this: the proxy's player port stops answering, `awaitProxyReady` returns at
     * the not-joinable branch, and a proxy recorded `REJECTED` comes back with
     * `usable` true and `CONTROL_ENDPOINT_READY` TRUE — the badge going green
     * because a *second*, unrelated thing broke.
     *
     * The record is therefore seeded from the previous one, exactly as
     * `lastContactAt` already is.
     */
    @Test
    fun `a pass that makes no authenticated call keeps the verdict rather than erasing it`() =
        coreTest {
            val harness = ProxyHarness(backends = listOf(backendDefinition("survival-01")))
            harness.bringUp()

            harness.plugin.rejectsCredential = true
            harness.sweep()
            harness
                .proxyStatus()
                .shouldNotBeNull()
                .control
                .shouldNotBeNull()
                .credential shouldBe
                ControlCredential.REJECTED

            // The player port stops answering. This pass returns before
            // `assertBackends` and makes no authenticated call at all — the
            // handshake is the only thing it asks, and that route needs no token.
            harness.proxyNode.joinable = false
            harness.sweep()

            val status = harness.proxyStatus().shouldNotBeNull()
            val control = status.control.shouldNotBeNull()
            control.reachable.shouldBeTrue()
            control.credential shouldBe ControlCredential.REJECTED
            control.usable.shouldBeFalse()
            // And the surface an alerting channel reads does not flip to green
            // because a second thing broke.
            status.conditions.single { it.type == ConditionType.CONTROL_ENDPOINT_READY }.status shouldBe
                ConditionStatus.FALSE
        }

    /**
     * The seed is a verdict about a *container*, so a replacement does not inherit
     * one.
     *
     * The token lives in the container's environment, put there at create time. A
     * refusal earned by the container that is being replaced says nothing about
     * its successor, which was created from whatever the secret store holds now —
     * and carrying it across would report a credential fault on a proxy that has
     * never been asked, with no pass able to clear it until an authenticated call
     * lands.
     */
    @Test
    fun `a replaced proxy container does not inherit its predecessor's refusal`() =
        coreTest {
            val harness = ProxyHarness()
            val name = harness.proxyDefinition.metadata.name
            harness.bringUp()
            harness.plugin.rejectsCredential = true
            harness.sweep()
            harness
                .proxyStatus()
                .shouldNotBeNull()
                .control
                .shouldNotBeNull()
                .credential shouldBe
                ControlCredential.REJECTED
            val replaced =
                harness
                    .proxyStatus()
                    .shouldNotBeNull()
                    .runtime
                    .shouldNotBeNull()
                    .containerId

            // The container comes back accepting the token it was created with,
            // and a hash-bearing edit replaces it. `maxPlayers` is in the proxy's
            // spec hash.
            harness.plugin.rejectsCredential = false
            harness.declare(proxyDefinition(maxPlayers = 300))
            repeat(12) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }

            val status = harness.proxyStatus().shouldNotBeNull()
            status.runtime.shouldNotBeNull().containerId shouldNotBe replaced
            // Not `UNTESTED`: the sweep on the new container authenticated
            // successfully. The point is that nothing carried the old refusal over
            // — which a seed with no identity gate would have done until the first
            // authenticated call, and for ever on a proxy whose port was down.
            status.control.shouldNotBeNull().credential shouldBe ControlCredential.ACCEPTED
        }

    /**
     * The drain path writes the proxy's status and never reaches the routing
     * sweep, and it is the path whose authenticated calls matter most.
     *
     * A replacement drain seals the fleet's front door through the same control
     * endpoint. When that endpoint refuses the credential, step 2 cannot be
     * asserted and the drain parks — with a `status.failure` saying exactly that.
     * The control record used to be untouched on this path (`drainProxy` drafts
     * without a `control` argument, so it defaults to the previous record, and
     * `readControl` is never called), so the same status carried
     * `credential = ACCEPTED, usable = true` **beside** that failure: two surfaces
     * disagreeing about one endpoint, on the one path where a stop is pending.
     *
     * Players are online deliberately. On an empty proxy the seal is *waived* —
     * `sealIsPrecondition` is false for a subject with no router and a fresh
     * zero-player reading — and the drain would carry on to the stop; the
     * disagreement this pins is the parked one.
     */
    @Test
    fun `a proxy drain that cannot seal records the refusal on the control record`() =
        coreTest {
            val harness = ProxyHarness()
            val name = harness.proxyDefinition.metadata.name
            harness.bringUp()
            harness.proxyNode.online = 3
            harness.plugin.rejectsCredential = true

            // A hash-bearing edit, so these passes drain rather than converge.
            // Two, which is what the parked-replacement case takes: the first
            // observes the hash it no longer matches, the second runs step 2 and
            // meets the refusal.
            harness.declare(proxyDefinition(maxPlayers = 300))
            harness.pass(name)
            harness.clock.advance(2.seconds)
            harness.pass(name)

            val status = harness.proxyStatus().shouldNotBeNull()
            // On the drain path: the record exists and the seal is what failed.
            status.drain.shouldNotBeNull()
            status.failure.shouldNotBeNull().message shouldContain "refused"
            val control = status.control.shouldNotBeNull()
            control.credential shouldBe ControlCredential.REJECTED
            control.usable.shouldBeFalse()
            // The proxy is still up. A refused seal parks the drain; it does not
            // take the front door away.
            harness.proxyNode.stops.shouldBeEmpty()
        }

    /**
     * The other direction of the same field, and the reason it is three-valued.
     *
     * A proxy whose control endpoint has never answered has established *nothing*
     * about the credential — no authenticated call was ever made, because every
     * pass stops at the handshake. Recording `REJECTED` there would put a
     * credential alarm on every network blip, and recording `ACCEPTED` would be a
     * green light nobody lit; `UNTESTED` is what was observed. `usable` is false
     * all the same, off `reachable` — an untested credential is *not refused*, and
     * the flag still reports the endpoint unusable because a different field says
     * so.
     *
     * **This test previously asserted the opposite of its second half** — that an
     * endpoint going unreachable *reset* an established verdict to `UNTESTED` —
     * and that assertion was the round-44 defect written down: a pass that
     * authenticates nothing was erasing what a pass that did had learned, so a
     * `REJECTED` proxy went green the moment a second thing broke. Rewritten
     * rather than deleted, because the distinction it was reaching for is real and
     * is now stated where it holds: `UNTESTED` is for having *no* evidence, not
     * for losing it.
     */
    @Test
    fun `a credential never established reads untested, and one already established survives`() =
        coreTest {
            // No prior observation at all: the endpoint is dead before the proxy
            // ever comes up, so nothing was ever seeded in.
            val harness = ProxyHarness(backends = listOf(backendDefinition("survival-01")))
            harness.plugin.unreachable = true
            harness.bringUp()

            val fresh =
                harness
                    .proxyStatus()
                    .shouldNotBeNull()
                    .control
                    .shouldNotBeNull()
            fresh.reachable.shouldBeFalse()
            fresh.credential shouldBe ControlCredential.UNTESTED
            fresh.usable.shouldBeFalse()

            // The endpoint comes back and a sweep authenticates against it.
            harness.plugin.unreachable = false
            harness.sweep()
            harness
                .proxyStatus()
                .shouldNotBeNull()
                .control
                .shouldNotBeNull()
                .credential shouldBe
                ControlCredential.ACCEPTED

            // …and goes away again. The pass learns nothing about the credential,
            // so it says nothing about it: the verdict stands, and `usable` is
            // false because `reachable` is.
            harness.plugin.unreachable = true
            harness.sweep()

            val lost =
                harness
                    .proxyStatus()
                    .shouldNotBeNull()
                    .control
                    .shouldNotBeNull()
            lost.reachable.shouldBeFalse()
            lost.credential shouldBe ControlCredential.ACCEPTED
            lost.usable.shouldBeFalse()
        }

    /**
     * The other side of the same classification: a node that is merely down.
     *
     * A proxy is the one workload whose absence blocks every drain in the fleet, so
     * a transient failure must not be allowed to look permanent — that would freeze
     * the status and stop the routing sweep that restores joins to a parked
     * backend.
     */
    @Test
    fun `a proxy create that fails transiently requeues and gets through on the next pass`() =
        coreTest {
            val harness = ProxyHarness()
            val name = harness.proxyDefinition.metadata.name
            harness.proxyNode.failOnce(NodeOperation.CREATE, harness.proxyNode.unreachable(NodeOperation.CREATE))
            harness.declareAll()

            // The proxy path ensures the image and creates in the same pass, so
            // the armed fault lands on the first one.
            harness.pass(name).shouldBeInstanceOf<ReconcileOutcome.Retry>()

            harness.proxyNode.creates.shouldBeEmpty()
            harness
                .proxyStatus()
                .shouldNotBeNull()
                .failure
                .shouldNotBeNull()
                .failureClass shouldBe FailureClass.RETRYABLE

            harness.pass(name)
            harness.proxyNode.creates shouldHaveSize 1
            harness.proxyNode.creates
                .single()
                .assets
                .shouldNotBeEmpty()
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
     * A backend this build cannot read is exempt from the sweep, not swept.
     *
     * The routing sweep reads `listAll` rather than `listServers` precisely so one
     * undecodable row cannot break it — but it then discarded the `unreadable` half,
     * which is the part that made the tolerance safe. An unreadable row is not "a
     * server that went away"; it is a server this build cannot describe, and the
     * garbage collector turns that absence into an outbound `DELETE` against a
     * backend that is running perfectly well and full of players.
     *
     * `DefinitionCodec` deliberately widens the population of rows that land in
     * `unreadable`, so this is the one consumer that converts that widening into a
     * destructive call. The exemption is exactly as wide as the ignorance, and it
     * lapses the moment the row is repaired.
     *
     * `BACKEND_OCCUPIED` is what keeps the blast radius small — the plugin refuses
     * to deregister a backend with anybody on it — which is why this is a warning
     * and not a critical. It is also why the assertion below is on the *empty*
     * backend: that is the case the plugin's own guard does not cover.
     */
    @Test
    fun `an unreadable definition does not make the sweep deregister its backend`() =
        coreTest {
            val backend = backendDefinition("survival-01")
            val harness = ProxyHarness(backends = listOf(backend))
            harness.bringUp()
            harness.plugin.backend("survival-01").shouldNotBeNull()

            // The row is still there and the container is still running; this build
            // simply cannot decode the spec document any more. The definition
            // disappears from `servers` and appears in `unreadable`.
            harness.store.hide(backend.metadata.name)
            harness.pass(harness.proxyDefinition.metadata.name)

            harness.plugin.deregistrations.shouldBeEmpty()
            harness.plugin.backend("survival-01").shouldNotBeNull()

            // Repaired: the row decodes again, and nothing was lost in between.
            harness.store.unhide(backend.metadata.name)
            harness.pass(harness.proxyDefinition.metadata.name)

            harness.plugin.deregistrations.shouldBeEmpty()
            harness.plugin.backend("survival-01").shouldNotBeNull()
        }

    /**
     * A conflict refuses the create. It must never refuse the delete.
     *
     * The refusal returns before placement, so with no exemption a backend that two
     * selectors start matching becomes **permanently undeletable** — with both
     * proxies routing to it — until a human narrows a selector. An undeletable
     * populated server is what produces a manual `crictl stop`, which is a container
     * stopped with no save, so the refusal has to be scoped to the thing it is
     * actually about: creating a container whose forwarding secret is ambiguous.
     *
     * A drain issues no create, so the ambiguity does not apply to it. It runs with
     * no binding at all, which means it blocks on players rather than transferring
     * them — the correct degradation, because sealing through one of the two proxies
     * would leave the other routing new players in.
     */
    @Test
    fun `a backend claimed by two proxies can still be deleted and drained`() =
        coreTest {
            val backend = backendDefinition("survival-01")
            val harness = ProxyHarness(backends = listOf(backend))
            harness.bringUp()

            // A second proxy starts matching it after it is already running.
            harness.declare(proxyDefinition(name = "front-02", node = "proxy-node"))
            harness.pass(backend.metadata.name).shouldBeInstanceOf<ReconcileOutcome.Retry>()
            harness
                .status(backend.metadata.name)
                .shouldNotBeNull()
                .failure
                .shouldNotBeNull()
                .reason shouldBe FailureReason.FORWARDING_SECRET_UNAVAILABLE

            // The operator gives up on it and deletes it. That must still work.
            harness.store.deleteDefinition(backend.metadata.name)
            repeat(12) { harness.pass(backend.metadata.name) }

            harness.nodeOf(backend).saves shouldHaveSize 1
            harness.nodeOf(backend).stops shouldHaveSize 1
            harness.store.getServer(backend.metadata.name) shouldBe null
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
