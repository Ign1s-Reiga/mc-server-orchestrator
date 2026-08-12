package mcorch.app.it

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.delay
import mcorch.core.WorkloadObservation
import mcorch.core.WorkloadState
import mcorch.schema.ServerPhase
import mcorch.schema.StorageSpec
import mcorch.store.getOrThrow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.time.Duration.Companion.seconds

/**
 * A Paper server through its whole life, against a real containerd.
 *
 * This is the only place the assumptions get tested. Everything else in this
 * repo runs against a simulator that agrees with the code by construction: the
 * env-var names, the in-container commands, the reply the loop reads as a
 * completed save, whether a sandbox even starts. `add-server-kind` step 7 asks
 * for exactly this — brought up to *actually joinable*, re-applied as a genuine
 * no-op, removed with the world surviving.
 *
 * The negative assertions matter more than the positive ones, as they do
 * throughout the drain protocol: **no stop was issued**, **the container is
 * still running**, **the world is still on disk**.
 */
@Timeout(value = 8, unit = TimeUnit.MINUTES)
internal class PaperServerLifecycleIT {
    @TempDir
    lateinit var root: Path

    private lateinit var harness: ContainerdHarness

    @BeforeEach
    fun open() {
        harness = ContainerdHarness(root)
    }

    @AfterEach
    fun cleanUp() {
        harness.close()
    }

    @Test
    fun `a declared server is brought up until it is actually joinable`() =
        integrationTest {
            val definition = paperServer(name = "it-bringup", hostPort = 30411)
            val name = definition.metadata.name
            harness.putSecret(rconSecret("it-bringup"), "integration-rcon-password")
            harness.declare(definition)
            harness.start(this)

            // `RUNNING` is not the finish line and this is the test that proves
            // why: on the runtime this was written against the container reached
            // RUNNING in about two seconds and the server did not answer a
            // Server List Ping for another forty-one.
            harness.await("the container to be running") {
                (harness.observe(name) as? WorkloadObservation.Present)?.state == WorkloadState.RUNNING
            }
            harness.await("the server to answer a Server List Ping") {
                harness.status(name)?.ready == true
            }

            val status = harness.status(name).shouldNotBeNull()
            status.phase shouldBe ServerPhase.RUNNING
            status.ready.shouldBeTrue()
            // Occupancy came from a real handshake with a real server.
            status.players.shouldNotBeNull().max shouldBe 20
            status.players.shouldNotBeNull().online shouldBe 0
            status.endpoint.shouldNotBeNull().port shouldBe 30411
            // The image was resolved on the node, not assumed.
            status.image
                .shouldNotBeNull()
                .available
                .shouldBeTrue()
            // And the world is where the definition said it would be.
            worldDirectory("it-bringup").exists().shouldBeTrue()
        }

    @Test
    fun `re-applying the same definition changes nothing at all`() =
        integrationTest {
            val definition = paperServer(name = "it-noop", hostPort = 30412)
            val name = definition.metadata.name
            harness.putSecret(rconSecret("it-noop"), "integration-rcon-password")
            harness.declare(definition)
            harness.start(this)
            harness.await("the server to answer a Server List Ping") { harness.status(name)?.ready == true }

            val before = harness.observe(name).shouldBeInstanceOf<WorkloadObservation.Present>()
            val generationBefore =
                harness.store
                    .getServer(name)
                    .shouldNotBeNull()
                    .definition.generation

            // The same bytes again. The store must not move the generation, so
            // the loop must not see a diff, so nothing may be created or
            // stopped.
            harness.store.putDefinition(definition).getOrThrow()
            delay(10.seconds)

            val after = harness.observe(name).shouldBeInstanceOf<WorkloadObservation.Present>()
            after.handle.containerId shouldBe before.handle.containerId
            after.state shouldBe WorkloadState.RUNNING
            harness.store
                .getServer(name)
                .shouldNotBeNull()
                .definition.generation shouldBe generationBefore
            harness
                .status(name)
                .shouldNotBeNull()
                .ready
                .shouldBeTrue()
            // Counted from the runtime rather than from the loop: asking the
            // loop whether the loop made a second container is a test that
            // agrees with itself.
            Crictl.containers(name.value) shouldHaveSize 1
            Crictl.sandboxes(name.value) shouldHaveSize 1
        }

