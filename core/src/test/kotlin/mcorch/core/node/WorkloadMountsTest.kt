package mcorch.core.node

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import mcorch.core.NodeException
import mcorch.core.NodeOperation
import mcorch.core.StorageRequest
import mcorch.core.WorkloadAsset
import mcorch.core.WorkloadSpec
import mcorch.core.nodeName
import mcorch.core.paper.PaperWorkloadPlanner
import mcorch.core.paperDefinition
import mcorch.core.proxy.VelocityWorkloadPlanner
import mcorch.core.proxyDefinition
import mcorch.schema.StorageSpec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * What actually gets mounted into a container, from the request a planner made.
 *
 * ## Why this test exists at all
 *
 * A planner asked for the Velocity control plugin to be inside the proxy
 * container. The node's mount derivation had a branch that returned
 * `emptyList()` and threw the requested path away, so the JAR was never
 * delivered, the plugin never loaded, the control endpoint never existed — and
 * every backend behind that proxy was undrainable. Twenty-three static audit
 * rounds and a full unit suite passed over it, because the request and the drop
 * are one layer apart and the drop lived in the one file `:core` tests may not
 * reach into (`LocalNode` is the only place allowed to name a `mcorch.cri`
 * type).
 *
 * So the derivation moved into [HostPaths.mounts], over this module's own types,
 * and this asserts the whole request-to-mount path with the *real* planners as
 * input. A test that built a [WorkloadSpec] by hand would pass against a planner
 * that stopped asking.
 *
 * The remaining hop — [HostPaths.mounts] to the CRI mount list — is pinned
 * structurally at the bottom of this file, because that is the hop the defect
 * lived in and no behavioural test in this module can see it.
 */
internal class WorkloadMountsTest {
    private val node = nodeName("node-a")

    @Test
    fun `a persistent world is mounted read-write at the path the definition declared`(
        @TempDir root: Path,
    ) {
        val spec = PaperWorkloadPlanner.plan(paperDefinition())

        val mounts = HostPaths.mounts(node, root.resolve("volumes"), root.resolve("assets"), spec)

        val world = mounts.single()
        world.containerPath shouldBe "/data"
        world.hostPath shouldBe root.resolve("volumes").resolve("survival-01-world").toString()
        // A world the server cannot write to is a server that cannot save.
        world.readOnly.shouldBeFalse()
    }

    @Test
    fun `an ephemeral workload gets no mount, and carries no path that could be dropped`(
        @TempDir root: Path,
    ) {
        val spec = PaperWorkloadPlanner.plan(paperDefinition(storage = StorageSpec.Ephemeral()))

        spec.storage shouldBe StorageRequest.Ephemeral
        HostPaths.mounts(node, root.resolve("volumes"), root.resolve("assets"), spec).shouldBeEmpty()
    }

    /**
     * The defect, end to end within this module: what the proxy planner asks for
     * is what the node mounts.
     *
     * Both halves are load-bearing and both were wrong. The *content* is the
     * plugin JAR — a container path under the image's plugin directory, backed by
     * a real file on this node — and the *count* is one, because the ephemeral
     * storage beside it must still contribute nothing.
     */
    @Test
    fun `the proxy's control plugin is mounted read-only from the node's asset root`(
        @TempDir root: Path,
    ) {
        val assets = root.resolve("assets")
        val jar = install(assets)
        val spec = VelocityWorkloadPlanner.plan(proxyDefinition())

        val mounts = HostPaths.mounts(node, root.resolve("volumes"), assets, spec)

        val plugin = mounts.single()
        plugin.containerPath shouldBe
            "${VelocityWorkloadPlanner.PLUGIN_DIRECTORY}/${WorkloadAsset.VELOCITY_CONTROL_PLUGIN.fileName}"
        plugin.hostPath shouldBe jar.toString()
        // A container that could rewrite the artefact could change what the next
        // one loads.
        plugin.readOnly.shouldBeTrue()
    }

