package mcorch.api

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Managing operators.
 *
 * The tests that matter most here are the two sweeps. A session resolves its
 * principal once, when it is issued — so without them, disabling an identity
 * would mean "cannot log in again" while its live session kept working, which is
 * not what an operator revoking a leaked credential believes they did.
 */
class IdentityRoutesTest {
    private lateinit var api: TestApi

    @BeforeEach
    fun setUp() {
        api = TestApi.start()
    }

    @AfterEach
    fun tearDown() {
        api.close()
    }

    private fun create(
        name: String,
        tier: String,
    ): TestApi.Reply = api.call("POST", "/api/v1/identities/$name", body = tier, contentType = "text/plain")

    /** Exchanges a credential for a session cookie. */
    private fun sessionFor(credential: String): String =
        api
            .anonymous("POST", "/api/v1/auth/session", headers = listOf("Authorization" to "Bearer $credential"))
            .header("Set-Cookie")
            .shouldNotBeNull()
            .substringBefore(';')

    @Test
    fun `creating an identity returns its credential exactly once`() {
        val created = create("rin", "operator")
        created.status shouldBe 201
        val credential = created.json()["credential"] as String
        credential.length shouldBe 43

        // The warning is part of the contract: there is no other channel, and a
        // caller that loses this rotates rather than recovers.
        (created.json()["warning"] as String) shouldContain "cannot be shown again"

        // It works.
        api
            .anonymous("GET", "/api/v1/servers", headers = listOf("Authorization" to "Bearer $credential"))
            .status shouldBe 200

        // And it is never readable again — not from the listing, which carries no
        // digest either.
        val listed = api.call("GET", "/api/v1/identities")
        listed.body shouldNotContain credential
        listed.body shouldNotContain "credentialDigest"
        listed.body shouldNotContain "digest"
    }

    @Test
    fun `POST never overwrites an existing identity`() {
        create("rin", "operator").status shouldBe 201
        val again = create("rin", "member")
        again.status shouldBe 409
        again.errorCode() shouldBe "IDENTITY_EXISTS"

        // The tier did not move, so the refusal is total rather than partial.
        val listed = api.call("GET", "/api/v1/identities").json()

        @Suppress("UNCHECKED_CAST")
        val identities = listed["identities"] as List<Map<*, *>>
        identities.single()["tier"] shouldBe "operator"
    }

    @Test
    fun `disabling an identity revokes the session it is already holding`() {
        val credential = create("rin", "operator").json()["credential"] as String
        val cookie = sessionFor(credential)

        // Control: the session works before the disable, so the refusal below is
        // about the sweep rather than about the session never having been valid.
        api.anonymous("GET", "/api/v1/servers", headers = listOf("Cookie" to cookie)).status shouldBe 200

        val disabled =
            api.call("PUT", "/api/v1/identities/rin/enabled", body = "false", contentType = "text/plain")
        disabled.status shouldBe 200
        disabled.json()["sessionsRevoked"] shouldBe 1.0

        // The live session is gone, not merely unable to be renewed.
        api.anonymous("GET", "/api/v1/servers", headers = listOf("Cookie" to cookie)).status shouldBe 401
    }

    @Test
    fun `rotating a credential revokes the sessions the old one opened`() {
        val original = create("rin", "operator").json()["credential"] as String
        val cookie = sessionFor(original)
        api.anonymous("GET", "/api/v1/servers", headers = listOf("Cookie" to cookie)).status shouldBe 200

        val rotated = api.call("POST", "/api/v1/identities/rin/credential")
        rotated.status shouldBe 200
        val replacement = rotated.json()["credential"] as String
        replacement shouldNotContain original
        rotated.json()["sessionsRevoked"] shouldBe 1.0

        // An operator rotating is usually responding to a leak, so the sessions the
        // leaked credential opened are the thing they most want gone.
        api.anonymous("GET", "/api/v1/servers", headers = listOf("Cookie" to cookie)).status shouldBe 401
        api
            .anonymous("GET", "/api/v1/servers", headers = listOf("Authorization" to "Bearer $original"))
            .status shouldBe 401
        api
            .anonymous("GET", "/api/v1/servers", headers = listOf("Authorization" to "Bearer $replacement"))
            .status shouldBe 200
    }

    @Test
    fun `the only enabled superuser cannot be demoted, disabled or removed`() {
        create("root", "superuser").status shouldBe 201

        val demoted = api.call("PUT", "/api/v1/identities/root", body = "member", contentType = "text/plain")
        demoted.status shouldBe 409
        demoted.errorCode() shouldBe "LAST_SUPERUSER"
        demoted.body shouldContain "create or enable another"

        api
            .call("PUT", "/api/v1/identities/root/enabled", body = "false", contentType = "text/plain")
            .errorCode() shouldBe "LAST_SUPERUSER"
        api.call("DELETE", "/api/v1/identities/root").errorCode() shouldBe "LAST_SUPERUSER"

        // With a second one, the first may go. The guard is about leaving nobody,
        // not about protecting a particular name.
        create("root-2", "superuser").status shouldBe 201
        api.call("DELETE", "/api/v1/identities/root").status shouldBe 204
        api.call("GET", "/api/v1/identities").json().let {
            @Suppress("UNCHECKED_CAST")
            (it["identities"] as List<Map<*, *>>) shouldHaveSize 1
        }
    }

    @Test
    fun `a tier the server does not know is refused rather than defaulted`() {
        val refused = create("rin", "emperor")
        refused.status shouldBe 400
        refused.body shouldContain "member, operator, superuser"

        // Nothing was created, so a rejected tier cannot leave a half-made identity.
        api.call("GET", "/api/v1/identities").json().let {
            @Suppress("UNCHECKED_CAST")
            (it["identities"] as List<Map<*, *>>) shouldHaveSize 0
        }
    }

    @Test
    fun `managing identities is superuser-only`() {
        val credential = create("rin", "operator").json()["credential"] as String
        val asOperator = listOf("Authorization" to "Bearer $credential")

        api.anonymous("GET", "/api/v1/identities", headers = asOperator).status shouldBe 403
        api
            .anonymous("POST", "/api/v1/identities/other", body = "member", headers = asOperator)
            .status shouldBe 403

        // Including their own credential: rotation is a Superuser operation, so an
        // Operator who believes theirs has leaked asks somebody rather than fixing
        // it themselves. That is a real cost and it is the conservative side.
        api.anonymous("POST", "/api/v1/identities/rin/credential", headers = asOperator).status shouldBe 403
    }
}