    @Test
    fun `deleting an empty server drains it, saves the world and leaves the world behind`() =
        integrationTest {
            val definition = paperServer(name = "it-drain", hostPort = 30413)
            val name = definition.metadata.name
            harness.putSecret(rconSecret("it-drain"), "integration-rcon-password")
            harness.declare(definition)
            harness.start(this)
            harness.await("the server to answer a Server List Ping") { harness.status(name)?.ready == true }

            val world = worldDirectory("it-drain")
            harness.store.deleteDefinition(name).getOrThrow()

            harness.await("the definition to be purged", timeout = ContainerdHarness.DRAIN_TIMEOUT) {
                harness.store.getServer(name) == null
            }

            // Nothing of the workload is left...
            harness.observe(name).shouldBeInstanceOf<WorkloadObservation.Absent>()
            Crictl.containers(name.value) shouldHaveSize 0
            Crictl.sandboxes(name.value) shouldHaveSize 0
            // ...and everything of the world is. This is CLAUDE.md invariant 2,
            // and it is the assertion that would catch a teardown that took the
            // volume with it.
            world.exists().shouldBeTrue()
            world.listDirectoryEntries().isEmpty().shouldBeFalse()
        }

    @Test
    fun `a definition change drains the old container before it creates the replacement`() =
        integrationTest {
            val definition = paperServer(name = "it-replace", hostPort = 30414)
            val name = definition.metadata.name
            harness.putSecret(rconSecret("it-replace"), "integration-rcon-password")
            harness.declare(definition)
            harness.start(this)
            harness.await("the server to answer a Server List Ping") { harness.status(name)?.ready == true }

            val original = harness.observe(name).shouldBeInstanceOf<WorkloadObservation.Present>()
            val world = worldDirectory("it-replace")

            // `maxPlayers` is in the spec hash, so this asks for a recreate
            // without also asking the node to pull a second image.
            harness.store
                .putDefinition(paperServer(name = "it-replace", hostPort = 30414, maxPlayers = 30))
                .getOrThrow()

            // The old container has to be gone before the new one exists.
            // `failure-modes.md` item 5 is about exactly the opposite ordering,
            // so this samples the runtime while the change is being applied and
            // never allows two.
            var mostContainersSeen = 0
            harness.await("the replacement to be running and joinable") {
                mostContainersSeen = maxOf(mostContainersSeen, Crictl.containers(name.value).size)
                val status = harness.status(name)
                val current = harness.observe(name) as? WorkloadObservation.Present
                status?.ready == true &&
                    current?.state == WorkloadState.RUNNING &&
                    current.handle.containerId != original.handle.containerId
            }

            // `failure-modes.md` item 5: drain the old container first, then
            // create the replacement — never both at once. Sampled from the
            // runtime throughout the change.
            mostContainersSeen shouldBe 1
            harness
                .status(name)
                .shouldNotBeNull()
                .players
                .shouldNotBeNull()
                .max shouldBe 30
            // Same world, new container.
            world.exists().shouldBeTrue()
        }

    // `a server whose save cannot be confirmed keeps running` was here, and is
    // gone because the state it constructed can no longer be constructed.
    //
    // It declared a `PaperServer` with RCON disabled. RCON is standard now, so the
    // rewrite tried the next-closest thing — a password the server would refuse —
    // and that does not work either: `RCON_PASSWORD` is one container environment
    // variable, the image configures the server from it, and `rcon-cli` inside the
    // same container reads the same one. They cannot disagree. The test ran, the
    // drain confirmed its save, the server was deleted, and the assertion was left
    // waiting on a store with nothing in it.
    //
    // A test that cannot fail is worse than no test, so it is not left in place
    // asserting nothing. What it protected is still covered where the condition is
    // reachable: `DrainTest` constructs the container that reports no save channel
    // and pins the refusal against it, which is the layer the guard actually lives
    // at — `PaperServerAgent.contractOf` reads the label off the running workload,
    // not off the definition.
    //
    // Reaching this state against a real server now needs a genuine runtime
    // failure — a wedged main thread, or a long world-generation pass — which is
    // `docs/failure-modes.md`'s territory and is not something a definition can ask
    // for.

    @Test
    fun `an explicitly ephemeral server is stopped without a save and leaves no volume`() =
        integrationTest {
            val definition =
                paperServer(
                    name = "it-lobby",
                    hostPort = 30416,
                    storage = StorageSpec.Ephemeral(),
                )
            harness.putSecret(rconSecret("it-lobby"), "integration-rcon-password")
            val name = definition.metadata.name
            harness.declare(definition)
            harness.start(this)
            harness.await("the server to answer a Server List Ping") { harness.status(name)?.ready == true }

            harness.store.deleteDefinition(name).getOrThrow()
            harness.await("the definition to be purged", timeout = ContainerdHarness.DRAIN_TIMEOUT) {
                harness.store.getServer(name) == null
            }

            harness.observe(name).shouldBeInstanceOf<WorkloadObservation.Absent>()
            // The only kind of server allowed to skip a volume, and it did.
            worldDirectory("it-lobby").exists().shouldBeFalse()
        }

    private fun worldDirectory(server: String): Path = root.resolve("volumes").resolve("$server-world")

    internal companion object {
        @JvmStatic
        @BeforeAll
        fun requireRuntime() {
            ContainerdHarness.requireContainerd()
            check(Files.isReadable(Path.of("/proc/self"))) { "expected to be running on Linux" }
        }
    }
}
