package mcorch.core

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldStartWith
import mcorch.schema.DrainState
import mcorch.schema.DrainStatus
import mcorch.schema.PaperServerStatus
import mcorch.schema.ServerPhase
import mcorch.schema.ServerStatus
import mcorch.schema.StatusReconstruction
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText

/**
 * The shapes `DrainController`'s safety claims are stated in, asserted against its
 * own source.
 *
 * ## Why these are not scenario tests
 *
 * Round 18 shipped two halves of one fix — the pass-entry adoption of a voided
 * drain, and [dropSaveContradictedByPlayers] applied on the way out of `advance` —
 * and **every scenario test stayed green with either half deleted**. That is not a
 * weakness in those tests. It is the design: the adoption makes the confirmation
 * unreachable to every step in the pass, so the net downstream of it can never be
 * made to fire by any input, and a net that cannot fire cannot be scenario-tested.
 * The one thing standing between the surviving half and a container stopped on an
 * outlived save was a docstring, which is precisely the protection that failed in
 * rounds 17 and 18.
 *
 * So the wiring is pinned structurally instead: not "the loop behaves correctly
 * when both lines are present" but "both lines are present, and the value that
 * leaves is the value they produced".
 *
 * ## What a structural assertion may and may not carry
 *
 * The twentieth audit mutated the real source four ways and every mutation stayed
 * green here, which set the division of labour these tests now keep to:
 *
 * - **The content of a rule is behavioural.** `advance` and `advanceOnce` each
 *   apply one — [dropSaveContradictedByPlayers] and [adoptSaveClause] — and both
 *   are functions a unit test can call with every shape of input. Narrow one and
 *   `SaveEvidenceTest` reddens. Nothing here asserts what they do.
 * - **The wiring is structural, and the only shape it needs is "unconditionally".**
 *   Two of the four mutations were a predicate wrapped around the call site
 *   (`if (progress.drain.playersEvacuated) …`), which is invisible to a test that
 *   follows the bound name and asserts only that the name leaves. So the right-hand
 *   side of each binding is asserted to be the call and nothing else, which is a
 *   short expression to pin and does not fight a rename.
 * - **An exit is a `return` token, not a line that starts with one.** The other two
 *   mutations were `?: return progress` and `if (…) return progress`, inserted
 *   above the rule. A prefix match sees neither.
 *
 * ## What is *not* here, and why
 *
 * A gate's condition is content, not shape. `mayStop` being *called* in each
 * stop-bearing function is asserted below; that it is the whole of the condition —
 * rather than `!mayStop(…) && !playersEvacuated`, which the twenty-first audit
 * demonstrated — is behaviour, and `DrainTest` carries it for the one gate that is
 * reachable. [DrainController.stop]'s own gate has no scenario, and the reason is
 * **constructive rather than a survey**: `stop` has one caller, `letGoAndStop`,
 * reached only from the `DEREGISTERED` arm that has just evaluated `mayStop` as
 * true. One branch of that caller hands `stop` the *same* drain with the same
 * arguments in the same pass — and `mayStop` reads nothing but what it is given —
 * while the other returns without stopping, so the next pass re-enters
 * `DEREGISTERED` and the gate runs again. Not "no input anybody has thought of
 * reaches it": nothing reaches it.
 *
 * The distinction is worth the sentence. "Invisible to every possible input" is
 * what rounds 18 and 19 wrote, and both times it was an enumeration that a later
 * reader falsified. And because the argument now rests on two facts about this
 * source — one caller, reached from a `mayStop` branch — those two are pinned below
 * rather than left in prose to rot, on the same rule that took the count of stop
 * call sites out of the class KDoc: a comment counting call sites is a defect
 * waiting.
 *
 * Precedent: `mcorch.velocity.control.TransferNeverKicksTest` scans its own
 * module's sources for the same reason — a guarantee that has to hold against code
 * nobody has written yet is a claim about the code's shape.
 *
 * `scripts/dev/drain-wiring-mutations.sh` is the red-proof, and it is a durable
 * one: it applies that audit's mutations and the ones the audit after it found to
 * a working copy in turn, requires each to redden the test cases it **names**, and
 * restores the source. A structural test cannot be sabotaged behaviourally, so its
 * red-proof has to sabotage the wiring, and a set is a better proof than one
 * sabotage because these assertions fail independently of each other — each
 * mutation reddening exactly what it claims is also the evidence that none of them
 * is carrying another.
 *
 * That the verdict is read per test case rather than per class is not a detail of
 * that script: reading it per class made a broken shared helper score every
 * mutation *and both controls* as caught, and one run of it had already been
 * reported as independent verification.
 */
internal class DrainWiringTest {
    /**
     * `advance` is the single exit, and nothing leaves it that has not been through
     * the record-level rule.
     *
     * The rule is asserted on the pass's *record* rather than at the steps that
     * build one, because the defect it exists for is a step that does not think to
     * ask — round 17's was a reader, round 18's read no player count at all. That
     * argument is worth nothing if a second exit appears, or if this one starts
     * returning the progress it was handed instead of the one the rule produced,
     * or if the rule is applied to only some of what it is handed.
     */
    @Test
    fun `nothing leaves advance that has not been through the record-level rule`() {
        val advance = codeIn(rangeOf("advance"))

        // Controls first. The assertions below are about what a filtered list does
        // *not* contain, which is how a scan that found nothing at all passes for a
        // scan that found nothing wrong.
        advance.size shouldBeGreaterThan 4
        advance.count { it.contains("advanceOnce(") } shouldBe 1

        // Applied to the pass, not to a subset of passes chosen here. A predicate
        // around this call is the mutation that restores round 18's critical while
        // leaving the name that leaves unchanged.
        val recorded = binding(advance.single { it.contains("dropSaveContradictedByPlayers()") })
        recorded.value shouldMatch Regex("""\w+\.dropSaveContradictedByPlayers\(\)""")

        // One exit, and it is the rule's result. A second exit that skipped the rule
        // would be a producer the rule never sees — the exact shape of both defects
        // it was written for — and it does not have to be at the start of a line to
        // be one: `?: return progress` and `if (…) return progress` are exits.
        advance.filter { RETURN.containsMatchIn(codeOf(it)) }.map { it.trim() } shouldBe
            listOf("return ${recorded.name}")
    }

    /**
     * The single exit is only single while `advanceOnce` has one caller.
     *
     * Splitting `advance`/`advanceOnce` is what created the exit in the first
     * place: every `DrainProgress` this controller produces is built inside
     * `advanceOnce` and passes through `advance` on the way to the reconciler. A
     * second caller — a state that wanted "one more pass" without the wrapper — is
     * a producer the record-level rule never sees, and it would break no test that
     * exists.
     */
    @Test
    fun `advanceOnce is private and advance is its only caller`() {
        val declaration = rangeOf("advanceOnce").first

        // Private, so the set of possible callers is this file.
        LINES[declaration].trimStart() shouldStartWith "private suspend fun advanceOnce("

        val callers =
            LINES.indices.filter { it != declaration && isCode(LINES[it]) && LINES[it].contains("advanceOnce(") }

        callers shouldHaveSize 1
        (callers.single() in rangeOf("advance")) shouldBe true
    }

    /**
     * The primary half: the pass's drain is established with the confirmation
     * already voided, and *that* is the value every state in the pass is run
     * against.
     *
     * This is the half that keeps the net above unreachable. Deleting it leaves a
     * pass whose steps decide against a drain still claiming a save a player has
     * outlived — the net repairs what is written down, but no repair of a record
     * can un-stop a container, so the two are not interchangeable however alike
     * their output looks today.
     *
     * What is asserted is that the pass's drain comes from [adoptSaveClause] and
     * from nothing else. Which clause that function adopts is its own business and
     * `SaveEvidenceTest` holds it: a narrowed predicate here — `is Occupied &&
     * !playersEvacuated`, a plausible misreading of the *Declined* paragraph at the
     * call site — used to be invisible to every test in the suite, and moving the
     * predicate into a function is what gave it somewhere to be caught.
     *
     * **The receiver is asserted too, and that is what the extraction cost.** Inline,
     * the clause could only ever be applied to the drain the reading came from;
     * `drain.adoptSaveClause(reading)` and `recorded.adoptSaveClause(reading)` are
     * both well-typed, and the second silently resurrects a confirmation
     * [dropUnusableSaveEvidence] had just taken away for want of a witness. So the
     * binding must read as the *same* name [readPlayers] was called on, applied to
     * the reading that call produced. When a remedy relocates a defect it gives it a
     * new address, and this is that address.
     */
    @Test
    fun `a pass is stepped with the drain the pass-entry reading voided`() {
        val once = codeIn(rangeOf("advanceOnce"))

        // Controls: this really is the function that reads the probe and runs the
        // state machine over the result.
        once.count { it.contains("readPlayers(") } shouldBe 1
        once.count { it.contains("step(pass,") } shouldBe 1

        val reading = binding(once.single { it.contains("readPlayers(") })
        val observed = binding(once.single { it.contains("adoptSaveClause(") })
        observed.value shouldBe "${callee(reading.value)}.adoptSaveClause(${reading.name})"

        once.single { it.contains("step(pass,") } shouldContain "step(pass, ${observed.name})"
    }

    /**
     * Every container stop sits behind `mayStop`, and there are two of them.
     *
     * The gate is the assertion; the count is a review trigger. Asserting only
     * *where* the stops are says nothing about what stands above them — deleting
     * the `mayStop` check in [awaitStopped] left the old version of this test
     * green — and a bare count invites the wrong edit when a third legitimate site
     * appears: bump the number, add a clause, and the maintained count of call
     * sites has moved from a KDoc into a test file without gaining anything. Here a
     * third site has to be behind the same gate before this passes again.
     *
     * **What "behind" cannot mean here is "and nothing else".** A gate narrowed to
     * `!mayStop(…) && !playersEvacuated` keeps the token, the count and the
     * enclosing set, and passes this test — the twenty-first audit's finding. That
     * a stop is not re-issued against a container whose save no longer describes it
     * is pinned in `DrainTest` instead, where it is what it is: behaviour.
     *
     * The claim used to be that there was one call. It has been false since
     * `awaitStopped` learned to re-issue a stop — that call is behind an inline
     * [readPlayers] and lets an *unanswered* probe through on purpose, which
     * [requireEmpty] would abort on.
     */
    @Test
    fun `every container stop sits behind mayStop, and there are two`() {
        val calls = LINES.indices.filter { isCode(LINES[it]) && codeOf(LINES[it]).contains("stopWorkload(") }

        calls shouldHaveSize 2

        val gates = calls.map { enclosing(it) }
        // Step 7 itself, behind `requireEmpty` + `mayStop`; and its re-issue, behind
        // an inline `readPlayers` + `mayStop`. Naming them is the control that
        // `enclosing` resolved a function rather than the whole file.
        gates.map { it.name }.toSet() shouldBe setOf("stop", "awaitStopped")
        gates.forEach { gate ->
            withClue("${gate.name} calls Node.stopWorkload without asking mayStop") {
                codeIn(gate.body).any { codeOf(it).contains("mayStop(") } shouldBe true
            }
        }
        // The re-issue's other gate. It cannot be [requireEmpty] — an unanswered
        // probe has to fall through here — so the count is read inline, and a stop
        // re-issued without reading it is the round-15 shape.
        codeIn(rangeOf("awaitStopped")).any { codeOf(it).contains("readPlayers(") } shouldBe true
    }

    /**
     * …and every container stop writes the record of itself **before** issuing the
     * request.
     *
     * The thirty-second audit's must-fix rests on `DrainStatus.stopDispatchedAt`, and
     * the ordering is the whole content of that field. A stamp moved below the call
     * is a record that exists only for a request that came *back* — which is the one
     * case the compensation does not need it for. The `saveRequestedAt` rule is the
     * opposite way round for the opposite reason, so "make them consistent" is a
     * plausible edit rather than a careless one, and a reviewer reading two lines in
     * either order cannot tell which is right from the lines alone.
     *
     * ## What this covers that a scenario cannot
     *
     * A **third** stop site. Both of today's are behaviourally covered — reversing
     * either order reddens `ProxyDrainTest` — but a stop added in a new function
     * would carry no stamp, and nothing would notice until a drain re-admitted
     * players to a container it was stopping. That is the same argument as the
     * `mayStop` test above, so the two are kept side by side and the count is
     * asserted in both: they must agree about how many stops this controller makes.
     *
     * ## What it deliberately does not assert
     *
     * Where the stamped value goes. That it reaches [DrainController.abort] rather
     * than the unstamped drain is *behaviour* — `a drain whose stop timed out does
     * not hand the backend back to the proxy` and `a stop that could not be re-issued
     * does not hand the backend back either` both fail on it — and the class note
     * above is why that split is kept. What is asserted here is the shape a
     * behavioural test cannot see: that the binding is unconditional, and that
     * something downstream reads it, so a stamp bound and dropped is not mistaken for
     * a record.
     */
    @Test
    fun `every container stop records the dispatch before it issues one`() {
        val calls = LINES.indices.filter { isCode(LINES[it]) && codeOf(LINES[it]).contains("stopWorkload(") }

        // The same count as the gate test, for the same reason: a third site has to
        // satisfy this one too before it passes again.
        calls shouldHaveSize 2

        calls.forEach { call ->
            val gate = enclosing(call)
            val stamps = gate.body.filter { isCode(LINES[it]) && codeOf(LINES[it]).contains("dispatchingStop(") }
            withClue("${gate.name} issues a container stop without recording that one was dispatched") {
                stamps shouldHaveSize 1
            }
            val stamp = stamps.single()
            withClue("${gate.name} records the dispatch below the call, so a request that never returns leaves none") {
                (stamp < call) shouldBe true
            }

            // Unconditionally, and on the drain the function was handed. A predicate
            // around this — `if (contract.holdsWorldData)`, say — is the mutation the
            // ordering check cannot see, and it is the shape the class note above
            // says a structural test *may* carry.
            val recorded = binding(LINES[stamp])
            recorded.value shouldMatch Regex("""\w+\.dispatchingStop\(\w+\)""")

            // …and something below reads it. A binding nothing uses is a record in
            // name only, and the compiler is content with one.
            val readers =
                gate.body.filter {
                    it > stamp && isCode(LINES[it]) &&
                        codeOf(LINES[it]).contains(recorded.name)
                }
            withClue("${gate.name} binds ${recorded.name} and nothing downstream of the stop reads it") {
                readers.size shouldBeGreaterThan 1
            }
        }
    }

