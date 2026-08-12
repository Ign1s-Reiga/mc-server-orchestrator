package mcorch.api

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import mcorch.schema.BackendRegistration
import mcorch.schema.BackendRoutingStatus
import mcorch.schema.BackendStatus
import mcorch.schema.ConditionStatus
import mcorch.schema.ConditionType
import mcorch.schema.ControlEndpointStatus
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
import mcorch.schema.RconSpec
import mcorch.schema.ResourceName
import mcorch.schema.SecretRef
import mcorch.schema.ServerDefinition
import mcorch.schema.ServerEndpoint
import mcorch.schema.ServerPhase
import mcorch.schema.StatusCondition
import mcorch.schema.StorageStatus
import mcorch.schema.VelocityProxyDefinition
import mcorch.schema.VelocityProxyStatus
import mcorch.schema.fixtures.ExampleDefinitions
import mcorch.schema.getOrThrow
import mcorch.schema.yaml.ServerDefinitionParser
import mcorch.store.SecretValue
import mcorch.store.getOrThrow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Nothing this API returns carries secret material, and nothing it returns
 * carries player identity.
 *
 * Modelled on `:store`'s `SecretLeakageTest`, including its control assertions.
 * A test that greps a response for a needle passes trivially if the needle was
 * never findable — because it was never stored, because the responses were
 * empty, because the search was wrong — so each assertion below is paired with
 * something that proves the search *could* have failed.
 *
 * The material is generated per test and never written as a literal: a literal
 * credential in a test file is how a real one eventually ends up in one.
 */
class ResponseLeakageTest {
    private lateinit var api: TestApi

    @BeforeEach
    fun start() {
        api = TestApi.start()
    }

    @AfterEach
    fun stop() {
        api.close()
    }

    private fun reveal(value: SecretValue): String = value.use { String(it) }

    /**
     * Every response body an operator can obtain, for one server that has a
     * secret reference, a full observation and a failed drain on it.
     */
    private fun everyResponse(): List<String> {
        val definition = ServerDefinitionParser.parse(ExampleDefinitions.valid("full.yaml"), "test").getOrThrow()
        val name = definition.metadata.name

        return buildList {
            add(api.call("POST", "/api/v1/servers", ExampleDefinitions.valid("full.yaml")).body)
            add(api.call("GET", "/api/v1/servers").body)
            add(api.call("GET", "/api/v1/servers/${name.value}").body)
            add(api.call("GET", "/api/v1/servers/${name.value}/status").body)
            add(api.call("POST", "/api/v1/validate", ExampleDefinitions.valid("full.yaml")).body)
            add(api.call("GET", "/api/v1/secrets").body)
            add(api.call("GET", "/api/v1/secrets/${secretName().value}").body)
            add(api.call("GET", "/api/v1/secrets/${secretName().value}/password").body)
            add(api.call("GET", "/api/v1/meta").body)
            // Failure paths carry messages, and a message is where content leaks.
            add(api.call("POST", "/api/v1/servers", ExampleDefinitions.valid("full.yaml")).body)
            add(api.call("POST", "/api/v1/servers", ExampleDefinitions.invalid("inline-secret.yaml")).body)
            add(api.call("PUT", "/api/v1/servers/${name.value}", ExampleDefinitions.valid("full.yaml")).body)
            add(api.call("DELETE", "/api/v1/servers/${name.value}").body)
            add(api.call("GET", "/api/v1/servers/${name.value}").body)
            addAll(api.stream(limit = 2).map { it.data })
        }
    }

    private fun secretName(): ResourceName =
        (
            ServerDefinitionParser
                .parse(ExampleDefinitions.valid("full.yaml"), "test")
                .getOrThrow()
                .paper()
                .spec.network.rcon
        ).passwordSecret.name

    @Test
    fun `no response carries secret material`() {
        val definition = ServerDefinitionParser.parse(ExampleDefinitions.valid("full.yaml"), "test").getOrThrow()
        val reference =
            (
                definition
                    .paper()
                    .spec.network.rcon
            ).passwordSecret
        val value = SecretValue.random(48)
        val material = reveal(value)

        kotlinx.coroutines.runBlocking { api.secrets.put(reference, value) }

        val bodies = everyResponse()
        val haystack = bodies.joinToString("\n")

        // Control 1: the material really is stored under the coordinates the
        // definition names, so there was something to leak.
        kotlinx.coroutines.runBlocking {
            reveal(api.secrets.resolve(reference).shouldNotBeNull()) shouldBe material
        }
        // Control 2: the responses really do describe that secret — its name and
        // key come back, because coordinates are exactly what an API may serve. So
        // the search below was looking at the right documents.
        haystack shouldContain reference.name.value
        haystack shouldContain reference.key
        // Control 3: the search itself can find a needle in a haystack this size
        // and shape. Without this the assertion below could pass because the
        // matcher never matches anything.
        (haystack + material) shouldContain material

        haystack shouldNotContain material
    }

