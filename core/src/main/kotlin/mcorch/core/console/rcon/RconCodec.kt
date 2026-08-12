package mcorch.core.console.rcon

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * The Source RCON wire format, encode and decode.
 *
 * RCON is not HTTP and not WebSocket-based, whatever the shape of the dashboard
 * in front of it: it is a length-prefixed binary protocol over plain TCP, with
 * the password sent in cleartext in the first packet. That last property is why
 * `PaperWorkload` keeps the port off the host and why the console channel dials
 * the sandbox's CNI address instead — see `spec/02-relay.md` §2.
 *
 * ## The frame
 *
 * ```
 * ┌────────────┬────────────┬────────────┬───────────────┬──────┬──────┐
 * │ size  i32  │ id    i32  │ type  i32  │ body          │ 0x00 │ 0x00 │
 * │ little-end │ little-end │ little-end │ UTF-8         │ term │ pad  │
 * └────────────┴────────────┴────────────┴───────────────┴──────┴──────┘
 *   not counted   ├─────────────── size counts these ──────────────────┤
 * ```
 *
 * `size` excludes itself, so the minimum legal value is 10: two `i32` fields
 * plus the two trailing nulls, with an empty body.
 *
 * ## The type field is overloaded by direction
 *
 * `2` means [EXEC_COMMAND] travelling to the server and [AUTH_RESPONSE] coming
 * back. Nothing in the frame says which, so the number alone is not enough to
 * interpret a packet — the reader has to know which way it is going. This codec
 * therefore does not model the type as an enum: it carries the raw value, and
 * the connection above it knows the direction because it knows what it sent.
 *
 * ## What this deliberately does not do
 *
 * No I/O, no connection state, no auth handshake, no reassembly of a reply split
 * across packets. Those belong to the connection; this is the frame and nothing
 * else, so that every bound and every malformed-input decision below is testable
 * without a server.
 */
internal object RconCodec {
    /** Client → server. Carries the password, in cleartext, as the body. */
    const val AUTH: Int = 3

    /** Client → server. Carries the console command as the body. */
    const val EXEC_COMMAND: Int = 2

    /** Server → client. Same wire value as [EXEC_COMMAND]; see the class note. */
    const val AUTH_RESPONSE: Int = 2

    /** Server → client. The reply to a command, possibly one of several. */
    const val RESPONSE_VALUE: Int = 0

    /**
     * The id a server returns in an [AUTH_RESPONSE] to refuse the password.
     *
     * It is a sentinel rather than an error field, so a client that matches
     * replies to requests by id — as it must, to survive out-of-order replies —
     * has to special-case it before the match, or a refusal looks like a reply to
     * a request nobody sent.
     */
    const val AUTH_FAILURE_ID: Int = -1

    /** `size` counts two `i32` fields and two nulls, so it is never below this. */
    const val MIN_SIZE_FIELD: Int = 10

    /**
     * The largest frame accepted from a server, `size` field included.
     *
     * Source caps a single response packet here and splits longer replies across
     * several. The cap is enforced rather than trusted: the length prefix arrives
     * before the body, so an unbounded one is an allocation a remote party
     * chooses for us.
     */
    const val MAX_INBOUND_BYTES: Int = 4096

    /**
     * The largest body sent to a server.
     *
     * Source's documented request limit. A command over it is rejected here
     * rather than truncated, because a truncated console command is a different
     * command.
     */
    const val MAX_OUTBOUND_BODY_BYTES: Int = 1446

    private const val HEADER_BYTES = 4 + 4 + 4
    private const val TRAILER_BYTES = 2

