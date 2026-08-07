package mcorch.schema.yaml

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import mcorch.schema.SchemaViolation
import mcorch.schema.violations
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.lowlevel.Compose
import org.snakeyaml.engine.v2.nodes.MappingNode
import org.snakeyaml.engine.v2.nodes.Node
import org.snakeyaml.engine.v2.nodes.ScalarNode
import org.snakeyaml.engine.v2.nodes.SequenceNode

/**
 * Nothing an operator writes on a path that can hold a secret comes back out in
 * a violation.
 *
 * Violations are rendered into API response bodies and into log lines, so a
 * message that quotes the offending scalar publishes it. On most fields that is
 * a helpful diagnostic; on `spec.forwarding`, `spec.control` and
 * `spec.network.rcon` it is a disclosure of the material CLAUDE.md says only
 * ever travels through the secret store — and the operator who put it there has
 * already made their mistake, so the echo is ours.
 *
 * Two mechanisms, tested separately because they fail differently:
 *
 * 1. [describe] names the *shape* of a node and never its value, so no shape
 *    mismatch anywhere in any kind can quote a scalar. This is what makes the
 *    paths safe, including the ones nobody listed.
 * 2. [SecretBearingPaths] then routes those paths to the secret-store message
 *    instead of "expected a mapping". This is what makes them *useful*, and it
 *    is guarded so a fourth secret-bearing field cannot be added without it.
 */
class SecretEchoTest {
    @TestFactory
    fun `every path that can hold a secret reference refuses a scalar without quoting it`(): List<DynamicTest> =
        DOCUMENTS.map { (path, yaml) ->
            DynamicTest.dynamicTest(path) {
                val violations = ServerDefinitionParser.parse(yaml, "$path.yaml").violations()
                val violation = violations.singleOrNull { it.field == path }
                requireNotNull(violation) {
                    "expected a violation on `$path`, got: ${violations.map(SchemaViolation::render)}"
                }
                violation.problem shouldContain "inline secrets are not supported"
                violations.forEach { it.render() shouldNotQuote MARKER }
            }
        }

    /**
     * The list in the production code and the documents here are one guard, and
     * a guard with a hole in it is worse than none.
     *
     * [MappingReader.secretRef] refuses to run at a path
     * [SecretBearingPaths.refs] does not name, so a field added without listing
     * it fails on the first parse. This is the other half: a path listed there
     * and not exercised here would be a claim nobody checked.
     */
    @Test
    fun `every declared secret-bearing path is exercised above`() {
        DOCUMENTS.keys shouldContainExactlyInAnyOrder
            (SecretBearingPaths.refs + SecretBearingPaths.containers).toList()
    }

    /**
     * The condition the guard actually depends on.
     *
     * [MappingReader.secretRef]'s check fires when the block *around* the field
     * is read, and two of the three blocks are optional — so "an unlisted field
     * fails on the first parse" is only true while every block is opened by some
     * valid example. It is today, by coincidence: `proxy-full.yaml` writes
     * `control:` and `full.yaml` writes `rcon:`, while `proxy-minimal.yaml`
     * reaches neither. This turns the coincidence into a checked fact, so that a
     * fourth secret-bearing field under a new optional block cannot arrive with
     * no example that opens it.
     */
    @Test
    fun `every block that can hold a secret reference is written by a valid example`() {
        val documents = Fixtures.names("valid").flatMap { compose(Fixtures.load("valid/$it")) }

        SecretBearingPaths.containers.forEach { container ->
            val written = documents.any { hasPath(it, container.split('.')) }
            if (!written) {
                throw AssertionError(
                    "no example under valid/ writes `$container`, so no parse opens that block and the " +
                        "check in MappingReader.secretRef would never run for a field added inside it",
                )
            }
        }
    }

