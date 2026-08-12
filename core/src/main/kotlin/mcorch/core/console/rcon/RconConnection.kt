package mcorch.core.console.rcon

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

/**
 * One authenticated RCON session against one server.
 *
 * Takes streams rather than a socket so the protocol above is testable without a
 * server — [connect] is the only part that opens one. Everything below the
 * streams is [RconCodec]; everything above them is the caller's.
 *
 * ## Serial by construction
 *
 * `spec/05-concurrency.md` requires commands to one server to be **strictly
 * serial**, because RCON dispatches onto the game's main thread and concurrent
 * commands only queue on the tick loop where the caller cannot see them. A
 * [Mutex] here makes that structural rather than a rule callers have to
 * remember: parallelism belongs *across* servers, which is a different
 * connection each time.
 *
 * It is also required by the wire. Replies are matched to requests by id, and
 * two commands in flight on one socket would interleave their response chunks
 * with nothing but the id to separate them.
 *
 * ## Reassembly is heuristic, and unverified
 *
 * A reply longer than one packet arrives as several, and the protocol carries no
 * "last chunk" flag. This reads chunks until one arrives that is not full, which
 * is what every RCON client does and what the 4096-byte cap makes *usually*
 * true. A reply whose length is an exact multiple of the chunk size would leave
 * this waiting for a chunk that never comes — bounded by the read timeout, so it
 * degrades to a timeout rather than a hang.
 *
 * **This has not been verified against a real Minecraft server**, in the same way
 * and for the same reason as `PaperServerAgent`'s reply patterns. It is confined
 * to [readReply] so an integration test can correct it in one place.
 *
 * ## What never appears in a log line
 *
 * The address, the password, and command bodies. [RconFrame] redacts its own
 * body, and nothing here logs a frame, an address or a command. Failures name
 * the port, which is declared configuration.
 */
