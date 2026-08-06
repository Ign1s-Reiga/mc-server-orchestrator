package mcorch.core

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import mcorch.schema.ConditionStatus
import mcorch.schema.ConditionType
import mcorch.schema.DrainState
import mcorch.schema.FailureClass
import mcorch.schema.FailureReason
import mcorch.schema.VelocityProxySpec
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What the loop does with a definition it cannot build a node request from.
 *
 * `ExecRequest` and `EndpointRequest` both refuse a non-positive timeout, and both
 * of those timeouts are *definition* fields — `spec.lifecycle.drain.saveTimeout` and
 * `spec.backends.drain.sealTimeout`. `mcorch.schema.SpecBounds` caps them where a
 * stored row is decoded and deliberately applies no floor, because flooring
 * `saveTimeout` raises the minimum the grace period beside it has to clear and
 * inverts a pair that satisfied the schema on disk. So zero and negative still
 * arrive, and what happens then is the subject of this class.
 *
 * ## The property under test is that something is *recorded*
 *
 * Before this, the `IllegalArgumentException` was built outside every typed catch on
 * the path: past `Reconciler`'s `catch (NodeException)` and `catch (StoreException)`,
 * into `ReconcileLoop.work`'s `catch (Throwable)`, and out as a requeue with **no
 * status write**. The drain was never recorded as failed, nothing raised
 * `NEEDS_ATTENTION`, the dashboard kept whatever it had, and the server could not be
 * deleted — one error line per pass was the entire signal. So the assertions here are
 * about the *record*: which failure, which class, and — the negative half that makes
 * a classification worth anything — that nothing was issued to reach it.
 *
 * ## The two classifications differ, and that is the interesting part
 *
 * A save timeout is on the server's own definition, so `PERMANENT` freezes a server
 * whose own edit unfreezes it. A seal timeout is on the **proxy's** definition while
 * the drain reading it is a **backend's**, so `PERMANENT` there would freeze a server
 * that no repair of the offending row could ever release —
 * `Reconciler.isBlockedByPermanentFailure` lifts on a generation bump of *that*
 * server. Both directions are asserted, because "the same defect, classified two
 * ways" is exactly the thing a later reader tidies into one.
 */
internal class UnbuildableRequestTest {
    /**
     * The proxy path, and the case this class was written for.
     *
     * The row arrives the way a bad row does: the fleet is healthy, and then the
     * proxy's `sealTimeout` becomes zero — a hand-edited record, a migration, a
     * restored backup. Nothing about the *proxy container* changes, because that
     * field is not a spec-hash input, so there is no replacement to hide behind: the
     * next backend drain simply cannot build its step-2 request.
     *
     * What must happen is a park with a record. What must not happen is a stop: the
     * backend has a world and no confirmed save, and a drain that cannot seal must
     * not proceed on the strength of one Server List Ping.
     */
    @Test
    fun `a proxy row with a zero seal timeout parks the backend drain and records why`() =
        coreTest {
            val leaving = backendDefinition("survival-01")
            val harness = ProxyHarness(backends = listOf(leaving))
            harness.bringUp()
            val name = leaving.metadata.name

            // The healthy fleet is the control: seals, registrations and calls all
            // happened before the row went bad, so an assertion that nothing new
            // reached the wire is about this edit and not about an inert harness.
            val callsBefore = harness.proxyNode.endpointCalls.size
            withClue("the fleet never spoke to its proxy, so nothing below is about the edit") {
                callsBefore shouldBeGreaterThan 0
            }

            harness.store.putDefinition(harness.proxyDefinition.copy(spec = harness.proxyDefinition.spec.withSeal()))
            harness.store.deleteDefinition(name)
            repeat(4) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }

            val drain =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            drain.state shouldBe DrainState.DRAIN_FAILED
            val failure = drain.failure.shouldNotBeNull()
            failure.reason shouldBe FailureReason.PROXY_CONTROL_UNREACHABLE
            // Retryable, and the reason is not "it might work next time" — it will
            // not. It is that the repair is an edit to the *proxy*, which bumps no
            // generation here: a permanent failure recorded against this backend
            // would freeze it with no lever on it, once per backend behind that
            // proxy. See `ControlChannel.unbuildable`.
            failure.failureClass shouldBe FailureClass.RETRYABLE
            // The operator-facing half: the field they can go and edit, named on the
            // status rather than only in a log line.
            failure.message shouldContain "spec.backends.drain.sealTimeout"
            failure.message shouldContain "nothing was sent"

