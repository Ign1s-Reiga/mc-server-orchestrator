package mcorch.api

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import mcorch.schema.ConditionType
import mcorch.schema.ControlCredential
import mcorch.schema.DrainState
import mcorch.schema.FailureReason
import mcorch.schema.ServerPhase
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `API.md` §14 transcribes `:schema`'s enums into TypeScript unions by hand, and
 * a hand-written transcription rots.
 *
 * It rotted: `BACKENDS_RESOLVED` and `CONTROL_ENDPOINT_READY` were added to
 * [ConditionType] and served live by `/meta`, while §14's union kept the older,
 * shorter list. A dashboard switching exhaustively on the documented type would
 * have compiled against a set the server had already outgrown — and the wire was
 * never wrong, so nothing failed. [MetaTest] pins the endpoint; nothing pinned
 * the document a client is actually read from.
 *
 * These assertions compare the document against the enums themselves. §14 also
 * carries a note that `/meta` wins on disagreement, which stays true and is the
 * reason the union is advisory — but advisory is not licence to be wrong.
 */
class ApiDocEnumTest {
    /**
     * Gradle runs tests with the module directory as the working directory, so
     * `API.md` sits right here.
     *
     * Resolved through [require] rather than a null-tolerant read on purpose: a
     * doc test that cannot find its document must fail loudly. Returning "no
     * members found" would make every assertion below pass against nothing,
     * which is the one outcome that looks identical to success.
     */
    private fun apiDoc(): String {
        val file = File("API.md")
        require(file.isFile) {
            "API.md not found at ${file.absolutePath}. This test compares the documented " +
                "enums against :schema's; it cannot silently skip, because passing with no " +
                "document read is indistinguishable from passing with a correct one."
        }
        return file.readText()
    }

    /**
     * Pulls the members out of `export type <name> = 'A' | 'B' …`, which may run
     * over several lines and carry KDoc between arms.
     *
     * The comments are stripped before the members are read, because §14's KDoc
     * quotes *other* enums' values to draw exactly the distinctions a client
     * gets wrong — `DRAIN_NO_DESTINATION` is documented by contrasting it with
     * `DrainBlockReason`'s `'AWAITING_ZERO_PLAYERS'`. Reading those as members
     * made this test report drift that was not there, against a document that
     * was correct. An extractor for a hand-written document has to be told what
     * is prose.
     */
    private fun documentedUnion(name: String): List<String> {
        val declaration = Regex("export type $name =(.*?);", RegexOption.DOT_MATCHES_ALL).find(apiDoc())
        require(declaration != null) { "API.md declares no `export type $name`" }
        val arms = declaration.groupValues[1].replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        val members = Regex("'([A-Z_]+)'").findAll(arms).map { it.groupValues[1] }.toList()
        require(members.isNotEmpty()) { "`export type $name` in API.md lists no members" }
        return members
    }

    @Test
    fun `the documented ConditionType lists every condition schema can emit`() {
        documentedUnion("ConditionType") shouldContainExactly ConditionType.entries.map { it.name }
    }

    @Test
    fun `the documented observed-state unions match schema`() {
        documentedUnion("ServerPhase") shouldContainExactly ServerPhase.entries.map { it.name }
        documentedUnion("DrainState") shouldContainExactly DrainState.entries.map { it.name }
        documentedUnion("FailureReason") shouldContainExactly FailureReason.entries.map { it.name }
        documentedUnion("ControlCredential") shouldContainExactly ControlCredential.entries.map { it.name }
    }

    /**
     * The guard on the guard: proves the extractor reads the document rather
     * than reporting whatever it was asked for.
     *
     * Without this, an extractor that returned its own argument list — or that
     * quietly matched nothing and was compared against nothing — would satisfy
     * every assertion above.
     */
    @Test
    fun `the extractor reads the document and not its own argument`() {
        documentedUnion("ConditionType") shouldContainExactly documentedUnion("ConditionType")
        runCatching { documentedUnion("NoSuchTypeInvented") }.isFailure shouldBe true
        apiDoc().contains("export type ConditionType") shouldBe true
    }

    /**
     * Comment stripping must remove prose without eating arms.
     *
     * `FailureReason`'s KDoc names a value from a different enum, so a member
     * count that included it would be wrong in the direction that fails loudly.
     * The risk in the other direction is a regex that swallows the arms after a
     * comment and silently reports a short list — checked here by requiring the
     * arm *following* the documented one to survive.
     */
    @Test
    fun `stripping the prose keeps the arms around it`() {
        val reasons = documentedUnion("FailureReason")
        reasons shouldContainExactly reasons.distinct()
        reasons.contains("AWAITING_ZERO_PLAYERS") shouldBe false
        reasons.contains("DRAIN_NO_DESTINATION") shouldBe true
        reasons.contains("DRAIN_TRANSFER_FAILED") shouldBe true
        reasons.last() shouldBe "UNKNOWN"
    }
}
