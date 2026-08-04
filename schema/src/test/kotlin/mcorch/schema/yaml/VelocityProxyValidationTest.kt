package mcorch.schema.yaml

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import mcorch.schema.violations
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/** Every malformed `VelocityProxy` example is rejected, and rejected for the right field. */
class VelocityProxyValidationTest {
    @TestFactory
    fun `each invalid example is rejected with a violation on the offending field`(): List<DynamicTest> =
        rejectionTests(CASES)

    /**
     * The invariant with the most words written about it in CLAUDE.md, checked
     * in both spellings an operator could reach for.
     *
     * `forwarding.secret: "<value>"` is caught by the reader that expects a
     * secret reference; `forwardingSecret: "<value>"` is caught by the parser's
     * blanket rule on unclaimed secret-looking keys, which applies at any depth
     * of any kind. Both have to say "secret store" rather than "unknown field" or
     * "expected a mapping" — the fix is not a typo fix.
     */
    @Test
    fun `both spellings of an inline forwarding secret point at the secret store`() {
        val inline = violationsOf("proxy-inline-forwarding-secret.yaml").single()
        inline.field shouldBe "spec.forwarding.secret"
        inline.problem.contains("Put the value in the secret store") shouldBe true

        val velocityStyle = violationsOf("proxy-forwarding-secret-key.yaml").single()
        velocityStyle.field shouldBe "spec.forwardingSecret"
        velocityStyle.problem.contains("Put the value in the secret store") shouldBe true
    }

    @Test
    fun `neither rejection echoes the value that was written`() {
        // The violation is rendered into logs and API responses. Whatever an
        // operator put in the file must not come back out of it.
        val rendered =
            (
                violationsOf("proxy-inline-forwarding-secret.yaml") +
                    violationsOf("proxy-forwarding-secret-key.yaml")
            ).joinToString("\n") { it.render() }

        rendered.contains("not-supported-name-it-in-the-secret-store") shouldBe false
    }

    @Test
    fun `a proxy missing every required field names each of them`() {
        val yaml =
            """
            apiVersion: mcorch.dev/v1alpha1
            kind: VelocityProxy
            metadata:
              name: proxy-01
            spec: {}
            """.trimIndent()

        val result = ServerDefinitionParser.parse(yaml, "bare.yaml")
        result.violations().map { it.field } shouldContainExactlyInAnyOrder
            listOf("spec.image", "spec.resources", "spec.forwarding", "spec.backends")
    }

    @Test
    fun `a bad fallback entry is reported on its own index and does not stop the others`() {
        val violation = violationsOf("proxy-fallback-bad-name.yaml").single()

        violation.field shouldBe "spec.backends.fallback[1]"
        violation.location?.line shouldBe 23
    }

    internal companion object {
        val CASES: List<ValidationCase> =
            listOf(
                ValidationCase(
                    "proxy-inline-forwarding-secret.yaml",
                    "spec.forwarding.secret",
                    "inline secrets are not supported",
                ),
                ValidationCase(
                    "proxy-forwarding-secret-key.yaml",
                    "spec.forwardingSecret",
                    "inline secrets are not supported",
                ),
                ValidationCase(
                    "proxy-empty-selector.yaml",
                    "spec.backends.selector.matchLabels",
                    "matches every server in the fleet",
                ),
                ValidationCase("proxy-missing-selector.yaml", "spec.backends.selector", "is required"),
                ValidationCase("proxy-legacy-forwarding.yaml", "spec.forwarding.mode", "must be one of `modern`"),
                ValidationCase(
                    "proxy-control-port-conflict.yaml",
                    "spec.control.port",
                    "must differ from spec.network.port",
                ),
                ValidationCase(
                    "proxy-published-control-without-token.yaml",
                    "spec.control.tokenSecret",
                    "is required when spec.control.hostPort is set",
                ),
                ValidationCase("proxy-storage-declared.yaml", "spec.storage", "unknown field"),
                ValidationCase("proxy-fallback-bad-name.yaml", "spec.backends.fallback[1]", "must be lowercase"),
            )
    }
}