    /**
     * Serialises [frame] into one packet.
     *
     * @throws IllegalArgumentException when the body exceeds
     *   [MAX_OUTBOUND_BODY_BYTES]. Callers size their input; nothing here
     *   silently shortens a command.
     */
    fun encode(frame: RconFrame): ByteArray {
        val body = frame.body.toByteArray(StandardCharsets.UTF_8)
        require(body.size <= MAX_OUTBOUND_BODY_BYTES) {
            "rcon body is ${body.size} bytes, over the $MAX_OUTBOUND_BODY_BYTES-byte limit"
        }
        val size = 4 + 4 + body.size + TRAILER_BYTES
        return ByteBuffer
            .allocate(4 + size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(size)
            .putInt(frame.id)
            .putInt(frame.type)
            .put(body)
            .put(0)
            .put(0)
            .array()
    }

    /**
     * Reads one frame from [bytes] starting at [offset].
     *
     * Returns [RconDecoding.Incomplete] when the buffer does not yet hold a whole
     * frame — the normal case on a stream socket, and not an error. Every other
     * disagreement with the wire format is [RconDecoding.Malformed]: a frame is
     * either read exactly or not at all, because a partially-trusted frame from a
     * remote party is worth less than no frame.
     */
    fun decode(
        bytes: ByteArray,
        offset: Int = 0,
    ): RconDecoding {
        require(offset >= 0 && offset <= bytes.size) { "offset $offset is outside a ${bytes.size}-byte buffer" }
        val available = bytes.size - offset
        if (available < 4) return RconDecoding.Incomplete

        val buffer = ByteBuffer.wrap(bytes, offset, available).order(ByteOrder.LITTLE_ENDIAN)
        val size = buffer.int
        if (size < MIN_SIZE_FIELD) {
            return RconDecoding.Malformed("size field $size is below the $MIN_SIZE_FIELD-byte minimum")
        }
        // Rearranged, not written as `size + 4 > MAX_INBOUND_BYTES`: `size` is an
        // untrusted i32 read straight off the wire, and Int.MAX_VALUE + 4
        // overflows negative — which passes that comparison, falls through to the
        // "wait for more bytes" branch below, and leaves the connection blocked
        // forever on a frame that can never arrive. Both operands here are
        // constants, so there is nothing to overflow.
        if (size > MAX_INBOUND_BYTES - 4) {
            return RconDecoding.Malformed("size field $size exceeds the $MAX_INBOUND_BYTES-byte frame cap")
        }
        // Only now, with size bounded, is it safe to wait for the rest.
        if (available - 4 < size) return RconDecoding.Incomplete

        val id = buffer.int
        val type = buffer.int
        val bodyBytes = size - MIN_SIZE_FIELD
        val body = String(bytes, offset + HEADER_BYTES, bodyBytes, StandardCharsets.UTF_8)

        val terminator = offset + HEADER_BYTES + bodyBytes
        if (bytes[terminator] != 0.toByte() || bytes[terminator + 1] != 0.toByte()) {
            return RconDecoding.Malformed("frame is not terminated by two null bytes")
        }
        return RconDecoding.Decoded(RconFrame(id, type, body), consumed = 4 + size)
    }
}

/**
 * One RCON packet.
 *
 * [type] is the raw wire value because it is ambiguous without a direction —
 * see [RconCodec]. [id] is chosen by the client and echoed by the server, which
 * is the only way to match a reply to a request.
 */
internal data class RconFrame(
    val id: Int,
    val type: Int,
    val body: String,
) {
    /**
     * Redacted, and not because the body is player data — an [RconCodec.AUTH]
     * frame's body *is* the RCON password, and a command's body can carry a
     * player name. Neither belongs in a log line.
     */
    override fun toString(): String = "RconFrame(id=$id, type=$type, body=<${body.length} chars redacted>)"
}

/** The outcome of [RconCodec.decode]. */
internal sealed interface RconDecoding {
    /**
     * A whole frame was read. [consumed] counts the length prefix too, so it is
     * the offset to resume from.
     */
    data class Decoded(
        val frame: RconFrame,
        val consumed: Int,
    ) : RconDecoding

    /** Not enough bytes yet. Read more and try again; this is not an error. */
    data object Incomplete : RconDecoding

    /**
     * The bytes are not a legal frame, and the stream cannot be resynchronised —
     * a length-prefixed protocol offers nothing to resynchronise *to*. The
     * connection is finished.
     */
    data class Malformed(
        val reason: String,
    ) : RconDecoding
}
