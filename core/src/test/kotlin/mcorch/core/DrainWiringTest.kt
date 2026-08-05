package mcorch.core

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
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
 * leaves is the value they produced". A structural assertion of this kind is only
 * as good as its own red-proof, since it cannot be sabotaged behaviourally —
 * removing the call at either site reddens the tests below and nothing else in the
 * suite, which is the whole finding they exist to close.
 *
 * Precedent: `mcorch.velocity.control.TransferNeverKicksTest` scans its own
 * module's sources for the same reason — a guarantee that has to hold against code
 * nobody has written yet is a claim about the code's shape.
 *
 * Every assertion here is written against a *shape* rather than against a literal
 * line, so an honest refactor — renaming a local, rewrapping an argument list —
 * keeps it green while a deletion does not.
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
     * returning the progress it was handed instead of the one the rule produced.
     */
    @Test
    fun `nothing leaves advance that has not been through the record-level rule`() {
        val advance = codeIn(rangeOf("advance"))

        // Controls first. Both assertions below are about what a filtered list
        // does *not* contain, which is how a scan that found nothing at all
        // passes for a scan that found nothing wrong.
        advance.size shouldBeGreaterThan 4
        advance.count { it.contains("advanceOnce(") } shouldBe 1

        val voiding = advance.single { it.contains("dropSaveContradictedByPlayers()") }
        val recorded =
            requireNotNull(BOUND.find(voiding)) {
                "expected the record-level rule's result to be bound to a name, found: ${voiding.trim()}"
            }.groupValues[1]

        // One return, and it returns what the rule produced. A second return that
        // skipped the rule would be a producer the rule never sees — which is the
        // exact shape of both defects it was written for.
        advance.map { it.trim() }.filter { it.startsWith("return ") } shouldBe listOf("return $recorded")
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
     */
    @Test
    fun `a pass is stepped with the drain the pass-entry reading voided`() {
        val once = codeIn(rangeOf("advanceOnce"))

        // Controls: this really is the function that reads the probe and runs the
        // state machine over the result.
        once.count { it.contains("readPlayers(") } shouldBe 1
        once.count { it.contains("step(pass,") } shouldBe 1

        val adoption = once.single { it.contains("PlayerReading.Occupied") }
        adoption shouldContain "unconfirmWorldSave()"
        val observed =
            requireNotNull(BOUND.find(adoption)) {
                "expected the adopted reading to be bound to a name, found: ${adoption.trim()}"
            }.groupValues[1]

        once.single { it.contains("step(pass,") } shouldContain "step(pass, $observed)"
    }

    /**
     * There are exactly two calls to [Node.stopWorkload] here, and the class KDoc
     * describes two gates because of it.
     *
     * The claim used to be that there was one, behind [requireEmpty] followed by
     * `mayStop`. It has been false since `awaitStopped` learned to re-issue a stop
     * that did not take — that call is behind an inline `readPlayers` and lets an
     * *unanswered* probe through on purpose, which `requireEmpty` would abort on.
     * A KDoc that counts call sites is a defect waiting; this is the count, held by
     * something that fails when it changes.
     */
    @Test
    fun `the container stop has exactly two call sites, one behind each gate`() {
        val calls = LINES.indices.filter { isCode(LINES[it]) && LINES[it].contains("stopWorkload(") }

        calls shouldHaveSize 2
        // Step 7 itself, behind `requireEmpty` + `mayStop`.
        calls.count { it in rangeOf("stop") } shouldBe 1
        // Its re-issue, behind an inline `readPlayers` + `mayStop`.
        calls.count { it in rangeOf("awaitStopped") } shouldBe 1
    }

    private companion object {
        /** `val <name> =`, so an assertion can follow a value instead of a literal. */
        val BOUND = Regex("""\bval\s+(\w+)\s*=""")

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

        fun codeIn(range: IntRange): List<String> = LINES.slice(range).filter(::isCode)

        /**
         * The lines of a member function, from its declaration to the brace that
         * closes it at the same indentation.
         */
        fun rangeOf(name: String): IntRange {
            val declaration = Regex("""^\s*(private\s+|internal\s+)?suspend fun $name\(""")
            val hits = LINES.indices.filter { declaration.containsMatchIn(LINES[it]) }
            check(hits.size == 1) { "expected exactly one declaration of `$name`, found ${hits.size}" }
            val start = hits.single()
            val closing = " ".repeat(LINES[start].takeWhile { it == ' ' }.length) + "}"
            val end =
                (start + 1..LINES.lastIndex).firstOrNull { LINES[it] == closing }
                    ?: error("no closing brace for `$name` at the declaration's indentation")
            return start..end
        }
    }
}
