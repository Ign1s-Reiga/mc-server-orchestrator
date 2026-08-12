package mcorch.schema.yaml

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import mcorch.schema.DrainPolicy
import mcorch.schema.ImageRef
import mcorch.schema.JvmHeapPolicy
import mcorch.schema.MemoryQuantity
import mcorch.schema.PaperServerDefaults
import mcorch.schema.PaperServerDefinition
import mcorch.schema.ParseResult
import mcorch.schema.RconSpec
import mcorch.schema.ResourceName
import mcorch.schema.SchemaVersion
import mcorch.schema.ServerKind
import mcorch.schema.StorageSpec
import mcorch.schema.getOrThrow
import mcorch.schema.violations
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The valid examples, and the defaults an omitted field is required to produce. */
class PaperServerParseTest {
    private fun parse(name: String): PaperServerDefinition {
        val result = ServerDefinitionParser.parse(Fixtures.load(name), name)
        result.violations().forEach { println(it.render()) }
        return result.getOrThrow().shouldBeInstanceOf<PaperServerDefinition>()
    }

    private fun memory(text: String): MemoryQuantity = MemoryQuantity.parse(text).getOrThrow()

    @Test
    fun `minimal definition parses`() {
        val definition = parse("valid/minimal.yaml")

        definition.apiVersion shouldBe SchemaVersion.V1ALPHA1
        definition.kind shouldBe ServerKind.PAPER_SERVER
        definition.metadata.name shouldBe ResourceName.of("survival-01").getOrThrow()
        definition.metadata.labels shouldBe emptyMap()
        definition.spec.image shouldBe
            ImageRef.Tagged("docker.io", "itzg/minecraft-server", "2026.6.1")
        definition.spec.paper.minecraftVersion.value shouldBe "1.21.8"
        definition.spec.paper.build
            .shouldBeNull()
    }

    @Test
    fun `an omitted storage block defaults to a persistent volume named after the server`() {
        val storage = parse("valid/minimal.yaml").spec.storage

        val persistent = storage.shouldBeInstanceOf<StorageSpec.Persistent>()
        persistent.volume.name shouldBe ResourceName.of("survival-01").getOrThrow()
        persistent.volume.size.shouldBeNull()
        persistent.mountPath shouldBe "/data"
    }

    @Test
    fun `an omitted lifecycle block still drains, and keeps the grace period above the save timeout`() {
        val lifecycle = parse("valid/minimal.yaml").spec.lifecycle

        lifecycle.drain.policy shouldBe DrainPolicy.WAIT_FOR_ZERO_PLAYERS
        lifecycle.drain.playerTransferTimeout shouldBe 120.seconds
        lifecycle.drain.saveTimeout shouldBe 180.seconds
        lifecycle.stopGracePeriod shouldBe 240.seconds
        (lifecycle.stopGracePeriod > lifecycle.drain.saveTimeout) shouldBe true
        lifecycle.startupTimeout shouldBe 5.minutes
    }

    @Test
    fun `an omitted heap is sized to leave the container headroom`() {
        val resources = parse("valid/minimal.yaml").spec.resources

        resources.memory shouldBe memory("4Gi")
        resources.heap.max shouldBe memory("3276Mi")
        resources.heap.min shouldBe resources.heap.max
        resources.cpu.shouldBeNull()
        (resources.heap.max < resources.memory) shouldBe true
        (resources.heap.max <= JvmHeapPolicy.maxAllowedHeap(resources.memory)) shouldBe true
    }

    @Test
    fun `omitted network settings default to the vanilla ports`() {
        val network = parse("valid/minimal.yaml").spec.network

        network.port shouldBe PaperServerDefaults.GAME_PORT
        network.hostPort.shouldBeNull()
        // The block itself is required — the secret cannot be defaulted — but the
        // port inside it can be, and is.
        network.rcon.port shouldBe PaperServerDefaults.RCON_PORT
        parse("valid/minimal.yaml").spec.maxPlayers shouldBe 20
        parse("valid/minimal.yaml")
            .spec.placement.node
            .shouldBeNull()
    }

    @Test
    fun `fully populated definition parses`() {
        val definition = parse("valid/full.yaml")
        val spec = definition.spec

        definition.metadata.labels shouldBe mapOf("tier" to "survival", "region" to "eu-west")
        spec.image shouldBe
            ImageRef.Digested(
                registry = "registry.example.com:5000",
                repository = "mc/paper",
                digest = "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            )
        spec.paper.build shouldBe 42
        spec.maxPlayers shouldBe 60
        spec.network.port shouldBe 25565
        spec.network.hostPort shouldBe 30002
        spec.resources.memory shouldBe memory("8Gi")
        spec.resources.cpu?.millicores shouldBe 2500
        spec.resources.heap.max shouldBe memory("6Gi")
        spec.lifecycle.drain.saveTimeout shouldBe 5.minutes
        spec.lifecycle.stopGracePeriod shouldBe 390.seconds
        spec.placement.node?.value shouldBe "node-a"
    }

    @Test
    fun `rcon is a reference to the secret store, never a password`() {
        val rcon = parse("valid/full.yaml").spec.network.rcon

        rcon.port shouldBe 25575
        rcon.passwordSecret.name shouldBe ResourceName.of("survival-02-rcon").getOrThrow()
        rcon.passwordSecret.key shouldBe "password"
        // Nothing that could hold secret material exists on the type, so no
        // rendering of it can leak one.
        rcon.toString().contains("hunter") shouldBe false
    }

    @Test
    fun `ephemeral storage is available but must be asked for by name`() {
        val storage = parse("valid/ephemeral-lobby.yaml").spec.storage

        storage.shouldBeInstanceOf<StorageSpec.Ephemeral>().mountPath shouldBe "/data"
        // Still drains, still keeps a grace period above the save timeout.
        val lifecycle = parse("valid/ephemeral-lobby.yaml").spec.lifecycle
        lifecycle.drain.policy shouldBe DrainPolicy.WAIT_FOR_ZERO_PLAYERS
        lifecycle.stopGracePeriod shouldBe 90.seconds
    }

    @Test
    fun `a multi-document file parses into one definition per document`() {
        val result = ServerDefinitionParser.parseAll(Fixtures.load("valid/multi-document.yaml"))

        result.getOrThrow().map { it.metadata.name.value } shouldContainExactly listOf("survival-a", "survival-b")
    }

    @Test
    fun `a single-document parse rejects a multi-document input`() {
        val result = ServerDefinitionParser.parse(Fixtures.load("valid/multi-document.yaml"))

        val invalid = result.shouldBeInstanceOf<ParseResult.Invalid>()
        invalid.violations
            .single()
            .problem
            .contains("2 YAML documents") shouldBe true
    }

    @Test
    fun `duplicate names within one input are rejected`() {
        val document = Fixtures.load("valid/minimal.yaml")
        val result = ServerDefinitionParser.parseAll("$document---\n$document")

        val invalid = result.shouldBeInstanceOf<ParseResult.Invalid>()
        invalid.violations.single().field shouldBe "metadata.name"
    }

    @Test
    fun `parsing the same document twice produces an equal definition`() {
        // The store and the loop compare specs to decide whether anything
        // changed; parsing has to be a pure function for that to work.
        parse("valid/full.yaml") shouldBe parse("valid/full.yaml")
    }
}
