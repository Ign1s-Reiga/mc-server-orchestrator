package mcorch.api

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import mcorch.api.auth.OperatorAuth
import mcorch.api.auth.SessionRegistry
import mcorch.api.http.Access
import mcorch.api.http.Route
import mcorch.api.stream.StreamRegistry
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
            auth = OperatorAuth(config.token.digest, sessions, Duration.ZERO),
            sessions = sessions,
            streams = StreamRegistry(config.maxStreams),
        )
    }

    @Test
    fun `no mutating route is reachable without a credential`() {
        val mutatingMethods = setOf("POST", "PUT", "PATCH", "DELETE")
        val unprotected =
            table()
                .filter { it.method in mutatingMethods && it.access == Access.PUBLIC }
                .map { "${it.method} ${it.pattern}" }

        // One exception, and it is the endpoint that *establishes* a credential:
        // the generic authenticator cannot run before a caller has one. It checks
        // the operator token itself before doing anything at all — see AuthRoutes.
        unprotected shouldBe listOf("POST /api/v1/auth/session")
    }

    @Test
    fun `the only public read is the liveness probe`() {
        table()
            .filter { it.method == "GET" && it.access == Access.PUBLIC }
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
        route.access shouldBe Access.OPERATOR

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
}