    /**
     * The declared stop grace period is read in one place, and everything else reads
     * the derived one.
     *
     * The thirtieth audit's first finding is that a ceiling applied to
     * `stopGracePeriod` without its `saveTimeout` inverts a pair the schema validated
     * together. `DrainController.stopGrace` is where the two are put back together,
     * and it has three readers — both stops and the overdue check — none of which
     * wants the raw field.
     *
     * That is a *count of call sites*, which is the shape this codebase has been
     * caught by three times, so it is asserted rather than written in a KDoc. What
     * goes red is a fourth reader taking `subject.stopGracePeriod` directly: it would
     * be reporting, or stopping with, a number the runtime was never given. The
     * assertion is on the **enclosing function**, not on a count, so a legitimate
     * fourth reader that goes through the derivation is invisible to it.
     */
    @Test
    fun `the declared stop grace period is read only where it is bounded`() {
        val reads = LINES.indices.filter { isCode(LINES[it]) && codeOf(LINES[it]).contains("stopGracePeriod") }

        // The control: a scan that finds nothing asserts nothing, and this one is
        // keyed on a field name that a rename would carry away silently.
        reads.shouldNotBeEmpty()
        reads.map { enclosing(it).name }.toSet() shouldBe setOf("stopGrace")
    }

    /**
     * …and the ceiling itself is applied where both halves of the pair are visible,
     * once, in this module.
     *
     * The test above closes the *field* half: nothing but [DrainController.stopGrace]
     * reads `subject.stopGracePeriod`. This closes the *factory* half, which it
     * leaves open. `StopGrace.of(requested, Duration.ZERO)` is a legal call from
     * anywhere, and its second argument is the floor — so a caller that supplies zero
     * for a workload that holds a world disables the floor and restores the thirtieth
     * audit's finding, with the type still saying the ceiling was applied. The type
     * proves the ceiling was applied; it cannot prove it was applied with the right
     * save timeout.
     *
     * Two call sites already pass `Duration.ZERO` — the integration harness's scrub
     * and `StopGraceGuardTest`'s own cases — and both are correct, because both are
     * world-free. Both are test code, which is why the scan is over main sources: it
     * is not that zero is wrong, it is that in a *reconcile path* the second argument
     * has to come from the subject rather than from the author's confidence.
     *
     * Scoped and unitised like the container-ending scan below it: one entry per call
     * site as `path to enclosingFunctionName`, so a second derivation added to the
     * file that already holds the first is visible, and a list of files would not
     * have been.
     */
    @Test
    fun `the stop grace ceiling is applied at one site, with the pair in front of it`() {
        val sources = mainSources()

        // Vacuity guards, the same two the scan below carries: a walk that found
        // nothing, or one that ran somewhere without the drain controller in it,
        // satisfies the assertion by accident.
        sources.size shouldBeGreaterThan 10
        sources.map { it.path } shouldContain CONTROLLER_PATH

        val built =
            sources.flatMap { source ->
                source.lines.indices
                    .filter { mentions(source.lines[it], "StopGrace.of") }
                    .map { source.path to source.enclosing(it).name }
            }

        built shouldBe listOf(CONTROLLER_PATH to "stopGrace")
    }

    /**
     * The backstop is a backstop, and this is what keeps it one.
     *
     * [DrainController.stop] re-asserts `mayStop` itself, and that re-assertion is
     * the only gate in this controller with no scenario behind it — narrow it and
     * every test in the suite stays green. The class note above says why that is
     * acceptable, in the form of a claim about this source, and the claim has
     * **three** premises: `stop` has one caller; that caller is reached from a
     * branch that has already asked `mayStop`; and what reaches `stop` is what that
     * branch asked about. All three are asserted here rather than asserted in prose,
     * because a KDoc that counts call sites is what was wrong the last three times.
     *
     * What goes red if a future edit routes into the stop from somewhere else is
     * this test, and what it means is not "the edit is wrong". It means the
     * unreachability argument has expired: the backstop has become a gate, its
     * condition is now behaviour somebody can narrow, and it needs a scenario in
     * `DrainTest` like the other two.
     *
     * ## The third premise is the one that decides whether the backstop is alive
     *
     * `stop(pass, drain)` and `stop(pass, drain.copy(…))` are both well-typed, and
     * under the second the backstop is answering a question about a drain nothing
     * upstream ever tested — which makes it a live gate rather than dead code, and a
     * live gate no scenario can reach is one somebody can narrow invisibly. The pass
     * is as load-bearing as the drain: `mayStop` takes the workload contract, the
     * container's start time and the clock, and all three are read off the pass at
     * both ends, so "the same question with the same arguments" is a claim about
     * both parameters. The call is therefore required to forward `letGoAndStop`'s
     * own parameters, unchanged and in order, with the names read off its
     * declaration — *following* them the way `a pass is stepped with the drain the
     * pass-entry reading voided` follows its receiver, rather than restating a
     * literal `drain` here.
     *
     * ## The gate half is a presence check, and what actually covers it
     *
     * That the state which reaches `letGoAndStop` asks `mayStop` at all is asserted;
     * that the question is the whole of its condition is not. The scan's unit is
     * `step` — one function over eight states — so a `mayStop(` in *any* arm
     * satisfies it, and the `DEREGISTERED` arm could drop its own gate with this
     * test green. Tightening it to the arm was the alternative and was not taken:
     * an arm has no boundary the language gives a handle on, so pinning one means
     * pinning a range of lines, which is a maintained list wearing a test's clothes
     * and reddens on a rewrap.
     *
     * What makes that acceptable is not the scan's tightness but what the two gates
     * are to each other. A stop reaches the runtime only if the arm's gate **and**
     * this backstop both allow it, so neither weakened alone loses a world; only the
     * composite does. The assertion for a composite is one that reads what reached
     * the node rather than which refusal was recorded, and `DrainTest`'s
     * `stops.shouldBeEmpty()` is exactly that — two of its scenarios turn on this
     * gate in particular (`a drain that keeps re-saving and never reaches the stop
     * asks for a human` and `a long stop grace period does not delay the report of a
     * drain that keeps re-saving`, both passes where `mayStop` is false at
     * `DEREGISTERED`). **Those assertions must not be rewritten into an assertion
     * about the failure the drain recorded.** A refusal's wording names which gate
     * spoke, and can be satisfied by the gate that is not the one under test; a
     * runtime that was never asked to stop cannot be.
     *
     * Both counts are counts *in this file*, so both declarations have to be
     * `private` for either to mean anything — an `internal suspend fun stop` can be
     * called from anywhere in `:core` and this scan would never know. That is the
     * same precondition the single-exit test above carries for `advanceOnce`, and it
     * is asserted here for the same reason — though today the compiler gets there
     * first: `internal suspend fun stop` is rejected outright for exposing
     * `DrainPass`, which is private in this class, so widening step 7 means widening
     * the pass type in the same change. The assertion is what covers the day
     * somebody does.
     */
    @Test
    fun `stop has one caller, reached from a branch that has already asked mayStop`() {
        val declaration = rangeOf("stop").first
        LINES[declaration].trimStart() shouldStartWith "private suspend fun stop("
        LINES[rangeOf("letGoAndStop").first].trimStart() shouldStartWith "private suspend fun letGoAndStop("

        val calls =
            LINES.indices.filter { it != declaration && isCode(LINES[it]) && codeOf(LINES[it]).contains("stop(") }

        calls shouldHaveSize 1
        withClue("the one call to `stop` is no longer inside `letGoAndStop`") {
            (calls.single() in rangeOf("letGoAndStop")) shouldBe true
        }

        // …and it is handed what its caller was handed, whole. Both parameters:
        // `mayStop`'s other three arguments are all read off the pass, so a
        // substituted pass changes the question exactly as a substituted drain does.
        // The names come off the declaration rather than being written here, so this
        // follows the parameters instead of restating them.
        val forwarded = parametersOf(rangeOf("letGoAndStop"))
        withClue("expected to read two parameters off `letGoAndStop`") { forwarded shouldHaveSize 2 }
        withClue("`stop` is handed something other than the pass and drain `letGoAndStop` was given") {
            codeOf(LINES[calls.single()]) shouldContain "stop(${forwarded.joinToString(", ")})"
        }

        // …and that caller is itself entered from one place. A second entry is a
        // second way into the stop however few callers `stop` has.
        val entries =
            LINES.indices
                .filter { mentions(LINES[it], "letGoAndStop") }
                .filterNot { it in rangeOf("letGoAndStop") }

        entries shouldHaveSize 1
        val state = enclosing(entries.single())
        // The control that `enclosing` resolved a function and not the whole file,
        // which would contain a `mayStop` whatever the branch above the call does.
        state.name shouldBe "step"
        withClue("${state.name} reaches letGoAndStop without asking mayStop") {
            codeIn(state.body).any { codeOf(it).contains("mayStop(") } shouldBe true
        }
    }

    /**
     * …and every *other* way of ending a container is decided at a site named here.
     *
     * The count above is scoped to one file; the claim it replaced was scoped to the
     * codebase. A stop added to `Reconciler`'s teardown, to a node-drain helper, or
     * to a rescheduling path once there is more than one node would leave every
     * assertion above green — and those are precisely the paths a drain audit goes
     * looking for, outside the scanned file by construction.
     *
     * ## The alphabet is both verbs, because both end a container
     *
     * This scanned `stopWorkload` alone, and named *rescheduling* as its motivation —
     * which reaches [Node.removeWorkload], and was outside the alphabet by
     * construction. `LocalNode` says it in its own words: the runtime's teardown
     * "kills whatever is inside with no grace and no save". The two verbs carry
     * different arguments and both are pinned here:
     *
     * - **`stopWorkload` ends a running container**, so its safety is the gate above,
     *   and what is added here is that no other call to it is decided anywhere.
     * - **`removeWorkload` refuses a running container** ([Node.removeWorkload]'s
     *   contract, enforced in `WorkloadView.teardown` and tested there), so a second
     *   deciding site is not a data-loss defect on its own; it is the thing a drain
     *   audit has to look at, which is what a review trigger is for.
     *
     * That refusal is **enforced through the runtime's enumeration**, and the
     * sentence above must not be read as more than that. `WorkloadView.teardown`
     * refuses a container the enumeration reports as anything but exited or created;
     * a container the enumeration *omits* leaves `own` null, falls through that guard
     * with no state check at all, and is removed forcibly — no grace, no save. Only a
     * lying runtime produces that, `containersIn` filters nothing, and the stale
     * handle variant is caught by the occupant guard beside it — but
     * `containers_statuses` came back unconditionally empty on containerd 2.3.3
     * earlier in this project, so it is round 4's residual rather than a
     * hypothetical: ruled and still open. The refusal is a strong reason this
     * assertion may be a review trigger instead of a gate; it is not a guarantee that
     * makes the assertion decoration.
     *
     * ## A file is too big a unit for the one case this exists for
     *
     * This pinned the deciding *files*, and the case it was widened for is not a new
     * file. Rescheduling is reconcile-loop work, so it lands in `Reconciler.kt` —
     * already on the list, carrying both teardowns — and a third, fourth or tenth
     * removal decided there left the list at exactly one entry. The vacuity control
     * beside it stayed true too. The one path the widening was performed for was the
     * one the assertion could not notice, and the mutation harness could not have
     * said so: its D14 adds a removal to a file that is off the list *by
     * construction*, which is why D15 exists.
     *
     * ## Calls are classified, not files
     *
     * A file that names [Node.stopWorkload] used to be sorted by whether it also
     * declared one — so a `private suspend fun stopWorkload(node, grace) =
     * node.stopWorkload(…)` wrapper, which is exactly what somebody writes when they
     * need a stop in two places, moved the whole file onto the *performing* side and
     * passed. The unit is the call: one inside an `override` of the verb is a node
     * performing what it was asked, any other call is a decision to end a container.
     * A distributed [Node] arriving later adds an override and passes; a wrapper does
     * not. That classification is what keeps the list below honest: it enumerates
     * *decisions*, and a new node implementation contributes none, so it is not
     * something a new node has to be edited past — which is how a maintained list of
     * files becomes a maintained lie.
     *
     * Scoped to `:core`'s main sources, which is what a test in this module can walk
     * honestly. `:app`'s stub node and the containerd harness implement or call the
     * same methods in test code, deliberately.
     */
    @Test
    fun `the calls that end a container are decided at the sites named here`() {
        val sources = mainSources()

        // Vacuity guards. A walk that found nothing, or that ran somewhere without
        // the reconcile loop in it, satisfies the assertions below by accident.
        sources.size shouldBeGreaterThan 10
        sources.map { it.path } shouldContain RECONCILER_PATH

        fun naming(verb: String) = sources.filter { source -> source.lines.any { mentions(it, verb) } }.map { it.path }

        // One entry per *call site*, because the file is the wrong unit: the path
        // this test exists for — a rescheduling helper — is written in a file that
        // already decides two removals, and a per-file list cannot tell a third from
        // the two it expects.
        fun deciding(verb: String) =
            sources.flatMap { source -> source.decisionsAbout(verb).map { source.path to it.name } }

        fun decidingFiles(verb: String) = deciding(verb).map { it.first }.distinct()

        // Control for the classifier: the files that *perform* each verb are really
        // being separated out, rather than everything landing on one side. The
        // interface declares both; the node implementation overrides both.
        NODE_FILES.forEach { performer ->
            naming("stopWorkload") shouldContain performer
            naming("removeWorkload") shouldContain performer
        }
        naming("stopWorkload").size shouldBeGreaterThan decidingFiles("stopWorkload").size
        naming("removeWorkload").size shouldBeGreaterThan decidingFiles("removeWorkload").size

        deciding("stopWorkload") shouldBe listOf(CONTROLLER_PATH to "stop", CONTROLLER_PATH to "awaitStopped")
        // Both teardowns, and nothing else. A removal decided anywhere else — a
        // rescheduling path, a node drain — is the case this test's own motivation
        // names, and `Reconciler.kt` is where that case is written, which is why the
        // file is not the unit.
        deciding("removeWorkload") shouldBe
            listOf(RECONCILER_PATH to "teardownProxy", RECONCILER_PATH to "teardown")
    }