    /**
     * The two shape rules the list itself has to keep, both of which decide
     * whether the guard fires on a developer error or on an operator's file.
     *
     * A path is not always a literal: [MappingReader.valueList] puts a document
     * position in one, so membership is tested against the flattened form. And a
     * reference one level too shallow would derive `spec` as a secret-bearing
     * block, which would answer `spec: <anything>` on every kind with the
     * secret-store message; that one is enforced by a `require` at
     * initialisation, and asserted here as the property it protects.
     *
     * Nothing is listed under a list yet, so the flattening cannot be shown
     * end-to-end — only that membership routes through it and leaves ordinary
     * paths alone.
     */
    @Test
    fun `the path list is in the shape the guard needs`() {
        SecretBearingPaths.normalised("spec.backends[2].tokenSecret") shouldBe "spec.backends[].tokenSecret"
        SecretBearingPaths.normalised("spec.forwarding.secret") shouldBe "spec.forwarding.secret"

        SecretBearingPaths.refs.forEach { SecretBearingPaths.isRef(it) shouldBe true }
        SecretBearingPaths.containers.forEach { container ->
            SecretBearingPaths.holds(container) shouldBe true
            if (!container.contains('.')) {
                throw AssertionError("`$container` is a top-level block: every document of its kind opens it")
            }
        }
    }

    /**
     * The coordinates lose the value, not the diagnostic.
     *
     * An empty `name` said "must not be empty" before this change and has to go
     * on saying it: replacing every rejection with the syntax rule would be a
     * quieter message wearing the same clothes as a safety fix.
     */
    @Test
    fun `a rejected coordinate still says which rule it broke`() {
        val yaml =
            proxy(
                "  forwarding:",
                "    secret:",
                "      name: \"\"",
                "      key: ${"k".repeat(300)}",
            )

        val violations = ServerDefinitionParser.parse(yaml, "coordinates.yaml").violations()

        violations.single { it.field == "spec.forwarding.secret.name" }.problem shouldBe "must not be empty"
        violations.single { it.field == "spec.forwarding.secret.key" }.problem shouldBe
            "must be at most 253 characters, found 300"
    }

    @Test
    fun `a secret reference read at an undeclared path fails loudly rather than quietly`() {
        val node = compose("token:\n  name: fleet-forwarding\n  key: modern-forwarding\n").first()
        val reader = MappingReader.of("spec.invented", node, ViolationSink("guard.yaml"))
        requireNotNull(reader)

        val failure = shouldThrow<IllegalStateException> { reader.secretRef("token") }

        failure.message shouldContain "SecretBearingPaths.refs"
    }

    /**
     * One level below the block: the operator who wrote the material into
     * `name:` instead of collapsing the block.
     *
     * Both coordinates would otherwise be rejected by messages that quote what
     * was written — `must be lowercase, found `…`` and ``must match …, found
     * `…```.
     */
    @Test
    fun `the coordinates of a secret reference are described, not quoted`() {
        val yaml =
            proxy(
                "  forwarding:",
                "    secret:",
                "      name: $MARKER",
                "      key: $MARKER",
            )

        val violations = ServerDefinitionParser.parse(yaml, "coordinates.yaml").violations()

        violations.map { it.field } shouldContainExactlyInAnyOrder
            listOf("spec.forwarding.secret.name", "spec.forwarding.secret.key")
        violations.forEach { it.render() shouldNotQuote MARKER }
    }

    /**
     * The example on disk, pinned to its field and its line: the fix changes
     * which message the path gets, and must not cost the operator the two things
     * that send them to it.
     */
    @Test
    fun `the block written as one scalar reports the secret store, on its own field and line`() {
        val violation = violationsOf("proxy-inline-forwarding-block.yaml").single()

        violation.field shouldBe "spec.forwarding"
        violation.location?.line shouldBe 22
        violation.problem shouldContain "Put the value in the secret store"
        violation.render() shouldNotQuote "not-supported-name-it-in-the-secret-store"
    }

    /**
     * What the ordinary shape mismatch says now, away from any secret.
     *
     * The value is gone from these messages too, and deliberately: a rule that
     * held only on the three paths someone remembered to list is a rule waiting
     * for a fourth field. The shape is the diagnostic here anyway, and the
     * violation still carries `file:line:column` pointing at the value itself.
     */
    @Test
    fun `a shape mismatch names the shape rather than the value`() {
        val text = ServerDefinitionParser.parse(paper("  resources: 4Gi"), "shape.yaml").violations()
        val number = ServerDefinitionParser.parse(paper("  resources: 4"), "shape.yaml").violations()

        text.single { it.field == "spec.resources" }.problem shouldBe "expected a mapping, found a string"
        number.single { it.field == "spec.resources" }.problem shouldBe "expected a mapping, found a number"
    }

