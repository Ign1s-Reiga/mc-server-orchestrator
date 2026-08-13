package mcorch.api

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import mcorch.schema.fixtures.ExampleDefinitions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Forcing a stop.
 *
 * `spec/termination/`. This is the most consequential thing the API can do, and
 * the assertions are shaped accordingly: what it refuses, who may reach it, and
 * whether the response says a world was lost.
 */
class ForcedStopTest {
    private var api: TestApi? = null

    private fun start(forced: RefusingForce = RefusingForce()): TestApi =
        TestApi.start(forced = forced).also { api = it }

    private fun startStopping(saveConfirmed: Boolean): Pair<TestApi, StoppingForce> {
        val force = StoppingForce(saveConfirmed)
        val started = TestApi.start(forced = force)
        api = started
        return started to force
    }

    @AfterEach
    fun tearDown() {
        api?.close()
    }

    @Test
    fun `an ordinary delete never reaches the forced path`() {
        val force = RefusingForce()
        val started = start(force)
        started.call("POST", "/api/v1/servers", ExampleDefinitions.valid("full.yaml")).status shouldBe 201

        started.call("DELETE", "/api/v1/servers/survival-02").status shouldBe 202

        // The whole safety of this feature rests on it being opt-in. A delete that
        // reached the forced path by default would stop containers the drain was
        // still working on.
        force.recorded.shouldBeEmpty()
    }

    @Test
    fun `forcing reports whether the world was saved`() {
        val (started, force) = startStopping(saveConfirmed = false)
        started.call("POST", "/api/v1/servers", ExampleDefinitions.valid("full.yaml")).status shouldBe 201

        val forced = started.call("DELETE", "/api/v1/servers/survival-02?force=true")
        forced.status shouldBe 202
        force.recorded shouldBe listOf("survival-02")

        // The field an investigator reads first, and the one that separates "an
        // operator retired a stuck server" from "an operator lost a world".
        forced.json()["saveConfirmed"] shouldBe false
        forced.json()["forced"] shouldBe true
        // `detail` is the seam's wording, and asserting it here would only be
        // asserting the test double. What this endpoint owns is that the flag is
        // carried through untouched — NodeForcedTerminationTest pins the words.
        (forced.json()["detail"] as String).isNotEmpty() shouldBe true

        // And it does not claim the name is free. Freeing it belongs to the loop,
        // which has to see the container gone first.
        (forced.json()["message"] as String) shouldContain "404"
    }

    @Test
    fun `a confirmed save says so rather than warning about loss`() {
        val (started, _) = startStopping(saveConfirmed = true)
        started.call("POST", "/api/v1/servers", ExampleDefinitions.valid("full.yaml")).status shouldBe 201

        val forced = started.call("DELETE", "/api/v1/servers/survival-02?force=true")
        forced.json()["saveConfirmed"] shouldBe true
    }

    @Test
    fun `forcing an already-terminating server is the case it exists for`() {
        val (started, force) = startStopping(saveConfirmed = false)
        started.call("POST", "/api/v1/servers", ExampleDefinitions.valid("full.yaml")).status shouldBe 201

        // The ordinary delete first, which is what stalls in the situation this
        // feature addresses.
        started.call("DELETE", "/api/v1/servers/survival-02").status shouldBe 202
        force.recorded.shouldBeEmpty()

        // Forcing afterwards must not short-circuit on "already tombstoned".
        started.call("DELETE", "/api/v1/servers/survival-02?force=true").status shouldBe 202
        force.recorded shouldBe listOf("survival-02")
    }

    @Test
    fun `a proxy cannot be forced, because its drain cannot stall on a save`() {
        val force = RefusingForce()
        val started = start(force)
        started.call("POST", "/api/v1/servers", ExampleDefinitions.valid("proxy-full.yaml")).status shouldBe 201

        val refused = started.call("DELETE", "/api/v1/servers/proxy-02?force=true")
        refused.errorCode() shouldBe "FORCE_NOT_APPLICABLE"
        refused.body shouldContain "holds no world"
        force.recorded.shouldBeEmpty()
    }

    @Test
    fun `a server with no running container reports that rather than pretending`() {
        val force = RefusingForce()
        val started = start(force)
        started.call("POST", "/api/v1/servers", ExampleDefinitions.valid("full.yaml")).status shouldBe 201

        val refused = started.call("DELETE", "/api/v1/servers/survival-02?force=true")
        refused.errorCode() shouldBe "FORCE_NOT_APPLICABLE"
        // It reached the seam — the refusal is the seam's, not a guess made above it.
        force.recorded shouldBe listOf("survival-02")
    }
}
