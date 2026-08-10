package mcorch.core.paper

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.maps.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import mcorch.core.Labels
import mcorch.core.StorageRequest
import mcorch.core.WorkloadHandle
import mcorch.core.WorkloadObservation
import mcorch.core.WorkloadState
import mcorch.core.memory
import mcorch.core.nodeName
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

    /**
     * The hash is a list of **definition fields**, and no label the planner writes
     * is among them.
     *
     * This is the claim the decision to record a fact as a *label* rests on:
     * labels are outside the fingerprint, so adding one recreates no container and
     * drains no fleet.
     *
     * ## Read this before extending it, because the obvious instrument cannot work
     *
     * **Do not reach for "hash two things and compare".** Every other assertion in
     * the sibling test does exactly that, and it is the wrong tool here for a
     * structural reason: what is being asserted is an **absence**, and a digest
     * comparison can only demonstrate that two inputs *differ*. To show that
     * labels are not an input you would need a definition edit that adds a label
     * and changes nothing else hashed — and there is none, because the label
     * values are themselves derived from fields the hash already reads. So the
     * input *list* has to be exposed and read. That is the whole reason
     * [canonicalSpec] exists separately from `specHash`, and until it did, this
     * property was true by reading the function and by nothing else: folding
     * `spec.labels` in would have been caught by no test in this repository.
     *
     * The same shape shows up whenever a decision rests on something *not* being
     * consulted. Ask what would have to be exposed before the absence is
     * assertable at all, rather than assuming the existing instrument reaches it.
     *
     * ## Why keys and not values
     *
     * The scan is over label **keys**, and that is not a shortcut. One label value
     * is deliberately in the hash — [Labels.VOLUME] carries the volume name and
     * `storage.volume` is a hashed field — so a scan over values would be
     * asserting something false. It is also the reason that label is safe to read
     * back as an *observation* rather than as a snapshot that goes stale: renaming
     * a volume already recreates the container, so the label and the container it
     * sits on cannot come to disagree.
     *
     * The positive assertions are not decoration. Without them this is a scan over
     * a string that a typo in [canonicalSpec]'s name would satisfy by returning
     * something else entirely.
     */
    @Test
    fun `the spec hash lists definition fields, and no label the planner writes is one of them`() {
        val definition = paperDefinition()
        val canonical = PaperWorkloadPlanner.canonicalSpec(definition)

        canonical shouldContain "image=${definition.spec.image.canonical}"
        canonical shouldContain "storage.mode=persistent"
        canonical shouldContain "storage.volume=survival-01-world"
        // The `none` spelling has nothing to vary against, and it is what keeps an
        // always-ephemeral lobby hashing the way it did before a volume could be
        // named — a definition edit cannot demonstrate it.
        PaperWorkloadPlanner.canonicalSpec(paperDefinition(storage = StorageSpec.Ephemeral())) shouldContain
            "storage.volume=none"

        val labels = PaperWorkloadPlanner.plan(definition).labels
        // The vacuity guard: a scan over an empty key set asserts nothing.
        labels.shouldNotBeEmpty()
        labels.keys.forEach { canonical shouldNotContain it }

        // Label *values* are a different question, and one of them is deliberately
        // in the hash. `Labels.VOLUME` carries the volume name and `storage.volume`
        // is a hashed field — which is exactly what makes reading that label an
        // observation rather than a snapshot that goes stale: renaming a volume
        // already recreates the container, so the label cannot come to disagree
        // with the container it sits on.
        labels[Labels.VOLUME] shouldBe "survival-01-world"
        PaperWorkloadPlanner.specHash(paperDefinition(storage = persistentVolume("other-world"))) shouldNotBe
            PaperWorkloadPlanner.specHash(definition)
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
    fun `the workload records what a later drain has to know about it`() {
        val persistent = PaperWorkloadPlanner.plan(paperDefinition())
        persistent.labels[Labels.WORLD_DATA] shouldBe "true"
        persistent.labels[Labels.SAVE_CONFIRMABLE] shouldBe "true"

        val disposable =
            PaperWorkloadPlanner.plan(
                paperDefinition(storage = StorageSpec.Ephemeral(), rcon = RconSpec.Disabled),
            )
        disposable.labels[Labels.WORLD_DATA] shouldBe "false"
        disposable.labels[Labels.SAVE_CONFIRMABLE] shouldBe "false"
    }

    /**
     * The volume is recorded on the container, and **absent rather than empty**
     * when there is none.
     *
     * The asymmetry with [Labels.WORLD_DATA] — which writes `false` — is the whole
     * design. Absent has to mean "the previous record stands", which is right both
     * for a workload with no volume and for one created before this label existed;
     * a workload that mounts nothing must not erase the record of the volume that
     * still holds the world it stopped mounting.
     *
     * An empty value would be defused by [Labels.volumeValue] rejecting it, so
     * this is not the last line of defence — but a sentinel spelling is one
     * careless parse away from becoming a claim that clears a carried name, and
     * omitting it costs nothing.
     */
    @Test
    fun `a persistent workload names its volume and an ephemeral one carries no such label`() {
        PaperWorkloadPlanner.plan(paperDefinition()).labels[Labels.VOLUME] shouldBe "survival-01-world"
        PaperWorkloadPlanner
            .plan(paperDefinition(storage = persistentVolume("shared-world")))
            .labels[Labels.VOLUME] shouldBe "shared-world"

        val disposable = PaperWorkloadPlanner.plan(paperDefinition(storage = StorageSpec.Ephemeral()))
        disposable.labels.containsKey(Labels.VOLUME).shouldBeFalse()
    }

    /**
     * A label value the runtime hands back is not this build's to trust, so an
     * unreadable one says nothing instead of throwing inside a reconcile pass.
     *
     * "Says nothing" and "is not there" are answered identically on purpose: the
     * caller's response to both is to keep the record it already has. The
     * alternative is a server that stops converging because something wrote a
     * label by hand.
     */
    @Test
    fun `an unreadable volume label says nothing rather than failing a pass`() {
        Labels.volumeValue(mapOf(Labels.VOLUME to "survival-01-world")) shouldBe resourceName("survival-01-world")
        Labels.volumeValue(emptyMap()) shouldBe null
        Labels.volumeValue(mapOf(Labels.VOLUME to "Not A Resource Name")) shouldBe null
        Labels.volumeValue(mapOf(Labels.VOLUME to "")) shouldBe null
    }

    private fun persistentVolume(name: String) = StorageSpec.Persistent(VolumeSpec(resourceName(name)))

    @Test
    fun `a workload that records nothing is read as holding a world, never as the definition says`() {
        val agent = PaperServerAgent(paperDefinition(storage = StorageSpec.Ephemeral()))

        fun observationWith(labels: Map<String, String>) =
            WorkloadObservation.Present(
                handle = WorkloadHandle(nodeName("node-a"), "sandbox-1", "container-1"),
                state = WorkloadState.RUNNING,
                labels = labels,
            )

        // The label is the container's own word and always wins.
        agent
            .contractOf(observationWith(mapOf(Labels.WORLD_DATA to "false")))
            .holdsWorldData
            .shouldBeFalse()
        agent
            .contractOf(observationWith(mapOf(Labels.WORLD_DATA to "true")))
            .holdsWorldData
            .shouldBeTrue()

        // With no label there is no second source worth asking. The definition
        // is the thing being edited — this one says `ephemeral`, and the
        // question is being asked *because* it may have just been changed to say
        // that. Observed status is no longer a copy of it (it is read off this
        // same label), so on an unlabelled container it says nothing either.
        // Neither is consulted: unknown means the safe side, per CLAUDE.md
        // invariant 2.
        agent.contractOf(observationWith(emptyMap())).holdsWorldData.shouldBeTrue()

        // Nothing derived from a guess is reported as observed.
        agent.contractOf(observationWith(emptyMap())).observed.shouldBeFalse()
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
    fun `the save command sends flush, and that argument is the safety`() {
        // Pinned as an exact list, not as "it contains save-all". Verified
        // against a real Paper server: `save-all flush` blocks until the write
        // completes, while plain `save-all` replies `Saved the game` — the same
        // bytes — about six seconds before the write finishes. Nothing
        // downstream can tell those two replies apart, so dropping this argument
        // would let a container stop mid-write while the loop believed the
        // world was on disk.
        PaperCommands.saveAll() shouldBe listOf("rcon-cli", "save-all", "flush")

        // The confirmation pattern cannot save you here: it matches the early
        // reply exactly as well as the real one. That is why the argument is
        // pinned rather than the reply.
        PaperCommands.confirmsSave("Saved the game").shouldBeTrue()
    }

    @Test
    fun `a Server List Ping summary is read without depending on its shape`() {
        // Real `mc-monitor status` output. Note `version=Paper 1.21.4` contains
        // a space, so nothing may split this positionally.
        val real = "127.0.0.1:25565 : version=Paper 1.21.4 online=0 max=20 motd='mcorch probe'"

        val occupancy = PaperCommands.parseOccupancy(real).shouldNotBeNull()

        occupancy.online shouldBe 0
        occupancy.max shouldBe 20
        // And nothing operator-supplied in the line is treated as a diagnostic.
        PaperCommands.diagnostics(real) shouldBe emptyList()
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