    private fun compose(yaml: String): List<Node> =
        Compose(LoadSettings.builder().build()).composeAllFromString(yaml).toList()

    /**
     * Whether a document writes a dotted path. A segment ending `[]` — the
     * spelling [SecretBearingPaths] uses for a field under a list — matches if
     * any element of that list continues the path.
     */
    private fun hasPath(
        node: Node,
        segments: List<String>,
    ): Boolean {
        val segment = segments.firstOrNull() ?: return true
        val rest = segments.drop(1)
        if (node !is MappingNode) return false
        val value =
            node.value
                .firstOrNull { (it.keyNode as? ScalarNode)?.value == segment.removeSuffix("[]") }
                ?.valueNode
                ?: return false
        if (!segment.endsWith("[]")) return hasPath(value, rest)
        return value is SequenceNode && value.value.any { hasPath(it, rest) }
    }

    private infix fun String.shouldNotQuote(written: String) {
        if (contains(written)) {
            throw AssertionError("a violation repeated what was written in the file: \"$this\"")
        }
    }

    private companion object {
        /**
         * What the documents below write on a secret-bearing path.
         *
         * A placeholder that reads as an instruction, never anything shaped like
         * a credential: these files and this source are checked in, and a test
         * fixture is one of the three places CLAUDE.md names as somewhere the
         * forwarding secret must never appear.
         */
        const val MARKER = "<written-here-by-mistake>"

        /** A `PaperServer` whose spec is valid apart from [specLines]. */
        fun paper(vararg specLines: String): String =
            (
                listOf(
                    "apiVersion: mcorch.dev/v1alpha1",
                    "kind: PaperServer",
                    "metadata:",
                    "  name: survival-01",
                    "spec:",
                    "  eulaAccepted: true",
                    "  image: docker.io/itzg/minecraft-server:2026.6.1",
                    "  paper:",
                    "    minecraftVersion: \"1.21.8\"",
                ) + specLines
            ).joinToString(separator = "\n", postfix = "\n")

        /** A `VelocityProxy` whose spec is valid apart from [specLines]. */
        fun proxy(vararg specLines: String): String =
            (
                listOf(
                    "apiVersion: mcorch.dev/v1alpha1",
                    "kind: VelocityProxy",
                    "metadata:",
                    "  name: proxy-01",
                    "spec:",
                    "  image: docker.io/itzg/mc-proxy:2026.6.1",
                    "  resources:",
                    "    memory: 1Gi",
                    "  backends:",
                    "    selector:",
                    "      matchLabels:",
                    "        mcorch.dev/fleet: main",
                ) + specLines
            ).joinToString(separator = "\n", postfix = "\n")

        private val VALID_FORWARDING =
            arrayOf(
                "  forwarding:",
                "    secret:",
                "      name: fleet-forwarding",
                "      key: modern-forwarding",
            )

        /** One document per secret-bearing path, writing [MARKER] at that path. */
        val DOCUMENTS: Map<String, String> =
            mapOf(
                "spec.network.rcon" to paper("  resources:", "    memory: 4Gi", "  network:", "    rcon: $MARKER"),
                "spec.network.rcon.passwordSecret" to
                    paper(
                        "  resources:",
                        "    memory: 4Gi",
                        "  network:",
                        "    rcon:",
                        "      enabled: true",
                        "      passwordSecret: $MARKER",
                    ),
                "spec.forwarding" to proxy("  forwarding: $MARKER"),
                "spec.forwarding.secret" to proxy("  forwarding:", "    secret: $MARKER"),
                "spec.control" to proxy(*VALID_FORWARDING, "  control: $MARKER"),
                "spec.control.tokenSecret" to proxy(*VALID_FORWARDING, "  control:", "    tokenSecret: $MARKER"),
            )
    }
}
