package mcorch.core.paper

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import mcorch.core.Labels
import mcorch.core.StorageRequest
import mcorch.core.memory
import mcorch.core.paperDefinition
import mcorch.core.resourceName
import mcorch.schema.MemoryQuantity
import mcorch.schema.RconSpec
import mcorch.schema.StorageSpec
import mcorch.schema.VolumeSpec
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

/** Turning a definition into a workload, and reading a server's answers. */
internal class PaperWorkloadTest {
    @Test
    fun `the spec hash changes with the container shape and not with anything else`() {
        val base = PaperWorkloadPlanner.specHash(paperDefinition())

        // Same definition, same hash — this is what stops a repeated pass from
        // deciding the container needs replacing.
        PaperWorkloadPlanner.specHash(paperDefinition()) shouldBe base

        PaperWorkloadPlanner.specHash(paperDefinition(image = "docker.io/itzg/minecraft-server:2026.7.0")) shouldNotBe
            base
        PaperWorkloadPlanner.specHash(paperDefinition(memoryBytes = 8L * MemoryQuantity.GIB)) shouldNotBe base
        PaperWorkloadPlanner.specHash(paperDefinition(storage = StorageSpec.Ephemeral())) shouldNotBe base
        PaperWorkloadPlanner.specHash(paperDefinition(rcon = RconSpec.Disabled)) shouldNotBe base
        PaperWorkloadPlanner.specHash(paperDefinition(maxPlayers = 40)) shouldNotBe base
        PaperWorkloadPlanner.specHash(paperDefinition(hostPort = 30099)) shouldNotBe base

        // Lifecycle timings do not reshape the container, so they must not
        // provoke a recreate of a server with players on it.
        PaperWorkloadPlanner.specHash(paperDefinition(saveTimeout = 9.minutes)) shouldBe base
        PaperWorkloadPlanner.specHash(paperDefinition(startupTimeout = 20.minutes)) shouldBe base
    }

    @Test
    fun `the workload carries the labels a later pass finds it by`() {
        val spec = PaperWorkloadPlanner.plan(paperDefinition())

        spec.labels[Labels.MANAGED_BY] shouldBe Labels.MANAGER
        spec.labels[Labels.SERVER] shouldBe "survival-01"
        spec.labels[Labels.KIND] shouldBe "PaperServer"
    }

    @Test
    fun `persistent storage names a volume and ephemeral storage does not`() {
        val persistent =
            PaperWorkloadPlanner
                .plan(
                    paperDefinition(storage = StorageSpec.Persistent(VolumeSpec(resourceName("w")), "/data")),
                ).storage
        persistent.shouldBeInstanceOf<StorageRequest.Persistent>().volume shouldBe resourceName("w")

        PaperWorkloadPlanner
            .plan(paperDefinition(storage = StorageSpec.Ephemeral()))
            .storage
            .shouldBeInstanceOf<StorageRequest.Ephemeral>()
    }

    @Test
    fun `RCON is published inside the sandbox only, and only when it is enabled`() {
        val enabled = PaperWorkloadPlanner.plan(paperDefinition())
        val rcon = enabled.ports.single { it.name == PaperWorkloadPlanner.RCON_PORT_NAME }
        rcon.containerPort shouldBe 25575
        rcon.hostPort shouldBe null

        val disabled = PaperWorkloadPlanner.plan(paperDefinition(rcon = RconSpec.Disabled))
        disabled.ports.none { it.name == PaperWorkloadPlanner.RCON_PORT_NAME }.shouldBeTrue()
        disabled.env[PaperImageContract.ENABLE_RCON] shouldBe "false"
    }

    @Test
    fun `heap is rendered the way a JVM reads it`() {
        PaperImageContract.jvmMemory(memory(6L * MemoryQuantity.GIB)) shouldBe "6G"
        PaperImageContract.jvmMemory(memory(1536L * MemoryQuantity.MIB)) shouldBe "1536M"
        PaperImageContract.jvmMemory(memory(512L * MemoryQuantity.KIB)) shouldBe "512K"
        PaperImageContract.jvmMemory(memory(12345L)) shouldBe "12345"
    }

    @Test
    fun `a Server List Ping reply is read as an occupancy`() {
        val occupancy =
            PaperCommands
                .parseOccupancy("2026/07/26 10:00:00 version=1.21.8 online=3 max=20 motd=hi")
                .shouldNotBeNull()
        occupancy.online shouldBe 3
        occupancy.max shouldBe 20

        PaperCommands.parseOccupancy("connection refused") shouldBe null
        PaperCommands.parseOccupancy("online=2") shouldBe null
    }

    @Test
    fun `only a completed save counts as a completed save`() {
        PaperCommands.confirmsSave("Saved the game").shouldBeTrue()
        PaperCommands.confirmsSave("Saved the world").shouldBeTrue()

        // The acknowledgement is not the completion.
        PaperCommands.confirmsSave("Saving the game (this may take a moment!)").shouldBeFalse()
        PaperCommands.confirmsSave("Unknown command. Try /help").shouldBeFalse()
        PaperCommands.confirmsSave("").shouldBeFalse()
    }
}
