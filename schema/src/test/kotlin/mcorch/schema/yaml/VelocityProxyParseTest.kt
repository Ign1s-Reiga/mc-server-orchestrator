package mcorch.schema.yaml

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import mcorch.schema.DrainPolicy
import mcorch.schema.ForwardingMode
import mcorch.schema.ImageRef
import mcorch.schema.JvmHeapPolicy
import mcorch.schema.MemoryQuantity
import mcorch.schema.ResourceName
import mcorch.schema.SchemaVersion
import mcorch.schema.ServerKind
import mcorch.schema.VelocityProxyDefaults
import mcorch.schema.VelocityProxyDefinition
import mcorch.schema.getOrThrow
import mcorch.schema.violations
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The valid proxy examples, and the defaults an omitted field is required to produce. */
class VelocityProxyParseTest {
    private fun parse(name: String): VelocityProxyDefinition {
        val result = ServerDefinitionParser.parse(Fixtures.load(name), name)
        result.violations().forEach { println(it.render()) }
        return result.getOrThrow().shouldBeInstanceOf<VelocityProxyDefinition>()
    }

    private fun memory(text: String): MemoryQuantity = MemoryQuantity.parse(text).getOrThrow()

    @Test
    fun `minimal proxy parses`() {
        val definition = parse("valid/proxy-minimal.yaml")

        definition.apiVersion shouldBe SchemaVersion.V1ALPHA1
        definition.kind shouldBe ServerKind.VELOCITY_PROXY
        definition.metadata.name shouldBe ResourceName.of("proxy-01").getOrThrow()
        definition.spec.image shouldBe ImageRef.Tagged("docker.io", "itzg/mc-proxy", "2026.6.1")
    }

    @Test
    fun `a proxy never holds world data, and cannot be declared to`() {
        // Structural, not a default: there is no storage block to set. A proxy
        // that answered true here would need a save confirmed before it could be
        // stopped, and it has none — so it could never be stopped at all.
        parse("valid/proxy-minimal.yaml").spec.holdsWorldData shouldBe false
        parse("valid/proxy-full.yaml").spec.holdsWorldData shouldBe false
    }

    @Test
    fun `forwarding defaults to modern and carries coordinates, never a value`() {
        val forwarding = parse("valid/proxy-minimal.yaml").spec.forwarding

        forwarding.mode shouldBe ForwardingMode.MODERN
        forwarding.secret.name shouldBe ResourceName.of("fleet-forwarding").getOrThrow()
        forwarding.secret.key shouldBe "modern-forwarding"
        // Nothing on the type can hold the material, so no rendering of it leaks one.
        forwarding.toString().contains("hunter") shouldBe false
    }

    @Test
    fun `an omitted control block is unpublished and needs no token`() {
        val control = parse("valid/proxy-minimal.yaml").spec.control

        control.port shouldBe VelocityProxyDefaults.CONTROL_PORT
        control.hostPort.shouldBeNull()
        control.tokenSecret.shouldBeNull()
    }

    @Test
    fun `an omitted lifecycle block still drains`() {
        val lifecycle = parse("valid/proxy-minimal.yaml").spec.lifecycle

        lifecycle.drain.policy shouldBe DrainPolicy.WAIT_FOR_ZERO_PLAYERS
        lifecycle.drain.sealTimeout shouldBe 10.seconds
        lifecycle.stopGracePeriod shouldBe 60.seconds
        lifecycle.startupTimeout shouldBe 2.minutes
    }

    @Test
    fun `omitted backend drain timings are the state machine's suggested ones`() {
        val drain = parse("valid/proxy-minimal.yaml").spec.backends.drain

        drain.sealTimeout shouldBe 10.seconds
        drain.destinationTimeout shouldBe 30.seconds
        drain.deregisterTimeout shouldBe 10.seconds
    }

    @Test
    fun `an omitted heap is sized to leave the container headroom`() {
        val resources = parse("valid/proxy-minimal.yaml").spec.resources

        resources.memory shouldBe memory("1Gi")
        resources.heap.max shouldBe memory("512Mi")
        resources.heap.min shouldBe resources.heap.max
        resources.cpu.shouldBeNull()
        (resources.heap.max <= JvmHeapPolicy.maxAllowedHeap(resources.memory)) shouldBe true
    }

    @Test
    fun `omitted network settings default to Velocity's own listener`() {
        val spec = parse("valid/proxy-minimal.yaml").spec

        spec.network.port shouldBe VelocityProxyDefaults.PLAYER_PORT
        spec.network.hostPort.shouldBeNull()
        spec.maxPlayers shouldBe VelocityProxyDefaults.MAX_PLAYERS
        spec.placement.node.shouldBeNull()
        spec.backends.fallback shouldContainExactly emptyList()
    }

    @Test
    fun `fully populated proxy parses`() {
        val definition = parse("valid/proxy-full.yaml")
        val spec = definition.spec

        definition.metadata.labels shouldBe mapOf("tier" to "edge", "region" to "eu-west")
        spec.image shouldBe
            ImageRef.Digested(
                registry = "registry.example.com:5000",
                repository = "mc/velocity",
                digest = "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            )
        spec.maxPlayers shouldBe 2000
        spec.network.port shouldBe 25577
        spec.network.hostPort shouldBe 25565
        spec.resources.memory shouldBe memory("4Gi")
        spec.resources.cpu?.millicores shouldBe 2000
        spec.resources.heap.max shouldBe memory("2Gi")
        spec.control.port shouldBe 8375
        spec.backends.drain.destinationTimeout shouldBe 45.seconds
        spec.lifecycle.stopGracePeriod shouldBe 90.seconds
        spec.placement.node?.value shouldBe "node-a"
    }

    @Test
    fun `the selector is what decides a backend, and it matches by subset`() {
        val selector = parse("valid/proxy-full.yaml").spec.backends.selector

        selector.matchLabels shouldBe mapOf("mcorch.dev/fleet" to "main", "tier" to "survival")
        selector.matches(mapOf("mcorch.dev/fleet" to "main", "tier" to "survival", "region" to "eu")) shouldBe true
        selector.matches(mapOf("mcorch.dev/fleet" to "main")) shouldBe false
        selector.matches(emptyMap()) shouldBe false
    }

    @Test
    fun `fallback is an ordered preference of server names`() {
        val fallback = parse("valid/proxy-full.yaml").spec.backends.fallback

        fallback.map { it.value } shouldContainExactly listOf("lobby-01", "lobby-02")
    }

    @Test
    fun `parsing the same document twice produces an equal definition`() {
        parse("valid/proxy-full.yaml") shouldBe parse("valid/proxy-full.yaml")
    }

    @Test
    fun `a proxy and its backends can be declared in one file`() {
        val yaml = Fixtures.load("valid/minimal.yaml") + "---\n" + Fixtures.load("valid/proxy-minimal.yaml")

        val definitions = ServerDefinitionParser.parseAll(yaml, "fleet.yaml").getOrThrow()

        definitions.map { it.kind } shouldContainExactly
            listOf(ServerKind.PAPER_SERVER, ServerKind.VELOCITY_PROXY)
    }
}
