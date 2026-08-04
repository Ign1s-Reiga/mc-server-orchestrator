package mcorch.schema.yaml

import mcorch.schema.ParseResult
import mcorch.schema.SchemaViolation
import org.junit.jupiter.api.DynamicTest

/**
 * One malformed example and the violation it has to produce.
 *
 * A test that only asserted "parsing failed" would pass while the message sent
 * an operator to the wrong line, so a case pins the field path and a distinctive
 * part of the problem text. Shared between the per-kind validation tests so both
 * pin the same things and so `ExampleCoverageTest` can check that every file
 * under `invalid/` belongs to one of them.
 */
internal data class ValidationCase(
    val file: String,
    val field: String,
    val problemContains: String,
)

internal fun violationsOf(file: String): List<SchemaViolation> {
    val result = ServerDefinitionParser.parse(Fixtures.load("invalid/$file"), file)
    check(result is ParseResult.Invalid) { "$file was expected to be rejected, but it parsed" }
    return result.violations
}

internal fun rejectionTests(cases: List<ValidationCase>): List<DynamicTest> =
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