    /**
     * The premise the step-2 waiver's narrowness rests on.
     *
     * [sealIsPrecondition] waives a failed step 2 for a subject with a
     * [DrainSeal] and no [DrainRouter], and the argument that this is narrow is
     * *constructive*: `Reconciler.drain` builds one link and passes the **same
     * object** as both, so a `PaperDrainSubject` has either both counterparties or
     * neither, and `ProxyDrainSubject` — whose `router` is a `get() = null` nothing
     * can fill — is the only subject the waiver can reach.
     *
     * `PaperDrainSubject(seal = link, router = null)` is well-typed, both parameters
     * default to null, and under it a *backend* whose proxy stopped answering would
     * carry on draining on the strength of one Server List Ping and stop a container
     * a proxy is still routing players to. Nothing in the type system refuses it, and
     * no scenario would show it as long as the reconciler keeps passing both — so
     * the premise is asserted here rather than left in the KDoc that argues from it.
     *
     * ## A second claim now rests on it, and it is smaller than it looks
     *
     * `DrainController.releaseSeal` has no `deregisteredAt` guard where `holdSeal`
     * has one, and what keeps the two consistent is the `router != null` early return
     * — which is total only because of the line asserted here. The twenty-sixth audit
     * read that as a stop ordering; it is not, and the twenty-seventh settled why in
     * a stronger form than "the compensation would be benign": **the body is
     * unreachable with `deregisteredAt` set.** The field is stamped at exactly two
     * sites, `letGoAndStop` and `releaseRegistration`, both downstream of a
     * `DrainRouter` call — and a subject with a router has already returned. So no
     * subject that can reach `assertAdmission` from there carries a stamp, and
     * nothing rests on judging whether re-registering would be safe. What the line
     * asserted here buys is that the early return is *total*: without it a Paper
     * subject could be given a seal and no router, and then the unreachability
     * argument has no premise.
     *
     * Follows the name rather than restating `link`: a rename stays green, a
     * substitution reddens.
     */
    @Test
    fun `a Paper subject is given the same object as its seal and its router`() {
        val reconciler = Source.of(RECONCILER_PATH)
        val built = reconciler.lines.indices.filter { mentions(reconciler.lines[it], "PaperDrainSubject") }

        built shouldHaveSize 1
        val arguments =
            reconciler.lines
                .drop(built.single() + 1)
                .takeWhile { !codeOf(it).trimStart().startsWith(")") }
                .map { codeOf(it).trim() }

        fun argument(name: String): String =
            arguments
                .single { it.startsWith("$name = ") }
                .removePrefix("$name = ")
                .removeSuffix(",")

        // Control: both counterparties are supplied here, by name, from something
        // that is a name rather than a literal.
        argument("seal") shouldMatch Regex("""\w+""")
        argument("seal") shouldBe argument("router")
    }

    /**
     * The loop's permanence gate and the answer the drain acts on are **one
     * expression**.
     *
     * `DrainController.abort` takes compensating edges that are only correct when
     * *no pass will look at this workload again* — releasing a self-sealing
     * workload's login seal is the one that exists today. What decides that is
     * `Reconciler.isBlockedByPermanentFailure`, and the twenty-seventh audit's
     * critical was the drain keying the edge on one of that predicate's **inputs**
     * instead: the failure class alone was true of a permanent abort under an
     * outstanding delete, whose passes carry on, and the release reopened the login
     * path of a fleet the loop kept reconciling — with the gated resume unable to
     * shut it again.
     *
     * A second derivation of the same fact is what made that possible, so the fact
     * has one home and this asserts it: the clause is declared once, both kinds'
     * gates ask it, and both drain entries hand the drain its answer rather than
     * anything they could compute for themselves. A third kind gets the same wiring
     * by writing the same call.
     *
     * The forwarded *parameter* is followed rather than restated — it has to be one
     * `DrainController.advance` declares — so a rename stays green and a
     * substitution reddens.
     */
    @Test
    fun `the drain is handed the loop's permanence gate rather than deriving one`() {
        val reconciler = Source.of(RECONCILER_PATH)

        fun declarations(name: String) =
            reconciler.lines.indices.filter {
                Regex("""${DECLARATION.pattern}(\w+\.)?$name\(""").containsMatchIn(codeOf(reconciler.lines[it]))
            }

        // One home for the clause, and one gate per kind asking it. Two is the
        // control: a kind whose gate stops asking would leave this at one.
        declarations(GATE_CLAUSE) shouldHaveSize 1
        val gates = declarations("isBlockedByPermanentFailure")
        gates shouldHaveSize 2
        gates.forEach { gate ->
            withClue("a permanence gate at line ${gate + 1} does not ask $GATE_CLAUSE") {
                reconciler.codeIn(reconciler.bodyOf(gate)).count {
                    codeOf(it).contains("$GATE_CLAUSE(")
                } shouldBe 1
            }
        }

        // …and every entry into the drain hands that answer down. A literal here —
        // or anything derived from the cause — is the defect this exists for.
        val entries =
            reconciler.lines.indices.filter { mentions(reconciler.lines[it], "drainController.advance") }
        entries shouldHaveSize 2
        val declared = parametersOf(rangeOf("advance"))
        entries.forEach { entry ->
            val supplied = argumentsOf(reconciler.lines, entry).single { it.contains("$GATE_CLAUSE(") }
            val name = supplied.substringBefore(" = ")
            withClue("`advance` is given an argument it does not declare, at line ${entry + 1}") {
                declared shouldContain name
            }
            withClue("the gate's answer is computed at the call site rather than read off the pass") {
                supplied.removePrefix("$name = ") shouldMatch Regex("""[\w.]+\.$GATE_CLAUSE\(\),""")
            }
        }
    }

    /**
     * …and inside the controller, the release is gated on that answer and on the
     * failure class, and on nothing else.
     *
     * The behavioural half is in `ProxyDrainTest`: a permanent abort under a delete
     * keeps the door shut, and one that really does freeze the server opens it. What
     * no scenario there can see is the *plausible* repair — keying the second half on
     * `DrainCause.DELETION`, which is available on the pass and agrees with the
     * reconciler's answer on every path a test can drive. It disagrees where
     * placement decides a cause first: a terminating definition whose container is on
     * a node the scheduler no longer chooses drains as a `RELOCATION`, and the door
     * would be reopened on a delete after all.
     *
     * So the guard may read the class and the parameter it was handed. Every other
     * name in scope — the cause, the state, the subject — reddens this.
     */
    @Test
    fun `the seal release is gated on the answer the abort was handed`() {
        val call =
            LINES.indices
                .filter { mentions(LINES[it], "releaseSeal") }
                .single { !DECLARATION.containsMatchIn(LINES[it]) }
        enclosing(call).name shouldBe "abort"

        // The condition alone: the call it guards names the subject, which is a
        // parameter too and says nothing about what was decided.
        val guard = codeOf(LINES[call]).replace(Regex("""releaseSeal\([^)]*\)"""), "")
        val read = parametersOf(rangeOf("abort")).filter { Regex("""\b$it\b""").containsMatchIn(guard) }
        withClue("the release is guarded by $read") {
            read.toSet() shouldBe setOf("failureClass", GATE_CLAUSE)
        }

        // …and what is handed in is the pass's copy at every site, never something
        // an abort branch worked out for itself.
        val calls =
            LINES.indices
                .filter { mentions(LINES[it], "abort") }
                .filterNot { DECLARATION.containsMatchIn(LINES[it]) }
        // A vacuity control rather than a maintained count: this file parks a drain
        // from many branches, and what must not happen is a scan that found none.
        calls.size shouldBeGreaterThan 4
        calls.forEach { site ->
            val supplied = argumentsOf(LINES, site).single { it.startsWith("$GATE_CLAUSE = ") }
            withClue("the abort at line ${site + 1} derives its own permanence gate") {
                supplied shouldMatch Regex("""$GATE_CLAUSE = (pass\.)?$GATE_CLAUSE,""")
            }
        }
    }

    /**
     * Every pass that gets the login seal in place writes down that it did.
     *
     * The twenty-eighth audit's first critical. `sealRequestedAt` was stamped by the
     * `DRAIN_REQUESTED` arm alone, while `holdSeal` runs on six other states and on
     * the gated `DRAIN_FAILED` resume — which, since the twenty-seventh audit, is
     * where a self-sealing workload's door is *first* shut whenever its opening
     * attempt failed with players on. The record stayed null, and the next pass to
     * lose the endpoint read that null and told an operator the server "keeps taking
     * players" about a fleet this controller had blacked out one pass earlier.
     *
     * ## Why this is structural
     *
     * The behavioural half is in `ProxyDrainTest` — a proxy sealed by its resume and
     * then parked reports the blackout — and it can only ever cover the one call site
     * a scenario happens to drive. Six more sites do the same work, and a build that
     * dropped the record at any of them would still be green: nothing *decides* on
     * this field, so the only way to see it is a status read taken in exactly the
     * right state. So the shape is asserted instead: a `holdSeal` result is bound,
     * consulted for its abort, and recorded on the drain the pass carries on with,
     * everywhere.
     *
     * What it cannot see is a site that records the stamp on a drain other than the
     * one the hold was taken with. The stamped name is followed for that, in the same
     * way the pass-entry test follows its receiver — as a set, because six of the
     * seven sites are arms of one `when` and share their names by construction.
     */
    @Test
    fun `every step-2 assertion is recorded on the drain the pass carries on with`() {
        val calls =
            LINES.indices
                .filter { mentions(LINES[it], "holdSeal") }
                .filterNot { DECLARATION.containsMatchIn(LINES[it]) }

        // Vacuity control, and a deliberately loose one: this is a count of the
        // states that assert step 2, which a new state may raise.
        calls.size shouldBeGreaterThan 5

        // Bound, never called for its abort alone. An unbound call is the shape that
        // cannot record anything, whatever it does with the result.
        val held = calls.map { binding(LINES[it]) }
        held.forEach { hold ->
            withClue("a step-2 assertion is not bound to a name: ${hold.value}") {
                hold.value shouldMatch Regex("""holdSeal\(\w+, (\w+)\)""")
            }
        }
        val names = held.map { it.name }.toSet()
        val stamped = held.map { requireNotNull(Regex("""holdSeal\(\w+, (\w+)\)""").find(it.value)).groupValues[1] }

        fun using(token: String) = LINES.filter { isCode(it) && codeOf(it).contains(token) }.map { codeOf(it).trim() }

        // One of each per call: the abort is taken, and the hold is recorded. A site
        // that consults the abort and drops the record is the defect; a site that
        // records without consulting would carry on past a park.
        using(".abortOrNull") shouldHaveSize calls.size
        val records = using(".recordedOn(")
        records shouldHaveSize calls.size
        records.forEach { line ->
            val receiver =
                requireNotNull(Regex("""(\w+)\.recordedOn\((\w+),""").find(line)) {
                    "expected `<hold>.recordedOn(<drain>, …)`, found: $line"
                }
            withClue("a step-2 record is taken from something other than the hold this pass held") {
                names shouldContain receiver.groupValues[1]
            }
            withClue("a step-2 record is stamped on a drain the hold was not taken with") {
                stamped shouldContain receiver.groupValues[2]
            }
        }
    }

    /**
     * No kind drains for a replacement its node was never asked about.
     *
     * A drain destroys the container it is replacing. Asking the node whether it can
     * build the replacement *after* that is a correct refusal at the wrong moment —
     * the twenty-fourth audit's finding on the proxy path, and the twenty-fifth's on
     * the path that holds worlds, where the pre-flight had simply never been added.
     *
     * ## Classified rather than enumerated
     *
     * The unit is "a function that computes a drain cause", not a list of the two
     * kinds that exist today. A third kind must compute a cause before it can drain,
     * so it lands in this scan by construction rather than by somebody remembering
     * to extend a list — which is the failure mode this test is about in the first
     * place.
     */
    @Test
    fun `every pass that decides to drain asks first whether the replacement can be built`() {
        val reconciler = Source.of(RECONCILER_PATH)
        val causes =
            reconciler.lines.indices
                .filter { DRAIN_CAUSE.containsMatchIn(codeOf(reconciler.lines[it])) && isCode(reconciler.lines[it]) }
                .filterNot { DECLARATION.containsMatchIn(reconciler.lines[it]) }
                .map { reconciler.enclosing(it) }

        // Control: the scan found the pass entries rather than nothing, and one per
        // kind. A third kind makes this two and is meant to.
        causes.map { it.name }.toSet() shouldBe setOf("reconcilePaper", "reconcileProxy")

        causes.forEach { pass ->
            val body = reconciler.codeIn(pass.body)
            withClue("${pass.name} computes a drain cause without a replacement pre-flight") {
                body.count { codeOf(it).contains("replacementBlocker(") } shouldBe 1
            }
            // …and asks before it drains, which is the whole point. `drain(` and
            // `drainProxy(` are the two ways into `DrainController` from here.
            val asked = body.indexOfFirst { codeOf(it).contains("replacementBlocker(") }
            val drains = body.indexOfFirst { ENTERS_DRAIN.containsMatchIn(codeOf(it)) }
            withClue("${pass.name} drains before it asks") {
                (drains > asked) shouldBe true
            }
        }
    }

