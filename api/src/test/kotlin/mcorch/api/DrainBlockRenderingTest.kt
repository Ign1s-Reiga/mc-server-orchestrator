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
        // False *here*, on a block with nothing else wrong — not because the two
        // are exclusive. They are not: see the both-true test below.
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

    @Test
    fun `a block and a drain failure together render as the failure at both sites`() {
        // The design declined a decode-time `require` enforcing that these two are
        // disjoint, because that cost is paid by the widest fleet read and one bad
        // row aborting `listServers` halts every in-flight drain. The consequence
        // is that this precedence *is* the specification — and it has to hold at
        // every site that reads it, not just at the condition.
        api.call("POST", "/api/v1/servers", ExampleDefinitions.valid("minimal.yaml")).status shouldBe 201
        observe(blockedAndFailed())

        val display = api.call("GET", "/api/v1/servers/survival-01").json()["display"] as Map<*, *>

        // The condition site already got this right.
        display["drainBlocked"] shouldBe false

        // The sentence site did not: it read `drain.blocked` raw, so one payload
        // carried `drainBlocked: false` beside "waiting, not stuck".
        val detail = display["detail"] as String
        detail shouldNotContain "waiting, not stuck"
        detail shouldContain "the drain aborted"
        detail shouldContain "no channel could confirm a completed save"
    }

    @Test
    fun `a node failure during a block is not reported as quietly waiting`() {
        // The sequence, and no hand-edited document is needed for any of it: the
        // drain blocks on players online, the next pass throws a NodeException, and
        // `Reconciler.nodeFailure` drafts with the block carried forward and the
        // node failure recorded on the pass. The server is terminating throughout.
        api.call("POST", "/api/v1/servers", ExampleDefinitions.valid("minimal.yaml")).status shouldBe 201
        api.call("DELETE", "/api/v1/servers/survival-01").status shouldBe 202
        observe(blocked(), passFailure = nodeFailure())

        val document = api.call("GET", "/api/v1/servers/survival-01").json()
        val display = document["display"] as Map<*, *>
        display["state"] shouldBe "TERMINATING"

        val detail = display["detail"] as String

        // What an operator used to be told about a server whose node the loop
        // cannot reach.
        detail shouldNotContain "waiting, not stuck"
        // The block's own message promises the drain resumes by itself. It does
        // not, if the loop cannot get to the node.
        detail shouldNotContain "resumes on its own"

        // What they are told now: the block is still explained, and the reason it
        // is not progressing is the headline rather than being dropped.
        detail shouldContain "delete requested"
        detail shouldContain "not resuming on its own"
        detail shouldContain "the node did not answer a status request within 20s"

        // The failure is on the pass, not on the drain — both are rendered, and a
        // client can tell which is which.
        val status = document["status"] as Map<*, *>
        (status["drain"] as Map<*, *>)["failure"] shouldBe null
        (status["failure"] as Map<*, *>)["reason"] shouldBe "NODE_UNAVAILABLE"

        // `drainBlocked` stays true, because the condition it comes from is
        // `:core`'s and is still accurate — the drain really is blocked on
        // players. The flag says what the drain is doing; the sentence says
        // whether anybody should act. Asserted so the split is deliberate rather
        // than discovered.
        display["drainBlocked"] shouldBe true
    }

    @Test
    fun `drainBlocked and needsAttention can both be true, and both are rendered`() {
        // This document used to claim in bold that they never were, and told
        // dashboards to render them as a mutually exclusive tri-state on that
        // basis. The claim is false, and false for the case that most needs
        // attention: a drain correctly waiting on players while its node is
        // unreachable. The block is accurate — people really are still connected —
        // and the pass failure escalates on its own arm.
        //
        // Pinned here so an implementation that "optimises" one flag out of the
        // other, on the strength of the retired claim, fails.
        api.call("POST", "/api/v1/servers", ExampleDefinitions.valid("minimal.yaml")).status shouldBe 201
        observe(blocked(), passFailure = nodeFailure(), escalated = true)

        val display = api.call("GET", "/api/v1/servers/survival-01").json()["display"] as Map<*, *>

        display["drainBlocked"] shouldBe true
        display["needsAttention"] shouldBe true

        // And the sentence names the pass failure rather than the reassurance, so
        // the two flags and the prose tell one story.
        val detail = display["detail"] as String
        detail shouldNotContain "waiting, not stuck"
        detail shouldContain "the node did not answer a status request within 20s"

        // Control: the ordinary blocked server, same fixture minus the failure,
        // raises only the one flag. Without this the assertions above would pass
        // against a renderer that reported every blocked drain as needing a human.
        observe(blocked())
        val quiet = api.call("GET", "/api/v1/servers/survival-01").json()["display"] as Map<*, *>
        quiet["drainBlocked"] shouldBe true
        quiet["needsAttention"] shouldBe false
    }

    @Test
    fun `the sentence and the attention message rank the two failures oppositely`() {
        // Deliberate, and worth pinning because a client comparing them would
        // otherwise read it as a bug. The condition answers "what is the worst
        // thing outstanding" and takes the drain arm; `detail` answers "what is
        // true now" and takes the newer pass failure.
        api.call("POST", "/api/v1/servers", ExampleDefinitions.valid("minimal.yaml")).status shouldBe 201
        observe(blockedAndFailed(), passFailure = nodeFailure(), escalated = true)

        val document = api.call("GET", "/api/v1/servers/survival-01").json()
        val display = document["display"] as Map<*, *>

        // The sentence: the newest fact, which is why nothing has moved since.
        (display["detail"] as String) shouldContain "the node did not answer a status request within 20s"

        // The condition message, which an alert renders: the worst standing
        // problem, which is the drain that aborted.
        @Suppress("UNCHECKED_CAST")
        val conditions = (document["status"] as Map<*, *>)["conditions"] as List<Map<String, Any?>>
        val attention = conditions.single { it["type"] == "NEEDS_ATTENTION" }
        attention["status"] shouldBe "TRUE"
        (attention["message"] as String) shouldContain "the drain cannot finish on its own"

        // Both are rendered, and they are not the same sentence. Asserting they
        // matched would be asserting the bug.
        (display["detail"] as String) shouldNotContain "the drain cannot finish on its own"
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

    /** A drain that is blocked *and* carries its own failure. See the both-set tests. */
    private fun blockedAndFailed(): DrainStatus =
        blocked().copy(
            failure =
                FailureStatus(
                    reason = FailureReason.DRAIN_STALLED,
                    failureClass = FailureClass.PERMANENT,
                    message = "no channel could confirm a completed save",
                    occurredAt = at,
                ),
        )

    /** What `Reconciler.nodeFailure` records when it cannot reach the node. */
    private fun nodeFailure(): FailureStatus =
        FailureStatus(
            reason = FailureReason.NODE_UNAVAILABLE,
            failureClass = FailureClass.RETRYABLE,
            message = "the node did not answer a status request within 20s",
            occurredAt = at,
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
    private fun observe(
        drain: DrainStatus,
        /**
         * A failure recorded on the *pass* rather than on the drain.
         *
         * This is what `Reconciler.nodeFailure` writes: it drafts with
         * `drain = previous.drain` — the block carried forward untouched — and the
         * node failure here. The two fields are independent, and only modelling
         * `drain.failure` would miss the sequence entirely.
         */
        passFailure: FailureStatus? = null,
        /**
         * Whether `NEEDS_ATTENTION` is raised.
         *
         * Named rather than derived, because the threshold that decides it lives
         * in `:core` and `:api` cannot call `:core` even in tests — see the note
         * on the class. `DisplayConformanceTest` in `:app` is what checks the
         * reconciler raises it on the right servers; this file's job is that the
         * renderer surfaces whatever was raised.
         */
        escalated: Boolean = false,
    ) {
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
                failure = passFailure ?: drain.failure,
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
                            if (escalated || !blocked) ConditionStatus.TRUE else ConditionStatus.FALSE,
                            // The condition's own message, which ranks the *drain*
                            // arm first — the opposite way round from `detail`. See
                            // the test that pins the divergence.
                            if (drain.failure != null) {
                                "this server needs a human: the drain cannot finish on its own."
                            } else if (escalated) {
                                "this server needs a human: the loop could not complete a pass."
                            } else {
                                ""
                            },
                            at,
                        ),
                    ),
            )
        runBlocking { api.store.putStatus(status).getOrThrow() }
    }
}
