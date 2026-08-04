package mcorch.schema.yaml

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import mcorch.schema.ParseResult
import mcorch.schema.violations
import org.junit.jupiter.api.Test

/**
 * Every example on disk is exercised by a test.
 *
 * The `invalid/` files are the interesting half: one added without a case would
 * otherwise sit there proving nothing, and the file that documents a rejection
 * is the one most likely to be added without the test that pins it.
 */
class ExampleCoverageTest {
    @Test
    fun `every invalid example is covered by a case`() {
        Fixtures.names("invalid") shouldContainExactlyInAnyOrder
            (PaperServerValidationTest.CASES + VelocityProxyValidationTest.CASES)
                .map { it.file }
                .distinct()
    }

    @Test
    fun `every valid example parses`() {
        Fixtures.names("valid").forEach { file ->
            val result = ServerDefinitionParser.parseAll(Fixtures.load("valid/$file"), file)
            check(result is ParseResult.Valid) {
                "valid/$file did not parse: ${result.violations().map { it.render() }}"
            }
        }
    }
}