    /**
     * Nothing in this module deletes a drain record on its own authority.
     *
     * The thirty-third audit's critical is a *lifetime*, and the shape it takes is
     * one token: `drain = null`, written by a pass that has concluded no drain is
     * wanted. That conclusion is correct about the drain and says nothing about
     * `DrainStatus.stopDispatchedAt`, which describes a `SIGTERM` already inside a
     * container — so the site that wrote it deleted the one record standing between a
     * proxy's routing sweep and a player's lost session.
     *
     * ## Why the shape rather than the behaviour
     *
     * Three scenarios in `ProxyDrainTest` cover the three sites that could reach it
     * *today*, and the sites are the problem: there were eight lines in one file
     * writing that token, on paths that have nothing to do with each other — a
     * readiness probe, a refused edit, two creates, a teardown. The audit named three
     * of them. Each is ordinary converging code with no reason to be thinking about a
     * stop, which is exactly the population a scenario suite cannot enumerate: the
     * ninth line is written by somebody adding a phase, and no test would notice.
     *
     * So the rule has one home, and this asserts that every site asks it. What it
     * cannot see is what the rule *does* — that is `DrainRecordLifetimeTest`, which
     * calls it directly, and the class note above says why that split is kept.
     *
     * ## The arguments are pinned too, and that is not decoration
     *
     * `clearedDrainRecord(null, observation)` and
     * `clearedDrainRecord(previous?.drain, someOtherObservation)` both satisfy "the
     * site asks", and the first is `drain = null` with more letters. The shape
     * required is a record read off a status and an observation named rather than
     * built, which follows a rename and refuses a substitution.
     */
    @Test
    fun `every drain record this loop retires is retired through the one rule`() {
        val sources = mainSources()

        // Vacuity guards: a walk that found nothing, or one that ran somewhere
        // without the reconcile loop in it, satisfies an absence by accident.
        sources.size shouldBeGreaterThan 10
        sources.map { it.path } shouldContain RECONCILER_PATH

        val deleted =
            sources.flatMap { source ->
                source.lines.indices
                    .filter { isCode(source.lines[it]) && CLEARS_DRAIN.containsMatchIn(codeOf(source.lines[it])) }
                    .map { source.path to source.enclosing(it).name }
            }
        withClue("a drain record is deleted without asking whether a stop is in flight: $deleted") {
            deleted shouldBe emptyList()
        }

        // …and the sites that do ask are asking about something. One entry per
        // `drain = <value>` argument in the loop, classified: the rule, the record
        // the drain itself produced, or the parameter a drafting helper forwards.
        val reconciler = Source.of(RECONCILER_PATH)
        val written =
            reconciler.lines
                .filter(::isCode)
                .map { codeOf(it).trim() }
                .filter { it.startsWith("drain = ") }
                .map { it.removePrefix("drain = ").removeSuffix(",") }

        // A count of assignments rather than of call sites: what must not happen is
        // a scan that found none, and a new phase legitimately raises it.
        written.count { it.startsWith("clearedDrainRecord(") } shouldBeGreaterThan 8
        written.filterNot { it.startsWith("clearedDrainRecord(") }.toSet() shouldBe setOf("progress.drain", "drain")
        written.filter { it.startsWith("clearedDrainRecord(") }.forEach { call ->
            withClue("a drain record is retired on something other than this pass's own record: $call") {
                call shouldMatch ASKS_THE_RULE
            }
        }
    }

    /**
     * The two rules that read a sandbox with no container in it get the same fact.
     *
     * `SANDBOX_ONLY` is two different worlds — a workload whose container has never
     * been created, and a live container the runtime is failing to enumerate — and
     * nothing in the observation tells them apart. `DrainController.advance` is given
     * a container id this loop recorded and separates them on it, because getting it
     * wrong there tears down a sandbox with a live server inside it. [stopIsInFlight]
     * answers the identical question on the identical observation, for the identical
     * reason, and until the thirty-fourth audit it was **not given the fact at all**:
     * it classified `SANDBOX_ONLY` as "not the signalled container" unconditionally,
     * so a runtime that stopped reporting a container mid-stop made the loop converge
     * over the top of its own `SIGTERM` and a proxy re-admit players to it.
     *
     * That defect is a *missing argument*, which is why this is a shape assertion and
     * not another mutant: no flip of an expression that was never written can be
     * scored, and the check that finds it is a hand comparison of the two rules. What
     * can be pinned is that there is one fact, derived once per kind of pass, and that
     * every consumer reads it rather than deriving it again or answering it with a
     * literal.
     */
    @Test
    fun `the fact that tells an emptied sandbox from an unreported container has one derivation`() {
        val reconciler = Source.of(RECONCILER_PATH)
        val code = reconciler.lines.filter(::isCode).map { codeOf(it).trim() }

        // One derivation per kind of pass — a backend and a proxy — and nowhere
        // else. A second spelling is how the two rules come to disagree.
        val derived = code.filter { it.contains("runtime?.containerId != null") }
        withClue("the container-id fact is derived somewhere other than a pass's own property: $derived") {
            derived.toSet() shouldBe setOf("val hadContainer: Boolean get() = previous?.runtime?.containerId != null")
        }
        derived shouldHaveSize 2

        // …and every consumer reads that property. `hadContainer = false` at a call
        // site restores the audited defect one site at a time while every other
        // assertion here stays green.
        val given = code.filter { it.startsWith("hadContainer = ") }
        given shouldHaveSize 2
        given.toSet() shouldBe setOf("hadContainer = pass.hadContainer,")

        // The routing half, which is the one the critical was found in: a withdrawn
        // cause is kept draining by the same rule, asked with the same fact.
        val routing = code.filter { it.contains("outstandingStopCause(") }
        routing shouldHaveSize 2
        routing.forEach { call ->
            withClue("a withdrawn cause is judged without the fact that separates the two sandboxes: $call") {
                ROUTES_ON_THE_RULE.containsMatchIn(call) shouldBe true
            }
        }
    }

    /**
     * The pre-flight is the create's own derivation, not a subset of it.
     *
     * `LocalNode` is the one file `:core`'s tests may not call into, so this is a
     * claim about its source. It ran [mountsFor] alone, which is one of the two ways
     * `containerSpecFor` can refuse a workload — so a secret reference that resolves
     * to nothing passed the pre-flight, the proxy was drained to zero, stopped and
     * removed, and the create then refused permanently.
     *
     * What is asserted is that there is nothing left to be a subset *of*: the
     * pre-flight calls the whole derivation, and each refusable half is reached from
     * exactly one place. A third refusal added to `containerSpecFor` is then
     * pre-flighted without anybody coming back here.
     */
    @Test
    fun `the replacement pre-flight runs the create's own container derivation`() {
        val local = Source.of(LOCAL_NODE_PATH)
        val preflight = local.codeIn(local.rangeOf("checkWorkload"))

        preflight.count { codeOf(it).contains("containerSpecFor(") } shouldBe 1
        // Nothing else: a derivation written here is the subset that let a secret
        // through, whatever it is a derivation of.
        withClue("the pre-flight re-derives part of the create instead of running it") {
            preflight.none { codeOf(it).contains("mountsFor(") || codeOf(it).contains("secrets.") } shouldBe true
        }

        fun calls(name: String) =
            local.lines.indices
                .filter { mentions(local.lines[it], name) }
                .filterNot { DECLARATION.containsMatchIn(local.lines[it]) }

        // One create and one pre-flight…
        calls("containerSpecFor") shouldHaveSize 2
        // …and each half that can refuse is reached only through it.
        calls("mountsFor") shouldHaveSize 1
        calls("secretsFor") shouldHaveSize 1
        // The refusal itself is one value, so the pre-flight cannot word or classify
        // it differently from the create that follows.
        calls("missingSecret") shouldHaveSize 2
    }

    /**
     * Every classification of a [WorkloadState] either **takes** the fact that
     * decides `SANDBOX_ONLY`, or **says at the site** why it does not need it.
     *
     * Two syntaxes are classifications and both are scanned: a `when` arm whose
     * pattern names a state, and an `==`/`!=` against `SANDBOX_ONLY`. The second was
     * added by the thirty-sixth audit, which found the docstring claiming "every
     * classification" over a scan that read arms alone — with three comparisons
     * already in `:core/main`, one of them the drain's own abort. What is *not*
     * covered is written on [Source.stateComparisons]: a comparison against a
     * different state lumps the two sandboxes together without naming either.
     *
     * ## Why this instrument exists, and what it is the first of
     *
     * Rounds 33, 34 and 35 were one defect three times: a fact this codebase models
     * exactly in one place, approximated at a *new consumer* that asked a narrower
     * question than the fact supports. All three were **omissions** — a missing
     * argument, a missing clause — and a mutation board scores **inversions**, so
     * none of them could have gone red. `drain-wiring-mutations.sh` was honestly
     * green through all three.
     *
     * `SANDBOX_ONLY` is the state that needs a fact from outside the observation.
     * It is two different worlds — a workload whose container was never created, and
     * a live container the runtime has stopped enumerating — and nothing the node
     * reports separates them; only [Pass.hadContainer], a container id this loop
     * wrote down, does. A rule that classifies it *without* asking has not made a
     * wrong choice, it has failed to notice there was one, and that is invisible to
     * every behavioural test whose fixture cannot express the case.
     *
     * So the shape asserted is the weakest one that catches an omission: the arm's
     * answer is a function of the fact, **or** the arm carries a note that names the
     * fact and therefore says why it is doing without. Neither is a claim that the
     * classification is *correct* — that is behaviour, and `DrainTest`,
     * `DrainRecordLifetimeTest` and `ReplacementTest` hold it. What cannot be
     * written any more is a sixth arm that neither reads the fact nor mentions it.
     *
     * ## What it costs and what it cannot see
     *
     * A note is prose and prose can be pasted. That is accepted: the value is that a
     * classification added without a thought about `hadContainer` cannot compile-and-
     * pass, and the author has to write a sentence a reviewer can disagree with.
     *
     * It cannot see an arm whose answer depends on the fact across several lines —
     * `-> {` followed by a block that reads it — because "the arm's own line" is the
     * shape this project writes and widening it to the whole block is how a scan
     * passes on an unrelated `hadContainer` deeper inside. Both `converge` arms
     * forward `pass.hadContainer` to [clearedDrainRecord] inside their blocks, which
     * is a different question from the one the arm answers; scanning the block would
     * have scored both of them as taking the fact and this test would have been
     * green on the day it was written.
     */
    @Test
    fun `every workload-state classification either takes the fact or argues at the SANDBOX_ONLY arm`() {
        // The fold, on the two shapes it has to tell apart — neither of which is in
        // this module today, so neither can be demonstrated by scanning it. The first
        // is the arm the thirty-sixth audit wrapped: three states, one arm, and the
        // two that matter are on continuation lines. The second is every `write(` in
        // this file, which is a `->` line with ordinary parameters above it at the
        // same indentation and is not an arm at all.
        Source(
            "<a wrapped arm>",
            listOf(
                "            when (state) {",
                "                WorkloadState.CREATED,",
                "                WorkloadState.SANDBOX_ONLY,",
                "                WorkloadState.EXITED -> false",
                "            }",
            ),
        ).stateArms().single().pattern shouldBe
            "WorkloadState.CREATED, WorkloadState.SANDBOX_ONLY, WorkloadState.EXITED"
        Source(
            "<a wrapped parameter list>",
            listOf(
                "    private fun write(",
                "        state: WorkloadState = WorkloadState.SANDBOX_ONLY,",
                "        outcome: () -> ReconcileOutcome,",
                "    )",
            ),
        ).stateArms() shouldBe emptyList()

        val arms = mainSources().flatMap { it.stateArms() }
        val comparisons = mainSources().flatMap { it.stateComparisons() }

        // Vacuity guards. An assertion over a filtered list is satisfied by a scan
        // that found nothing, and the count is not maintained here: what is asserted
        // is that both kinds exist, so neither branch of the rule is dead.
        arms.shouldNotBeEmpty()
        val classifyingArms = arms.filter { it.names(UNCLASSIFIED) }
        classifyingArms.size shouldBeGreaterThan 1
        // …and that both *syntaxes* exist, so neither reader is scanning for a shape
        // this module has stopped writing.
        comparisons.shouldNotBeEmpty()

        val classifying = classifyingArms.map { it.asDecision() } + comparisons
        val takesTheFact = classifying.filter { it.answer.contains(THE_FACT) }
        val argues = classifying.filter { it.note.contains(THE_FACT) }
        withClue("no classification reads the fact, so the scan is looking at the wrong thing") {
            takesTheFact.shouldNotBeEmpty()
        }
        withClue("no classification argues its way out, so the note half of the rule is untested") {
            argues.shouldNotBeEmpty()
        }

        val silent = classifying.filterNot { it in takesTheFact || it in argues }
        withClue(
            "a workload state is classified without the fact that decides `$UNCLASSIFIED` and without saying " +
                "why it does not need it: ${silent.map { it.where }}",
        ) {
            silent shouldBe emptyList()
        }

        // The alphabet control, over the arms alone: `CREATED` is in every one of
        // these `when` blocks and in none of the expressions this scan is not about,
        // so the two counts moving together is evidence the arm scan is seeing whole
        // blocks rather than a formatting accident.
        //
        // It is a *control*, and the thirty-sixth audit's first hole was that it had
        // become the only thing watching a whole shape: a wrapped pattern took an arm
        // out of the alphabet, and because `CREATED` wrapped with it both counts fell
        // together and this balanced anyway. Folding continuation lines back into the
        // pattern is what puts such an arm back in front of the silent check above;
        // this line is not what catches it and was never meant to be.
        classifyingArms shouldHaveSize arms.count { it.names("CREATED") }
    }

