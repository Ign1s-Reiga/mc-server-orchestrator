package mcorch.core

import io.kotest.assertions.withClue
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
 *
 * ## Red-proofing this file needs `--rerun-tasks --no-build-cache`
 *
 * These scans read **other modules'** sources, which are not inputs to
 * `:core:test`. So a sabotage planted in `:app` — a gate on the flag, exactly the
 * thing the scan exists to catch — leaves this task up to date, and `cleanTest`
 * does not help: the outputs come back out of the build cache and the tests never
 * execute. The run exits 0 and reads as a *surviving mutation* when in truth
 * nothing was measured. Verified: the same mutation reads GREEN under
 * `:core:cleanTest :core:test` and RED under
 * `:core:test --rerun-tasks --no-build-cache`.
 */
internal class ControlCredentialWiringTest {
    /**
     * **Both** collection points, and the alphabet read off the channel rather
     * than written here.
     *
     * There are two places that turn control outcomes into a credential verdict —
     * the routing sweep's `noting` and `ProxySelfLink.note` — and a scan covering
     * one of them is the funnel argument applied to half the funnels: a proxy
     * drain growing a second control call would drop its verdict with nothing
     * noticing, which is the "fourth site gets forgotten" shape one level up.
     *
     * The call names come from `ControlChannel`'s own declarations, so a method
     * added there joins the alphabet without anybody remembering to add it here.
     * Keying on the receiver being spelled `channel.` instead would let a renamed
     * local walk straight past a scan whose entire value is against the site that
     * does not exist yet.
     */
    @Test
    fun `every control call in either funnel goes through it`() {
        val alphabet = channelCalls()
        (alphabet.size >= 6) shouldBe true

        val scopes =
            listOf(
                "noting" to bodyOf(reconciler(), "private suspend fun assertBackends("),
                // The **class**, not its one method. A proxy drain that grows a
                // second control call is the case this exists for, and scoping to
                // `assertAdmission` would miss it by construction. Scoped to
                // `ProxySelfLink` rather than to the file, because `BackendLink`'s
                // calls in the same file are a *backend* sealing through the same
                // endpoint: their verdict cannot be recorded here, since this pass
                // writes the backend's row and not the proxy's — the residual
                // named in `credentialVerdict`.
                "note" to bodyOf(proxyLink(), "internal class ProxySelfLink("),
            )
        for ((funnel, body) in scopes) {
            val calls = body.filter { line -> alphabet.any { codeOf(line).contains("$it(") } }
            withClue("$funnel: the scan found no calls to check") { calls.isNotEmpty() shouldBe true }
            val unfunnelled = calls.filterNot { codeOf(it).contains("$funnel(") }
            withClue(funnel) { unfunnelled.map { it.trim() } shouldContainExactly emptyList() }
        }
    }

    /**
     * The negative control: the matcher fires on the shape it is meant to catch,
     * including the renamed-receiver one the alphabet exists to survive.
     *
     * Without this, a matcher that matched nothing would report every site as
     * funnelled and the assertion above would pass against a build with no funnel
     * at all.
     */
    @Test
    fun `the funnel scan sees an unfunnelled call whatever the receiver is called`() {
        val alphabet = channelCalls()
        fun calls(line: String): Boolean = alphabet.any { codeOf(line).contains("$it(") }

        calls("            when (val outcome = channel.deregister(name)) {") shouldBe true
        // The rename that a receiver-keyed scan would miss.
        calls("            when (val outcome = link.deregister(name)) {") shouldBe true
        codeOf("            when (val outcome = noting(channel.deregister(name))) {").contains("noting(") shouldBe true
        // Prose about a call is not a call.
        calls("        // channel.deregister(name) is what the sweep does") shouldBe false
        // A method this build's channel does not declare is not in the alphabet,
        // which is what makes the list a reading of `ControlChannel` rather than
        // a match on anything with brackets.
        calls("            channel.rebootTheProxy(name)") shouldBe false
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
            everyModuleSource()
                .filter { readsUsable(it.readLines()) }
                .map { it.invariantSeparatorsPath }
                .sorted()

        readers shouldContainExactly
            listOf(
                "../api/src/main/kotlin/mcorch/api/render/ServerJson.kt",
                "../core/src/main/kotlin/mcorch/core/StatusDrafting.kt",
            )
    }

    /**
     * The vacuity controls for the scan above: it walks every module, it compares
     * whole paths, and it catches the spellings a gate could use.
     *
     * A path that resolved to nothing would make the assertion above pass with an
     * empty list against any build, including one where the reconciler gates a
     * drain on the flag. Comparing `File.name` would have let a second
     * `StatusDrafting.kt` anywhere in the tree pass as the allowed one.
     *
     * The token is matched on both word boundaries — so a receiverless read in a
     * `with` block and a `::usable` reference count, not only `.usable` — and the
     * false positives that widening buys are excluded by requiring the file to
     * know about the record at all. `HostPaths` has a local named `usable` and no
     * notion of a control endpoint; a gate necessarily has both.
     */
    @Test
    fun `the usable scan walks every module, compares paths and catches each spelling`() {
        val all = everyModuleSource()
        val modules = MODULES.associateWith { module -> all.count { it.invariantSeparatorsPath.contains("/$module/") } }
        withClue("modules with no sources walked: $modules") { modules.values.all { it > 0 } shouldBe true }
        all.any { it.invariantSeparatorsPath.endsWith("mcorch/core/Reconciler.kt") } shouldBe true
        all.any { it.invariantSeparatorsPath.endsWith("mcorch/store/codec/StatusCodec.kt") } shouldBe true

        // Each spelling of a read, in a file that knows what it is reading.
        readsUsable(listOf("val c: ControlEndpointStatus", "put(\"usable\", c.usable)")) shouldBe true
        readsUsable(listOf("val c: ControlEndpointStatus", "with(c) { if (usable) drain() }")) shouldBe true
        readsUsable(listOf("val c: ControlEndpointStatus", "val gate = ControlEndpointStatus::usable")) shouldBe true
        // A quoted key is not a read, or the renderer's own JSON name would count
        // as one and the scan would pass on a file that never touches the value.
        readsUsable(listOf("val c: ControlEndpointStatus", "put(\"usable\", true)")) shouldBe false
        // Nor is the camel-cased field that carries it to a client.
        readsUsable(listOf("val c: ControlEndpointStatus", "put(x, status.controlUsable)")) shouldBe false
        // Prose is not a read either.
        readsUsable(listOf("val c: ControlEndpointStatus", " * `usable` is the one derivation")) shouldBe false
        // And the token alone, in a file with no control record in it, is somebody
        // else's local variable — the exclusion that lets the token be matched
        // widely without an exception list.
        readsUsable(listOf("val usable = mounts.all { it.readable }", "if (!usable) refuse()")) shouldBe false
    }

