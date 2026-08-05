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
 * reachable. [DrainController.stop]'s own gate is deliberately unreachable through
 * the state machine (`DEREGISTERED` decides the same thing first), so a narrowing
 * *there* is invisible to every test in this suite, exactly like round 18's net:
 * it is a backstop, and the primary is what keeps it unreachable.
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
     * …and every *other* way of ending a container is decided in one file too.
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
     * - **`stopWorkload` ends a running container**, so its safety is the gate above
     *   and there is exactly one file allowed to decide one.
     * - **`removeWorkload` refuses a running container** ([Node.removeWorkload]'s
     *   contract, enforced in `WorkloadView.teardown` and tested there), so what is
     *   pinned is only that the decision to remove is taken in one file. A second
     *   deciding file is not a data-loss defect on its own; it is the thing a drain
     *   audit has to look at, which is what a review trigger is for.
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
     * not. Listing the files by name would have made this test something a new node
     * implementation has to be edited past, which is how a maintained list becomes a
     * maintained lie.
     *
     * Scoped to `:core`'s main sources, which is what a test in this module can walk
     * honestly. `:app`'s stub node and the containerd harness implement or call the
     * same methods in test code, deliberately.
     */
    @Test
    fun `the calls that end a container are decided in one file each`() {
        val sources =
            Path
                .of("src/main/kotlin")
                .toFile()
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .map(Source::of)
                .toList()

        // Vacuity guards. A walk that found nothing, or that ran somewhere without
        // the reconcile loop in it, satisfies the assertions below by accident.
        sources.size shouldBeGreaterThan 10
        sources.map { it.path } shouldContain "src/main/kotlin/mcorch/core/Reconciler.kt"

        fun naming(verb: String) = sources.filter { source -> source.lines.any { mentions(it, verb) } }.map { it.path }

        fun deciding(verb: String) = sources.filter { it.decisionsAbout(verb).isNotEmpty() }.map { it.path }

        // Control for the classifier: the files that *perform* each verb are really
        // being separated out, rather than everything landing on one side. The
        // interface declares both; the node implementation overrides both.
        NODE_FILES.forEach { performer ->
            naming("stopWorkload") shouldContain performer
            naming("removeWorkload") shouldContain performer
        }
        naming("stopWorkload").size shouldBeGreaterThan deciding("stopWorkload").size
        naming("removeWorkload").size shouldBeGreaterThan deciding("removeWorkload").size

        deciding("stopWorkload") shouldBe listOf("src/main/kotlin/mcorch/core/DrainController.kt")
        // Both teardowns, and nothing else. A removal decided anywhere else — a
        // rescheduling path, a node drain — is the case this test's own motivation
        // names and the case its alphabet used to miss.
        deciding("removeWorkload") shouldBe listOf("src/main/kotlin/mcorch/core/Reconciler.kt")
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

        /** The interface and the implementations of it — the files that perform, never decide. */
        val NODE_FILES =
            listOf(
                "src/main/kotlin/mcorch/core/Node.kt",
                "src/main/kotlin/mcorch/core/node/LocalNode.kt",
            )

        val CONTROLLER: Source = Source.of("src/main/kotlin/mcorch/core/DrainController.kt")

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

        /** The name an expression is applied to, so an assertion can follow it rather than restate it. */
        fun callee(expression: String): String =
            requireNotNull(RECEIVER.find(expression)) {
                "expected a `<receiver>.<call>(…)` expression, found: $expression"
            }.groupValues[1]
    }
}
