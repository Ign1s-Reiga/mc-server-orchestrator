package mcorch.api

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import mcorch.schema.fixtures.ExampleDefinitions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Nothing that changes anything is reachable without an operator credential.
 *
 * The list of mutating endpoints below is written out by hand and asserted
 * against the router's own table, so an endpoint added without a credential
 * check fails here rather than in production. That is the whole point of the
 * arrangement: authentication is enforced by the dispatcher before a handler
 * runs, so "did this handler remember?" is not a question anybody has to answer
 * per endpoint — but a route registered as `PUBLIC` would slip through, and this
 * is what catches that.
 */
class AuthenticationTest {
    private lateinit var api: TestApi

    @BeforeEach
    fun start() {
        api = TestApi.start()
    }

    @AfterEach
    fun stop() {
        api.close()
    }

    private val minimal: String get() = ExampleDefinitions.valid("minimal.yaml")

    @Test
    fun `every mutating endpoint rejects an unauthenticated request`() {
        val mutations =
            listOf(
                Triple("POST", "/api/v1/servers", minimal),
                Triple("PUT", "/api/v1/servers/survival-01", minimal),
                Triple("DELETE", "/api/v1/servers/survival-01", null),
                Triple("POST", "/api/v1/validate", minimal),
                Triple("PUT", "/api/v1/secrets/survival-01-rcon/password", "material"),
                Triple("DELETE", "/api/v1/secrets/survival-01-rcon/password", null),
                Triple("DELETE", "/api/v1/secrets/survival-01-rcon", null),
                Triple("DELETE", "/api/v1/auth/session", null),
            )
        for ((method, path, body) in mutations) {
            val reply = api.anonymous(method, path, body, contentType = "text/plain")
            withClue("$method $path") {
                reply.status shouldBe 401
                reply.errorCode() shouldBe "UNAUTHENTICATED"
            }
        }

        // Control: the same requests with a credential are not 401. Without this
        // the assertions above could pass because the paths do not exist.
        api.call("POST", "/api/v1/servers", minimal).status shouldBe 201
        api
            .call("PUT", "/api/v1/secrets/survival-01-rcon/password", "material", contentType = "text/plain")
            .status shouldBe 201
    }

    @Test
    fun `reads are authenticated too, and the liveness probe is the only exception`() {
        for (path in listOf("/api/v1/servers", "/api/v1/servers/survival-01", "/api/v1/meta", "/api/v1/secrets")) {
            withClue(path) { api.anonymous("GET", path).status shouldBe 401 }
        }
        // The event stream is a read, and it is a read of everything.
        api.anonymous("GET", "/api/v1/stream").status shouldBe 401

        val health = api.anonymous("GET", "/healthz")
        health.status shouldBe 200
        health.json()["status"] shouldBe "ok"
        // It says one word, and touches no state: a probe that fails when the
        // database is slow turns a degraded API into a restarted one.
        health.body.length shouldBe """{"status":"ok"}""".length
    }

    @Test
    fun `a wrong token is rejected and never echoed back`() {
        val reply = api.anonymous("GET", "/api/v1/servers", headers = listOf("Authorization" to "Bearer wrong-token"))
        reply.status shouldBe 401
        reply.body shouldNotContain "wrong-token"
        reply.body shouldNotContain api.token
    }

    @Test
    fun `a session cookie authenticates, and mutating with it needs the CSRF token`() {
        val opened =
            api.anonymous(
                "POST",
                "/api/v1/auth/session",
                headers = listOf("Authorization" to "Bearer ${api.token}"),
            )
        opened.status shouldBe 200
        val csrf = opened.json()["csrfToken"] as String
        val cookie = opened.header("Set-Cookie").shouldNotBeNull()

        // The properties that make a cookie safe to hand a browser.
        cookie shouldContain "HttpOnly"
        cookie shouldContain "SameSite=Strict"
        cookie shouldContain "Path=/"
        val session = cookie.substringBefore(';')

        // A read works with the cookie alone.
        api.anonymous("GET", "/api/v1/servers", headers = listOf("Cookie" to session)).status shouldBe 200

        // A write does not: a cookie is attached by the browser to whatever
        // request the browser is willing to make, so the cookie alone cannot be
        // the proof that the operator's own page made it.
        val withoutCsrf =
            api.anonymous("POST", "/api/v1/servers", minimal, "application/yaml", listOf("Cookie" to session))
        withoutCsrf.status shouldBe 403
        withoutCsrf.errorCode() shouldBe "CSRF_REQUIRED"

        val wrongCsrf =
            api.anonymous(
                "POST",
                "/api/v1/servers",
                minimal,
                "application/yaml",
                listOf("Cookie" to session, "X-CSRF-Token" to "not-the-token"),
            )
        wrongCsrf.status shouldBe 403
        wrongCsrf.errorCode() shouldBe "CSRF_INVALID"

        val accepted =
            api.anonymous(
                "POST",
                "/api/v1/servers",
                minimal,
                "application/yaml",
                listOf("Cookie" to session, "X-CSRF-Token" to csrf),
            )
        accepted.status shouldBe 201
    }

