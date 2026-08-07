package mcorch.store

import mcorch.schema.BackendRegistration
import mcorch.schema.BackendRoutingStatus
import mcorch.schema.BackendStatus
import mcorch.schema.ConditionStatus
import mcorch.schema.ConditionType
import mcorch.schema.ControlCredential
import mcorch.schema.ControlEndpointStatus
import mcorch.schema.DrainBlock
import mcorch.schema.DrainBlockReason
import mcorch.schema.DrainState
import mcorch.schema.DrainStatus
import mcorch.schema.FailureClass
import mcorch.schema.FailureReason
import mcorch.schema.FailureStatus
import mcorch.schema.ImageRef
import mcorch.schema.ImageStatus
import mcorch.schema.NodeName
import mcorch.schema.PaperServerDefinition
import mcorch.schema.PaperServerStatus
import mcorch.schema.PlayerOccupancy
import mcorch.schema.ResourceName
import mcorch.schema.RuntimeIdentity
import mcorch.schema.ServerEndpoint
import mcorch.schema.ServerPhase
import mcorch.schema.StatusCondition
import mcorch.schema.StorageStatus
import mcorch.schema.VelocityProxyDefinition
import mcorch.schema.VelocityProxyStatus
import mcorch.schema.fixtures.ExampleDefinitions
import mcorch.schema.getOrThrow
import mcorch.schema.yaml.ServerDefinitionParser
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Test data.
 *
 * Definitions come from `:schema`'s own example files rather than from a second
 * copy written here — the store's job is to hold what the parser produces, and a
 * fixture that has drifted from the examples would test the drift. They arrive
 * through [ExampleDefinitions], a declared dependency on `:schema`'s test
 * fixtures; nothing here knows where on disk those files sit.
 *
 * Statuses are built here, because `:schema` has no reader for them on purpose.
 * [fullStatus] sets *every* field, including a [DrainStatus] with every timestamp
 * populated: the round-trip tests are only worth anything if nothing is left at
 * its default.
 */
internal object Fixtures {
    val T0: Instant = Instant.parse("2026-07-26T10:15:30.123456789Z")

    fun yaml(name: String): String = ExampleDefinitions.valid(name)

    fun definition(name: String): PaperServerDefinition =
        ServerDefinitionParser.parse(yaml(name), name).getOrThrow() as PaperServerDefinition

    /** The fully-populated example, renamed so several can coexist in one store. */
    fun definitionNamed(
        name: String,
        example: String = "full.yaml",
    ): PaperServerDefinition {
        val parsed = definition(example)
        return parsed.copy(metadata = parsed.metadata.copy(name = resourceName(name)))
    }

    fun proxyDefinition(name: String): VelocityProxyDefinition =
        ServerDefinitionParser.parse(yaml(name), name).getOrThrow() as VelocityProxyDefinition

    /** The fully-populated proxy example, renamed so several can coexist in one store. */
    fun proxyDefinitionNamed(
        name: String,
        example: String = "proxy-full.yaml",
    ): VelocityProxyDefinition {
        val parsed = proxyDefinition(example)
        return parsed.copy(metadata = parsed.metadata.copy(name = resourceName(name)))
    }

    /**
     * A definition carrying deadlines no reader would have accepted.
     *
     * Built by copying a parsed example rather than by parsing one, because the
     * parser is precisely what refuses these values. That is the population the
     * bound exists for: a hand-edited row, a restored backup, a fixture — anything
     * that reached the store without passing a reader.
     *
     * The pair is kept legal (`stopGracePeriod` above `saveTimeout` by more than
     * the margin) so that the definition is one the schema itself accepts. A pair
     * that was already inverted would be refused by `LifecycleSpec.init` long
     * before any of this, and would be testing a different thing.
     */
    fun unboundedDefinition(
        name: String,
        stopGracePeriod: Duration = 30.hours,
        saveTimeout: Duration = 20.hours,
        playerTransferTimeout: Duration = 40.hours,
        startupTimeout: Duration = 50.hours,
    ): PaperServerDefinition {
        val parsed = definitionNamed(name)
        return parsed.copy(
            spec =
                parsed.spec.copy(
                    lifecycle =
                        parsed.spec.lifecycle.copy(
                            drain =
                                parsed.spec.lifecycle.drain.copy(
                                    saveTimeout = saveTimeout,
                                    playerTransferTimeout = playerTransferTimeout,
                                ),
                            stopGracePeriod = stopGracePeriod,
                            startupTimeout = startupTimeout,
                        ),
                ),
        )
    }