    private fun reconciler(): List<String> = File(RECONCILER).readLines()

    private fun proxyLink(): List<String> = File(PROXY_LINK).readLines()

    /**
     * The names `ControlChannel` declares, which is the alphabet of calls that can
     * carry a credential verdict.
     *
     * Read from the source rather than listed here so that a method added to the
     * channel is covered by the funnel scan on the day it is added — the scan's
     * whole value being against the call site nobody has written yet.
     */
    private fun channelCalls(): List<String> {
        val names =
            File(CONTROL_CHANNEL)
                .readLines()
                .mapNotNull { DECLARATION.find(codeOf(it))?.groupValues?.get(1) }
                .distinct()
        require(names.isNotEmpty()) { "no `suspend fun` declarations found in $CONTROL_CHANNEL" }
        return names
    }

    /**
     * Every `.kt` main source in every module, as paths relative to this one.
     *
     * All of them, not `:core` and `:api`: a gate on the flag is no less a gate
     * for being written in `:app` or `:store`, and the scan's claim is about the
     * repository rather than about the two modules that happen to read it today.
     * `:schema`'s own declaration file is the single exclusion, named by path,
     * because the property is declared and documented there.
     */
    /**
     * Whether these lines *read* the derived flag.
     *
     * Two conditions, and the second is what lets the first be wide. The token is
     * matched on both word boundaries so that `with(control) { usable }` and
     * `::usable` count — and the file must also name the record the property
     * belongs to, which excludes an unrelated local of the same name without an
     * exception list that would have to grow.
     *
     * The residual, stated rather than closed: a read written in a file that
     * names neither [mcorch.schema.ControlEndpointStatus] nor a `control`
     * receiver. Reaching the value at all needs one of the two, short of passing
     * it through a `Boolean` parameter — at which point what is being gated on is
     * no longer identifiable as this property by any scan.
     */
    private fun readsUsable(lines: List<String>): Boolean {
        val code = lines.map(::codeOf)
        val knowsTheRecord = code.any { it.contains("ControlEndpointStatus") || CONTROL_RECEIVER.containsMatchIn(it) }
        return knowsTheRecord && code.any { USABLE.containsMatchIn(it) }
    }

    private fun everyModuleSource(): List<File> =
        MODULES.flatMap { module ->
            val root = File("../$module/src/main/kotlin")
            require(root.isDirectory) {
                "no sources for `$module` at ${root.absolutePath}. This scan enumerates the readers of a " +
                    "schema property across every module; an empty walk would report no readers and pass " +
                    "against any build, including one that gates a drain on the flag."
            }
            root
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filterNot { it.invariantSeparatorsPath.endsWith(DECLARATION_SITE) }
                .toList()
        }

    /** A line with its string literals and its trailing comment removed. */
    private fun codeOf(line: String): String {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return ""
        return line.replace(STRING, "\"\"").substringBefore("//")
    }

    /** The lines between the declaration starting with [declaration] and its closing brace. */
    private fun bodyOf(
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

        const val PROXY_LINK: String = "src/main/kotlin/mcorch/core/proxy/ProxyLink.kt"

        const val CONTROL_CHANNEL: String = "src/main/kotlin/mcorch/core/proxy/ControlChannel.kt"

        /** Where the property is declared and documented, so a mention there is not a read. */
        const val DECLARATION_SITE: String = "mcorch/schema/Status.kt"

        /** Every module with Kotlin main sources. `:core` is this one, hence the relative walk. */
        val MODULES: List<String> = listOf("schema", "cri", "core", "store", "api", "app", "velocity-plugin")

        val STRING: Regex = Regex("\"(\\\\.|[^\"\\\\])*\"")

        /** A `suspend fun` declaration, which is how `ControlChannel` spells every call it offers. */
        val DECLARATION: Regex = Regex("\\bsuspend fun ([A-Za-z][A-Za-z0-9]*)\\(")

        /**
         * A read of the derived flag.
         *
         * `\b` on both sides rather than a leading `.`, so a receiverless read
         * inside a `with` block and a `::usable` reference both count. The string
         * that names it on the wire does not: literals are stripped first.
         */
        val USABLE: Regex = Regex("\\busable\\b")

        /** A file that handles the record the property belongs to. */
        val CONTROL_RECEIVER: Regex = Regex("\\bcontrol[.?]|\\.control\\b")
    }
}
