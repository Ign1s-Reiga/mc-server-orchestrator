package mcorch.core.node

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import mcorch.core.AssetMount
import mcorch.core.NodeException
import mcorch.core.NodeOperation
import mcorch.core.StorageRequest
import mcorch.core.WorkloadAsset
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

    /**
     * A world mount that is not an absolute path fails the **create** and nothing
     * else.
     *
     * `StorageRequest.Persistent` used to enforce this in its `init`, and that was
     * a worse place for it than it looks. The value is `spec.storage.mountPath` —
     * operator data, which also arrives by a second route, a stored row read back
     * through a codec that does not re-run the reader's validation. An
     * `IllegalArgumentException` there throws out of `Reconciler`'s `Pass`
     * construction, which sits *above* the delete exemption, so the server is
     * recorded `PERMANENT` and becomes unreconcilable — **drain included**. A row
     * that bypassed the reader would therefore freeze a world-holding server rather
     * than merely failing its create, and an undeletable populated server is what
     * produces a manual `crictl stop`.
     *
     * Here it is a `Rejected` on `CREATE`: same permanence, same message to the
     * operator, and a container that already exists is still drained against its
     * own labels without ever asking this type anything.
     */
    @Test
    fun `a world mount that is not absolute is refused by the node rather than by the type`(
        @TempDir root: Path,
    ) {
        val planned = spec()
        val relative =
            planned.copy(
                storage = StorageRequest.Persistent(volume = resourceName("survival-01-world"), mountPath = "data"),
            )

        val refusal =
            shouldThrow<NodeException.Rejected> {
                HostPaths.mounts(node, root.resolve("volumes"), root.resolve("assets"), relative)
            }
        refusal.operation shouldBe NodeOperation.CREATE
        refusal.retryable.shouldBeFalse()
        refusal.message shouldContain "has to start with `/`"
        refusal.message shouldContain "can still be drained"
    }

    /**
     * An artefact must not be mounted at or under the world.
     *
     * Nothing had ever checked the two path-bearing fields against each other —
     * `WorkloadSpec`'s `init` checked assets against assets — and both are now
     * genuinely honoured, which is what makes the gap real rather than theoretical.
     * Overlapping bind mounts are resolved by an ordering this code does not
     * choose, so the outcomes are a read-only artefact sitting on a world
     * directory, or a writable world hiding the control plugin. The second is the
     * silent one: a proxy that starts perfectly and has no control endpoint.
     *
     * Neither planner can spell it today. That is the reason to write it down now
     * and not later — an unreachable rule is cheap, and the rule that was missing
     * here was unreachable right up until `AssetMount` existed.
     */
    @Test
    fun `an asset under the world mount is refused rather than left to the runtime to resolve`(
        @TempDir root: Path,
    ) {
        val planned = spec()
        val world = (planned.storage as StorageRequest.Persistent).mountPath
        val overlapping =
            planned.copy(
                assets = listOf(AssetMount(WorkloadAsset.VELOCITY_CONTROL_PLUGIN, "$world/plugins")),
            )

        val refusal =
            shouldThrow<NodeException.Rejected> {
                HostPaths.mounts(node, root.resolve("volumes"), root.resolve("assets"), overlapping)
            }
        refusal.message shouldContain "at or under its world mount"

        // A sibling directory is not an overlap, and refusing one would be a rule
        // that grows to forbid every asset on a server with a world.
        val beside = planned.copy(assets = listOf(AssetMount(WorkloadAsset.VELOCITY_CONTROL_PLUGIN, "/plugins")))
        shouldThrow<NodeException.Rejected> {
            HostPaths.mounts(node, root.resolve("volumes"), root.resolve("assets"), beside)
        }.message shouldContain "does not have it"
    }
}
