package mcorch.api

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import mcorch.schema.ConditionStatus
import mcorch.schema.ConditionType
import mcorch.schema.DrainBlock
import mcorch.schema.DrainBlockReason
import mcorch.schema.DrainState
import mcorch.schema.DrainStatus
import mcorch.schema.FailureClass
import mcorch.schema.FailureReason
import mcorch.schema.FailureStatus
import mcorch.schema.PaperServerStatus
import mcorch.schema.ResourceName
import mcorch.schema.ServerPhase
import mcorch.schema.StatusCondition
import mcorch.schema.fixtures.ExampleDefinitions
import mcorch.schema.getOrThrow
import mcorch.store.getOrThrow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The wire shape of a drain that is waiting rather than broken.
 *
 * ## What this can and cannot establish
 *
 * `:api` has no `:core` dependency, not even for tests, so everything below is
 * asserted against a status written by hand. That means this file **cannot** show
 * that the reconciler sets the condition on the right servers — a test here would
 * pass against a rule that raised `DRAIN_BLOCKED` on every server in the fleet.
 * `DisplayConformanceTest` in `:app` is where the rule meets the renderer, and it
 * is the one that would catch that.
 *
 * What this file does establish is the contract a TypeScript client is written
 * against: the key names, that `blocked` and `failure` are siblings rather than
 * variants of one object, and that the prose an operator reads on a blocked drain
 * does not tell them a drain aborted. All three are things `:app` does not look
 * at and a dashboard breaks on.
 */
class DrainBlockRenderingTest {
    private lateinit var api: TestApi

    @BeforeEach
    fun start() {
        api = TestApi.start()
    }

    @AfterEach
    fun stop() {
        api.close()
    }

    private val at: Instant = Instant.parse("2026-07-28T10:20:00Z")

    @Test
    fun `a blocked drain renders its block, no failure, and a detail that does not say aborted`() {
        api.call("POST", "/api/v1/servers", ExampleDefinitions.valid("minimal.yaml")).status shouldBe 201
        observe(blocked())

        val document = api.call("GET", "/api/v1/servers/survival-01").json()
        val status = document["status"] as Map<*, *>
        val drain = status["drain"] as Map<*, *>

        val block = (drain["blocked"] as? Map<*, *>).shouldNotBeNull()
        block["reason"] shouldBe "AWAITING_ZERO_PLAYERS"
        block["since"] shouldBe "2026-07-28T10:05:00Z"
        block["observations"] shouldBe 12
        (block["message"] as String) shouldContain "3 of 20 player slots"

        // Siblings, not variants. A client reads `blocked !== null && failure ===
        // null` as *waiting*, and that only works if both keys are always present.
        drain.containsKey("failure") shouldBe true
        drain["failure"] shouldBe null
        status["failure"] shouldBe null

        val display = document["display"] as Map<*, *>
        display["drainBlocked"] shouldBe true
        // The inverse flag, and they are never both true.
        display["needsAttention"] shouldBe false
        // The badge is unchanged: the drain really has been requested and the
        // server really is on its way out, so softening it would be the lie in the
        // other direction. It is the flag and the detail that carry the news.
        display["drainState"] shouldBe "DRAIN_FAILED"
        val detail = display["detail"] as String
        detail shouldContain "waiting, not stuck"
        // The sentence that used to be shown here, and the reason the branch is
        // ordered ahead of the DRAIN_FAILED one: it sends somebody to fix a server
        // where people are playing.
        detail shouldNotContain "the drain aborted"
    }

    @Test
    fun `a drain that actually failed still renders as a failure, and carries no block`() {
        // The control. Without it every assertion above would pass against a
        // renderer that reported every parked drain as a healthy wait, which is the
        // failure mode that matters: it is the one that hides a stuck server.
        api.call("POST", "/api/v1/servers", ExampleDefinitions.valid("minimal.yaml")).status shouldBe 201
        observe(failed())

        val document = api.call("GET", "/api/v1/servers/survival-01").json()
        val drain = (document["status"] as Map<*, *>)["drain"] as Map<*, *>

        drain["blocked"] shouldBe null
        (drain["failure"] as? Map<*, *>).shouldNotBeNull()["reason"] shouldBe "DRAIN_STALLED"

        val display = document["display"] as Map<*, *>
        display["drainBlocked"] shouldBe false
        display["needsAttention"] shouldBe true
    }

    private fun blocked(): DrainStatus =
        DrainStatus(
            state = DrainState.DRAIN_FAILED,
            startedAt = at,
            enteredStateAt = at,
            blocked =
                DrainBlock(
                    reason = DrainBlockReason.AWAITING_ZERO_PLAYERS,
                    message = "3 of 20 player slots are in use and there is no proxy to transfer them through",
                    since = Instant.parse("2026-07-28T10:05:00Z"),
                    observations = 12,
                ),
        )

    private fun failed(): DrainStatus =
        DrainStatus(
            state = DrainState.DRAIN_FAILED,
            startedAt = at,
            enteredStateAt = at,
            failure =
                FailureStatus(
                    reason = FailureReason.DRAIN_STALLED,
                    failureClass = FailureClass.PERMANENT,
                    message = "no channel could confirm a completed save",
                    occurredAt = at,
                ),
        )

    /**
     * Writes the observation the reconciler would have written for this drain,
     * conditions included.
     *
     * The conditions are typed out here because `:api` cannot call `:core` to
     * derive them — see the note on the class. They are what `display` reads, so
     * getting them consistent with the drain record is this test's own job.
     */
    private fun observe(drain: DrainStatus) {
        val blocked = drain.blocked != null && drain.failure == null
        val status =
            PaperServerStatus(
                name = ResourceName.of("survival-01").getOrThrow(),
                observedGeneration = 1,
                phase = ServerPhase.RUNNING,
                observedAt = at,
                lastTransitionAt = at,
                ready = true,
                drain = drain,
                failure = drain.failure,
                conditions =
                    listOf(
                        StatusCondition(
                            ConditionType.DRAIN_BLOCKED,
                            if (blocked) ConditionStatus.TRUE else ConditionStatus.FALSE,
                            "",
                            at,
                        ),
                        StatusCondition(
                            ConditionType.NEEDS_ATTENTION,
                            if (blocked) ConditionStatus.FALSE else ConditionStatus.TRUE,
                            "",
                            at,
                        ),
                    ),
            )
        runBlocking { api.store.putStatus(status).getOrThrow() }
    }
}
