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
 * Precedent: `mcorch.velocity.control.TransferNeverKicksTest` scans its own
 * module's sources for the same reason — a guarantee that has to hold against code
 * nobody has written yet is a claim about the code's shape.
 *
 * `scripts/dev/drain-wiring-mutations.sh` is the red-proof, and it is a durable
 * one: it applies that audit's four mutations and six more to a working copy in
 * turn, requires each to redden the named test class, and restores the source. A
 * structural test cannot be sabotaged behaviourally, so its red-proof has to
 * sabotage the wiring, and a set is a better proof than one sabotage because these
 * assertions fail independently of each other — each mutation reddens exactly one
 * of them, which is also the evidence that none of them is carrying another.
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
     */
    @Test
    fun `a pass is stepped with the drain the pass-entry reading voided`() {
        val once = codeIn(rangeOf("advanceOnce"))

        // Controls: this really is the function that reads the probe and runs the
        // state machine over the result.
        once.count { it.contains("readPlayers(") } shouldBe 1
        once.count { it.contains("step(pass,") } shouldBe 1

        val observed = binding(once.single { it.contains("adoptSaveClause(") })
        observed.value shouldMatch Regex("""\w+\.adoptSaveClause\(\w+\)""")

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
     * The claim used to be that there was one call. It has been false since
     * `awaitStopped` learned to re-issue a stop that did not take — that call is
     * behind an inline [readPlayers] and lets an *unanswered* probe through on
     * purpose, which [requireEmpty] would abort on.
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
     * …and no other file in this module calls it at all.
     *
     * The count above is scoped to one file; the claim it replaced was scoped to the
     * codebase. A stop added to `Reconciler`'s teardown, to a node-drain helper, or
     * to a rescheduling path once there is more than one node would leave every
     * assertion above green — and those are precisely the paths a drain audit goes
     * looking for, outside the scanned file by construction.
     *
     * The files are **classified, not listed**. A file that names
     * [Node.stopWorkload] either declares or overrides it — the interface and the
     * node implementations, which are where the stop is performed rather than
     * decided — or it is a caller, and there is one of those. A distributed [Node]
     * arriving later adds an override and passes; a second decision point anywhere
     * fails. Listing the files by name would have made this test something a new
     * node implementation has to be edited past, which is how a maintained list
     * becomes a maintained lie.
     *
     * Scoped to `:core`'s main sources, which is what a test in this module can walk
     * honestly. `:app`'s stub node and the containerd harness implement or call the
     * same method in test code, deliberately.
     */
    @Test
    fun `the container stop is called from one file in this module`() {
        val sources =
            Path
                .of("src/main/kotlin")
                .toFile()
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .toList()

        // Vacuity guards. A walk that found nothing, or that ran somewhere without
        // the reconcile loop in it, satisfies the assertion below by accident.
        sources.size shouldBeGreaterThan 10
        sources.map { it.invariantSeparatorsPath } shouldContain "src/main/kotlin/mcorch/core/Reconciler.kt"

        fun File.mentions(pattern: String) = readLines().any { isCode(it) && codeOf(it).contains(pattern) }

        val named = sources.filter { it.mentions("stopWorkload(") }
        val (performing, calling) = named.partition { it.mentions("fun stopWorkload(") }

        // Control for the classifier: the declaration is really being separated out,
        // rather than the partition putting everything on one side.
        performing.map { it.invariantSeparatorsPath } shouldContain "src/main/kotlin/mcorch/core/Node.kt"

        calling.map { it.invariantSeparatorsPath } shouldBe listOf("src/main/kotlin/mcorch/core/DrainController.kt")
    }

    private data class Binding(
        val name: String,
        val value: String,
    )

    private data class Enclosing(
        val name: String,
        val body: IntRange,
    )

    private companion object {
        /** `val <name> = <value>`, so an assertion can pin both halves. */
        val BOUND = Regex("""^val\s+(\w+)\s*=\s*(\S.*)$""")

        /**
         * Any `return`, including `x ?: return y`, `if (c) return y` and a labelled
         * `return@advance`. A labelled return out of a lambda would be flagged too
         * and is not an exit — that is the safe direction, and there are none.
         */
        val RETURN = Regex("""\breturn\b""")

        /** A member function's declaration, whatever its modifiers. */
        val DECLARATION = Regex("""^\s*(?:private\s+|internal\s+|public\s+)?(?:suspend\s+)?(?:inline\s+)?fun\s+""")

        /** A string literal, so a keyword scan cannot be fooled by prose. */
        val STRING = Regex(""""([^"\\]|\\.)*"""")

        val LINES: List<String> = source().lines()

        fun source(): String {
            val path = Path.of("src/main/kotlin/mcorch/core/DrainController.kt")
            check(Files.isRegularFile(path)) {
                "expected to run with the module directory as the working directory; no $path"
            }
            return path.readText()
        }

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

        fun codeIn(range: IntRange): List<String> = LINES.slice(range).filter(::isCode)

        fun binding(line: String): Binding {
            val match =
                requireNotNull(BOUND.find(codeOf(line).trim())) {
                    "expected a `val <name> = <value>` binding, found: ${line.trim()}"
                }
            return Binding(name = match.groupValues[1], value = match.groupValues[2].trim())
        }

        /**
         * The lines of a member function, from its declaration to the brace that
         * closes it at the same indentation.
         */
        fun rangeOf(name: String): IntRange {
            val declaration = Regex("""${DECLARATION.pattern}$name\(""")
            val hits = LINES.indices.filter { declaration.containsMatchIn(LINES[it]) }
            check(hits.size == 1) { "expected exactly one declaration of `$name`, found ${hits.size}" }
            return bodyAt(hits.single())
        }

        /** The innermost member function containing [line]. */
        fun enclosing(line: Int): Enclosing {
            val declaration =
                (line downTo 0).firstOrNull { DECLARATION.containsMatchIn(LINES[it]) }
                    ?: error("no enclosing function for line ${line + 1}")
            val body = bodyAt(declaration)
            check(line in body) { "line ${line + 1} is not inside the function declared at ${declaration + 1}" }
            val name =
                requireNotNull(Regex("""fun\s+(\w+)\(""").find(LINES[declaration])) {
                    "could not read a name from: ${LINES[declaration].trim()}"
                }.groupValues[1]
            return Enclosing(name = name, body = body)
        }

        fun bodyAt(declaration: Int): IntRange {
            val closing = " ".repeat(LINES[declaration].takeWhile { it == ' ' }.length) + "}"
            val end =
                (declaration + 1..LINES.lastIndex).firstOrNull { LINES[it] == closing }
                    ?: error("no closing brace for the function declared at ${declaration + 1}")
            return declaration..end
        }
    }
}
