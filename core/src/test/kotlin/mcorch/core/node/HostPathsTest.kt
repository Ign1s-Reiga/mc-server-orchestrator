package mcorch.core.node

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import mcorch.core.NodeException
import mcorch.core.NodeOperation
import mcorch.core.StorageRequest
import mcorch.core.WorkloadSpec
import mcorch.core.nodeName
import mcorch.core.paper.PaperWorkloadPlanner
import mcorch.core.paperDefinition
import mcorch.core.resourceName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The part of the node that fails without a runtime being involved.
 *
 * It is here rather than inside `LocalNode` because it is the only part of that
 * class reachable without a containerd — and because it is where a raw
 * `IOException` used to cross the [mcorch.core.Node] boundary. Everything above
 * that boundary is built on the promise that it does not: the reconcile loop's
 * worker catches [NodeException], and anything else escaping cancels every
 * worker at once.
 */
internal class HostPathsTest {
    private val node = nodeName("node-a")

    private fun spec(): WorkloadSpec = PaperWorkloadPlanner.plan(paperDefinition())

    @Test
    fun `preparing paths creates the log and volume directories and leaves an existing world alone`(
        @TempDir root: Path,
    ) {
        val volumes = root.resolve("volumes")
        val logs = root.resolve("logs")
        val spec = spec()
        val volume = (spec.storage as StorageRequest.Persistent).volume

        HostPaths.prepare(node, volumes, logs, spec)
        val world = HostPaths.volumePath(volumes, volume).resolve("level.dat")
        Files.writeString(world, "a world")

        // The path a restart goes through. Whatever is in the volume is older
        // than the container about to be created and has to outlive it.
        HostPaths.prepare(node, volumes, logs, spec)

        Files.exists(HostPaths.logDirectory(logs, spec.server)).shouldBeTrue()
        Files.readString(world) shouldBe "a world"
    }

    @Test
    fun `a volume directory that cannot be created is a permanent node failure, not a raw IOException`(
        @TempDir root: Path,
    ) {
        val logs = root.resolve("logs")
        // A file where the volume root has to be. Stands in for the full disk,
        // the read-only mount and the wrong owner: all of them arrive here as an
        // `IOException` and none of them is something the loop can fix by trying
        // again.
        val volumes = root.resolve("volumes")
        Files.writeString(volumes, "not a directory")

        val failure =
            shouldThrow<NodeException> {
                HostPaths.prepare(node, volumes, logs, spec())
            }

        failure.retryable.shouldBeFalse()
        failure.operation shouldBe NodeOperation.CREATE
        failure.node shouldBe node
        // The operator gets the path, not a stack trace, and the cause is kept
        // rather than swallowed.
        failure.message.contains("survival-01").shouldBeTrue()
        (failure.cause is java.io.IOException).shouldBeTrue()
    }

    @Test
    fun `a log directory that cannot be created is a permanent node failure too`(
        @TempDir root: Path,
    ) {
        val volumes = root.resolve("volumes")
        val logs = root.resolve("logs")
        Files.writeString(logs, "not a directory")

        shouldThrow<NodeException> {
            HostPaths.prepare(node, volumes, logs, spec())
        }.retryable.shouldBeFalse()
    }

    @Test
    fun `an ephemeral workload asks for no volume directory at all`(
        @TempDir root: Path,
    ) {
        val volumes = root.resolve("volumes")
        val logs = root.resolve("logs")
        val spec =
            PaperWorkloadPlanner.plan(
                paperDefinition(storage = mcorch.schema.StorageSpec.Ephemeral()),
            )

        HostPaths.prepare(node, volumes, logs, spec)

        Files.exists(HostPaths.logDirectory(logs, spec.server)).shouldBeTrue()
        Files.exists(HostPaths.volumePath(volumes, resourceName("survival-01-world"))).shouldBeFalse()
    }
}