internal class RconConnection(
    private val input: InputStream,
    private val output: OutputStream,
    private val readTimeout: Duration,
    private val closer: AutoCloseable? = null,
) : AutoCloseable {
    private val lock = Mutex()
    private val ids = AtomicInteger(1)
    private var buffer = ByteArray(0)

    /**
     * Authenticates, or throws.
     *
     * [password] is held for exactly as long as it takes to build one frame. The
     * caller resolves it at use time and destroys it afterwards — `SecretStore`
     * states that contract and `LocalNode.resolveToken` already follows it for
     * the Velocity control token.
     *
     * @throws RconException.AuthFailed when the server rejects it. Permanent:
     *   retrying the same material cannot succeed.
     */
    suspend fun authenticate(password: String) {
        lock.withLock {
            val id = ids.getAndIncrement()
            write(RconFrame(id, RconCodec.AUTH, password))
            while (true) {
                val frame = readFrame()
                // Some servers precede the verdict with an empty RESPONSE_VALUE.
                // It carries no verdict, so it is skipped rather than believed.
                if (frame.type == RconCodec.RESPONSE_VALUE) continue
                if (frame.type != RconCodec.AUTH_RESPONSE) {
                    throw RconException.Protocol("expected an auth response, got type ${frame.type}")
                }
                // Checked before the id match, not after: the sentinel is not a
                // reply to request -1, and matching first would read a refusal as
                // an answer to something nobody sent.
                if (frame.id == RconCodec.AUTH_FAILURE_ID) throw RconException.AuthFailed
                if (frame.id != id) throw RconException.Protocol("auth reply carried id ${frame.id}, expected $id")
                return
            }
        }
    }

    /**
     * Runs one command and returns the server's reply, reassembled.
     *
     * Serialised against every other call on this connection; a caller that
     * needs concurrency needs another server, not another coroutine.
     */
    suspend fun execute(command: String): String =
        lock.withLock {
            val id = ids.getAndIncrement()
            write(RconFrame(id, RconCodec.EXEC_COMMAND, command))
            readReply(id)
        }

    /**
     * Accumulates response chunks for [id] until one arrives that is not full.
     *
     * See the class note: this is the heuristic every RCON client uses and it is
     * unverified here.
     */
    private suspend fun readReply(id: Int): String {
        val reply = StringBuilder()
        while (true) {
            val frame = readFrame()
            if (frame.type != RconCodec.RESPONSE_VALUE) {
                throw RconException.Protocol("expected a command reply, got type ${frame.type}")
            }
            if (frame.id != id) {
                throw RconException.Protocol("reply carried id ${frame.id}, expected $id")
            }
            reply.append(frame.body)
            if (frame.body.toByteArray(Charsets.UTF_8).size < MAX_CHUNK_BODY_BYTES) return reply.toString()
        }
    }

    private suspend fun write(frame: RconFrame) {
        val bytes =
            try {
                RconCodec.encode(frame)
            } catch (tooLong: IllegalArgumentException) {
                throw RconException.Protocol(tooLong.message ?: "command is too long for one frame", tooLong)
            }
        withContext(Dispatchers.IO) {
            try {
                output.write(bytes)
                output.flush()
            } catch (failure: IOException) {
                throw RconException.Transport("the connection dropped while sending", failure)
            }
        }
    }

    /**
     * Reads until [buffer] yields one whole frame.
     *
     * The buffer is recopied on each consume, which is O(n²) in frames held at
     * once — bounded at one, because a frame is at most 4 KiB and this consumes
     * each before asking for more.
     */
    private suspend fun readFrame(): RconFrame {
        while (true) {
            when (val decoding = RconCodec.decode(buffer)) {
                is RconDecoding.Decoded -> {
                    buffer = buffer.copyOfRange(decoding.consumed, buffer.size)
                    return decoding.frame
                }

                // Not resynchronisable: a length-prefixed stream offers nothing to
                // resynchronise to, so the connection is finished.
                is RconDecoding.Malformed -> {
                    throw RconException.Protocol(decoding.reason)
                }

                RconDecoding.Incomplete -> {
                    buffer += readMore()
                }
            }
        }
    }

    private suspend fun readMore(): ByteArray =
        withContext(Dispatchers.IO) {
            val chunk = ByteArray(RconCodec.MAX_INBOUND_BYTES)
            val read =
                try {
                    input.read(chunk)
                } catch (timeout: SocketTimeoutException) {
                    throw RconException.Timeout(
                        "the server did not answer within ${readTimeout.inWholeSeconds}s",
                        timeout,
                    )
                } catch (failure: IOException) {
                    throw RconException.Transport("the connection dropped while reading", failure)
                }
            // A half-open socket reports end-of-stream rather than failing, and a
            // frame that can never arrive is not something to keep waiting for.
            if (read < 0) throw RconException.Transport("the server closed the connection", null)
            chunk.copyOf(read)
        }

    override fun close() {
        closer?.close()
    }

    companion object {
        /**
         * The largest body a full response chunk carries: the 4 KiB frame cap
         * less the length prefix and the frame's own fields. A chunk under this
         * is the last one — see the class note on reassembly.
         */
        const val MAX_CHUNK_BODY_BYTES: Int = RconCodec.MAX_INBOUND_BYTES - 4 - RconCodec.MIN_SIZE_FIELD

        /**
         * Opens a socket to [address] and wraps it.
         *
         * [address] comes from the `Node` handle — never resolved by the caller,
         * which is what invariant 7 requires and what `Node.callEndpoint`'s own
         * note explains. It is never logged.
         *
         * [readTimeout] becomes `SO_TIMEOUT`, so it bounds each read rather than
         * the call as a whole. That is also what makes cancellation effective: a
         * blocked socket read does not observe coroutine cancellation, so the
         * timeout is what returns control between reads.
         */
        suspend fun connect(
            address: String,
            port: Int,
            connectTimeout: Duration,
            readTimeout: Duration,
        ): RconConnection {
            require(connectTimeout.isPositive() && connectTimeout.isFinite()) {
                "connect timeout must be positive and finite, was $connectTimeout"
            }
            require(readTimeout.isPositive() && readTimeout.isFinite()) {
                "read timeout must be positive and finite, was $readTimeout"
            }
            return withContext(Dispatchers.IO) {
                val socket = Socket()
                try {
                    socket.connect(InetSocketAddress(address, port), connectTimeout.inWholeMilliseconds.toInt())
                    socket.soTimeout = readTimeout.inWholeMilliseconds.toInt()
                    socket.tcpNoDelay = true
                    RconConnection(socket.inputStream, socket.outputStream, readTimeout, socket)
                } catch (failure: IOException) {
                    socket.runCatching { close() }
                    throw RconException.Transport("port $port refused or dropped the connection", failure)
                }
            }
        }
    }
}

/** Why an RCON session failed. Never carries a password, an address or a command body. */
internal sealed class RconException(
    message: String,
    cause: Throwable?,
) : Exception(message, cause) {
    /**
     * The server rejected the password.
     *
     * Permanent rather than retryable: the same material cannot start working.
     * It is indistinguishable on the wire from a password that was correct and
     * has since been rotated, which is the same conflation `DrainTest` records
     * for the wrong-RCON-password case.
     */
    object AuthFailed : RconException("the server rejected the RCON password", null) {
        private fun readResolve(): Any = AuthFailed
    }

    /** The peer is not speaking RCON, or not speaking it in an order this can follow. */
    class Protocol(
        reason: String,
        cause: Throwable? = null,
    ) : RconException(reason, cause)

    /** A read ran out of time. Retryable: the main thread may simply be busy. */
    class Timeout(
        reason: String,
        cause: Throwable?,
    ) : RconException(reason, cause)

    /** The socket failed. Retryable; says nothing about whether the command ran. */
    class Transport(
        reason: String,
        cause: Throwable?,
    ) : RconException(reason, cause)
}