    @Test
    fun `no response carries player identity, and occupancy has nowhere to put any`() {
        // Nothing in the system holds a player name, a UUID or a client address,
        // so there is no value to inject and grep for. The check has to be about
        // the *shape* of what is served instead: occupancy is two counts and a
        // timestamp, and the endpoint is the server's own address.
        val playerName = "Notch"
        val playerUuid = "069a79f4-44e9-4726-a5be-fca90e38aaf5"
        val clientAddress = "203.0.113.42"

        api.call("POST", "/api/v1/servers", ExampleDefinitions.valid("full.yaml")).status shouldBe 201
        observe()

        val document = api.call("GET", "/api/v1/servers/survival-02").json()
        val status = document["status"] as Map<*, *>

        val players = status["players"] as Map<*, *>
        // Exactly these keys. A field added here is the only way identity could
        // arrive, and this is what refuses it.
        players.keys.map { it.toString() }.sorted() shouldBe listOf("max", "observedAt", "online")
        players["online"] shouldBe 3

        // The endpoint is the *server's* address. This is the value that came out
        // of the observation, and it is not the fabricated client address.
        val endpoint = status["endpoint"] as Map<*, *>
        endpoint["address"] shouldBe "10.88.0.7"

        val haystack = everyResponse().joinToString("\n")
        // Control: the search finds these when they are present.
        listOf(playerName, playerUuid, clientAddress).forEach { needle ->
            (haystack + needle) shouldContain needle
        }
        haystack shouldNotContain playerName
        haystack shouldNotContain playerUuid
        haystack shouldNotContain clientAddress
    }

    @Test
    fun `the occupancy type has no field that could hold identity`() {
        // An assertion about the type rather than about today's rendering: if
        // somebody hangs a name or a list of names off PlayerOccupancy, this fails
        // before the renderer has to be told about it. Two ints and an Instant is
        // the whole type.
        val fields = PlayerOccupancy::class.java.declaredFields.filterNot { it.isSynthetic }
        fields.map { it.type.simpleName }.sorted() shouldBe listOf("Instant", "int", "int")
        fields.none { CharSequence::class.java.isAssignableFrom(it.type) } shouldBe true
        fields.none { Collection::class.java.isAssignableFrom(it.type) } shouldBe true
    }

    @Test
    fun `a failure message reaches the operator without being widened into a raw state dump`() {
        api.call("POST", "/api/v1/servers", ExampleDefinitions.valid("full.yaml")).status shouldBe 201
        observe()

        val status = api.call("GET", "/api/v1/servers/survival-02").json()["status"] as Map<*, *>
        val failure = status["failure"] as Map<*, *>
        // The message the reconciler recorded, served verbatim: it is redacted
        // upstream for the three CRI operations whose request carries a secret, and
        // nothing here un-redacts it or offers a second, unredacted view.
        failure["message"] shouldBe "rcon probe was refused"
        failure.keys.map { it.toString() }.sorted() shouldBe
            listOf("attempts", "failureClass", "message", "occurredAt", "reason")

        // There is no endpoint that returns unrendered state.
        api.call("GET", "/api/v1/debug").status shouldBe 404
        api.call("GET", "/api/v1/servers/survival-02/raw").status shouldBe 404
    }

    @Test
    fun `the served status renders every field the schema defines`() {
        // The renderer is total on purpose: a field that is silently dropped is a
        // field nobody can check the safety of. If :schema grows a status field,
        // this fails and forces a decision about it rather than letting it vanish.
        api.call("POST", "/api/v1/servers", ExampleDefinitions.valid("full.yaml")).status shouldBe 201
        observe()

        val status = api.call("GET", "/api/v1/servers/survival-02").json()["status"] as Map<*, *>
        val declared =
            PaperServerStatus::class.java.declaredFields
                .filterNot { it.isSynthetic }
                .map { it.name }
                .filterNot { it == "Companion" }
        status.keys.map { it.toString() } shouldContainAll declared
    }

