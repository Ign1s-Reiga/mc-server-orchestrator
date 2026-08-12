package mcorch.core.console.rcon

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The frame, pinned against the wire rather than against itself.
 *
 * A round trip through one codec proves the two halves agree, which they would
 * even if both were wrong about endianness or about what the length prefix
 * counts. So the layout is asserted byte by byte at least once, and the
 * boundary cases are asserted against hand-built buffers that `encode` would
 * never produce.
 */
internal class RconCodecTest {
    private fun frameOf(
        id: Int = 1,
        type: Int = RconCodec.EXEC_COMMAND,
        body: String = "list",
    ) = RconFrame(id, type, body)

    private fun decoded(bytes: ByteArray): RconDecoding.Decoded =
        RconCodec.decode(bytes).shouldBeInstanceOf<RconDecoding.Decoded>()

    /** Builds a frame the encoder would refuse to, so malformed input can be tested. */
    private fun handBuilt(
        size: Int,
        id: Int = 1,
        type: Int = 0,
        body: ByteArray = ByteArray(0),
        trailer: ByteArray = byteArrayOf(0, 0),
    ): ByteArray =
        ByteBuffer
            .allocate(4 + 4 + 4 + body.size + trailer.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(size)
            .putInt(id)
            .putInt(type)
            .put(body)
            .put(trailer)
            .array()

    @Test
    fun `the layout is little-endian and the size field excludes itself`() {
        val bytes = RconCodec.encode(RconFrame(id = 1, type = RconCodec.AUTH, body = "pw"))

        // size = id(4) + type(4) + body(2) + two nulls = 12, and the prefix is
        // not counted, so the packet is 16 bytes on the wire.
        bytes.size shouldBe 16
        bytes.toList() shouldBe
            listOf<Byte>(
                12,
                0,
                0,
                0, // size, little-endian
                1,
                0,
                0,
                0, // id
                3,
                0,
                0,
                0, // type: AUTH
                0x70,
                0x77, // "pw"
                0,
                0, // terminator and pad
            )
    }

    @Test
    fun `a frame survives a round trip`() {
        val original = frameOf(id = 7, type = RconCodec.EXEC_COMMAND, body = "save-all flush")
        decoded(RconCodec.encode(original)).frame shouldBe original
    }

    @Test
    fun `an empty body is legal and sits at the minimum size`() {
        val bytes = RconCodec.encode(frameOf(body = ""))
        bytes.size shouldBe 4 + RconCodec.MIN_SIZE_FIELD

        val result = decoded(bytes)
        result.frame.body shouldBe ""
        result.consumed shouldBe bytes.size
    }

    @Test
    fun `the size field counts bytes, not characters`() {
        // "é" is two bytes in UTF-8. A codec that measured the String would
        // under-report by one and desynchronise every following frame.
        val bytes = RconCodec.encode(frameOf(body = "é"))
        bytes.size shouldBe 4 + 4 + 4 + 2 + 2
        decoded(bytes).frame.body shouldBe "é"
    }

    @Test
    fun `the auth failure sentinel decodes as an ordinary id`() {
        // -1 is a legal id on the wire; recognising it as a refusal is the
        // connection's job, not the codec's.
        val refusal = RconFrame(RconCodec.AUTH_FAILURE_ID, RconCodec.AUTH_RESPONSE, "")
        decoded(RconCodec.encode(refusal)).frame.id shouldBe -1
    }

    @Test
    fun `consumed is the offset the next frame starts at`() {
        val first = frameOf(id = 1, body = "list")
        val second = frameOf(id = 2, body = "tps")
        val stream = RconCodec.encode(first) + RconCodec.encode(second)

        val one = decoded(stream)
        one.frame shouldBe first

        val two = RconCodec.decode(stream, one.consumed).shouldBeInstanceOf<RconDecoding.Decoded>()
        two.frame shouldBe second
        one.consumed + two.consumed shouldBe stream.size
    }

    @Test
    fun `a buffer too short to hold the length prefix is incomplete, not malformed`() {
        listOf(0, 1, 2, 3).forEach { length ->
            RconCodec.decode(ByteArray(length)) shouldBe RconDecoding.Incomplete
        }
    }

    @Test
    fun `a frame whose body has not arrived yet is incomplete`() {
        val whole = RconCodec.encode(frameOf(body = "save-all flush"))
        // Every prefix short of the whole thing is a wait, not a failure.
        (4 until whole.size).forEach { cut ->
            RconCodec.decode(whole.copyOfRange(0, cut)) shouldBe RconDecoding.Incomplete
        }
        decoded(whole).consumed shouldBe whole.size
    }

    @Test
    fun `a size field below the minimum is malformed`() {
        listOf(9, 0, -1, Int.MIN_VALUE).forEach { size ->
            RconCodec
                .decode(handBuilt(size = size))
                .shouldBeInstanceOf<RconDecoding.Malformed>()
                .reason shouldContain "minimum"
        }
    }

    @Test
    fun `an oversized length prefix is refused before anything is allocated for it`() {
        // The prefix arrives before the body, so an unbounded one is an
        // allocation the far side chooses. Int.MAX_VALUE must not be waited for.
        listOf(RconCodec.MAX_INBOUND_BYTES - 3, Int.MAX_VALUE).forEach { size ->
            RconCodec
                .decode(handBuilt(size = size))
                .shouldBeInstanceOf<RconDecoding.Malformed>()
                .reason shouldContain "cap"
        }
    }

    @Test
    fun `the largest legal frame is accepted, so the cap is a boundary and not an off-by-one`() {
        // Control for the test above: one byte under the cap decodes.
        val bodyBytes = RconCodec.MAX_INBOUND_BYTES - 4 - RconCodec.MIN_SIZE_FIELD
        val body = "x".repeat(bodyBytes)
        val bytes =
            handBuilt(
                size = RconCodec.MAX_INBOUND_BYTES - 4,
                body = body.toByteArray(Charsets.UTF_8),
            )
        bytes.size shouldBe RconCodec.MAX_INBOUND_BYTES
        decoded(bytes).frame.body shouldBe body
    }

    @Test
    fun `a frame that is not null-terminated is malformed`() {
        listOf(byteArrayOf(0, 1), byteArrayOf(1, 0), byteArrayOf(1, 1)).forEach { trailer ->
            RconCodec
                .decode(handBuilt(size = RconCodec.MIN_SIZE_FIELD, trailer = trailer))
                .shouldBeInstanceOf<RconDecoding.Malformed>()
                .reason shouldContain "null"
        }
    }

    @Test
    fun `encoding refuses an oversized body rather than truncating it`() {
        // A truncated console command is a different console command.
        val tooLong = "x".repeat(RconCodec.MAX_OUTBOUND_BODY_BYTES + 1)
        shouldThrow<IllegalArgumentException> { RconCodec.encode(frameOf(body = tooLong)) }
            .message
            .toString() shouldContain "over the"

        // Control: exactly at the limit encodes, so the refusal is a boundary.
        val atLimit = "x".repeat(RconCodec.MAX_OUTBOUND_BODY_BYTES)
        decoded(RconCodec.encode(frameOf(body = atLimit))).frame.body shouldBe atLimit
    }

    @Test
    fun `a frame never prints its own body`() {
        // An AUTH frame's body is the RCON password, and a command's body can
        // carry a player name. Neither may reach a log line.
        val rendered = RconFrame(1, RconCodec.AUTH, "hunter2-the-password").toString()
        rendered shouldNotContain "hunter2"
        rendered shouldContain "redacted"

        // Control: the fields that are safe to print are still printed.
        rendered shouldContain "id=1"
    }

    @Test
    fun `decoding outside the buffer is a caller error, not a silent empty read`() {
        shouldThrow<IllegalArgumentException> { RconCodec.decode(ByteArray(4), offset = 5) }
        shouldThrow<IllegalArgumentException> { RconCodec.decode(ByteArray(4), offset = -1) }
    }
}
