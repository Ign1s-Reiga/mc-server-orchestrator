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
 * Every malformed `PaperServer` example is rejected, and rejected *for the right
 * field*, plus the document-level rejections that are not per-kind.
 *
 * The proxy's cases live in [VelocityProxyValidationTest]; `ExampleCoverageTest`
 * is what makes sure an example added to `invalid/` lands in one of the two
 * rather than sitting unexercised.
 */
class PaperServerValidationTest {
    @TestFactory
    fun `each invalid example is rejected with a violation on the offending field`(): List<DynamicTest> =
        rejectionTests(CASES)

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
            listOf(
                "spec.image",
                "spec.paper",
                "spec.eulaAccepted",
                "spec.resources",
                // Required since RCON became standard: the block carries a
                // passwordSecret that cannot be defaulted, so neither can it.
                "spec.network",
            )
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
            kind: BungeeCord
            metadata:
              name: proxy-01
            spec: {}
            """.trimIndent()

        val violations = ServerDefinitionParser.parse(yaml).shouldBeInstanceOf<ParseResult.Invalid>().violations
        val kindViolation = violations.single { it.field == "kind" }
        kindViolation.problem.contains("`PaperServer`") shouldBe true
        kindViolation.problem.contains("`VelocityProxy`") shouldBe true
    }

    internal companion object {
        val CASES: List<ValidationCase> =
            listOf(
                ValidationCase("unknown-field.yaml", "spec.persistance", "unknown field"),
                ValidationCase(
                    "inline-secret.yaml",
                    "spec.network.rcon.password",
                    "inline secrets are not supported",
                ),
                // No longer "when rcon is enabled": there is no enabling. RCON is
                // standard, so the secret is required outright.
                ValidationCase(
                    "rcon-without-secret.yaml",
                    "spec.network.rcon.passwordSecret",
                    "is required",
                ),
                ValidationCase("heap-exceeds-memory.yaml", "spec.resources.heap.max", "must leave headroom"),
                ValidationCase(
                    "grace-below-save-timeout.yaml",
                    "spec.lifecycle.stopGracePeriod",
                    "must exceed spec.lifecycle.drain.saveTimeout",
                ),
                ValidationCase("port-out-of-range.yaml", "spec.network.port", "must be between 1 and 65535"),
                ValidationCase(
                    "rcon-port-conflict.yaml",
                    "spec.network.rcon.port",
                    "must differ from spec.network.port",
                ),
                ValidationCase("image-latest.yaml", "spec.image", "`latest`"),
                ValidationCase("image-unpinned.yaml", "spec.image", "must be pinned to a tag or a digest"),
                ValidationCase("bad-name.yaml", "metadata.name", "must be lowercase"),
                ValidationCase("eula-not-accepted.yaml", "spec.eulaAccepted", "must be true"),
                ValidationCase("ephemeral-with-volume.yaml", "spec.storage.volume", "must not be set when"),
                ValidationCase("missing-required.yaml", "spec.image", "is required"),
                ValidationCase("duplicate-key.yaml", "spec.resources.memory", "is declared more than once"),
                ValidationCase("explicit-null.yaml", "spec.storage", "must not be null"),
                ValidationCase("yaml-1-1-boolean.yaml", "spec.eulaAccepted", "not a boolean"),
                ValidationCase("reserved-mount-path.yaml", "spec.storage.mountPath", "system path"),
                ValidationCase("unknown-api-version.yaml", "apiVersion", "must be one of"),
                ValidationCase("bad-duration.yaml", "spec.lifecycle.drain.saveTimeout", "expected a duration"),
                ValidationCase("memory-too-small.yaml", "spec.resources.memory", "must be at least 1Gi"),
                ValidationCase("many-problems.yaml", "spec.strage", "did you mean `storage`?"),
            )
    }
}