    @Test
    fun `no response carries the forwarding secret or the control token`() {
        // The kind that finally carries a forwarding secret, and CLAUDE.md's
        // fourth invariant is the most explicitly written rule in this repository:
        // the value travels through the secret store and nowhere else — not a
        // definition, not a log line, not a response. `proxy-full.yaml` names two
        // references, and both are exercised because they take different paths
        // through the renderer.
        val definition =
            ServerDefinitionParser.parse(ExampleDefinitions.valid("proxy-full.yaml"), "test").getOrThrow().proxy()
        val forwarding = definition.spec.forwarding.secret
        val forwardingValue = SecretValue.random(48)
        val forwardingMaterial = reveal(forwardingValue)

        // The control token is optional and `proxy-full.yaml` leaves it out — an
        // unpublished endpoint needs none — so it is stored under its own
        // coordinates and asserted on the same terms.
        val token = SecretRef.of("proxy-02-control", "token").getOrThrow()
        val tokenValue = SecretValue.random(48)
        val tokenMaterial = reveal(tokenValue)

        kotlinx.coroutines.runBlocking {
            api.secrets.put(forwarding, forwardingValue)
            api.secrets.put(token, tokenValue)
        }

        val bodies = everyProxyResponse()
        val haystack = bodies.joinToString("\n")

        // Control 1: both are really stored, so there was something to leak.
        kotlinx.coroutines.runBlocking {
            reveal(api.secrets.resolve(forwarding).shouldNotBeNull()) shouldBe forwardingMaterial
            reveal(api.secrets.resolve(token).shouldNotBeNull()) shouldBe tokenMaterial
        }
        // Control 2: the responses really do describe the forwarding secret — its
        // coordinates come back, which is exactly what an API may serve — so the
        // search below looked at the right documents.
        haystack shouldContain forwarding.name.value
        haystack shouldContain forwarding.key
        // Control 3: the matcher can find a needle in a haystack of this size.
        (haystack + forwardingMaterial) shouldContain forwardingMaterial

        haystack shouldNotContain forwardingMaterial
        haystack shouldNotContain tokenMaterial
    }

    /** Every response an operator can obtain for a proxy that has been observed. */
    private fun everyProxyResponse(): List<String> =
        buildList {
            add(api.call("POST", "/api/v1/servers", ExampleDefinitions.valid("proxy-full.yaml")).body)
            observeProxy()
            add(api.call("GET", "/api/v1/servers").body)
            add(api.call("GET", "/api/v1/servers/proxy-02").body)
            add(api.call("GET", "/api/v1/servers/proxy-02/status").body)
            add(api.call("POST", "/api/v1/validate", ExampleDefinitions.valid("proxy-full.yaml")).body)
            add(api.call("GET", "/api/v1/secrets").body)
            add(api.call("GET", "/api/v1/meta").body)
            // Failure paths carry messages, and a message is where content leaks.
            add(api.call("POST", "/api/v1/servers", ExampleDefinitions.valid("proxy-full.yaml")).body)
            add(api.call("PUT", "/api/v1/servers/proxy-02", ExampleDefinitions.valid("proxy-full.yaml")).body)
            add(api.call("DELETE", "/api/v1/servers/proxy-02").body)
            addAll(api.stream(limit = 2).map { it.data })
        }

    /** A fully populated proxy observation, so every proxy renderer is exercised. */
    private fun observeProxy() {
        val at = Instant.parse("2026-08-05T10:20:00Z")
        val name = ResourceName.of("proxy-02").getOrThrow()
        val status =
            VelocityProxyStatus(
                name = name,
                observedGeneration = 1,
                phase = ServerPhase.RUNNING,
                observedAt = at,
                lastTransitionAt = at,
                ready = true,
                players = PlayerOccupancy(online = 40, max = 2000, observedAt = at),
                backends =
                    BackendRoutingStatus(
                        observedAt = at,
                        backends =
                            listOf(
                                BackendStatus(
                                    server = ResourceName.of("survival-01").getOrThrow(),
                                    registration = BackendRegistration.REGISTERED,
                                    players = PlayerOccupancy(online = 12, max = 60, observedAt = at),
                                    lastTransitionAt = at,
                                ),
                            ),
                    ),
                control =
                    ControlEndpointStatus(
                        reachable = true,
                        pluginApiVersion = "1",
                        compatible = true,
                        lastContactAt = at,
                    ),
                conditions =
                    listOf(
                        StatusCondition(ConditionType.BACKENDS_RESOLVED, ConditionStatus.TRUE, "", at),
                        StatusCondition(ConditionType.CONTROL_ENDPOINT_READY, ConditionStatus.TRUE, "", at),
                    ),
            )
        kotlinx.coroutines.runBlocking { api.store.putStatus(status).getOrThrow() }
    }