    /**
     * The failure that must not be silent.
     *
     * A missing artefact is not a mount the runtime can improvise: it would
     * create an empty directory, the proxy would start, serve players, and have
     * no control endpoint. Refusing at create time is the only point where that
     * is visible before a drain needs it.
     *
     * Permanent, on the rule the rest of [HostPaths] follows — nothing this loop
     * does puts a JAR on a host — so it surfaces on observed status and stops
     * being retried.
     */
    @Test
    fun `a workload whose asset the node does not have is refused permanently`(
        @TempDir root: Path,
    ) {
        val spec = VelocityWorkloadPlanner.plan(proxyDefinition())

        val failure =
            shouldThrow<NodeException> {
                HostPaths.mounts(node, root.resolve("volumes"), root.resolve("assets"), spec)
            }

        failure.retryable.shouldBeFalse()
        failure.operation shouldBe NodeOperation.CREATE
        failure.node shouldBe node
        // Enough for an operator to act on: which artefact, where it was looked
        // for, and what running without it would have cost.
        failure.message shouldContain WorkloadAsset.VELOCITY_CONTROL_PLUGIN.name
        failure.message shouldContain WorkloadAsset.VELOCITY_CONTROL_PLUGIN.fileName
        failure.message shouldContain "undrainable"
    }

    @Test
    fun `a directory where the artefact should be is refused too, rather than mounted`(
        @TempDir root: Path,
    ) {
        val assets = root.resolve("assets")
        Files.createDirectories(assets.resolve(WorkloadAsset.VELOCITY_CONTROL_PLUGIN.fileName))
        val spec = VelocityWorkloadPlanner.plan(proxyDefinition())

        shouldThrow<NodeException> {
            HostPaths.mounts(node, root.resolve("volumes"), assets, spec)
        }.retryable.shouldBeFalse()
    }

    /**
     * The hop this module cannot test behaviourally, pinned by shape.
     *
     * `LocalNode` is the only file allowed to name a `mcorch.cri` type, so no
     * test here can call it and read back a `VolumeMount`. The defect was
     * precisely a second, private derivation living in that blind spot — a
     * `when` whose `Ephemeral` branch answered `emptyList()` — so what is
     * asserted is that no such derivation exists any more: every mount the node
     * hands the runtime is built from [HostPaths.mounts], in one place, and the
     * fields are copied rather than recomputed.
     *
     * Red-proof: reinstating the old branch — or building a `VolumeMount`
     * anywhere else in the file — fails this. A rename of `mounts` fails it too,
     * loudly, which is the intended cost of a claim about a source.
     */
    @Test
    fun `every mount the local node hands the runtime comes from the one derivation`() {
        val source = Path.of(LOCAL_NODE)
        withClue("expected the module directory as the working directory; no $LOCAL_NODE") {
            Files.isRegularFile(source).shouldBeTrue()
        }
        val lines = source.readText().lines().filter { it.trimStart().startsWith("//").not() }

        // Vacuity guards: a scan that read the wrong file, or one that stopped
        // finding either end of the copy, satisfies the assertions below by
        // accident.
        lines.size shouldBeGreaterThan 100
        lines.filter { it.contains("HostPaths.mounts(") } shouldHaveSize 1
        // One construction, so there is no second place a mount can come from.
        lines.filter { it.contains("VolumeMount(") } shouldHaveSize 1

        val derived = lines.indexOfFirst { it.contains("HostPaths.mounts(") }
        val built = lines.indexOfFirst { it.contains("VolumeMount(") }
        withClue("the one VolumeMount is built from the derivation, not before it") {
            (built > derived).shouldBeTrue()
        }
        // And built *from* it rather than beside it: a `when` on the storage or on
        // the assets between the two is the shape the dropped mount was written
        // in, and so is a bare `emptyList()`.
        val between = lines.slice(derived..built)
        between.size shouldBeGreaterThan 1
        between.none { it.contains("when (") || it.contains("emptyList()") }.shouldBeTrue()
    }

    private fun install(assets: Path): Path {
        Files.createDirectories(assets)
        val jar = assets.resolve(WorkloadAsset.VELOCITY_CONTROL_PLUGIN.fileName)
        Files.writeString(jar, "not really a JAR, but a readable regular file is what is asked")
        return jar
    }

    private companion object {
        const val LOCAL_NODE: String = "src/main/kotlin/mcorch/core/node/LocalNode.kt"
    }
}