    /** [unboundedDefinition] for the kind whose seal timeout reaches a blocking HTTP call. */
    fun unboundedProxyDefinition(
        name: String,
        sealTimeout: Duration = 30.hours,
        stopGracePeriod: Duration = 9.hours,
    ): VelocityProxyDefinition {
        val parsed = proxyDefinitionNamed(name)
        return parsed.copy(
            spec =
                parsed.spec.copy(
                    backends =
                        parsed.spec.backends.copy(
                            drain =
                                parsed.spec.backends.drain
                                    .copy(sealTimeout = sealTimeout),
                        ),
                    lifecycle = parsed.spec.lifecycle.copy(stopGracePeriod = stopGracePeriod),
                ),
        )
    }

    fun resourceName(raw: String): ResourceName = ResourceName.of(raw).getOrThrow()

    fun nodeName(raw: String): NodeName = NodeName.of(raw).getOrThrow()

    /** A minimal observation. Enough to exist, nothing more. */
    fun pendingStatus(
        name: String,
        generation: Long,
        at: Instant = T0,
    ): PaperServerStatus = PaperServerStatus.pending(resourceName(name), generation, at)

    /**
     * Every field set, including a drain mid-flight with every side-effect timestamp
     * recorded. Losing any of these on a restart re-issues the side effect.
     */
    fun fullStatus(
        name: String,
        generation: Long = 1L,
        phase: ServerPhase = ServerPhase.DRAINING,
        drainState: DrainState = DrainState.SAVING,
        at: Instant = T0,
    ): PaperServerStatus =
        PaperServerStatus(
            name = resourceName(name),
            observedGeneration = generation,
            phase = phase,
            observedAt = at,
            lastTransitionAt = at.minusSeconds(30),
            ready = false,
            image =
                ImageStatus(
                    requested = ImageRef.Tagged("registry.example.com:5000", "mc/paper", "2026.6.1"),
                    resolvedDigest = "sha256:${"ab".repeat(32)}",
                    pulledAt = at.minusSeconds(600),
                ),
            runtime =
                RuntimeIdentity(
                    node = nodeName("node-a"),
                    sandboxId = "sandbox-0123456789",
                    containerId = "container-9876543210",
                    createdAt = at.minusSeconds(500),
                    startedAt = at.minusSeconds(480),
                    finishedAt = at.minusSeconds(5),
                    exitCode = 143,
                    restartCount = 3,
                ),
            endpoint = ServerEndpoint(node = nodeName("node-a"), address = "10.42.0.7", port = 25565),
            players = PlayerOccupancy(online = 0, max = 60, observedAt = at.minusSeconds(2)),
            storage =
                StorageStatus(
                    persistent = true,
                    volumeName = resourceName("survival-02-world"),
                    bound = true,
                    lastSaveConfirmedAt = at.minusSeconds(45),
                ),
            drain = fullDrain(drainState, at),
            failure =
                FailureStatus(
                    reason = FailureReason.DRAIN_SAVE_TIMEOUT,
                    failureClass = FailureClass.RETRYABLE,
                    message = "save completion not confirmed within the save timeout",
                    occurredAt = at.minusSeconds(1),
                    attempts = 2,
                ),
            conditions =
                listOf(
                    StatusCondition(
                        type = ConditionType.IMAGE_AVAILABLE,
                        status = ConditionStatus.TRUE,
                        message = "",
                        lastTransitionAt = at.minusSeconds(600),
                    ),
                    StatusCondition(
                        type = ConditionType.READY,
                        status = ConditionStatus.FALSE,
                        message = "draining: waiting for the world save to be confirmed",
                        lastTransitionAt = at.minusSeconds(30),
                    ),
                    StatusCondition(
                        type = ConditionType.PLAYERS_EVACUATED,
                        status = ConditionStatus.UNKNOWN,
                        message = "an = sign, a\nnewline and a \\ backslash, to prove the encoding escapes them",
                        lastTransitionAt = at,
                    ),
                ),
        )

