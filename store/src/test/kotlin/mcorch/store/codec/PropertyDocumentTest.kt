package mcorch.store.codec

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import mcorch.store.Fixtures
import mcorch.store.StoreException
import org.junit.jupiter.api.Test

/**
 * The on-disk encoding's two load-bearing properties.
 *
 * *Canonical*, because `putDefinition` decides whether the generation moves by
 * comparing encoded specs, and a generation that moves when nothing changed makes
 * the reconcile loop look permanently behind.
 *
 * *Refuses rather than guesses*, because the alternative to failing on a document
 * this build does not understand is quietly reinterpreting it.
 */
class PropertyDocumentTest {
    @Test
    fun `equal specs encode identically`() {
        val first = Fixtures.definition("full.yaml")
        val second = Fixtures.definition("full.yaml")

        DefinitionCodec.encodeSpec(first.spec) shouldBe DefinitionCodec.encodeSpec(second.spec)
    }

    @Test
    fun `a spec that differs anywhere encodes differently`() {
        val definition = Fixtures.definition("full.yaml")

        val changed = definition.spec.copy(maxPlayers = definition.spec.maxPlayers + 1)

        DefinitionCodec.encodeSpec(changed) shouldNotBe DefinitionCodec.encodeSpec(definition.spec)
    }

    @Test
    fun `label order does not change the encoding`() {
        val definition = Fixtures.definition("full.yaml")
        val forwards = definition.metadata.copy(labels = linkedMapOf("a" to "1", "b" to "2"))
        val backwards = definition.metadata.copy(labels = linkedMapOf("b" to "2", "a" to "1"))

        DefinitionCodec.encodeMetadata(forwards) shouldBe DefinitionCodec.encodeMetadata(backwards)
    }

    @Test
    fun `awkward characters survive the encoding`() {
        val awkward = "an = sign\na newline\r a carriage return and a \\ backslash"
        val writer = DocumentWriter()
        writer.put("message", awkward)
        writer.put("empty", "")

        val reader = PropertyDocument.parse(writer.render(), "test")

        reader.requireString("message") shouldBe awkward
        // Empty is a value; absent is not. They must not collapse into each other.
        reader.requireString("empty") shouldBe ""
        reader.string("missing") shouldBe null
    }

    @Test
    fun `a missing required key is corruption, not a default`() {
        val reader = PropertyDocument.parse("present=yes", "status of `survival-02`")

        val failure = runCatching { reader.requireString("absent") }.exceptionOrNull()

        val corrupt = failure.shouldBeInstanceOf<StoreException.Corrupt>()
        corrupt.retryable shouldBe false
        corrupt.message.shouldBeInstanceOf<String>() shouldContain "absent"
    }

    @Test
    fun `a value this build does not recognise is refused rather than guessed at`() {
        val reader = PropertyDocument.parse("state=TELEPORTING", "status of `survival-02`")

        val failure =
            runCatching { reader.requireEnum<mcorch.schema.DrainState>("state") }.exceptionOrNull()

        val corrupt = failure.shouldBeInstanceOf<StoreException.Corrupt>()
        corrupt.message.shouldBeInstanceOf<String>() shouldContain "Refusing to guess"
    }

    @Test
    fun `a malformed line is corruption`() {
        val failure = runCatching { PropertyDocument.parse("no separator here", "test") }.exceptionOrNull()

        failure.shouldBeInstanceOf<StoreException.Corrupt>()
    }
}
