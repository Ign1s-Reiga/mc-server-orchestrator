package mcorch.core

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The two claims about the credential field that behaviour cannot pin.
 *
 * **The funnel.** Every `ControlOutcome` that `Reconciler.assertBackends` obtains
 * goes through one helper. The argument for reading the verdict off the backend
 * `PUT` as well as off `state()` — *a verdict only one call site can write is one
 * that goes missing the day the order changes* — applies verbatim to the
 * deregistration sweep and to the proxy's own admission assertion, and those two
 * were left out when it was written three times as an `if`. A scan is the
 * instrument that notices the *fourth* site, which is the one a behavioural test
 * by definition cannot: it does not exist yet.
 *
 * **The consumer list.** [mcorch.schema.ControlEndpointStatus.usable] is a
 * presentation predicate, and its leniency — an untested credential counts as not
 * refused — is only sound while nothing gates on it. Its KDoc says so; this is
 * what makes the sentence enforcement rather than decoration.
 */
internal class ControlCredentialWiringTest {
    @Test
    fun `every control call in the routing sweep goes through the credential funnel`() {
        val body = functionBody(reconciler(), "private suspend fun assertBackends(")
        val calls = body.filter { CHANNEL_CALL.containsMatchIn(codeOf(it)) }

        // The scan is worth nothing if it found no calls to check.
        (calls.size >= 4) shouldBe true
        val unfunnelled = calls.filterNot { codeOf(it).contains("noting(") }
        unfunnelled.map { it.trim() } shouldContainExactly emptyList()
    }

    /**
     * The negative control: the matcher fires on the shape it is meant to catch.
     *
     * Without this, a regex that matched nothing would report every site as
     * funnelled and the assertion above would pass against a build with no funnel
     * at all.
     */
    @Test
    fun `the funnel scan sees an unfunnelled call`() {
        val funnelled = "            when (val outcome = noting(channel.deregister(name))) {"
        val raw = "            when (val outcome = channel.deregister(name)) {"
        CHANNEL_CALL.containsMatchIn(codeOf(raw)) shouldBe true
        codeOf(raw).contains("noting(") shouldBe false
        CHANNEL_CALL.containsMatchIn(codeOf(funnelled)) shouldBe true
        codeOf(funnelled).contains("noting(") shouldBe true
        // …and it is not fooled by prose about a call.
        CHANNEL_CALL.containsMatchIn(codeOf("        // channel.deregister(name) is what the sweep does")) shouldBe
            false
    }

    /**
     * `usable` may be read by the condition that reports it and by the renderers
     * that draw it, and by nothing else.
     *
     * A gate — `if (control.usable)` before starting a drain, or before choosing a
     * destination — would be reading "nobody has established a problem" as "this
     * endpoint works", which is precisely what the field's leniency makes false. A
     * gate must require [mcorch.schema.ControlCredential.ACCEPTED] and read the
     * enum itself.
     */
    @Test
    fun `usable is read only by the condition and the renderers`() {
        val readers =
            (mainSources(File("src/main/kotlin")) + mainSources(apiSources()))
                .filter { file -> file.readLines().any { USABLE.containsMatchIn(codeOf(it)) } }
                .map { it.name }
                .sorted()

        readers shouldContainExactly listOf("ServerJson.kt", "StatusDrafting.kt")
    }

    /**
     * The vacuity control for the scan above, in both halves: the reader set is
     * non-empty *and* the tree it walked is the real one.
     *
     * A path that resolved to nothing would make the assertion above pass with an
     * empty list against any build, including one where the reconciler gates a
     * drain on the flag.
     */
    @Test
    fun `the usable scan walks both trees and finds real references`() {
        val core = mainSources(File("src/main/kotlin"))
        val api = mainSources(apiSources())
        (core.size > 20) shouldBe true
        (api.size > 5) shouldBe true
        core.any { it.name == "Reconciler.kt" } shouldBe true
        api.any { it.name == "ServerJson.kt" } shouldBe true

        USABLE.containsMatchIn(codeOf("            put(\"usable\", control.usable)")) shouldBe true
        // A quoted key is not a read, or the renderer's own JSON name would count
        // as one and the scan would pass on a file that never touches the value.
        USABLE.containsMatchIn(codeOf("            put(\"usable\", true)")) shouldBe false
        // Prose is not a read either.
        USABLE.containsMatchIn(codeOf("     * `usable` is the one derivation")) shouldBe false
    }

    private fun reconciler(): List<String> = File(RECONCILER).readLines()

    private fun apiSources(): File = File("../api/src/main/kotlin")

    /**
     * Every `.kt` file under [root].
     *
     * Resolved through [require] rather than a tolerant walk: a scan that cannot
     * find its sources must fail loudly, because passing with nothing read is
     * indistinguishable from passing with everything read.
     */
    private fun mainSources(root: File): List<File> {
        require(root.isDirectory) {
            "no sources at ${root.absolutePath}. This scan enumerates the readers of a schema property " +
                "across two modules; an empty walk would report no readers at all and pass against any build."
        }
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /** A line with its string literals and its trailing comment removed. */
    private fun codeOf(line: String): String {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return ""
        return line.replace(STRING, "\"\"").substringBefore("//")
    }

    /** The lines between the declaration starting with [declaration] and its closing brace. */
    private fun functionBody(
        lines: List<String>,
        declaration: String,
    ): List<String> {
        val start = lines.indexOfFirst { it.trimStart().startsWith(declaration) }
        require(start >= 0) { "no declaration starting `$declaration`" }
        val indent = lines[start].takeWhile { it == ' ' }.length
        val end =
            (start + 1 until lines.size).first { index ->
                val line = lines[index]
                line.startsWith(" ".repeat(indent) + "}") && line.isNotBlank()
            }
        return lines.subList(start, end)
    }

    private companion object {
        const val RECONCILER: String = "src/main/kotlin/mcorch/core/Reconciler.kt"

        val STRING: Regex = Regex("\"(\\\\.|[^\"\\\\])*\"")

        /** A call made on the pass's control channel, which is what carries a verdict. */
        val CHANNEL_CALL: Regex = Regex("\\bchannel\\.[A-Za-z]+\\(")

        /** A read of the derived flag, rather than the string that names it on the wire. */
        val USABLE: Regex = Regex("[.?]usable\\b")
    }
}
