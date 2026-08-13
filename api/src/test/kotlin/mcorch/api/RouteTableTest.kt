package mcorch.api

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import mcorch.api.auth.OperatorAuth
import mcorch.api.auth.SessionRegistry
import mcorch.api.http.Access
import mcorch.api.http.Route
import mcorch.api.stream.StreamRegistry
import mcorch.schema.Tier
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Assertions about the shape of the route table rather than about its behaviour.
 *
 * `AuthenticationTest` exercises endpoints and proves the ones it knows about
 * are protected. This proves it for the ones nobody has written a test for yet:
 * if somebody adds a `PUBLIC` mutating route, this fails at the point the route
 * is registered rather than at the point somebody notices.
 */
class RouteTableTest {
    private lateinit var api: TestApi

    @BeforeEach
    fun start() {
        api = TestApi.start()
    }

    @AfterEach
    fun stop() {
        api.close()
    }

    private fun table(): List<Route> {
        val config = ApiConfig(token = OperatorToken.of("x".repeat(40)).getOrThrow(), clock = TestApi.CLOCK)
        val sessions = SessionRegistry(TestApi.CLOCK, 1.hours)
        return ApiServer.routeTable(
            config = config,
            store = api.store,
            secrets = api.secrets,
            auth = OperatorAuth(config.token.digest, sessions, Duration.ZERO, api.identities),
            sessions = sessions,
            streams = StreamRegistry(config.maxStreams),
        )
    }

    @Test
    fun `no mutating route is reachable without a credential`() {
        val mutatingMethods = setOf("POST", "PUT", "PATCH", "DELETE")
        val unprotected =
            table()
                .filter { it.method in mutatingMethods && it.access == Access.Public }
                .map { "${it.method} ${it.pattern}" }

        // One exception, and it is the endpoint that *establishes* a credential:
        // the generic authenticator cannot run before a caller has one. It checks
        // the operator token itself before doing anything at all — see AuthRoutes.
        unprotected shouldBe listOf("POST /api/v1/auth/session")
    }

    @Test
    fun `the only public read is the liveness probe`() {
        table()
            .filter { it.method == "GET" && it.access == Access.Public }
            .map { it.pattern } shouldBe listOf("/healthz")
    }

    @Test
    fun `this module cannot reach a container runtime at all`() {
        // The strongest form of "a mutating handler writes desired state and does
        // not act": the types it would have to call are not on the classpath, so no
        // handler can call one however it is written, and no future one can either
        // without a visible change to api/build.gradle.kts.
        //
        // A test rather than a build-file comment because the comment cannot fail.
        val outOfReach =
            listOf(
                "mcorch.cri.CriClient",
                "mcorch.cri.ContainerSpec",
                "mcorch.cri.StopGracePeriod",
                "mcorch.core.Node",
                "mcorch.core.Reconciler",
                "mcorch.core.DrainController",
                "io.grpc.ManagedChannel",
            )
        outOfReach.filter { runCatching { Class.forName(it) }.isSuccess } shouldBe emptyList()

        // Control: the classpath is not simply empty, and the search does find the
        // things this module *is* allowed to depend on.
        listOf("mcorch.store.Store", "mcorch.schema.PaperServerDefinition")
            .filter { runCatching { Class.forName(it) }.isSuccess }
            .size shouldBe 2
    }

    @Test
    fun `the table has no route that could free a name or stop a container`() {
        val patterns = table().map { "${it.method} ${it.pattern}" }
        // purge, force-stop, restart-now: none of them exist, and none of them may.
        // A name is freed by :core once the drain has finished and the container is
        // gone; an endpoint that could reach past that guard would orphan a running
        // container, and one that could stop a container directly could stop one
        // with players on it.
        patterns.none { it.contains("purge", ignoreCase = true) } shouldBe true
        patterns.none { it.contains("stop", ignoreCase = true) } shouldBe true
        patterns.none { it.contains("kill", ignoreCase = true) } shouldBe true
        patterns.none { it.contains("force", ignoreCase = true) } shouldBe true

        // Control: the table is not empty and does hold the routes it should, so
        // the assertions above are about absence rather than about nothing at all.
        patterns shouldContainAll
            listOf(
                "GET /api/v1/servers",
                "POST /api/v1/servers",
                "PUT /api/v1/servers/{name}",
                "DELETE /api/v1/servers/{name}",
                "GET /api/v1/stream",
            )
    }

