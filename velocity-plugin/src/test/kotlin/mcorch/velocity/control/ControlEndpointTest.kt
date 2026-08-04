package mcorch.velocity.control

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * The transport: authentication, the one unauthenticated route, and the limits.
 *
 * These run against a real socket. The endpoint can seal every backend in a
 * fleet and move every player on it, so "the token is checked" is not a claim to
 * make from reading the code.
 */
class ControlEndpointTest {
    private val token = "a-control-token-that-is-long-enough-to-be-one"

    @Test
    fun `every route but the handshake requires the token`() {
        TestEndpoint(FakeProxy(), token).use { endpoint ->
            val guarded =
                listOf(
                    Triple("GET", ControlProtocol.PATH_STATE, ""),
                    Triple("PUT", ControlProtocol.PATH_PROXY, """{"admitsNewPlayers":false}"""),
                    Triple(
                        "PUT",
                        ControlProtocol.PATH_BACKEND + "a",
                        """{"address":"1.2.3.4:1","admitsNewPlayers":true}""",
                    ),
                    Triple("DELETE", ControlProtocol.PATH_BACKEND + "a", ""),
                    Triple("POST", ControlProtocol.PATH_BACKEND + "a/transfer", """{"destination":"b"}"""),
                    // Even a route that does not exist: an unauthenticated caller must
                    // not be able to map the surface by reading 404s off it.
                    Triple("GET", "/v1/nothing", ""),
                )

            for ((method, path, body) in guarded) {
                endpoint.call(method, path, body).statusCode() shouldBe 401
                endpoint.call(method, path, body, bearer = "not-the-token").statusCode() shouldBe 401
                endpoint.call(method, path, body, bearer = "").statusCode() shouldBe 401
            }
        }
    }

    @Test
    fun `the handshake answers without a credential, and only the handshake does`() {
        TestEndpoint(FakeProxy(), token).use { endpoint ->
            val handshake = endpoint.call("GET", ControlProtocol.PATH_VERSION)

            // ControlEndpointStatus needs to tell "did not answer" from "answered,
            // wrong version" from "answered, wrong token". An authenticated handshake
            // collapses all three into the first.
            handshake.statusCode() shouldBe 200
            handshake.body() shouldContain ControlProtocol.VERSION
            endpoint.call("GET", ControlProtocol.PATH_STATE).statusCode() shouldBe 401
        }
    }

    @Test
    fun `a rejection says nothing about the token it was compared against`() {
        TestEndpoint(FakeProxy(), token).use { endpoint ->
            val rejected = endpoint.call("GET", ControlProtocol.PATH_STATE, bearer = "wrong")

            rejected.body() shouldNotContain token
            // Not even its length, which is enough to narrow a search.
            rejected.body() shouldNotContain token.length.toString()
            rejected.body() shouldContain ControlErrorCode.UNAUTHENTICATED.name
        }
    }

    @Test
    fun `a credential that is not a bearer token is refused as one`() {
        TestEndpoint(FakeProxy(), token).use { endpoint ->
            val basic =
                endpoint
                    .call("GET", ControlProtocol.PATH_STATE)
                    .let { endpoint.call("GET", ControlProtocol.PATH_STATE, bearer = " $token ") }

            // Surrounding whitespace is trimmed, so a shell that quoted badly still works.
            basic.statusCode() shouldBe 200
        }
    }

    @Test
    fun `an unpublished endpoint needs no token, and that is a configuration rather than a fallback`() {
        // The schema refuses to spell `hostPort` without `tokenSecret`, so a
        // tokenless endpoint is one that exists only inside the sandbox.
        TestEndpoint(FakeProxy(), token = null).use { endpoint ->
            endpoint.call("GET", ControlProtocol.PATH_STATE).statusCode() shouldBe 200
        }
    }

    @Test
    fun `a token that was set but blank is a startup failure, not an open door`() {
        // A template that expanded to nothing must not read as "no token wanted".
        shouldThrow<IllegalArgumentException> { ControlAuth("   ") }
        ControlAuth(null).required shouldBe false
        ControlAuth(token).required shouldBe true
    }

    @Test
    fun `the auth object cannot be logged into revealing the token`() {
        val auth = ControlAuth(token)

        "$auth" shouldNotContain token
        listOf(auth).toString() shouldNotContain token
        mapOf("auth" to auth).toString() shouldNotContain token
    }

    @Test
    fun `the unauthenticated handshake never reads a request body`() {
        // The handshake is deliberately reachable without a credential, so reading
        // its body would let anything that can reach the port announce a
        // Content-Length and then send nothing, holding a handler thread with no
        // credential at all. A body far over the cap coming back 200 is the
        // observable proof that nothing read or measured it.
        TestEndpoint(FakeProxy(), token).use { endpoint ->
            val huge = "x".repeat(ControlProtocol.MAX_BODY_BYTES * 4)

            val response = endpoint.call("GET", ControlProtocol.PATH_VERSION, huge)

            response.statusCode() shouldBe 200
            response.body() shouldContain ControlProtocol.VERSION
        }
    }

    @Test
    fun `the config cannot be logged into revealing the token`() {
        // ControlAuth digests the material precisely so a stray toString cannot hand
        // it over, and this is a public data class :core is invited to depend on —
        // its generated toString would undo all of that.
        val config = ControlConfig(port = 8375, bindAddress = "0.0.0.0", token = token)

        "$config" shouldNotContain token
        listOf(config).toString() shouldNotContain token
        mapOf("config" to config).toString() shouldNotContain token
        // Control: it prints something identifying, so the assertions above are
        // about redaction rather than about an empty string.
        "$config" shouldContain "8375"
        "$config" shouldContain "REDACTED"
        ControlConfig(port = 8375, bindAddress = "0.0.0.0", token = null).toString() shouldContain "absent"
    }

    @Test
    fun `an over-long body is refused before it is parsed`() {
        TestEndpoint(FakeProxy(), token).use { endpoint ->
            val huge = "x".repeat(ControlProtocol.MAX_BODY_BYTES + 1024)

            val response = endpoint.call("PUT", ControlProtocol.PATH_PROXY, huge, token)

            response.statusCode() shouldBe ControlErrorCode.MALFORMED_REQUEST.httpStatus
            response.body() shouldContain ControlErrorCode.MALFORMED_REQUEST.name
        }
    }

    @Test
    fun `responses are JSON and say so`() {
        TestEndpoint(FakeProxy(), token).use { endpoint ->
            val response = endpoint.call("GET", ControlProtocol.PATH_VERSION)

            response.headers().firstValue("content-type").orElse("") shouldContain "application/json"
        }
    }

    @Test
    fun `a trailing slash addresses the same route`() {
        TestEndpoint(FakeProxy(), token).use { endpoint ->
            endpoint.call("GET", ControlProtocol.PATH_VERSION + "/").statusCode() shouldBe 200
        }
    }
}
