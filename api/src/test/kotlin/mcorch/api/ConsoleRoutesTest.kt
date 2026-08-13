package mcorch.api

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import mcorch.schema.fixtures.ExampleDefinitions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The remote console's gates, as an operator meets them.
 *
 * There is no containerd behind these — [RefusingConsole] answers as the real
 * seam does when a workload is not running. So what is covered is the policy, the
 * error mapping and the audit; what is **not** covered is a command reaching a
 * Minecraft server, which is an integration test's job and is recorded as owed in
 * `spec/README.md`.
 *
 * That split is deliberate rather than convenient: every refusal below happens
 * *before* dispatch, so a fake that always refuses to dispatch cannot make one of
 * them pass by accident.
 */
class ConsoleRoutesTest {
    private lateinit var api: TestApi

    @BeforeEach
    fun setUp() {
        api = TestApi.start()
        // full.yaml declares `console.maxTier: operator`.
        api.call("POST", "/api/v1/servers", ExampleDefinitions.valid("full.yaml")).status shouldBe 201
    }

    @AfterEach
    fun tearDown() {
        api.close()
    }

    private val server get() = "survival-02"

    private fun run(
        command: String,
        headers: List<Pair<String, String>> = emptyList(),
    ) = api.call(
        "POST",
        "/api/v1/servers/$server/console",
        body = command,
        contentType = "text/plain",
        headers = headers,
    )

    @Test
    fun `stop is refused at every tier, and points at the drain instead`() {
        // The operator token is Superuser, so this is the top tier being refused —
        // which is the whole point of Gate 1. A console that let the most trusted
        // caller stop a server would have reintroduced the unconditional stop the
        // drain protocol exists to prevent.
        val refused = run("stop")
        refused.status shouldBe 409
        refused.errorCode() shouldBe "CONSOLE_COMMAND_REFUSED"
        refused.body shouldContain "DELETE /api/v1/servers/$server"

        run("save-off").errorCode() shouldBe "CONSOLE_COMMAND_REFUSED"

        // Every spelling, since Gate 1 refuses on the normalised verb.
        run("/minecraft:STOP").errorCode() shouldBe "CONSOLE_COMMAND_REFUSED"
    }

    @Test
    fun `the server's ceiling clamps a superuser`() {
        // full.yaml declares maxTier: operator, and the operator token is a
        // Superuser — so a command needing Superuser is refused *by the server*.
        val refused = run("someplugin:dosomething")
        refused.status shouldBe 403
        refused.errorCode() shouldBe "FORBIDDEN"

        val error = refused.json()["error"] as Map<*, *>
        error["requiredTier"] shouldBe "superuser"
        // The effective tier is named too, so a caller can tell the server's
        // ceiling from their own credential and knows where to look.
        (error["message"] as String) shouldContain "operator"
    }

    @Test
    fun `a permitted command reaches the seam, and its failure is reported as one`() {
        // `list` passes both gates, so this exercises the whole path down to the
        // console seam — which has no workload behind it in these tests.
        val attempted = run("list")
        attempted.status shouldBe 503
        attempted.errorCode() shouldBe "CONSOLE_UNAVAILABLE"
        attempted.json()["error"].let { (it as Map<*, *>)["retryable"] shouldBe true }
        attempted.header("Retry-After") shouldBe "2"
    }

    @Test
    fun `the capability endpoint says what this caller may run here`() {
        val capability = api.call("GET", "/api/v1/servers/$server/console").json()

        capability["available"] shouldBe true
        // Clamped by the server, not by the credential.
        capability["tier"] shouldBe "operator"
        // Not unrestricted, so a dashboard offers a picker rather than a prompt.
        capability["unrestricted"] shouldBe false

        @Suppress("UNCHECKED_CAST")
        val commands = capability["commands"] as List<String>
        commands.contains("say") shouldBe true
        commands.contains("list") shouldBe true
        // Never offered, at any tier.
        commands.contains("stop") shouldBe false
        commands.contains("save-off") shouldBe false
    }

    @Test
    fun `one request carries one command`() {
        // A newline could carry a second command, and nothing downstream could say
        // which one a refusal or an audit record referred to.
        run("list\nstop").status shouldBe 400
        run("").status shouldBe 400
    }

    @Test
    fun `a proxy has no console, and is told so rather than 404ed`() {
        api.call("POST", "/api/v1/servers", ExampleDefinitions.valid("proxy-full.yaml")).status shouldBe 201

        val refused = api.call("POST", "/api/v1/servers/proxy-02/console", body = "list", contentType = "text/plain")
        refused.errorCode() shouldBe "CONSOLE_NOT_APPLICABLE"
        refused.body shouldContain "control plugin"
    }
}
