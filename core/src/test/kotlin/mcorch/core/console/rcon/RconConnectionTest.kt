package mcorch.core.console.rcon

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.time.Duration.Companion.seconds

/**
 * The session, driven against streams rather than a socket.
 *
 * Server frames are hand-built rather than produced by [RconCodec.encode],
 * because `encode` enforces the *outbound* body limit and a real server's
 * response chunk is nearly three times that. Building them by hand is also what
 * lets the reassembly boundary be tested from both sides.
 */
internal class RconConnectionTest {
    private val timeout = 5.seconds

    /** A server→client frame, without the outbound size limit `encode` applies. */
    private fun serverFrame(
        id: Int,
        type: Int,
        body: String,
    ): ByteArray {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val size = 4 + 4 + bytes.size + 2
        return ByteBuffer
            .allocate(4 + size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(size)
            .putInt(id)
            .putInt(type)
            .put(bytes)
            .put(0)
            .put(0)
            .array()
    }

    private fun sessionOf(vararg fromServer: ByteArray): Pair<RconConnection, ByteArrayOutputStream> {
        val sent = ByteArrayOutputStream()
        val input = ByteArrayInputStream(fromServer.fold(ByteArray(0)) { acc, next -> acc + next })
        return RconConnection(input, sent, timeout) to sent
    }

    /** The frames a connection wrote, decoded back for assertions. */
    private fun framesIn(sent: ByteArrayOutputStream): List<RconFrame> {
        val bytes = sent.toByteArray()
        val frames = mutableListOf<RconFrame>()
        var offset = 0
        while (offset < bytes.size) {
            val decoded = RconCodec.decode(bytes, offset).shouldBeInstanceOf<RconDecoding.Decoded>()
            frames += decoded.frame
            offset += decoded.consumed
        }
        return frames
    }

    @Test
    fun `authenticating sends the password once and accepts the matching reply`() =
        runTest {
            val (session, sent) = sessionOf(serverFrame(1, RconCodec.AUTH_RESPONSE, ""))
            session.authenticate("hunter2")

            val frames = framesIn(sent)
            frames.size shouldBe 1
            frames[0].type shouldBe RconCodec.AUTH
            frames[0].body shouldBe "hunter2"
        }

    @Test
    fun `an empty response value before the verdict is skipped, not believed`() =
        runTest {
            // Some servers precede the auth verdict with an empty RESPONSE_VALUE.
            // It carries no verdict, so it must not be read as one.
            val (session, _) =
                sessionOf(
                    serverFrame(1, RconCodec.RESPONSE_VALUE, ""),
                    serverFrame(1, RconCodec.AUTH_RESPONSE, ""),
                )
            session.authenticate("pw")
        }

    @Test
    fun `the failure sentinel is a refusal, not a reply to request minus one`() =
        runTest {
            val (session, _) = sessionOf(serverFrame(RconCodec.AUTH_FAILURE_ID, RconCodec.AUTH_RESPONSE, ""))
            shouldThrow<RconException.AuthFailed> { session.authenticate("wrong") }
        }

    @Test
    fun `an auth reply carrying somebody else's id is a protocol failure`() =
        runTest {
            val (session, _) = sessionOf(serverFrame(99, RconCodec.AUTH_RESPONSE, ""))
            shouldThrow<RconException.Protocol> { session.authenticate("pw") }
                .message
                .toString() shouldContain "id 99"
        }

    @Test
    fun `a command's reply comes back whole`() =
        runTest {
            val (session, sent) =
                sessionOf(
                    serverFrame(1, RconCodec.AUTH_RESPONSE, ""),
                    serverFrame(2, RconCodec.RESPONSE_VALUE, "There are 3 of a max of 20 players online"),
                )
            session.authenticate("pw")
            session.execute("list") shouldBe "There are 3 of a max of 20 players online"

            val frames = framesIn(sent)
            frames[1].type shouldBe RconCodec.EXEC_COMMAND
            frames[1].body shouldBe "list"
        }

    @Test
    fun `each request gets its own id, so replies can be matched`() =
        runTest {
            val (session, sent) =
                sessionOf(
                    serverFrame(1, RconCodec.AUTH_RESPONSE, ""),
                    serverFrame(2, RconCodec.RESPONSE_VALUE, "a"),
                    serverFrame(3, RconCodec.RESPONSE_VALUE, "b"),
                )
            session.authenticate("pw")
            session.execute("tps") shouldBe "a"
            session.execute("seed") shouldBe "b"

            framesIn(sent).map { it.id } shouldBe listOf(1, 2, 3)
        }

    @Test
    fun `a reply split across chunks is reassembled in order`() =
        runTest {
            val full = "x".repeat(RconConnection.MAX_CHUNK_BODY_BYTES)
            val tail = "done"
            val (session, _) =
                sessionOf(
                    serverFrame(1, RconCodec.AUTH_RESPONSE, ""),
                    serverFrame(2, RconCodec.RESPONSE_VALUE, full),
                    serverFrame(2, RconCodec.RESPONSE_VALUE, tail),
                )
            session.authenticate("pw")
            session.execute("list") shouldBe full + tail
        }

    @Test
    fun `a chunk one byte under full ends the reply, so the boundary is not an off-by-one`() =
        runTest {
            // Control for the test above. A short chunk terminates; anything left
            // unread proves termination happened rather than the stream running out.
            val short = "y".repeat(RconConnection.MAX_CHUNK_BODY_BYTES - 1)
            val (session, _) =
                sessionOf(
                    serverFrame(1, RconCodec.AUTH_RESPONSE, ""),
                    serverFrame(2, RconCodec.RESPONSE_VALUE, short),
                    serverFrame(3, RconCodec.RESPONSE_VALUE, "never read"),
                )
            session.authenticate("pw")
            session.execute("list") shouldBe short
        }

    @Test
    fun `a reply carrying the wrong id is refused rather than returned`() =
        runTest {
            val (session, _) =
                sessionOf(
                    serverFrame(1, RconCodec.AUTH_RESPONSE, ""),
                    serverFrame(77, RconCodec.RESPONSE_VALUE, "somebody else's answer"),
                )
            session.authenticate("pw")
            shouldThrow<RconException.Protocol> { session.execute("list") }
        }

    @Test
    fun `a malformed frame ends the connection rather than being resynchronised`() =
        runTest {
            val (session, _) = sessionOf(byteArrayOf(1, 0, 0, 0, 9, 9, 9, 9))
            shouldThrow<RconException.Protocol> { session.authenticate("pw") }
        }

    @Test
    fun `a server that closes mid-frame is a transport failure, not a silent empty reply`() =
        runTest {
            val truncated = serverFrame(1, RconCodec.AUTH_RESPONSE, "").copyOfRange(0, 6)
            val (session, _) = sessionOf(truncated)
            shouldThrow<RconException.Transport> { session.authenticate("pw") }
                .message
                .toString() shouldContain "closed"
        }

    @Test
    fun `a command too long for one frame is refused before anything is sent`() =
        runTest {
            val (session, sent) = sessionOf(serverFrame(1, RconCodec.AUTH_RESPONSE, ""))
            session.authenticate("pw")
            val before = sent.size()

            shouldThrow<RconException.Protocol> {
                session.execute("x".repeat(RconCodec.MAX_OUTBOUND_BODY_BYTES + 1))
            }
            // Nothing reached the wire, so the server ran no partial command.
            sent.size() shouldBe before
        }

    @Test
    fun `no failure carries the password`() =
        runTest {
            val (session, _) = sessionOf(serverFrame(RconCodec.AUTH_FAILURE_ID, RconCodec.AUTH_RESPONSE, ""))
            val failure = shouldThrow<RconException.AuthFailed> { session.authenticate("hunter2") }
            failure.message.toString() shouldNotContain "hunter2"
            failure.toString() shouldNotContain "hunter2"

            // Control: the message says something, so the assertion above is not
            // passing on an empty string.
            failure.message.toString() shouldContain "rejected"
        }

    @Test
    fun `connect refuses a timeout that would mean forever`() =
        runTest {
            listOf(0.seconds, (-1).seconds).forEach { bad ->
                shouldThrow<IllegalArgumentException> {
                    RconConnection.connect("127.0.0.1", 25575, connectTimeout = bad, readTimeout = timeout)
                }
                shouldThrow<IllegalArgumentException> {
                    RconConnection.connect("127.0.0.1", 25575, connectTimeout = timeout, readTimeout = bad)
                }
            }
        }
}