            // The side-effect assertions, which are the load-bearing ones. Not one
            // request was built, so the proxy heard nothing…
            harness.proxyNode.endpointCalls shouldHaveSize callsBefore
            harness.plugin.transfers.shouldBeEmpty()
            harness.plugin.deregistrations.shouldBeEmpty()
            // …and the backend was neither saved nor stopped.
            harness.nodeOf(leaving).saves.shouldBeEmpty()
            harness.nodeOf(leaving).stops.shouldBeEmpty()
            // Nor was a seal claimed that never landed: a dashboard reading this
            // field would otherwise say new joins are stopped.
            drain.sealRequestedAt shouldBe null
        }

    /**
     * …and the park is the same park every pass, with nothing issued on any of them.
     *
     * The idempotency claim for this path, and it is not "the second pass writes no
     * status" — the attempt counter and the escalation anchor are *supposed* to move,
     * because a fault that is still true is still being observed. It is that a pass
     * which cannot build a request performs **no side effect**, however many times it
     * runs: no exec, no endpoint call, no stop, and no second `save-all flush`
     * (CLAUDE.md invariant 5).
     *
     * The instrument is the wire, not the returned outcome. A pass that returned
     * `Retry` having issued a transfer would satisfy any assertion about the status.
     */
    @Test
    fun `a second pass against the same bad row issues nothing and lands in the same place`() =
        coreTest {
            val leaving = backendDefinition("survival-01")
            val harness = ProxyHarness(backends = listOf(leaving))
            harness.bringUp()
            val name = leaving.metadata.name

            harness.store.putDefinition(harness.proxyDefinition.copy(spec = harness.proxyDefinition.spec.withSeal()))
            harness.store.deleteDefinition(name)
            repeat(3) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }

            val settled =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            val callsAfterFirst = harness.proxyNode.endpointCalls.size
            val execsAfterFirst = harness.nodeOf(leaving).execs.size

            harness.pass(name)
            harness.clock.advance(2.seconds)
            harness.pass(name)

            val again =
                harness
                    .status(name)
                    .shouldNotBeNull()
                    .drain
                    .shouldNotBeNull()
            again.state shouldBe settled.state
            again.failure.shouldNotBeNull().reason shouldBe FailureReason.PROXY_CONTROL_UNREACHABLE
            // The anchor holds while the attempts rise: the same fault, still being
            // watched, not a new one every pass.
            again.failure.shouldNotBeNull().occurredAt shouldBe settled.failure.shouldNotBeNull().occurredAt

            harness.proxyNode.endpointCalls shouldHaveSize callsAfterFirst
            harness.nodeOf(leaving).saves.shouldBeEmpty()
            harness.nodeOf(leaving).stops.shouldBeEmpty()
            harness.nodeOf(leaving).removals.shouldBeEmpty()
            // Probes are reads and are expected to repeat; what must not repeat is a
            // side effect, and the count above proves none was ever performed. This
            // asserts the loop is still *looking*, so the two counts above are not
            // both zero because the pass stopped doing anything at all.
            withClue("the drain stopped probing, so the counts above prove nothing") {
                harness.nodeOf(leaving).execs.size shouldBeGreaterThan execsAfterFirst
            }
        }

    /**
     * The save path, permanently — and the asymmetry with the proxy path above.
     *
     * `saveTimeout = 0` beside `stopGracePeriod = 60s` satisfies
     * `SpecInvariants.stopGraceProblem` exactly, which is why the decode caps these
     * fields and does not floor them. The drain then reaches step 5 with a request it
     * cannot build.
     *
     * `PERMANENT` is right here for the reason it is wrong there: the field is on
     * *this* server's definition, so the edit that repairs it is the edit that bumps
     * this server's generation and resumes its passes. And `NotDelivered` rather than
     * `Unconfirmed` matters as much as the class — no exec was dispatched, so the
     * never-re-send wedge must not be armed, or a repaired definition would find
     * itself unable to save at all.
     */
    @Test
    fun `a zero save timeout is recorded as a permanent drain failure with no exec sent`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition(saveTimeout = 0.seconds)
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.deleteDefinition(name)

            // A standalone server still walks every state — sealed, target,
            // transfer — with nothing to do in each, so the save is several passes
            // in. Advanced by a poll interval rather than by nothing, because the
            // drain's own evidence rules are written against a clock that moves.
            repeat(8) {
                harness.pass(name)
                harness.clock.advance(2.seconds)
            }

            val status = harness.status(name).shouldNotBeNull()
            val drain = status.drain.shouldNotBeNull()
            drain.state shouldBe DrainState.DRAIN_FAILED
            val failure = drain.failure.shouldNotBeNull()
            failure.reason shouldBe FailureReason.DRAIN_STALLED
            failure.failureClass shouldBe FailureClass.PERMANENT
            failure.message shouldContain "spec.lifecycle.drain.saveTimeout"

            // A permanent failure escalates at once, so the one condition an operator
            // is paged on is true on the pass that found it.
            status.condition(ConditionType.NEEDS_ATTENTION).status shouldBe ConditionStatus.TRUE

            // Nothing went out, and nothing is wedged: `saveRequestedAt` is what
            // stops a later pass sending a second `save-all flush`, and there was no
            // first one to protect.
            harness.node.saves.shouldBeEmpty()
            harness.node.stops.shouldBeEmpty()
            harness.node.removals.shouldBeEmpty()
            drain.saveRequestedAt shouldBe null
            drain.worldSavedAt shouldBe null
        }

    /**
     * The rule the two classifications above rest on, asserted against this module's
     * sources.
     *
     * Every one of these requests is built from a definition — a timeout on all four
     * sites, a port on the endpoint one — and a construction that throws out of an
     * agent is a pass with no status write. The behavioural tests can only ever cover
     * the sites a scenario drives, and two of the four are unreachable today (both
     * probes take a private constant), so what keeps the rule from decaying at the
     * sites no scenario reaches is this scan.
     *
     * ## Shape, not content
     *
     * The unit is the **enclosing function**: a construction whose function does not
     * catch `IllegalArgumentException` is one whose exception leaves the module
     * unclassified, and that is a property of the source's shape. *Which* outcome each
     * site returns is behaviour, and the three tests above hold it. A site that caught
     * and rethrew would pass this and fail those, which is the right division: this
     * one is a presence check and says so.
     *
     * Classified rather than enumerated: any file under `src/main/kotlin` that builds
     * one of these requests is in the scan, so a fifth site written tomorrow is
     * covered without anybody extending a list — the failure mode this whole class is
     * about.
     */
    @Test
    fun `every request built from a definition is built where its refusal is classified`() {
        val sources = mainSources()

        // Vacuity guards: a walk that found nothing, or one that ran somewhere
        // without the agents in it, satisfies the assertion below by accident.
        sources.size shouldBeGreaterThan 10
        sources.map { it.first } shouldContain CHANNEL_PATH

        val built =
            sources.flatMap { (path, lines) ->
                lines.indices
                    .filter { REQUEST.containsMatchIn(codeOf(lines[it])) }
                    .filterNot { DECLARATION.containsMatchIn(lines[it]) }
                    .map { Triple(path, enclosing(lines, it), lines) }
            }

        // The four sites, named so that a fifth is a review trigger rather than a
        // silent pass — and so that a site *disappearing* (a probe folded into a
        // helper, say) reddens here rather than quietly leaving the rule with
        // nothing to enforce.
        built.map { (path, function, _) -> path to function.first } shouldBe
            listOf(
                PAPER_AGENT_PATH to "probe",
                PAPER_AGENT_PATH to "requestSave",
                CHANNEL_PATH to "call",
                PROXY_AGENT_PATH to "probe",
            )

        built.forEach { (path, function, lines) ->
            val (name, body) = function
            withClue("$path builds a node request in `$name` without classifying its refusal") {
                lines.slice(body).any { CATCHES.containsMatchIn(codeOf(it)) } shouldBe true
            }
        }
    }

    /**
     * …and the sentence those refusals print names the field the value came from.
     *
     * `ControlChannel.unbuildable` tells an operator to go and look at
     * `spec.backends.drain.sealTimeout`, which is a claim about **both** of
     * `ControlChannel`'s construction sites rather than about the file it is written
     * in. A third site handing it some other duration would make that message point
     * at the wrong field — the failure would still be recorded, and the operator
     * would edit something that is not the problem.
     *
     * Follows the argument rather than restating a literal: the assertion is that
     * every site's `timeout` argument *is* that field, read off the call.
     */
    @Test
    fun `every control channel is given the seal timeout its message names`() {
        val sources = mainSources()

        val supplied =
            sources.flatMap { (path, lines) ->
                lines.indices
                    .filter { codeOf(lines[it]).contains("ControlChannel(") }
                    .filterNot { path == CHANNEL_PATH }
                    .map { path to argumentsOf(lines, it).single { argument -> argument.startsWith("timeout = ") } }
            }

        // Two construction sites: the proxy's own pass and the backend link. A
        // count, deliberately — this is the vacuity control for a scan whose
        // assertion is about what each one passes.
        supplied shouldHaveSize 2
        supplied.forEach { (path, argument) ->
            withClue("$path builds a ControlChannel with $argument") {
                // The receiver differs between the two sites — one holds a
                // definition, the other a spec — so what is pinned is the *field*,
                // to the end of the expression. A different duration on either site
                // makes `unbuildable`'s message name a field the operator did not
                // set.
                argument.endsWith("spec.backends.drain.sealTimeout,") shouldBe true
            }
        }
    }

    private companion object {
        const val CHANNEL_PATH: String = "src/main/kotlin/mcorch/core/proxy/ControlChannel.kt"
        const val PAPER_AGENT_PATH: String = "src/main/kotlin/mcorch/core/paper/PaperServerAgent.kt"
        const val PROXY_AGENT_PATH: String = "src/main/kotlin/mcorch/core/proxy/VelocityProxyAgent.kt"

        /** A construction of either request type. Not the declaration, which is filtered out. */
        val REQUEST = Regex("""\b(Exec|Endpoint)Request\(""")

        /** `catch (x: IllegalArgumentException)`, however the binding is named. */
        val CATCHES = Regex("""catch\s*\(\s*\w+:\s*IllegalArgumentException\s*\)""")

        val DECLARATION = Regex("""^\s*public data class """)

        val FUNCTION =
            Regex(
                """^\s*(?:(?:private|internal|public|protected|override|abstract|open|final|suspend|inline|""" +
                    """operator|infix|tailrec|external)\s+)*fun\s+""",
            )

        val STRING = Regex(""""([^"\\]|\\.)*"""")

        fun codeOf(line: String): String = line.replace(STRING, "\"\"").substringBefore("//")

        /** Every `.kt` file in this module's main sources, in the source tree's own order. */
        fun mainSources(): List<Pair<String, List<String>>> =
            Path
                .of("src/main/kotlin")
                .toFile()
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .map { it.invariantSeparatorsPath to it.readLines() }
                .sortedBy { it.first }
                .toList()
                .also {
                    check(it.isNotEmpty()) {
                        "expected to run with the module directory as the working directory"
                    }
                }

        /** The innermost function containing [line], as its name and the range of its body. */
        fun enclosing(
            lines: List<String>,
            line: Int,
        ): Pair<String, IntRange> {
            val declaration =
                (line downTo 0).firstOrNull { FUNCTION.containsMatchIn(lines[it]) }
                    ?: error("no enclosing function for line ${line + 1}")
            val closing = " ".repeat(lines[declaration].takeWhile { it == ' ' }.length) + "}"
            val end =
                (declaration + 1..lines.lastIndex).firstOrNull { lines[it] == closing }
                    ?: error("no closing brace for the function declared at line ${declaration + 1}")
            // The type parameter list is optional and `ControlChannel.call` has one,
            // so a name regex that does not allow for it reads no name at all — and
            // a scan that throws on the one site this class exists for would have
            // been an instrument that could not see its own subject.
            val name =
                requireNotNull(Regex("""fun\s+(?:<[^>]*>\s*)?(\w+)\(""").find(lines[declaration])) {
                    "could not read a name from: ${lines[declaration].trim()}"
                }.groupValues[1]
            check(line in declaration..end) { "line ${line + 1} is not inside `$name`" }
            return name to (declaration..end)
        }

        /** The lines of the call opened at [call], to the bracket that closes it. */
        fun argumentsOf(
            lines: List<String>,
            call: Int,
        ): List<String> {
            var depth = 0
            val arguments = mutableListOf<String>()
            for (line in call..lines.lastIndex) {
                val code = codeOf(lines[line])
                if (line > call) arguments += code.trim()
                depth += code.count { it == '(' } - code.count { it == ')' }
                if (depth <= 0) break
            }
            return arguments
        }
    }
}

/**
 * The proxy spec with a seal timeout no reader would have produced.
 *
 * Zero rather than negative because zero is the reachable one: it is what a
 * half-written migration and a hand-cleared column both leave behind, and it is the
 * value `SpecBounds` deliberately does not raise — flooring it would push the
 * stop-grace pair's minimum above the grace period declared beside it.
 */
private fun VelocityProxySpec.withSeal(sealTimeout: Duration = Duration.ZERO): VelocityProxySpec =
    copy(backends = backends.copy(drain = backends.drain.copy(sealTimeout = sealTimeout)))