    /**
     * A classification of a [WorkloadState] writes the type in front of the entry.
     *
     * The other way out of the alphabet, and it takes the control with it. The scan
     * above keys on the qualified name, so a `when` whose arms read `SANDBOX_ONLY ->`
     * contributes **zero** classifying arms and **zero** `CREATED` arms: no count
     * moves, nothing reads as silent, and the state and its control disappear
     * together. The thirty-sixth audit's second hole.
     *
     * ## How a bare entry gets written, which is not what that audit said
     *
     * It gave Kotlin's context-sensitive resolution — `SANDBOX_ONLY -> true` in a
     * `when` over the enum — and against this build's compiler that does not compile:
     * every entry reads "Unresolved reference". What does compile, in every Kotlin
     * version, is `import mcorch.core.WorkloadState.SANDBOX_ONLY`, which is one IDE
     * quick-fix away and leaves the arm looking exactly the same. The finding was
     * right about the hole and wrong about the way in, so the mutation that scores it
     * imports the entries — and the day a Kotlin release turns the other form on, this
     * rule already covers it.
     *
     * Refusing the shape is the answer rather than teaching the scan to read bare
     * entries, because the qualified name is what tells this type's `RUNNING` from
     * [mcorch.schema.ServerPhase]'s. A bare arm that is *not* a workload state at all
     * is refused too, and that is the honest cost: the demand is to qualify it, which
     * is what every arm in this module already does and what makes the alphabet
     * readable in the first place.
     */
    @Test
    fun `no workload-state classification writes a bare enum entry`() {
        val states = Source.of(NODE_PATH).enumEntries(WORKLOAD_STATE_TYPE)

        // The alphabet is read from the declaration, so it has to be checked that
        // something was read: an empty list makes every assertion below vacuous.
        states.size shouldBeGreaterThan 2
        states shouldContain UNCLASSIFIED
        states shouldContain "CREATED"

        // And the detector has to be able to fire. These are the two lines the whole
        // rule is about, and asserting the qualified one is *not* reported is what
        // stops a matcher that flags everything from reading as a clean scan.
        bareStates("$UNCLASSIFIED -> true", states) shouldBe listOf(UNCLASSIFIED)
        bareStates("$WORKLOAD_STATE_TYPE.$UNCLASSIFIED -> true", states) shouldBe emptyList()

        val unqualified =
            mainSources().flatMap { source ->
                source.arms().filter { bareStates(it.pattern, states).isNotEmpty() }.map {
                    "${it.where} (${bareStates(it.pattern, states)})"
                }
            }
        withClue(
            "a `when` arm names a workload state without its type, where the classification scan cannot " +
                "see it: $unqualified",
        ) {
            unqualified shouldBe emptyList()
        }
    }

    /**
     * No `when` that classifies a [WorkloadState] may fold one into an `else`.
     *
     * The scan above has an alphabet — arms that *name* `SANDBOX_ONLY` — and an
     * `else` arm is exactly how a state leaves it. `when (state) { RUNNING -> …;
     * else -> … }` classifies all four other states, decides `SANDBOX_ONLY` among
     * them, and is invisible to an instrument built for omissions, which would be
     * the omission-shaped hole in the omission detector.
     *
     * There was one when this was written: the phase badge in `forbiddenTransition`,
     * where `CREATED` and `SANDBOX_ONLY` are refused upstream and the answer is a
     * badge rather than a decision about a container. Enumerating them there costs a
     * line and is what makes the claim above complete rather than nearly complete.
     */
    @Test
    fun `no workload-state classification hides a state in an else arm`() {
        val hidden =
            mainSources().flatMap { source ->
                val blocks = source.stateArms().map { source.whenBlockAround(it.line) }.distinct()
                // Vacuity: a `whenBlockAround` that started returning a one-line range
                // would satisfy the scan below without having read any arm at all.
                blocks.forEach { it.count() shouldBeGreaterThan 2 }
                blocks.flatMap { block ->
                    block
                        .filter { isCode(source.lines[it]) && armPattern(codeOf(source.lines[it])) == "else" }
                        .map { "${source.path}:${it + 1}" }
                }
            }
        withClue("a workload state is decided by an `else`, where nothing goes looking for a classification: $hidden") {
            hidden shouldBe emptyList()
        }
    }

    /**
     * The premise the two `converge` arms argue from, pinned rather than left in
     * prose.
     *
     * Neither `converge` nor `convergeProxy` takes [Pass.hadContainer], and each
     * one's `SANDBOX_ONLY` arm creates a container into the sandbox — which, on the
     * observation that cannot tell an emptied sandbox from an unreported one, is the
     * shape of a second create over the top of a stop this loop has already
     * dispatched. Their note says the fact was asked *above* them, and that is a
     * claim about this source with two parts:
     *
     * - every call to either one is inside the function that asks the rule, with the
     *   fact ([ROUTES_ON_THE_RULE], which the test above pins the shape of); and
     * - every such call is an arm of the same `when` that routes the other way into
     *   the drain, so converging is the *alternative* to draining rather than
     *   something reachable beside it.
     *
     * A converge reached from anywhere else is a route that never asked, and the
     * arms' notes would be quietly false — which is the state rounds 18 and 19 both
     * ended in, with the argument in a docstring and nothing holding it.
     */
    @Test
    fun `every converge is an arm of the routing that asks the rule`() {
        val reconciler = Source.of(RECONCILER_PATH)
        val calls =
            reconciler.lines.indices
                .filter {
                    mentions(
                        reconciler.lines[it],
                        "converge",
                    ) || mentions(reconciler.lines[it], "convergeProxy")
                }.filterNot { DECLARATION.containsMatchIn(reconciler.lines[it]) }

        // Both kinds, so a name that stops matching is not read as a clean scan.
        calls.count { mentions(reconciler.lines[it], "converge") } shouldBeGreaterThan 1
        calls.count { mentions(reconciler.lines[it], "convergeProxy") } shouldBeGreaterThan 1

        calls.forEach { call ->
            val code = codeOf(reconciler.lines[call])
            // An **arm**, not merely a line inside the deciding function. `if (blocker
            // != null) return converge(…)` above the `when` is behaviourally identical
            // and moves the decision out of the one place both answers are weighed
            // together — after which "converging is the alternative to draining" is a
            // sentence rather than a shape.
            withClue("a converge at ${reconciler.path}:${call + 1} is not a `when` arm: ${code.trim()}") {
                (armPattern(code) != null && code.substringAfter("->").contains("converge")) shouldBe true
            }
            val routing = reconciler.whenBlockAround(call).map { codeOf(reconciler.lines[it]) }.filter(::isCode)
            withClue("a converge at ${reconciler.path}:${call + 1} is not an arm beside the drain it replaces") {
                routing.any { ENTERS_DRAIN.containsMatchIn(it) } shouldBe true
            }
            val asking = reconciler.enclosing(call).body.map { codeOf(reconciler.lines[it]) }
            withClue("a converge at ${reconciler.path}:${call + 1} is decided without asking whether a stop is out") {
                asking.any { ROUTES_ON_THE_RULE.containsMatchIn(it) } shouldBe true
            }
        }
    }

    /**
     * The premise the drain's `SANDBOX_ONLY` abort argues from, pinned rather than
     * left in prose.
     *
     * `advanceOnce` classifies the state with an `if`, not an arm, and it does not
     * read the fact at that line — the abort it takes is right *because* the same
     * observation has already been through `containerIsDown(hadContainer)`, which
     * returns for the world this loop emptied itself. What reaches the comparison is
     * therefore the other world: a workload that has had a container the runtime has
     * stopped reporting, which is the one thing that must not be torn down.
     *
     * That is a constructive argument, so its premises go in a test rather than in
     * the note beside it — this file's own rule, and the third time it has been
     * applied. Four of them, and each is a way the note goes quietly false:
     *
     * - the rule is asked in this function at all, **once**, so there is no second
     *   derivation to disagree with;
     * - it is asked with the enclosing function's own `hadContainer` parameter,
     *   rather than a constant, which is the exact shape of the thirty-fourth
     *   audit's critical;
     * - its answer is *bound* and the guard reads that name, so a rewrap or a rename
     *   stays green and a substitution does not; and
     * - the guard **returns**, above the comparison, which is what makes "what
     *   reaches this line" a fact rather than a hope.
     */
    @Test
    fun `the drain's sandbox abort is reached only past the rule that separates the two sandboxes`() {
        val sites =
            LINES.indices.filter { isCode(LINES[it]) && COMPARES_UNCLASSIFIED.containsMatchIn(codeOf(LINES[it])) }
        withClue("the drain classifies `$UNCLASSIFIED` somewhere this claim has not been read against: $sites") {
            sites shouldHaveSize 1
        }
        val classification = sites.single()
        val deciding = enclosing(classification)

        val asked = deciding.body.filter { mentions(LINES[it], "containerIsDown") }
        withClue("the sandbox rule is asked ${asked.size} times in `${deciding.name}`, not once") {
            asked shouldHaveSize 1
        }
        val rule = asked.single()
        withClue("the abort classifies the state before the rule that separates its two worlds is asked") {
            (rule < classification) shouldBe true
        }

        // The fact, read off this function's own parameter list rather than restated,
        // so that renaming it stays green and answering it with `false` does not.
        val fact = parametersOf(deciding.body).single { it == THE_FACT }
        val down = binding(LINES[rule])
        withClue("the sandbox rule is asked with something other than this pass's observation and fact") {
            down.value shouldBe "observation.containerIsDown($fact)"
        }

        val guard =
            deciding.body.first { it > rule && codeOf(LINES[it]).trim().startsWith("if (${down.name} != null)") }
        val guarded = CONTROLLER.bodyOf(guard)
        withClue("the answer to the sandbox rule is read and then fallen through, so nothing is decided by it") {
            codeIn(guarded).any { RETURN.containsMatchIn(it) } shouldBe true
        }
        withClue("the abort is inside the branch that already handled the container being down") {
            (classification > guarded.last) shouldBe true
        }
    }