    /**
     * A proxy observation with every field set, including a routing table holding
     * one backend of every [BackendRegistration] there is.
     *
     * [backends] is the parameter the round-trip tests vary: `null` for never
     * observed, an empty list for a selector that matched nothing, and the default
     * for a populated table. Those three are different facts and the codec has to
     * keep them apart — an empty table that came back as "not observed" would hide
     * a selector matching nothing, which is a condition an operator has to see.
     */
    fun fullProxyStatus(
        name: String,
        generation: Long = 1L,
        phase: ServerPhase = ServerPhase.DRAINING,
        drainState: DrainState = DrainState.SAVING,
        at: Instant = T0,
        backends: BackendRoutingStatus? = fullBackends(at),
    ): VelocityProxyStatus =
        VelocityProxyStatus(
            name = resourceName(name),
            observedGeneration = generation,
            phase = phase,
            observedAt = at,
            lastTransitionAt = at.minusSeconds(30),
            ready = false,
            image =
                ImageStatus(
                    requested = ImageRef.Tagged("registry.example.com:5000", "mc/velocity", "3.4.0"),
                    resolvedDigest = "sha256:${"cd".repeat(32)}",
                    pulledAt = at.minusSeconds(600),
                ),
            runtime =
                RuntimeIdentity(
                    node = nodeName("node-a"),
                    sandboxId = "sandbox-proxy-0123",
                    containerId = "container-proxy-9876",
                    createdAt = at.minusSeconds(500),
                    startedAt = at.minusSeconds(480),
                    finishedAt = at.minusSeconds(5),
                    exitCode = 143,
                    restartCount = 2,
                ),
            endpoint = ServerEndpoint(node = nodeName("node-a"), address = "10.42.0.2", port = 25577),
            players = PlayerOccupancy(online = 0, max = 500, observedAt = at.minusSeconds(2)),
            backends = backends,
            control =
                ControlEndpointStatus(
                    reachable = true,
                    pluginApiVersion = "1.4.2",
                    compatible = true,
                    lastContactAt = at.minusSeconds(3),
                    // Deliberately not the default. A fixture that leaves an
                    // optional field at its default cannot fail for a codec that
                    // drops the key: the object comes back equal because the
                    // constructor rebuilt the same value, and the round-trip test
                    // measures the constructor rather than the encoding.
                    credential = ControlCredential.ACCEPTED,
                ),
            drain = fullDrain(drainState, at),
            failure =
                FailureStatus(
                    reason = FailureReason.PROXY_PLUGIN_INCOMPATIBLE,
                    failureClass = FailureClass.PERMANENT,
                    message = "the proxy plugin reports an api version this build does not speak",
                    occurredAt = at.minusSeconds(1),
                    attempts = 2,
                ),
            conditions =
                listOf(
                    StatusCondition(
                        type = ConditionType.BACKENDS_RESOLVED,
                        status = ConditionStatus.TRUE,
                        message = "",
                        lastTransitionAt = at.minusSeconds(600),
                    ),
                    StatusCondition(
                        type = ConditionType.CONTROL_ENDPOINT_READY,
                        status = ConditionStatus.FALSE,
                        message = "an = sign, a\nnewline and a \\ backslash, to prove the encoding escapes them",
                        lastTransitionAt = at,
                    ),
                ),
        )

    /**
     * One backend per [BackendRegistration], so no value of the enum goes
     * unexercised, and with `drainInitiated` set on exactly one of them.
     *
     * That flag is the reason this fixture is careful: the drain reads it to
     * exclude a destination that is itself draining. If the codec dropped it every
     * backend here would come back eligible, and the test would still pass on
     * every other field.
     */
    fun fullBackends(at: Instant = T0): BackendRoutingStatus =
        BackendRoutingStatus(
            observedAt = at.minusSeconds(4),
            backends =
                BackendRegistration.entries.mapIndexed { index, registration ->
                    BackendStatus(
                        server = resourceName("survival-0$index"),
                        registration = registration,
                        players =
                            if (index == 0) {
                                null
                            } else {
                                PlayerOccupancy(online = index, max = 40, observedAt = at.minusSeconds(6L + index))
                            },
                        drainInitiated = registration == BackendRegistration.SEALED,
                        lastTransitionAt = at.minusSeconds(10L + index),
                    )
                },
        )