    @Test
    fun `reading one secret key is routed to a refusal rather than left to a generic 404`() {
        val route = table().single { it.method == "GET" && it.pattern == "/api/v1/secrets/{name}/{key}" }
        route.access shouldBe Access.AtLeast(Tier.SUPERUSER)

        val reply = api.call("GET", "/api/v1/secrets/anything/at-all")
        reply.status shouldBe 405
        reply.errorCode() shouldBe "SECRET_NOT_READABLE"
        // The refusal does not depend on whether the secret exists, so it says
        // nothing about which coordinates are real.
        api
            .call("PUT", "/api/v1/secrets/real-secret/password", "material", contentType = "text/plain")
            .status shouldBe 201
        val again = api.call("GET", "/api/v1/secrets/real-secret/password")
        again.status shouldBe 405
        again.body shouldBe reply.body
    }

    /**
     * A bad coordinate in the path is refused in this route's own words.
     *
     * `:schema` rejects the same two coordinates for a definition file, and its
     * message explains that a coordinate is where material lands "when someone
     * abbreviates the reference away" — a YAML mistake, and one that cannot be
     * made over a URL where there is no reference to abbreviate. Relaying it
     * would answer a client with advice about a file it never wrote. The rule is
     * shared (`ResourceName.SYNTAX`, `SecretRef.KEY_SYNTAX`); the framing is not.
     *
     * Neither segment is repeated back, on the same rule as every other message
     * about a secret coordinate in this system.
     */
    @Test
    fun `a malformed secret coordinate is refused in this route's own words`() {
        val badName = api.call("PUT", "/api/v1/secrets/My_Secret/password", "material", contentType = "text/plain")
        badName.status shouldBe 400
        badName.body shouldContain "the `name` segment of the path"
        badName.body shouldContain "lowercase letters"

        // `!` rather than a space: still outside the key syntax, but a legal URI
        // path character, so the request reaches the route it is aimed at.
        val badKey = api.call("PUT", "/api/v1/secrets/survival-01-rcon/pass!word", "m", contentType = "text/plain")
        badKey.status shouldBe 400
        badKey.body shouldContain "the `key` segment of the path"

        listOf(badName, badKey).forEach { reply ->
            reply.body.contains("abbreviates the reference away") shouldBe false
            reply.body.contains("My_Secret") shouldBe false
            reply.body.contains("pass!word") shouldBe false
        }
    }

    /**
     * Every route states a tier, and the dangerous ones state the right one.
     *
     * `Access` is sealed and required, so "somebody forgot" cannot happen — this
     * asserts the *values*, which the compiler cannot. The two rows that matter are
     * not access-control decisions: `api/API.md` §12 says every mutating endpoint
     * can request a drain, and a drain is how a Minecraft server stops.
     */
    @Test
    fun `the routes that can end or replace a server require the tiers they should`() {
        val tiers = table().associate { "${it.method} ${it.pattern}" to it.access }

        // DELETE ends a server.
        tiers["DELETE /api/v1/servers/{name}"] shouldBe Access.AtLeast(Tier.SUPERUSER)

        // PUT is not obviously worse than a GET until you read what an edit does: a
        // spec change makes the loop drain the running server and replace it. It
        // sits at Operator knowingly — the replacement still drains, so a careless
        // PUT costs a restart rather than data.
        tiers["PUT /api/v1/servers/{name}"] shouldBe Access.AtLeast(Tier.OPERATOR)

        // Writing the forwarding secret or an RCON password.
        tiers["PUT /api/v1/secrets/{name}/{key}"] shouldBe Access.AtLeast(Tier.SUPERUSER)
        tiers["DELETE /api/v1/secrets/{name}/{key}"] shouldBe Access.AtLeast(Tier.SUPERUSER)

        // Creating cannot stop or replace anything that exists; the failure mode is
        // a wasted container, which is recoverable.
        tiers["POST /api/v1/servers"] shouldBe Access.AtLeast(Tier.OPERATOR)

        // Reads.
        tiers["GET /api/v1/servers"] shouldBe Access.AtLeast(Tier.MEMBER)
        tiers["GET /api/v1/stream"] shouldBe Access.AtLeast(Tier.MEMBER)
    }

    @Test
    fun `only the caller's own session is tier-independent`() {
        // AnyIdentity is a deliberate choice, not a place to put a route whose tier
        // nobody decided — so the set of routes using it is asserted whole. Reading
        // who you are and logging yourself out cannot be tier-gated without locking
        // the lowest tier out of its own login.
        table()
            .filter { it.access == Access.AnyIdentity }
            .map { "${it.method} ${it.pattern}" }
            .sorted() shouldBe
            listOf(
                "DELETE /api/v1/auth/session",
                "GET /api/v1/auth/session",
            )
    }
}
