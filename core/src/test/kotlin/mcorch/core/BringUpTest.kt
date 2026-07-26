package mcorch.core

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import mcorch.core.paper.PaperImageContract
import mcorch.schema.MemoryQuantity
import mcorch.schema.ServerPhase
import mcorch.schema.StorageSpec
import mcorch.schema.VolumeSpec
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

/** Bringing a declared Paper server up to actually joinable. */
internal class BringUpTest {
    @Test
    fun `a declared server is pulled, created, started and only then ready`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)

            harness.pass(name).shouldBeInstanceOf<ReconcileOutcome.Progressed>()
            harness.node.pulls shouldHaveSize 1
            harness.node.creates shouldHaveSize 1
            harness.status(name)?.phase shouldBe ServerPhase.CREATING

            harness.pass(name).shouldBeInstanceOf<ReconcileOutcome.Progressed>()
            harness.node.starts shouldHaveSize 1
            harness.status(name)?.phase shouldBe ServerPhase.STARTING

            harness.pass(name).shouldBeInstanceOf<ReconcileOutcome.Settled>()
            val status = harness.status(name).shouldNotBeNull()
            status.phase shouldBe ServerPhase.RUNNING
            status.ready.shouldBeTrue()
            status.observedGeneration shouldBe 1L
            status.endpoint?.port shouldBe 30001
        }

    @Test
    fun `a running container is not a ready server`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            // The process is up, but the server does not answer a Server List
            // Ping — world generation, or a deadlock.
            harness.node.joinable = false

            harness.pass(name)
            harness.pass(name)
            val outcome = harness.pass(name)

            outcome.shouldBeInstanceOf<ReconcileOutcome.Waiting>()
            val status = harness.status(name).shouldNotBeNull()
            status.ready.shouldBeFalse()
            status.phase shouldBe ServerPhase.STARTING
            harness.node.workload
                .shouldBeInstanceOf<WorkloadObservation.Present>()
                .state shouldBe WorkloadState.RUNNING
        }

    @Test
    fun `a server that never becomes joinable surfaces a readiness timeout and is not restarted`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition(startupTimeout = 1.minutes)
            val name = definition.metadata.name
            harness.declare(definition)
            harness.node.joinable = false

            harness.pass(name)
            harness.pass(name)
            harness.clock.advance(5.minutes)
            val outcome = harness.pass(name)

            outcome.shouldBeInstanceOf<ReconcileOutcome.Retry>()
            val status = harness.status(name).shouldNotBeNull()
            status.failure.shouldNotBeNull().reason shouldBe mcorch.schema.FailureReason.READINESS_TIMEOUT
            status.failure.shouldNotBeNull().failureClass shouldBe mcorch.schema.FailureClass.RETRYABLE
            // A restart is a stop path, and a stop path drains first. Nothing
            // was stopped or removed.
            harness.node.stops.shouldHaveSize(0)
            harness.node.removals.shouldHaveSize(0)
        }

    @Test
    fun `a server with persistent storage gets a volume that the workload spec names`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            harness.declare(definition)
            harness.pass(definition.metadata.name)

            val spec = harness.node.creates.single()
            val storage = spec.storage.shouldBeInstanceOf<StorageRequest.Persistent>()
            storage.volume shouldBe resourceName("survival-01-world")
            storage.mountPath shouldBe "/data"
        }

    @Test
    fun `only an explicitly ephemeral server skips the volume`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition(storage = StorageSpec.Ephemeral())
            harness.declare(definition)
            harness.pass(definition.metadata.name)

            harness.node.creates
                .single()
                .storage
                .shouldBeInstanceOf<StorageRequest.Ephemeral>()
            harness.node.volumes.shouldHaveSize(0)
        }

    @Test
    fun `the container memory limit and the JVM heap come straight from the definition`() =
        coreTest {
            val harness = Harness()
            val definition =
                paperDefinition(
                    memoryBytes = 8L * MemoryQuantity.GIB,
                    heapBytes = 6L * MemoryQuantity.GIB,
                )
            harness.declare(definition)
            harness.pass(definition.metadata.name)

            val spec = harness.node.creates.single()
            spec.resources.memoryBytes shouldBe 8L * MemoryQuantity.GIB
            spec.env[PaperImageContract.MAX_MEMORY] shouldBe "6G"
            spec.env[PaperImageContract.INIT_MEMORY] shouldBe "6G"
            spec.env[PaperImageContract.EULA] shouldBe "TRUE"
            spec.env[PaperImageContract.MAX_PLAYERS] shouldBe "20"
        }

    @Test
    fun `the RCON password travels as a reference, never as a value`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            harness.declare(definition)
            harness.pass(definition.metadata.name)

            val spec = harness.node.creates.single()
            spec.env.keys
                .contains(PaperImageContract.RCON_PASSWORD)
                .shouldBeFalse()
            spec.secretEnv[PaperImageContract.RCON_PASSWORD] shouldBe secretRef()
            // Nothing about the value can leak through a log line built from
            // the spec.
            spec.toString().contains("password").shouldBeFalse()
        }

    @Test
    fun `an existing sandbox with no container is adopted rather than duplicated`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            // A previous pass got as far as the sandbox and then died.
            harness.node.workload =
                WorkloadObservation.Present(
                    handle = WorkloadHandle(harness.node.name, "sandbox-$name"),
                    state = WorkloadState.SANDBOX_ONLY,
                    createdAt = harness.clock.instant(),
                )

            harness.pass(name)

            harness.node.creates shouldHaveSize 1
            harness.node.creates
                .single()
                .server shouldBe name
            // The container that was just created is what gets recorded, not the
            // sandbox-only observation the pass started from. Recording the
            // older one leaves `containerId` null for a container that exists
            // and costs a pass rediscovering it.
            harness
                .status(name)
                .shouldNotBeNull()
                .runtime
                .shouldNotBeNull()
                .containerId
                .shouldNotBeNull()
        }

    @Test
    fun `a definition with a volume claim keeps the volume when the workload is removed`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.node.volumes shouldHaveSize 1

            harness.store.deleteDefinition(name)
            harness.settle(name)

            harness.node.removals
                .isNotEmpty()
                .shouldBeTrue()
            // CLAUDE.md invariant 2: the world outlives the container.
            harness.node.volumes shouldBe mutableSetOf(resourceName("survival-01-world"))
        }

    @Test
    fun `a volume spec with an explicit name is honoured`() =
        coreTest {
            val harness = Harness()
            val definition =
                paperDefinition(
                    storage = StorageSpec.Persistent(VolumeSpec(resourceName("shared-world")), "/srv/mc"),
                )
            harness.declare(definition)
            harness.pass(definition.metadata.name)

            val storage =
                harness.node.creates
                    .single()
                    .storage
                    .shouldBeInstanceOf<StorageRequest.Persistent>()
            storage.volume shouldBe resourceName("shared-world")
            storage.mountPath shouldBe "/srv/mc"
        }
}
