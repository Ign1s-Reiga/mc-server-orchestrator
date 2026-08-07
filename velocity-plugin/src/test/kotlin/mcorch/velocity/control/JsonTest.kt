package mcorch.velocity.control

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The hand-rolled JSON, held to being strict rather than merely working.
 *
 * A control plane that can seal every backend in a fleet reads its requests with
 * this. Every case below is a body that a lenient parser would accept with a
 * meaning nobody chose.
 */
class JsonTest {
    @Test
    fun `a well-formed object reads back as its fields`() {
        val parsed = Json.parseObject("""{"address":"10.0.0.4:25565","admitsNewPlayers":false,"message":null}""")

        parsed.string("address") shouldBe "10.0.0.4:25565"
        parsed.boolean("admitsNewPlayers") shouldBe false
        parsed.optionalString("message") shouldBe null
    }

    @Test
    fun `a missing field is a refusal and never a default`() {
        val parsed = Json.parseObject("""{"address":"10.0.0.4:25565"}""")

        // The seal is asserted through one of these. A body whose
        // `admitsNewPlayers` was dropped in transit must not read as "admits".
        refusal { parsed.boolean("admitsNewPlayers") }
        refusal { parsed.string("nope") }
    }

    @Test
    fun `a field of the wrong type is refused rather than coerced`() {
        val parsed = Json.parseObject("""{"admitsNewPlayers":"false","address":12345,"message":7}""")

        refusal { parsed.boolean("admitsNewPlayers") }
        refusal { parsed.string("address") }
        refusal { parsed.optionalString("message") }
    }

    @Test
    fun `malformed documents are refused`() {
        val bad =
            listOf(
                "",
                "   ",
                "not json",
                "{",
                """{"a":}""",
                """{"a" 1}""",
                """{"a":1,}""",
                """{"a":"unterminated}""",
                """{"a":"bad \q escape"}""",
                """{"a":"truncated \u12"}""",
                """["not","an","object"]""",
                // Trailing content is how two documents in one body get half-read.
                """{"a":1} {"b":2}""",
                """{"a":1}garbage""",
                // A duplicate key is a document whose meaning depends on which one
                // the reader kept.
                """{"a":1,"a":2}""",
                // Depth, so a pathological body cannot be aimed at the parser.
                "[".repeat(64) + "]".repeat(64),
            )

        for (body in bad) {
            shouldThrow<ControlFailure> { Json.parseObject(body) }.code shouldBe ControlErrorCode.MALFORMED_REQUEST
        }
    }

    @Test
    fun `strings survive a round trip through the writer`() {
        val awkward = "quote\" backslash\\ newline\n tab\t control unicodeé delete"

        val rendered = JsonWriter().obj { field("value", awkward) }.toString()

        Json.parseObject(rendered).string("value") shouldBe awkward
    }

    @Test
    fun `the writer renders each type as itself`() {
        val rendered =
            JsonWriter()
                .obj {
                    field("text", "hello")
                    field("absent", null as String?)
                    field("flag", true)
                    field("count", 3)
                    field("stamp", 1_770_000_000_000L)
                    field("missingStamp", null as Long?)
                    nullField("nothing")
                    stringArray("versions", listOf("1", "2"))
                    objectField("nested") { field("inner", 1) }
                    objectArray("items", listOf("a", "b")) { item -> field("name", item) }
                }.toString()

        val parsed = Json.parseObject(rendered)
        parsed.string("text") shouldBe "hello"
        parsed.optionalString("absent") shouldBe null
        parsed.boolean("flag") shouldBe true
        parsed.int("count") shouldBe 3
        // Epoch milliseconds, exact rather than nearly: `:core` reads these back and
        // a timestamp that came out different from what went in is a drain record
        // that disagrees with itself.
        parsed.long("stamp") shouldBe 1_770_000_000_000L
        parsed.isNull("missingStamp") shouldBe true
        parsed.isNull("nothing") shouldBe true
        parsed.array("versions").map { (it as JsonString).value } shouldBe listOf("1", "2")
        parsed.obj("nested").int("inner") shouldBe 1
        parsed.array("items").map { (it as JsonObject).string("name") } shouldBe listOf("a", "b")
    }

    @Test
    fun `an empty object and an empty array are documents, not failures`() {
        Json.parseObject("{}").fields.size shouldBe 0
        Json.parseObject("""{"items":[]}""").array("items").size shouldBe 0
    }

    @Test
    fun `a backend address is parsed strictly`() {
        BackendAddress.parse("10.0.0.4:25565") shouldBe BackendAddress("10.0.0.4", 25565)
        BackendAddress.parse(" survival.internal:25565 ") shouldBe BackendAddress("survival.internal", 25565)
        BackendAddress.parse("[fd00::4]:25565") shouldBe BackendAddress("fd00::4", 25565)
        BackendAddress.parse("[fd00::4]:25565").toString() shouldBe "[fd00::4]:25565"
        BackendAddress.parse("10.0.0.4:25565").toString() shouldBe "10.0.0.4:25565"

        for (bad in listOf(
            "10.0.0.4",
            "10.0.0.4:",
            ":25565",
            "10.0.0.4:0",
            "10.0.0.4:70000",
            "10.0.0.4:abc",
            "[fd00::4]25565",
            "",
        )) {
            shouldThrow<ControlFailure> { BackendAddress.parse(bad) }.code shouldBe ControlErrorCode.MALFORMED_REQUEST
        }
    }

    private fun refusal(read: () -> Unit) {
        shouldThrow<ControlFailure> { read() }.code shouldBe ControlErrorCode.MALFORMED_REQUEST
    }
}