    @Test
    fun `a bearer credential needs no CSRF token because a browser cannot send one`() {
        // The exemption is not a convenience: an Authorization header is never
        // attached by a browser on its own, so a cross-site page cannot produce
        // this request at all, and there is nothing for a CSRF token to add.
        api.call("POST", "/api/v1/servers", minimal).status shouldBe 201
    }

    @Test
    fun `a closed session stops working`() {
        val opened =
            api.anonymous("POST", "/api/v1/auth/session", headers = listOf("Authorization" to "Bearer ${api.token}"))
        val csrf = opened.json()["csrfToken"] as String
        val session = opened.header("Set-Cookie").shouldNotBeNull().substringBefore(';')

        api.anonymous("GET", "/api/v1/auth/session", headers = listOf("Cookie" to session)).status shouldBe 200

        val closed =
            api.anonymous(
                "DELETE",
                "/api/v1/auth/session",
                headers = listOf("Cookie" to session, "X-CSRF-Token" to csrf),
            )
        closed.status shouldBe 204
        closed.header("Set-Cookie").shouldNotBeNull() shouldContain "Max-Age=0"

        api.anonymous("GET", "/api/v1/auth/session", headers = listOf("Cookie" to session)).status shouldBe 401
    }

    @Test
    fun `an unconfigured cross-origin request is refused before any handler runs`() {
        val refused =
            api.call(
                "GET",
                "/api/v1/servers",
                headers = listOf("Origin" to "https://evil.example"),
            )
        refused.status shouldBe 403
        refused.errorCode() shouldBe "ORIGIN_NOT_ALLOWED"

        // Same-origin is fine and needs no configuration.
        val sameOrigin = api.call("GET", "/api/v1/servers", headers = listOf("Origin" to api.base))
        sameOrigin.status shouldBe 200
        sameOrigin.header("Access-Control-Allow-Origin") shouldBe null
    }

    @Test
    fun `a configured origin gets credentialed CORS headers and a preflight`() {
        val dashboard = "https://ops.example.com"
        val configured = TestApi.start { it.copy(allowedOrigins = setOf(dashboard)) }
        try {
            val reply = configured.call("GET", "/api/v1/servers", headers = listOf("Origin" to dashboard))
            reply.status shouldBe 200
            // Never `*`: a browser refuses to combine a wildcard with credentials,
            // and this API is credentialed by construction.
            reply.header("Access-Control-Allow-Origin") shouldBe dashboard
            reply.header("Access-Control-Allow-Credentials") shouldBe "true"
            reply.header("Vary") shouldBe "Origin"

            val preflight =
                configured.anonymous(
                    "OPTIONS",
                    "/api/v1/servers",
                    headers =
                        listOf(
                            "Origin" to dashboard,
                            "Access-Control-Request-Method" to "POST",
                        ),
                )
            preflight.status shouldBe 204
            preflight.header("Access-Control-Allow-Methods").shouldNotBeNull() shouldContain "POST"
            preflight.header("Access-Control-Allow-Headers").shouldNotBeNull() shouldContain "X-CSRF-Token"
            preflight.header("Access-Control-Expose-Headers").shouldNotBeNull() shouldContain "ETag"
        } finally {
            configured.close()
        }
    }

    private fun withClue(
        clue: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (failure: AssertionError) {
            throw AssertionError("$clue: ${failure.message}", failure)
        }
    }
}