    @Test
    fun `a proxy status renders every field the schema defines`() {
        // A proxy sees every player in the fleet, so a field dropped silently here
        // is the one that matters most. Total rendering is what makes "no identity
        // anywhere" checkable rather than promised.
        api.call("POST", "/api/v1/servers", ExampleDefinitions.valid("proxy-full.yaml")).status shouldBe 201
        observeProxy()

        val status = api.call("GET", "/api/v1/servers/proxy-02").json()["status"] as Map<*, *>
        val declared =
            VelocityProxyStatus::class.java.declaredFields
                .filterNot { it.isSynthetic }
                .map { it.name }
                .filterNot { it == "Companion" }
        status.keys.map { it.toString() } shouldContainAll declared

        @Suppress("UNCHECKED_CAST")
        val backends = (status["backends"] as Map<*, *>)["backends"] as List<Map<String, Any?>>
        // Counts only, on the backend as well as on the proxy.
        (backends.single()["players"] as Map<*, *>).keys.map { it.toString() }.sorted() shouldBe
            listOf("max", "observedAt", "online")
    }

    /** Writes one fully populated observation, the way the reconcile loop would. */
    private fun observe() {
        val at = Instant.parse("2026-07-28T10:20:00Z")
        val name = ResourceName.of("survival-02").getOrThrow()
        val status =
            PaperServerStatus(
                name = name,
                observedGeneration = 1,
                phase = ServerPhase.RUNNING,
                observedAt = at,
                lastTransitionAt = at,
                ready = true,
                image =
                    ImageStatus(
                        requested = ImageRef.parse("docker.io/itzg/minecraft-server:2026.6.1").getOrThrow(),
                        resolvedDigest = "sha256:" + "ab".repeat(32),
                        pulledAt = at,
                    ),
                runtime =
                    mcorch.schema.RuntimeIdentity(
                        node = NodeName.of("node-a").getOrThrow(),
                        sandboxId = "sandbox-1",
                        containerId = "container-1",
                        createdAt = at,
                        startedAt = at,
                    ),
                endpoint = ServerEndpoint(NodeName.of("node-a").getOrThrow(), "10.88.0.7", 25565),
                players = PlayerOccupancy(online = 3, max = 60, observedAt = at),
                storage = StorageStatus(persistent = true, volumeName = name, bound = true, lastSaveConfirmedAt = at),
                drain =
                    DrainStatus(
                        state = DrainState.DRAIN_FAILED,
                        startedAt = at,
                        enteredStateAt = at,
                        destination = ResourceName.of("survival-01").getOrThrow(),
                        failure =
                            FailureStatus(
                                reason = FailureReason.DRAIN_STALLED,
                                failureClass = FailureClass.RETRYABLE,
                                message = "the drain could not reach the rcon listener",
                                occurredAt = at,
                            ),
                    ),
                failure =
                    FailureStatus(
                        reason = FailureReason.READINESS_TIMEOUT,
                        failureClass = FailureClass.RETRYABLE,
                        message = "rcon probe was refused",
                        occurredAt = at,
                        attempts = 2,
                    ),
                conditions =
                    listOf(
                        StatusCondition(ConditionType.READY, ConditionStatus.TRUE, "joinable", at),
                        StatusCondition(ConditionType.NEEDS_ATTENTION, ConditionStatus.TRUE, "stalled", at),
                    ),
            )
        kotlinx.coroutines.runBlocking { api.store.putStatus(status).getOrThrow() }
    }
}

/** The one kind there is today. A cast rather than a helper in `:schema`: this is a test's problem. */
private fun ServerDefinition.paper(): PaperServerDefinition = this as PaperServerDefinition

/** The proxy kind. A cast rather than a helper in `:schema`: this is a test's problem. */
private fun ServerDefinition.proxy(): VelocityProxyDefinition = this as VelocityProxyDefinition
