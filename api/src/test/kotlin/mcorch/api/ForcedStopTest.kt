package mcorch.api

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import mcorch.core.termination.OccupancyAcknowledgement
import mcorch.schema.fixtures.ExampleDefinitions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant

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

        // And it is still there. The first version wrote the tombstone before this
        // check, so a proxy got a fleet-wide deletion delivered under a status that
        // said nothing had happened — and this test passed, because it asserted the
        // status and the message and never that the definition survived.
        val survivor = started.call("GET", "/api/v1/servers/proxy-02")
        survivor.status shouldBe 200
        (survivor.json()["metadata"] as Map<*, *>)["terminating"] shouldBe false
    }

    @Test
    fun `a force reaches the seam rather than being guessed at above it`() {
        val force = RefusingForce()
        val started = start(force)
        started.call("POST", "/api/v1/servers", ExampleDefinitions.valid("full.yaml")).status shouldBe 201

        started.call("DELETE", "/api/v1/servers/survival-02?force=true").status shouldBe 202
        // Whether there is a container to stop is the seam's question, not the
        // route's: the route cannot see a workload.
        force.recorded shouldBe listOf("survival-02")
    }

    @Test
    fun `forcing with nothing to stop degenerates into an ordinary delete`() {
        val force = RefusingForce()
        val started = start(force)
        started.call("POST", "/api/v1/servers", ExampleDefinitions.valid("full.yaml")).status shouldBe 201

        // The seam finds no container. The tombstone is already written and is the
        // right outcome — the loop tears down a stopped container without any of
        // this — so the honest answer is the delete's, not a refusal that has
        // already deleted the thing it declined to touch.
        val answered = started.call("DELETE", "/api/v1/servers/survival-02?force=true")
        answered.status shouldBe 202
        answered.json()["forced"] shouldBe false

        // And it is idempotent in the same way a repeated DELETE is, rather than
        // turning into a 409 on the second call.
        started.call("DELETE", "/api/v1/servers/survival-02?force=true").status shouldBe 202
    }

    @Test
    fun `the occupancy acknowledgement is carried to the seam rather than assumed`() {
        val force = StoppingForce(saveConfirmed = true)
        val started = TestApi.start(forced = force)
        api = started
        started.call("POST", "/api/v1/servers", ExampleDefinitions.valid("full.yaml")).status shouldBe 201

        started.call("DELETE", "/api/v1/servers/survival-02?force=true")
        force.acknowledgements shouldBe listOf(OccupancyAcknowledgement.None)
        // And preflight saw it too. It runs above the tombstone and is the only
        // refusal point where "correct that and force again" is advice the caller
        // can still act on, so an acknowledgement that reached only `stop` would
        // move every occupancy refusal below the point of no return.
        force.preflighted shouldBe listOf(OccupancyAcknowledgement.None)
    }

    @Test
    fun `the response reports whether a save was even attempted`() {
        // "not confirmed" covers both a save that timed out and one that was never
        // sent, and those are different events. The API has to carry both halves or
        // the audit record cannot tell them apart.
        val force = StoppingForce(saveConfirmed = false, saveAttempted = false, playersOnline = null)
        val started = TestApi.start(forced = force)
        api = started
        started.call("POST", "/api/v1/servers", ExampleDefinitions.valid("full.yaml")).status shouldBe 201

        val forced = started.call("DELETE", "/api/v1/servers/survival-02?force=true&acknowledgeOccupancy=unreadable")
        forced.json()["saveAttempted"] shouldBe false
        forced.json()["saveConfirmed"] shouldBe false
        // Null, not zero. A client must not render an unknown count as an empty one.
        forced.json()["playersOnline"] shouldBe null
        // No save was outstanding, so this really is "nothing ever reached it".
        forced.json()["saveOutstandingSince"] shouldBe null
    }

    @Test
    fun `a skipped save is distinguishable from one that could never be sent`() {
        // `saveAttempted: false` covers both, and they are not the same event: here a
        // request demonstrably *did* go out — the drain sent it — and was never
        // confirmed, so the world may well be on disk. An investigator reading the
        // boolean alone would conclude nothing was ever sent and be wrong.
        val outstanding = Instant.parse("2026-02-01T12:00:00Z")
        val force = StoppingForce(saveConfirmed = false, saveAttempted = false, saveOutstandingSince = outstanding)
        val started = TestApi.start(forced = force)
        api = started
        started.call("POST", "/api/v1/servers", ExampleDefinitions.valid("full.yaml")).status shouldBe 201

        val forced = started.call("DELETE", "/api/v1/servers/survival-02?force=true")

        forced.json()["saveAttempted"] shouldBe false
        // The instant, not a flag: how long ago is what decides whether it landed.
        forced.json()["saveOutstandingSince"] shouldBe outstanding.toString()
    }

    @Test
    fun `a refused force leaves the definition alive and editable`() {
        val force = PreflightRefusingForce("this server has 12 players online")
        val started = TestApi.start(forced = force)
        api = started
        started.call("POST", "/api/v1/servers", ExampleDefinitions.valid("full.yaml")).status shouldBe 201

        val refused = started.call("DELETE", "/api/v1/servers/survival-02?force=true")
        refused.errorCode() shouldBe "FORCE_REFUSED"
        force.recorded.shouldBeEmpty()

        // **The assertion whose absence hid a critical.** `a proxy cannot be forced`
        // had it; no FORCE_REFUSED test did, and every one of them would have failed
        // it. A tombstoned definition cannot be edited — the store answers
        // `TERMINATING` to any write against a deleted row and nothing un-tombstones
        // it — so a refusal below the tombstone that says "fix it and force again"
        // freezes the server: undrainable, unforceable, `crictl` only. Which is the
        // exact state this endpoint exists to remove.
        val survivor = started.call("GET", "/api/v1/servers/survival-02")
        survivor.status shouldBe 200
        (survivor.json()["metadata"] as Map<*, *>)["terminating"] shouldBe false

        // Editable, not merely present. "Still listed" would pass on a tombstoned
        // row, and being able to edit it is the whole remedy a refusal offers.
        started
            .call(
                "PUT",
                "/api/v1/servers/survival-02",
                ExampleDefinitions.valid("full.yaml"),
                headers = listOf("If-Match" to "*"),
            ).status shouldBe 200
    }

    @Test
    fun `the acknowledgement is a count, and a bare true is refused`() {
        val force = StoppingForce(saveConfirmed = true)
        val started = TestApi.start(forced = force)
        api = started
        started.call("POST", "/api/v1/servers", ExampleDefinitions.valid("full.yaml")).status shouldBe 201

        // `true` was the old spelling and is refused rather than read as either
        // value: it is exactly the "proceed regardless" this replaced, and a caller
        // still sending it has not seen a count. Reading it as `Unreadable` would
        // silently keep the checkbox.
        val bare = started.call("DELETE", "/api/v1/servers/survival-02?force=true&acknowledgeOccupancy=true")
        bare.errorCode() shouldBe "BAD_REQUEST"
        force.recorded.shouldBeEmpty()

        // And the refusal happened before anything was written.
        (started.call("GET", "/api/v1/servers/survival-02").json()["metadata"] as Map<*, *>)["terminating"] shouldBe
            false

        val counted = started.call("DELETE", "/api/v1/servers/survival-02?force=true&acknowledgeOccupancy=12")
        counted.status shouldBe 202
        force.acknowledgements shouldBe listOf(OccupancyAcknowledgement.Count(12))
    }
}
