package mcorch.schema.yaml

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import mcorch.schema.ParseResult
import mcorch.schema.SchemaViolation
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Every malformed example is rejected, and rejected *for the right field*.
 *
 * A test that only asserts "parsing failed" would pass while the message sends
 * an operator to the wrong line, so each case pins the field path and a
 * distinctive part of the problem text.
 */
class PaperServerValidationTest {
    private data class Case(
        val file: String,
        val field: String,
        val problemContains: String,
    )

    private val cases =
        listOf(
            Case("unknown-field.yaml", "spec.persistance", "unknown field"),
            Case("inline-secret.yaml", "spec.network.rcon.password", "inline secrets are not supported"),
            Case("rcon-without-secret.yaml", "spec.network.rcon.passwordSecret", "is required when rcon is enabled"),
            Case("heap-exceeds-memory.yaml", "spec.resources.heap.max", "must leave headroom"),
            Case(
                "grace-below-save-timeout.yaml",
                "spec.lifecycle.stopGracePeriod",
                "must exceed spec.lifecycle.drain.saveTimeout",
            ),
            Case("port-out-of-range.yaml", "spec.network.port", "must be between 1 and 65535"),
            Case("rcon-port-conflict.yaml", "spec.network.rcon.port", "must differ from spec.network.port"),
            Case("image-latest.yaml", "spec.image", "`latest`"),
            Case("image-unpinned.yaml", "spec.image", "must be pinned to a tag or a digest"),
            Case("bad-name.yaml", "metadata.name", "must be lowercase"),
            Case("eula-not-accepted.yaml", "spec.eulaAccepted", "must be true"),
            Case("ephemeral-with-volume.yaml", "spec.storage.volume", "must not be set when"),
            Case("missing-required.yaml", "spec.image", "is required"),
            Case("duplicate-key.yaml", "spec.resources.memory", "is declared more than once"),
            Case("explicit-null.yaml", "spec.storage", "must not be null"),
            Case("yaml-1-1-boolean.yaml", "spec.eulaAccepted", "not a boolean"),
            Case("reserved-mount-path.yaml", "spec.storage.mountPath", "system path"),
            Case("unknown-api-version.yaml", "apiVersion", "must be one of"),
            Case("bad-duration.yaml", "spec.lifecycle.drain.saveTimeout", "expected a duration"),
            Case("memory-too-small.yaml", "spec.resources.memory", "must be at least 1Gi"),
            Case("many-problems.yaml", "spec.strage", "did you mean `storage`?"),
        )

    private fun violationsOf(file: String): List<SchemaViolation> {
        val result = ServerDefinitionParser.parse(Fixtures.load("invalid/$file"), file)
        return result.shouldBeInstanceOf<ParseResult.Invalid>().violations
    }

    @TestFactory
    fun `each invalid example is rejected with a violation on the offending field`(): List<DynamicTest> =
        cases.map { case ->
            DynamicTest.dynamicTest(case.file) {
                val violations = violationsOf(case.file)
                val match = violations.singleOrNull { it.field == case.field }
                requireNotNull(match) {
                    "expected a violation on `${case.field}`, got: ${violations.map(SchemaViolation::render)}"
                }
                if (!match.problem.contains(case.problemContains)) {
                    throw AssertionError(
                        "${case.file}: expected the problem on `${case.field}` to mention " +
                            "\"${case.problemContains}\", got \"${match.problem}\"",
                    )
                }
            }
        }

    @Test
    fun `every invalid example is covered by a case`() {
        Fixtures.names("invalid") shouldContainExactlyInAnyOrder cases.map { it.file }.distinct()
    }

    @Test
    fun `violations carry the line they were found on`() {
        val violation = violationsOf("port-out-of-range.yaml").single { it.field == "spec.network.port" }

        violation.location?.line shouldBe 14
        violation.render().contains("port-out-of-range.yaml:14") shouldBe true
    }

    @Test
    fun `a file with several problems reports all of them in one parse`() {
        val fields = violationsOf("many-problems.yaml").map { it.field }

        fields shouldContainExactlyInAnyOrder
            listOf(
                "metadata.name",
                "spec.image",
                "spec.paper.minecraftVersion",
                "spec.maxPlayers",
                "spec.network.port",
                "spec.resources.memory",
                "spec.strage",
            )
    }

    @Test
    fun `a definition missing several required fields names each of them`() {
        val fields = violationsOf("missing-required.yaml").map { it.field }

        fields shouldContainExactlyInAnyOrder
            listOf("spec.image", "spec.paper", "spec.eulaAccepted", "spec.resources")
    }

    @Test
    fun `malformed YAML is reported as a document-level problem, not a crash`() {
        val result = ServerDefinitionParser.parse("apiVersion: [unterminated\n", "broken.yaml")

        val violations = result.shouldBeInstanceOf<ParseResult.Invalid>().violations
        violations.single().field shouldBe "<document>"
        violations.single().problem.contains("is not valid YAML") shouldBe true
    }

    @Test
    fun `a document that is not a mapping is rejected`() {
        val result = ServerDefinitionParser.parse("- survival-01\n", "list.yaml")

        result
            .shouldBeInstanceOf<ParseResult.Invalid>()
            .violations
            .single()
            .field shouldBe "<document>"
    }

    @Test
    fun `an unknown kind lists the kinds that exist`() {
        val yaml =
            """
            apiVersion: mcorch.dev/v1alpha1
            kind: VelocityProxy
            metadata:
              name: proxy-01
            spec: {}
            """.trimIndent()

        val violations = ServerDefinitionParser.parse(yaml).shouldBeInstanceOf<ParseResult.Invalid>().violations
        val kindViolation = violations.single { it.field == "kind" }
        kindViolation.problem.contains("`PaperServer`") shouldBe true
    }
}
