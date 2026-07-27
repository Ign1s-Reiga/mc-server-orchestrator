package mcorch.store

import mcorch.schema.ConditionStatus
import mcorch.schema.ConditionType
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
import mcorch.schema.fixtures.ExampleDefinitions
import mcorch.schema.getOrThrow
import mcorch.schema.yaml.ServerDefinitionParser
import java.time.Instant

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
            deregisteredAt = null,
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
        state: DrainState = DrainState.DEREGISTERED,
        at: Instant = T0,
    ): DrainStatus =
        fullDrain(state, at).copy(
            saveRequestedAt = null,
            worldSavedAt = at.minusSeconds(20),
            deregisteredAt = at.minusSeconds(10),
            failure = null,
        )
}
