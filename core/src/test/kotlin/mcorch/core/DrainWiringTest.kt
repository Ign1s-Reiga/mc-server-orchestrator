package mcorch.core

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
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
        val sources =
            Path
                .of("src/main/kotlin")
                .toFile()
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .map(Source::of)
                // Sorted, so the list below is the source's own order and not the
                // order the filesystem happened to hand the walk.
                .sortedBy { it.path }
                .toList()

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

        /** The two calls in `Reconciler` that hand a pass to `DrainController`. */
        val ENTERS_DRAIN = Regex("""\bdrain(Proxy)?\(""")

        const val LOCAL_NODE_PATH: String = "src/main/kotlin/mcorch/core/node/LocalNode.kt"

        /** The interface and the implementations of it — the files that perform, never decide. */
        val NODE_FILES =
            listOf(
                "src/main/kotlin/mcorch/core/Node.kt",
                LOCAL_NODE_PATH,
            )

        const val CONTROLLER_PATH: String = "src/main/kotlin/mcorch/core/DrainController.kt"

        const val RECONCILER_PATH: String = "src/main/kotlin/mcorch/core/Reconciler.kt"

        val CONTROLLER: Source = Source.of(CONTROLLER_PATH)

        val LINES: List<String> get() = CONTROLLER.lines

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

        /** The name an expression is applied to, so an assertion can follow it rather than restate it. */
        fun callee(expression: String): String =
            requireNotNull(RECEIVER.find(expression)) {
                "expected a `<receiver>.<call>(…)` expression, found: $expression"
            }.groupValues[1]
    }
}