    /**
     * Every producer of a drain state that a **decode rule** keys on says, at the
     * producer, what it does about the record that rule reconstructs.
     *
     * ## The defect this exists for
     *
     * `mcorch.schema.StatusReconstruction` reads a stored observation and, for a
     * document in [DrainState.STOPPING] with no `stopDispatchedAt`, supplies one —
     * justified by *"a drain reaches `STOPPING` only after a stop request returned
     * cleanly"*. That sentence has an antecedent with **two** producers in this
     * module and its author surveyed one. The other is the already-down branch in
     * [DrainController.advanceOnce], reached whenever the runtime says the container
     * is gone, dispatching nothing — so the current build routinely writes exactly
     * the document the rule says cannot exist.
     *
     * Nothing could have caught it. 921 unit tests, two mutation harnesses and an
     * integration run were all honestly green: there is no expression to flip in a
     * rule whose premise is a survey, and a survey is not a shape any scan was
     * looking for. It is the thirty-fourth audit's finding one level up — a fact
     * modelled in one place and approximated by a consumer that asked a narrower
     * question — and the answer is the same one: an instrument whose unit is the
     * thing the survey enumerates.
     *
     * ## How the scan learns which states are keyed on
     *
     * Not from a list here. A hardcoded `STOPPING` would be a maintained list wearing
     * a test's clothes, which is the failure this suite exists to retire.
     *
     * The rule is a pure, public function of a status, so it can simply be **asked**:
     * build one observation per [DrainState] with no side-effect records at all, run
     * it through [StatusReconstruction.reconstruct], and the states that come back
     * reconstructed are the states the rule keys on. The alphabet is `DrainState`'s
     * own entries, so a ninth state joins the probe on the day it is declared, and the
     * *record* each rule restores is read off the report it returns rather than named
     * here — [mcorch.schema.ReconstructedRecord.field] is the rule's own word for what
     * it wrote.
     *
     * That is why this lives in `:core` and not beside the rule in `:schema`. The rule
     * is discoverable from anywhere; the **producers** are not. They are drain
     * transitions, they exist only in this module, and `:schema` cannot see one — it
     * is the module `:core` depends on, not the other way round.
     *
     * The limit of the discovery, stated rather than papered over: it finds decode
     * rules keyed on a `DrainState`. A rule keyed on some other field of a status is
     * invisible to this probe, and widening it means giving the probe an axis per
     * field. `DrainState` is the axis that had the defect.
     *
     * ## What "named by the justification" means mechanically
     *
     * Not a KDoc in `:schema` listing `DrainController.kt:302`. A line number rots on
     * the next edit; a function name rots on the next rename; and both point the wrong
     * way across a module boundary, since the justification lives in the module that
     * cannot name a producer.
     *
     * So the obligation is discharged **at the producer**, and the two ends meet at
     * the name of the record: the rule reports which field it reconstructs, and every
     * producer of a keyed state has to name that field — in its own statement, or in
     * the contiguous `//` block above it. Nothing on either side names a location, and
     * whoever writes the third producer of `STOPPING` is the person who has to think
     * about the stamp, which is where the thinking was missing.
     *
     * A note is prose and prose can be pasted; that is accepted here for the reason
     * the classification scan above accepts it, and it is scored the same way — one
     * mutation adds a producer with no note, another gives it a note that argues the
     * branch without naming the record.
     */
    @Test
    fun `every producer of a state a decode rule keys on names the record it reconstructs`() {
        // The read/write classifier, on the shapes it has to tell apart — three of
        // which are in this module and one of which (a write folded across lines with
        // its note above the whole statement) is the shape both live producers have.
        val shapes =
            Source(
                "<the four shapes>",
                listOf(
                    "        return when (drain.state) {",
                    "            DrainState.SEALED,",
                    "            DrainState.STOPPING,",
                    "            -> awaitStopped(pass, drain)",
                    "            else -> drain.state == DrainState.STOPPING",
                    "        }",
                    "        val moved = drain.moveTo(DrainState.STOPPING, now)",
                ),
            ).writesOf(DRAIN_STATE_TYPE, "STOPPING")
        withClue("a wrapped pattern or a comparison reads as a producer, so the scan flags what it should not") {
            shapes.map { it.where } shouldBe listOf("<the four shapes>:7")
        }
        val wrapped =
            Source(
                "<a wrapped write>",
                listOf(
                    "        // the stopDispatchedAt stamp travels with the state",
                    "        drain =",
                    "            dispatching",
                    "                .moveTo(DrainState.STOPPING, now),",
                ),
            ).writesOf(DRAIN_STATE_TYPE, "STOPPING").single()
        wrapped.answer shouldBe "drain = dispatching .moveTo(DrainState.STOPPING, now),"
        wrapped.note shouldContain "stopDispatchedAt"

        // The name-resolution hole, refused rather than read. The scan is keyed on the
        // qualified spelling, and the thirty-sixth audit's second hole was the same
        // scan one level up going blind to `import mcorch.core.WorkloadState.SANDBOX_ONLY`
        // — one IDE quick-fix, and the producer looks identical. Teaching the scan to
        // read bare entries is not the answer here: `ServerPhase` declares `STOPPING`
        // too, so a bare `STOPPING` cannot be attributed to a type at all. An alias is
        // the same hole spelled differently and is refused with it.
        val detector = "import mcorch.schema.$DRAIN_STATE_TYPE.STOPPING"
        IMPORTS_A_STATE.containsMatchIn(detector) shouldBe true
        IMPORTS_A_STATE.containsMatchIn("import mcorch.schema.$DRAIN_STATE_TYPE") shouldBe false
        val unqualified =
            mainSources().flatMap { source ->
                source.lines.filter { IMPORTS_A_STATE.containsMatchIn(it) }.map { "${source.path}: ${it.trim()}" }
            }
        withClue(
            "a drain state can be written without its type here, where the producer scan cannot see it: $unqualified",
        ) {
            unqualified shouldBe emptyList<String>()
        }

        val rules = decodeRules()
        withClue(
            "no decode rule keys on a $DRAIN_STATE_TYPE any more. Either the rule whose producers this " +
                "enumerates was retired — delete this test and say why — or it now keys on something the probe " +
                "cannot discover, and the discovery has to be widened rather than left reading green",
        ) {
            rules.shouldNotBeEmpty()
        }
        // Two controls on the discovery itself, because a probe that answers the same
        // thing for every state has discovered nothing. The first is that it
        // discriminates between states at all; the second is that what it detects is
        // the record being **absent** rather than the state being what it is —
        // reconstructing a reconstruction has to find nothing left to do.
        rules.size shouldBeLessThan DrainState.entries.size
        rules.forEach { rule ->
            rule.records.shouldNotBeEmpty()
            val once = StatusReconstruction.reconstruct(statusInDrainState(rule.state))
            withClue("the rule for ${rule.state} fires on a status it has already reconstructed") {
                StatusReconstruction.reconstruct(once.status).wasReconstructed shouldBe false
            }
        }

        rules.forEach { rule ->
            val producers = mainSources().flatMap { it.writesOf(DRAIN_STATE_TYPE, rule.state.name) }
            withClue(
                "a decode rule keys on ${rule.state} and nothing in this module writes it. Either the scan has " +
                    "stopped recognising this module's transitions, or the rule keys on a state the drain " +
                    "cannot reach",
            ) {
                producers.shouldNotBeEmpty()
            }
            rule.records.forEach { record ->
                val silent = producers.filterNot { it.answer.contains(record) || it.note.contains(record) }
                withClue(
                    "a drain reaches ${rule.state} at a site that says nothing about `$record`, which a decode " +
                        "rule reconstructs from that state alone. Either this producer writes the record, or " +
                        "the note above it says why it does not and the rule's premise is wrong: " +
                        "${silent.map { it.where }}",
                ) {
                    silent.map { it.where } shouldBe emptyList<String>()
                }
            }
        }
    }

    /**
     * The premise both `STOPPING` producers' notes rest on: the already-down branch is
     * above the state dispatch, so **every** drain state passes it.
     *
     * A constructive argument, so its premises go in a test rather than in the note
     * beside them — this file's rule, and the note added at that branch makes a claim
     * that needs it: *"in whatever state the drain was in, including one that never
     * got near step 7"*. The same fact falsified the exclusion `:schema`'s rule was
     * written with, so it is load-bearing in two places and pinned in neither until
     * now.
     *
     * Four premises, each a way the claim goes quietly false:
     *
     * - the states are dispatched in **one** function, so there is no second router a
     *   state could be worked from;
     * - that function is `private`, which is what makes the call sites in this file
     *   all of its call sites;
     * - the branch that answers the container being down **returns**, above the call
     *   that dispatches; and
     * - every other call to the dispatch is inside a function that is itself only ever
     *   reached from the dispatch, so it is downstream of the same return.
     *
     * The last premise is walked backwards from each callee at any depth — the chain
     * is `step` → `resume` → `resumeInto` → `step`, and a check that resolved a fixed
     * number of hops would have been wrong about it either way. What it cannot see is
     * a call written as something other than `name(`: a function *reference* passed as
     * a value would be missed, and no member of this chain is one.
     *
     * Scoped to this file on purpose. `Reconciler` matches on a drain state too and
     * `DrainSubject.sealsBackend` is a `when` over the whole enum, but neither runs a
     * drain step: they badge a phase and answer a pure question about a state. What is
     * claimed here is about the function that *does the work*.
     */
    @Test
    fun `every drain state's work is dispatched below the branch that answers the container being down`() {
        val dispatchingArms = CONTROLLER.arms().filter { it.pattern.contains("$DRAIN_STATE_TYPE.") }
        withClue("no arm in this file matches on a $DRAIN_STATE_TYPE, so the dispatch was not found at all") {
            dispatchingArms.size shouldBeGreaterThan 2
        }
        val owners = dispatchingArms.map { enclosing(it.line).name }.distinct()
        withClue("drain states are dispatched from more than one function, so no single ordering covers them") {
            owners shouldHaveSize 1
        }
        val dispatch = enclosing(dispatchingArms.first().line)
        withClue("the dispatch is not private, so the calls in this file are not all of its calls") {
            dispatch.declaration shouldContain "private"
        }

        // The guard, re-derived here rather than shared with the sandbox-abort test: a
        // premise asserted through another test's local is a premise held by that
        // test's continued existence.
        val asked =
            LINES.indices
                .filter { mentions(LINES[it], "containerIsDown") }
                .filterNot { DECLARATION.containsMatchIn(LINES[it]) }
        withClue("the container-down rule is asked ${asked.size} times in this file, not once") {
            asked shouldHaveSize 1
        }
        val rule = asked.single()
        val down = binding(LINES[rule])
        val guardOwner = enclosing(rule)
        val opens = "if (${down.name} != null)"
        val guard = guardOwner.body.first { it > rule && codeOf(LINES[it]).trim().startsWith(opens) }
        val guarded = CONTROLLER.bodyOf(guard)
        withClue("the container-down branch does not return, so a state below it is not past a decision") {
            codeIn(guarded).any { RETURN.containsMatchIn(it) } shouldBe true
        }

        fun callSitesOf(name: String): List<Int> =
            LINES.indices
                .filter { mentions(LINES[it], name) }
                .filterNot { DECLARATION.containsMatchIn(LINES[it]) }

        // Walked backwards from the callee, at any depth: `step` → `resume` →
        // `resumeInto` → `step` is the chain today, and a scan that resolved a fixed
        // number of hops would have said the wrong thing about it. A function is
        // downstream of the dispatch when **every** call site of it is inside the
        // dispatch or inside something already known to be downstream. Backwards
        // rather than forwards on purpose: reachability computed from the dispatch
        // outwards would have to over-approximate, and an over-approximation here
        // *admits* a route rather than refusing one. A cycle answers false, which is
        // the same safe direction.
        fun onlyReachedFromTheDispatch(
            name: String,
            seen: Set<String>,
        ): Boolean {
            val sites = callSitesOf(name)
            return sites.isNotEmpty() &&
                sites.all { site ->
                    val owner = enclosing(site).name
                    site in dispatch.body || (owner !in seen && onlyReachedFromTheDispatch(owner, seen + name))
                }
        }

        val calls = callSitesOf(dispatch.name)
        calls.shouldNotBeEmpty()
        calls.forEach { call ->
            val within = enclosing(call)
            val belowTheGuard = within.name == guardOwner.name && call > guarded.last
            withClue(
                "`${dispatch.name}` is called at ${CONTROLLER.path}:${call + 1}, in `${within.name}`, by a route " +
                    "that has not been through the container-down branch",
            ) {
                (belowTheGuard || onlyReachedFromTheDispatch(within.name, setOf(dispatch.name))) shouldBe true
            }
        }
    }

    /**
     * One `when` arm, split into the parts a claim about it is made of.
     *
     * [answer] is the arm's own line only — see the scan's docstring for why the
     * block is deliberately outside it — and [note] is the contiguous `//` block
     * directly above, which is where this project writes the reason for a branch.
     *
     * [pattern] is the **whole** pattern, folded back together when the formatter
     * has wrapped it over several lines. The thirty-sixth audit's first hole was
     * that it was not: `armPattern` reads the `->` line alone, so widening an arm
     * by one state — which is what
     * `WorkloadState.CREATED, WorkloadState.SANDBOX_ONLY -> false` is one edit away
     * from — makes spotless push the two states that matter onto continuation
     * lines, and the arm leaves the scan's alphabet entirely. Both counts fall
     * together, so the alphabet control below balances and the whole thing stays
     * green while the state it exists for is classified by an arm nothing reads.
     */
    private data class Arm(
        val path: String,
        /** The arm's **first** line, which is its pattern's, not necessarily its `->`. */
        val line: Int,
        /** The code left of `->`, including any wrapped continuation lines: what the arm matches. */
        val pattern: String,
        /** The code right of `->` on the arm's own line: what it answers with. */
        val answer: String,
        val note: String,
    ) {
        val where: String get() = "$path:${line + 1}"

        fun names(state: String): Boolean = pattern.contains("$WORKLOAD_STATE_TYPE.$state")

        /** This arm as a classification of a workload state, whatever it is written as. */
        fun asDecision(): Decision = Decision(where = where, answer = answer, note = note)
    }

    /**
     * One place a workload state is classified, whatever syntax it is written in.
     *
     * A `when` arm is the shape this module writes today; `if (state ==
     * WorkloadState.SANDBOX_ONLY)` is the shape that was outside the scan while its
     * docstring claimed "every classification", and `:core/main` already held three
     * of them — one of which is a drain decision. The rule applied to both is the
     * same one: the decision's own code names the fact, or the note above it does.
     */
    private data class Decision(
        val where: String,
        /**
         * The code the decision is written in: an arm's own line right of `->`, or
         * the whole statement a comparison is part of.
         *
         * A statement rather than a line, unlike an arm, and the difference is
         * deliberate. An arm's block is a different question from the arm's own —
         * both `converge` arms forward `pass.hadContainer` inside theirs — where a
         * comparison folded across lines by the formatter is one expression, and the
         * fact appearing anywhere in it is part of the same decision.
         */
        val answer: String,
        val note: String,
    )

    /**
     * One decode rule, as discovered by asking it rather than by listing it.
     *
     * [state] is the drain state the rule keys on; [records] are the fields it
     * restores, named in the rule's own vocabulary — the leaf of
     * [mcorch.schema.ReconstructedRecord.field], which is the identifier a producer
     * would write. A rule that restored two fields would carry two, and each is
     * demanded of every producer independently.
     */
    private data class DecodeRule(
        val state: DrainState,
        val records: List<String>,
    )

    private data class Binding(
        val name: String,
        val value: String,
    )

    private data class Enclosing(
        val name: String,
        /** The declaration line, so a caller can ask what kind of function it is. */
        val declaration: String,
        val body: IntRange,
    ) {
        /**
         * Whether this function is a [Node] implementation carrying out [verb],
         * rather than somewhere deciding to call it.
         *
         * The name alone is not enough — a same-named private wrapper is the
         * evasion this exists to refuse — so it has to be an `override`, which only
         * a type implementing the interface can write.
         *
         * ## What an `override` is trusted with, and what nobody checks
         *
         * A *decorator* is an override and so contributes no entry:
         * `override suspend fun stopWorkload(handle, grace) =
         * delegate.stopWorkload(handle, ZERO)` passes this classifier while having
         * changed the one argument that keeps a world on disk.
         * [Node.stopWorkload]'s "strictly positive" is a KDoc promise enforced in
         * `LocalNode` alone, so the single thing this scan cannot see is the seam
         * CLAUDE.md exists to protect. It is not a live defect: there is one
         * implementation, it is not a decorator, and a decorator is not something a
         * single-host build has a reason to write. When a second [Node] lands — a
         * remote one, or anything wrapping another — the check to add is that every
         * implementation passes its own `gracePeriod` through unmodified, which is a
         * different claim from this one and needs its own assertion.
         */
        fun performs(verb: String): Boolean = name == verb && declaration.contains("override ")
    }