    /**
     * A drain with every field populated that can be populated at once.
     *
     * [saveRequestedAt] and [worldSavedAt] are disjoint by design — a confirmed
     * save has no outstanding request — so one fixture cannot carry both. This
     * one is the *unconfirmed* case, which is the wedge that must never be lost
     * in a round trip. [confirmedDrain] covers the other.
     */
    fun fullDrain(
        state: DrainState,
        at: Instant = T0,
    ): DrainStatus =
        DrainStatus(
            state = state,
            startedAt = at.minusSeconds(120),
            enteredStateAt = at.minusSeconds(20),
            playersEvacuated = true,
            sealRequestedAt = at.minusSeconds(115),
            saveRequestedAt = at.minusSeconds(20),
            // The two set-once anchors, both of which decide a bound the drain
            // cannot reach if a round trip drops them: one hands step 4 its
            // allowance back on every restart, the other restarts the count of a
            // drain that keeps re-saving and never stops.
            resaveForcedAt = at.minusSeconds(95),
            transferStartedAt = at.minusSeconds(100),
            deregisteredAt = null,
            // Only in `STOPPING`, because only there is it a record a build could
            // have written: a drain reaches that state exactly when a stop request
            // returned cleanly, so `STOPPING` with no dispatch is a document no
            // version of this orchestrator produces. `StatusReconstruction` restores
            // one on the way out of a store, so a fixture carrying the impossible
            // pair would make the round-trip tests assert that a store hands back
            // something it is required not to hand back. Distinct from
            // `enteredStateAt` on purpose — a store that dropped the key would be
            // served the reconstruction, and the round trip has to be able to see
            // the difference.
            stopDispatchedAt = if (state == DrainState.STOPPING) at.minusSeconds(8) else null,
            transferAttempts = 4,
            destination = resourceName("lobby-01"),
            failure =
                FailureStatus(
                    reason = FailureReason.DRAIN_TRANSFER_FAILED,
                    failureClass = FailureClass.RETRYABLE,
                    message = "2 of 6 transfers were refused by the destination",
                    occurredAt = at.minusSeconds(60),
                    attempts = 4,
                ),
        )

    /**
     * A drain holding a *confirmed* save: the server reported the write
     * completed, so there is no outstanding request.
     *
     * The pair with [fullDrain] is what makes the round trip meaningful. These
     * two records differ by one timestamp moving between two keys, and reading
     * one back as the other is the difference between a container that may stop
     * and a drain that needs a human.
     */
    fun confirmedDrain(
        state: DrainState = DrainState.STOPPING,
        at: Instant = T0,
    ): DrainStatus =
        fullDrain(state, at).copy(
            saveRequestedAt = null,
            worldSavedAt = at.minusSeconds(20),
            deregisteredAt = at.minusSeconds(10),
            // The record that a stop request left the orchestrator, and the one
            // field here whose loss is a *reversal* rather than a repeat: a drain
            // that comes back without it puts the backend into the proxy's routing
            // table on its next park, sending players to a container that has been
            // sent SIGTERM. It only makes sense past the deregistration, which is
            // why it sits on this fixture and not on [fullDrain] — and why this one
            // is `STOPPING`.
            stopDispatchedAt = at.minusSeconds(5),
            failure = null,
        )

    /**
     * A drain that is parked and *not* failing: people are online and there is
     * nowhere to send them.
     *
     * The third record in the set, and the one whose defining feature is a null.
     * `blocked` and `failure` are disjoint by construction, so a fixture cannot
     * carry both — and a round trip that quietly resurrected a failure here would
     * put a healthy server back into the escalation path on the first read after a
     * restart.
     */
    fun blockedDrain(at: Instant = T0): DrainStatus =
        fullDrain(DrainState.DRAIN_FAILED, at).copy(
            saveRequestedAt = null,
            playersEvacuated = false,
            destination = null,
            failure = null,
            blocked =
                DrainBlock(
                    reason = DrainBlockReason.AWAITING_ZERO_PLAYERS,
                    message = "3 of 20 player slots are in use; an = sign and a \\ backslash, to prove escaping",
                    since = at.minusSeconds(900),
                    observations = 37,
                ),
        )
}