    /** One `.kt` file, scanned as lines. */
    private class Source(
        val path: String,
        val lines: List<String>,
    ) {
        fun codeIn(range: IntRange): List<String> = lines.slice(range).filter(::isCode)

        /**
         * The lines of the function declared at [declaration].
         *
         * The half of [rangeOf] that does not insist on a unique name: two inner
         * classes may each declare the same member, and a scan that has already
         * found both needs the body of each.
         */
        fun bodyOf(declaration: Int): IntRange = bodyAt(declaration)

        /**
         * The lines of a member function, from its declaration to the brace that
         * closes it at the same indentation.
         */
        fun rangeOf(name: String): IntRange {
            val declaration = Regex("""${DECLARATION.pattern}$name\(""")
            val hits = lines.indices.filter { declaration.containsMatchIn(lines[it]) }
            check(hits.size == 1) { "expected exactly one declaration of `$name` in $path, found ${hits.size}" }
            return bodyAt(hits.single())
        }

        /** The innermost member function containing [line]. */
        fun enclosing(line: Int): Enclosing {
            val declaration =
                (line downTo 0).firstOrNull { DECLARATION.containsMatchIn(lines[it]) }
                    ?: error("no enclosing function for $path line ${line + 1}")
            val body = bodyAt(declaration)
            check(line in body) { "$path line ${line + 1} is not inside the function declared at ${declaration + 1}" }
            val name =
                requireNotNull(Regex("""fun\s+(\w+)\(""").find(lines[declaration])) {
                    "could not read a name from: ${lines[declaration].trim()}"
                }.groupValues[1]
            return Enclosing(name = name, declaration = codeOf(lines[declaration]), body = body)
        }

        /**
         * Every call to [verb] in this file that is a *decision* to end a container.
         *
         * Two kinds of line are not: the declaration or override of [verb] itself,
         * and a call inside one — a node handing the work to whatever it is a node
         * for.
         */
        fun decisionsAbout(verb: String): List<Enclosing> =
            lines.indices
                .filter { mentions(lines[it], verb) }
                .filterNot { declares(verb, it) }
                .map { enclosing(it) }
                .filterNot { it.performs(verb) }

        /**
         * Every `when` arm in this file, wrapped patterns folded back together.
         *
         * The unit is the **arm**, not the `when` and not the enclosing function.
         * The thirty-fourth audit's critical was one arm of one `when` deciding a
         * state without a fact its neighbours had, and the enclosing function is the
         * wrong unit twice over: `converge`'s classification sits inside a *local*
         * `fun status(`, so an enclosing-function scan reports the wrong name, and a
         * function that takes the fact for some other reason would score every arm
         * in it as safe.
         */
        fun arms(): List<Arm> =
            lines.indices.mapNotNull { line ->
                if (!isCode(lines[line])) return@mapNotNull null
                val code = codeOf(lines[line])
                val tail = armPattern(code) ?: return@mapNotNull null
                val start = armStart(line)
                val wrapped = (start until line).joinToString("") { codeOf(lines[it]).trim() + " " }
                Arm(
                    path = path,
                    line = start,
                    pattern = (wrapped + tail).trim(),
                    answer = code.substringAfter("->").trim(),
                    // Above the arm, not above its `->`: a wrapped pattern puts
                    // several lines between the note and the answer, and the note
                    // belongs to the whole arm.
                    note = noteAbove(start),
                )
            }

        /** Every arm in this file whose pattern names a [WorkloadState]. */
        fun stateArms(): List<Arm> = arms().filter { it.pattern.contains("$WORKLOAD_STATE_TYPE.") }

        /**
         * Every `==` or `!=` in this file that classifies [UNCLASSIFIED].
         *
         * The other half of "every classification". A comparison against one of the
         * *other* four states is deliberately outside this: what the rule is about is
         * telling `SANDBOX_ONLY`'s two worlds apart, and only an expression that
         * singles that state out makes the distinction at all. `state ==
         * WorkloadState.UNKNOWN` decides something else and answers `false` for both
         * worlds alike.
         *
         * The limitation that leaves, stated rather than papered over: `state !=
         * WorkloadState.RUNNING` lumps the two worlds in with `EXITED` **without
         * naming them**, and nothing here sees it. The `when` form of the same
         * mistake is caught, because an arm has to enumerate what it matches and the
         * `else` that would avoid that is refused; the comparison form has no such
         * obligation. A rule that answers "not running" for a sandbox is the shape to
         * read this scan's silence about.
         */
        fun stateComparisons(): List<Decision> =
            lines.indices
                .filter { isCode(lines[it]) && COMPARES_UNCLASSIFIED.containsMatchIn(codeOf(lines[it])) }
                .map { line ->
                    val start = statementStart(line)
                    Decision(
                        where = "$path:${line + 1}",
                        answer = (start..line).joinToString(" ") { codeOf(lines[it]).trim() },
                        note = noteAbove(start),
                    )
                }

        /**
         * Every place in this file that **writes** [entry] of the enum [type] into a
         * value, rather than reading one.
         *
         * ## Why the unit is an occurrence and not a call site
         *
         * The producers of a drain state are not all call sites of one function.
         * `moveTo(DrainState.STOPPING, now)` is one shape; `state =
         * DrainState.DRAIN_REQUESTED` in a constructor is another; and the shape that
         * defeats a call-site scan outright is `moveTo(resume, now)`, where the state
         * is a local bound by a `when` whose arms answer with literals. A scan keyed on
         * the call would have to resolve that variable. A scan keyed on the **literal**
         * does not: the arms that bind it are themselves occurrences in a writing
         * position, so the indirect producer is caught where its state is chosen.
         *
         * ## What counts as reading
         *
         * Two positions, and they are exempt because neither puts the state anywhere:
         *
         * - a `when` **pattern** — the text left of the arm's `->`, plus any lines the
         *   formatter has wrapped the pattern onto, which is where
         *   [DrainSubject.sealsBackend] writes six states on six lines; and
         * - an operand of `==` or `!=`, which is how `Reconciler` badges a phase and
         *   how this file asks whether a drain has parked.
         *
         * Everything else is treated as a write, and the direction of that error is
         * chosen: a set expression such as `DrainState.entries.toSet() -
         * DrainState.DRAIN_FAILED` in `ReconcileLoop` reads as a write and would be
         * asked for a note if `DRAIN_FAILED` were ever keyed on. A note there costs a
         * line and says something true; the other direction loses a producer.
         *
         * Counting occurrences rather than matching positions is what lets one line
         * hold both — `state == DrainState.X -> …` is a read, and a line that compares
         * one state and assigns another is still a write.
         */
        fun writesOf(
            type: String,
            entry: String,
        ): List<Decision> {
            val names = Regex("""(?<![\w.])$type\.$entry\b""")
            val compares = Regex("""[=!]=\s*$type\.$entry\b|(?<![\w.])$type\.$entry\s*[=!]=""")
            val continuations = wrappedPatternLines()
            return lines.indices
                .filter { isCode(lines[it]) && it !in continuations }
                .filter { line ->
                    val code = codeOf(lines[line])
                    // An arm's answer, or the whole line when it is not an arm. The
                    // pattern half of an arm line is a read by construction.
                    val written = if (armPattern(code) != null) code.substringAfter("->") else code
                    names.findAll(written).count() > compares.findAll(written).count()
                }.map { line ->
                    val start = statementStart(line)
                    Decision(
                        where = "$path:${line + 1}",
                        // Folded **upward** only, to the start of the expression. A
                        // record set on a line *below* the transition — a sibling
                        // argument of the same constructor call — is deliberately
                        // outside it: folding a whole call in would let any argument of
                        // it satisfy the rule, which is the same mistake as scanning an
                        // arm's block instead of the arm.
                        answer = (start..line).joinToString(" ") { codeOf(lines[it]).trim() },
                        note = noteAbove(start),
                    )
                }
        }

        /**
         * The lines the formatter has wrapped a `when` pattern onto, arm lines
         * themselves excluded.
         *
         * The same fold [arms] uses, so the two scans cannot disagree about what a
         * pattern is: the thirty-sixth audit's first hole was a pattern wrapped out of
         * one scan's alphabet, and a second scan with its own idea of wrapping is that
         * hole waiting to be re-cut.
         */
        private fun wrappedPatternLines(): Set<Int> =
            lines.indices
                .filter { isCode(lines[it]) && armPattern(codeOf(lines[it])) != null }
                .flatMap { arrow -> armStart(arrow) until arrow }
                .toSet()

        /**
         * The entries of the enum [type] declared in this file, in declaration order.
         *
         * Read from the declaration rather than listed here, so that a sixth
         * [WorkloadState] joins the alphabet on the day it is written rather than on
         * the day somebody remembers this file.
         */
        fun enumEntries(type: String): List<String> {
            val declaration = lines.indexOfFirst { isCode(it) && codeOf(it).contains("enum class $type") }
            check(declaration >= 0) { "no `enum class $type` in $path" }
            return bodyAt(declaration)
                .filter { isCode(lines[it]) }
                .mapNotNull { ENUM_ENTRY.find(codeOf(lines[it]))?.groupValues?.get(1) }
        }

        /**
         * The lines of the `when` whose arm is at [line], brace to brace.
         *
         * By indentation for the opening and bracket balance for the close, which is
         * what makes a nested `when` resolve to the inner one: both `converge`s
         * classify inside an outer `when` over the observation, and an arm scan that
         * walked out to the outer block would judge an `else` that belongs to a
         * different question.
         */
        fun whenBlockAround(line: Int): IntRange {
            val indent = indentOf(lines[line])
            val opening =
                (line downTo 0).firstOrNull {
                    isCode(lines[it]) && WHEN.containsMatchIn(codeOf(lines[it])) && indentOf(lines[it]) < indent
                } ?: error("no enclosing `when` for $path line ${line + 1}")
            var depth = 0
            for (at in opening..lines.lastIndex) {
                val code = codeOf(lines[at])
                depth += code.count { it == '{' } - code.count { it == '}' }
                if (at > opening && depth <= 0) return opening..at
            }
            error("no closing brace for the `when` at $path line ${opening + 1}")
        }

        /**
         * The contiguous `//` block directly above [line], joined.
         *
         * Blank lines are skipped only *before* the block starts, so a note that
         * belongs to the branch above cannot be read as this one's: once a comment
         * line has been found, anything that is not one ends the block. A `//` on its
         * own — how a paragraph break is written here — is a comment line.
         */
        private fun noteAbove(line: Int): String {
            val note = mutableListOf<String>()
            for (at in line - 1 downTo 0) {
                val trimmed = lines[at].trim()
                if (trimmed.isEmpty() && note.isEmpty()) continue
                if (!trimmed.startsWith("//")) break
                note += trimmed
            }
            return note.joinToString(" ")
        }

        /**
         * The first line of the arm whose `->` is at [arrow].
         *
         * A pattern too long for one line is wrapped by the formatter one entry per
         * line, at the arm's own indentation, each line but the last ending in a
         * comma — so that is what is walked back over, and nothing else.
         *
         * "Entries", not "a line ending in a comma", and the difference is a
         * wrapped **parameter list**: every `write(` in this module has
         * `outcome: () -> ReconcileOutcome,` as its last parameter, which is a `->`
         * line with ordinary parameters above it at the same indentation. Folding
         * those together would make a pattern out of a signature, and the day one of
         * them takes a `WorkloadState` it would be a classification this scan
         * invented. [WRAPPED_PATTERN] is what tells the two apart, and the indent
         * check is what keeps the walk inside the arm.
         */
        private fun armStart(arrow: Int): Int {
            var start = arrow
            while (start > 0) {
                val above = start - 1
                if (!isCode(lines[above])) break
                if (!WRAPPED_PATTERN.matches(codeOf(lines[above]).trim())) break
                if (indentOf(lines[above]) != indentOf(lines[arrow])) break
                start = above
            }
            return start
        }

        /**
         * The first line of the statement [line] is part of.
         *
         * A comparison the formatter has wrapped — `val settled =` on one line, the
         * `&&` on the next, the state on the third — has its note above the first of
         * them, and reading only the line the state is on would find no note however
         * carefully one was written. Continuation lines are indented further than the
         * line they continue; a line ending in `{` is a block being opened rather
         * than a statement being continued, which is what stops this at the enclosing
         * `if` instead of walking out to the function declaration.
         */
        private fun statementStart(line: Int): Int {
            var start = line
            while (start > 0) {
                val above = start - 1
                if (!isCode(lines[above])) break
                if (indentOf(lines[above]) >= indentOf(lines[start])) break
                if (codeOf(lines[above]).trimEnd().endsWith("{")) break
                start = above
            }
            return start
        }

        private fun declares(
            verb: String,
            line: Int,
        ): Boolean = Regex("""${DECLARATION.pattern}$verb\(""").containsMatchIn(codeOf(lines[line]))

        private fun bodyAt(declaration: Int): IntRange {
            val closing = " ".repeat(lines[declaration].takeWhile { it == ' ' }.length) + "}"
            val end =
                (declaration + 1..lines.lastIndex).firstOrNull { lines[it] == closing }
                    ?: error("no closing brace for the function declared at $path line ${declaration + 1}")
            return declaration..end
        }

        companion object {
            fun of(file: File): Source = Source(file.invariantSeparatorsPath, file.readLines())

            fun of(path: String): Source {
                val file = Path.of(path)
                check(Files.isRegularFile(file)) {
                    "expected to run with the module directory as the working directory; no $path"
                }
                return Source(path, file.readText().lines())
            }
        }
    }

    private companion object {
        /** `val <name> = <value>`, so an assertion can pin both halves. */
        val BOUND = Regex("""^val\s+(\w+)\s*=\s*(\S.*)$""")

        /** The name a `<receiver>.<call>(…)` expression is applied to. */
        val RECEIVER = Regex("""^(\w+)\.""")

        /** `<name>: <Type>,` — one parameter, on its own line, as this project formats them. */
        val PARAMETER = Regex("""^\s*(\w+):\s*\S""")

        /**
         * Any `return`, including `x ?: return y`, `if (c) return y` and a labelled
         * `return@advance`. A labelled return out of a lambda would be flagged too
         * and is not an exit — that is the safe direction, and there are none.
         */
        val RETURN = Regex("""\breturn\b""")

        /**
         * A function's declaration, whatever its modifiers and in whatever order
         * they were written. `override` is one of them: a call inside an override of
         * the verb being scanned is a node performing a stop, not a decision to
         * issue one.
         */
        val DECLARATION =
            Regex(
                """^\s*(?:(?:private|internal|public|protected|override|abstract|open|final|suspend|inline|""" +
                    """operator|infix|tailrec|external)\s+)*fun\s+""",
            )

        /** A string literal, so a keyword scan cannot be fooled by prose. */
        val STRING = Regex(""""([^"\\]|\\.)*"""")

        /** `drainCause(` or `proxyDrainCause(` — a call, never the `DrainCause` type. */
        val DRAIN_CAUSE = Regex("""\w*[Dd]rainCause\(""")

        /**
         * A drain record deleted outright — the thirty-third audit's critical, as a
         * token.
         *
         * `\s*=\s*` rather than `" = "` so that a reformat cannot slip one past, and
         * anchored on the argument name so that `failure = null` beside it is not one.
         */
        val CLEARS_DRAIN = Regex("""\bdrain\s*=\s*null\b""")

        /**
         * `clearedDrainRecord(<something>.drain, <name>, <something>.hadContainer)`.
         *
         * The first argument has to be a record read off a status and the second an
         * observation this pass already has, because both are ways of writing
         * `drain = null` in a form that satisfies a scan for the call alone.
         *
         * **The third is pinned for the same reason and it is the newest one.** The
         * thirty-fourth audit's critical was not a flip of the rule's expression but
         * a *missing argument*: the rule classified `SANDBOX_ONLY` without the fact
         * that tells a sandbox this loop has emptied from one whose container the
         * runtime has stopped enumerating. `clearedDrainRecord(x.drain, observation,
         * false)` restores that defect at a single call site while satisfying every
         * other assertion here, so the argument has to be the pass's own property
         * rather than any expression that fits.
         */
        val ASKS_THE_RULE = Regex("""^clearedDrainRecord\([\w.?]*\bdrain,\s*\w+,\s*[\w.?]*\bhadContainer\)$""")

        /**
         * `?: outstandingStopCause(<something>.drain, <name>, <something>.hadContainer)`.
         *
         * The routing half of [ASKS_THE_RULE], pinned to the same shape for the same
         * reason: this is the call that decides whether a pass whose cause has been
         * withdrawn converges over the top of a stop it has already dispatched.
         */
        val ROUTES_ON_THE_RULE =
            Regex("""outstandingStopCause\([\w.?]*\bdrain,\s*\w+,\s*[\w.?]*\bhadContainer\)$""")

        /**
         * The one expression that answers *"will a permanent failure recorded here
         * stop the passes"*, named once because it is the subject of the assertions
         * that follow it rather than something they forward.
         *
         * Renaming it is a source change these tests will fail on, deliberately: the
         * prose above them names it too, and a claim whose subject has moved has to
         * be re-read rather than re-pointed.
         */
        const val GATE_CLAUSE: String = "permanentFailureStopsPasses"

        /** The two calls in `Reconciler` that hand a pass to `DrainController`. */
        val ENTERS_DRAIN = Regex("""\bdrain(Proxy)?\(""")

        /**
         * The type whose arms this suite classifies, named once so an assertion and
         * the prose around it cannot drift apart.
         */
        const val WORKLOAD_STATE_TYPE: String = "WorkloadState"

        /**
         * The one state no observation can decide on its own, and the fact that
         * decides it.
         *
         * Spelled as the bare names rather than as `WorkloadState.SANDBOX_ONLY` and
         * `pass.hadContainer`, because the scan looks for them in two places that
         * qualify them differently: an arm's pattern always writes the type, a note
         * writing prose about it may not, and the fact reaches a call site as
         * `hadContainer`, `pass.hadContainer` or a parameter of that name.
         */
        const val UNCLASSIFIED: String = "SANDBOX_ONLY"

        const val THE_FACT: String = "hadContainer"

        /**
         * The enum whose producers the decode-rule scan enumerates, named once so the
         * scan and the prose about it cannot drift apart.
         */
        const val DRAIN_STATE_TYPE: String = "DrainState"

        /**
         * An import that would let a drain state be written without its type: an
         * entry imported directly, or the type imported under another name.
         *
         * Both are ways a producer leaves [writesOf]'s alphabet without looking any
         * different, which is the class of hole the thirty-sixth audit found in the
         * classification scan. Refusing them is cheaper than reading them, and here it
         * is also the only sound option: [ServerPhase] declares `STOPPING` as well, so
         * a bare entry cannot be attributed to a type by a source scan at all.
         */
        val IMPORTS_A_STATE = Regex("""^\s*import\s+[\w.]*\b$DRAIN_STATE_TYPE(?:\.[A-Z]|\s+as\s)""")

        /** Any instant. Nothing in a decode rule reads one; they only get copied. */
        val PROBE_AT: Instant = Instant.parse("2026-08-07T00:00:00Z")

        /**
         * An observation whose drain is in [state] and which carries **no** side-effect
         * record at all.
         *
         * Every optional field left at its default on purpose: what is being discovered
         * is which states a decode rule fills a gap for, and a probe that arrived with
         * the record already present would discover nothing. That the absence is what
         * the rule keys on — rather than the state — is asserted separately, by feeding
         * a reconstruction back through it.
         */
        fun statusInDrainState(state: DrainState): ServerStatus =
            PaperServerStatus(
                name = resourceName("probe"),
                observedGeneration = 1,
                phase = ServerPhase.DRAINING,
                observedAt = PROBE_AT,
                lastTransitionAt = PROBE_AT,
                drain = DrainStatus(state = state, startedAt = PROBE_AT, enteredStateAt = PROBE_AT),
            )

        /**
         * The decode rules `:schema` applies on the read, discovered by running each
         * drain state through them.
         *
         * The alphabet is [DrainState.entries] — the declaration itself, so a ninth
         * state is probed on the day it is written — and the records are read off the
         * report the rule returns. Nothing about either is written down here, which is
         * the whole point: a list of keyed states in this file would be the maintained
         * list this suite exists to replace.
         */
        fun decodeRules(): List<DecodeRule> =
            DrainState.entries.mapNotNull { state ->
                val reconstructed = StatusReconstruction.reconstruct(statusInDrainState(state))
                if (!reconstructed.wasReconstructed) {
                    null
                } else {
                    DecodeRule(state, reconstructed.reconstructed.map { it.field.substringAfterLast('.') })
                }
            }

        /**
         * `x == WorkloadState.SANDBOX_ONLY`, in either order, qualified or not.
         *
         * The `when` arm is not the only way to classify a state, and the docstring
         * above claimed "every classification" while the scan read arms alone. Three
         * comparisons were already in `:core/main` when that was written, one of them
         * a drain decision.
         *
         * The optional qualifier is what makes this see the bare form as well —
         * `state == SANDBOX_ONLY` compiles — and no false positive comes with it: the
         * alternation demands the entry name *immediately* after the operator, so
         * `x == Other.SANDBOX_ONLY` matches neither branch. No other enum in this
         * build declares the name in any case.
         */
        val COMPARES_UNCLASSIFIED =
            Regex(
                """[=!]=\s*(?:$WORKLOAD_STATE_TYPE\.)?$UNCLASSIFIED\b""" +
                    """|(?<![\w.])(?:$WORKLOAD_STATE_TYPE\.)?$UNCLASSIFIED\s*[=!]=""",
            )

        /** One entry of an enum declaration, on its own line as this project writes them. */
        val ENUM_ENTRY = Regex("""^\s+([A-Z][A-Z0-9_]*)\s*,?\s*$""")

        /**
         * A continuation line of a wrapped `when` pattern: entries and commas, ending
         * in the comma that continues it.
         *
         * Deliberately narrower than "ends in a comma", so that a wrapped parameter
         * list above a lambda-typed parameter is not folded into a pattern. It is
         * also narrower than every legal pattern — a wrapped `is Foo,` does not match
         * — which is the safe direction here: the scan's subject is enum states, and
         * the arms this module writes are entries.
         */
        val WRAPPED_PATTERN = Regex("""^[\w.]+(?:\s*,\s*[\w.]+)*\s*,$""")

        /**
         * The [WorkloadState] entries [pattern] names **without** the type in front.
         *
         * `(?<![\w.])` is the whole of it: it is what tells a bare `SANDBOX_ONLY`
         * from `WorkloadState.SANDBOX_ONLY`, and also from another type's entry of
         * the same name — `ServerPhase.RUNNING` is not a workload state and is not
         * reported here.
         */
        fun bareStates(
            pattern: String,
            states: List<String>,
        ): List<String> = states.filter { Regex("""(?<![\w.])$it\b""").containsMatchIn(pattern) }

        /** A `when` in either form — `when (subject)` and the subjectless `when {`. */
        val WHEN = Regex("""\bwhen\s*[({]""")

        /** The leading spaces of a line, as this project's formatter writes them. */
        fun indentOf(line: String): Int = line.takeWhile { it == ' ' }.length

        /**
         * What a `when` arm matches on, or null when [code] is not an arm.
         *
         * The first `->` and nothing after it: an arm's *answer* may contain arrows
         * of its own (a lambda, a second `when`), and reading past the first one is
         * how `else` gets found inside a branch that is not one.
         */
        fun armPattern(code: String): String? = if (code.contains("->")) code.substringBefore("->").trim() else null

        const val LOCAL_NODE_PATH: String = "src/main/kotlin/mcorch/core/node/LocalNode.kt"

        /** Where [WorkloadState] is declared, and so where its entries are read from. */
        const val NODE_PATH: String = "src/main/kotlin/mcorch/core/Node.kt"

        /** The interface and the implementations of it — the files that perform, never decide. */
        val NODE_FILES =
            listOf(
                NODE_PATH,
                LOCAL_NODE_PATH,
            )

        const val CONTROLLER_PATH: String = "src/main/kotlin/mcorch/core/DrainController.kt"

        const val RECONCILER_PATH: String = "src/main/kotlin/mcorch/core/Reconciler.kt"

        val CONTROLLER: Source = Source.of(CONTROLLER_PATH)

        val LINES: List<String> get() = CONTROLLER.lines

        /**
         * Every `.kt` file in this module's main sources, in the source tree's own
         * order rather than the filesystem's.
         *
         * Shared by the two scans whose claims are about the *module* rather than
         * about one file. Test sources are deliberately outside it: a fake node, a
         * harness scrub and a guard test legitimately do things a reconcile path may
         * not, and folding them in would make either scan a maintained exception list.
         */
        fun mainSources(): List<Source> =
            Path
                .of("src/main/kotlin")
                .toFile()
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .map(Source::of)
                .sortedBy { it.path }
                .toList()

        fun rangeOf(name: String): IntRange = CONTROLLER.rangeOf(name)

        fun enclosing(line: Int): Enclosing = CONTROLLER.enclosing(line)

        fun codeIn(range: IntRange): List<String> = CONTROLLER.codeIn(range)

        fun isCode(line: String): Boolean {
            val trimmed = line.trimStart()
            return trimmed.isNotEmpty() &&
                !trimmed.startsWith("//") &&
                !trimmed.startsWith("*") &&
                !trimmed.startsWith("/*")
        }

        /**
         * A line with its string literals and its trailing comment removed.
         *
         * [isCode] only drops lines that *begin* with a comment marker, which is
         * enough for a scan for an identifier and not for a scan for a keyword: a
         * message that mentions returning, or a `// returns early` beside a call,
         * would both read as an exit.
         */
        fun codeOf(line: String): String = line.replace(STRING, "\"\"").substringBefore("//")

        /** Whether [line] is code naming [call], rather than prose about it. */
        fun mentions(
            line: String,
            call: String,
        ): Boolean = isCode(line) && codeOf(line).contains("$call(")

        fun binding(line: String): Binding {
            val match =
                requireNotNull(BOUND.find(codeOf(line).trim())) {
                    "expected a `val <name> = <value>` binding, found: ${line.trim()}"
                }
            return Binding(name = match.groupValues[1], value = match.groupValues[2].trim())
        }

        /**
         * The parameter names of the function whose body is [range], in order.
         *
         * Read off the declaration for the same reason [callee] reads a receiver off
         * an expression: an assertion that *follows* a name survives the name
         * changing and still refuses a substitution, where one that restates the
         * literal `drain` only refuses the substitution.
         */
        fun parametersOf(range: IntRange): List<String> {
            val declaration = LINES[range.first]
            // One parameter per line is this project's formatting, and the scan
            // depends on it. A single-line declaration would make the `takeWhile`
            // below walk into the body, so it is refused rather than misread.
            check(codeOf(declaration).trimEnd().endsWith("(")) {
                "expected one parameter per line, found: ${declaration.trim()}"
            }
            return LINES
                .slice(range)
                .drop(1)
                .takeWhile { !it.trimStart().startsWith(")") }
                .map { line ->
                    requireNotNull(PARAMETER.find(codeOf(line))) {
                        "expected a `<name>: <Type>,` parameter, found: ${line.trim()}"
                    }.groupValues[1]
                }
        }

        /**
         * The lines of the call opened at [call], to the bracket that closes it.
         *
         * By bracket balance rather than by "up to the next line beginning with
         * `)`". An argument that is itself a constructor call — which is how both
         * drain entries are written — closes at that indentation too, so the
         * simpler scan reads a prefix of the argument list and treats it as the
         * whole: an assertion that a forwarded argument is *absent* would then be
         * satisfied by a call whose arguments it never reached.
         */
        fun argumentsOf(
            lines: List<String>,
            call: Int,
        ): List<String> {
            var depth = 0
            val arguments = mutableListOf<String>()
            for (line in call..lines.lastIndex) {
                val code = codeOf(lines[line])
                if (line > call && isCode(lines[line])) arguments += code.trim()
                depth += code.count { it == '(' } - code.count { it == ')' }
                if (depth <= 0) break
            }
            return arguments
        }

        /** The name an expression is applied to, so an assertion can follow it rather than restate it. */
        fun callee(expression: String): String =
            requireNotNull(RECEIVER.find(expression)) {
                "expected a `<receiver>.<call>(…)` expression, found: $expression"
            }.groupValues[1]
    }
}
