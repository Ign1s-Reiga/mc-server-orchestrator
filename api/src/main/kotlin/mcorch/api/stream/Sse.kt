package mcorch.api.stream

import com.sun.net.httpserver.HttpExchange
import mcorch.api.http.HeaderNames
import mcorch.api.http.MediaTypes
import mcorch.api.json.Json
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

/**
 * One server-sent-events connection.
 *
 * ## Why SSE and not a WebSocket
 *
 * The dashboard needs server-to-client push and nothing else — every operator
 * action is a request with a status code and a body, which a WebSocket would
 * make worse rather than better. SSE is plain HTTP/1.1, so it inherits the
 * cookie, the CORS decision and the reverse proxy configuration already in
 * place; `EventSource` reconnects on its own and replays `Last-Event-ID` without
 * a line of client code; and there is no frame protocol to implement here. The
 * one thing a WebSocket would have bought — sending a header on connect — is
 * exactly what SSE cannot do either, which is why the session cookie exists.
 *
 * ## Backpressure
 *
 * There is no queue. Not a bounded one, not a coalescing one: none. The
 * connection's own loop pulls current state from the store and writes it
 * synchronously, so a client that stops reading blocks its own loop at the
 * socket, and the loop stops pulling. Memory held for a stalled client is one
 * snapshot plus a socket buffer, and it does not grow with time.
 *
 * That also gets coalescing for free, which a queue would have had to implement.
 * A client thirty seconds behind does not receive thirty seconds of history when
 * it drains: the next poll reads whatever is true *now* and sends one update per
 * server, because the loop only ever sends what a fresh read says. Intermediate
 * states are dropped, deliberately — a dashboard wants the current value, not
 * the path taken to it.
 *
 * Two bounds on top of that: [StreamRegistry] caps how many connections can
 * exist at once, and each one is closed after
 * [mcorch.api.ApiConfig.maxStreamLifetime] so a forgotten tab cannot hold a slot
 * for ever. The browser reconnects with `Last-Event-ID` and carries on, which
 * means the resume path is exercised in normal operation rather than only after
 * a failure.
 */
internal class SseConnection(
    private val exchange: HttpExchange,
    private val registry: StreamRegistry,
) : AutoCloseable {
    private val output: OutputStream = exchange.responseBody

    @Volatile
    private var closed = false

    /** Writes the response head. Must be called before anything else. */
    fun begin(retry: Duration) {
        val headers = exchange.responseHeaders
        headers.set(HeaderNames.CONTENT_TYPE, MediaTypes.EVENT_STREAM)
        headers.set("Cache-Control", "no-cache, no-transform")
        headers.set("Connection", "keep-alive")
        // nginx buffers proxied responses by default, which turns an event stream
        // into a very slow batch job. This is the documented opt-out.
        headers.set("X-Accel-Buffering", "no")
        // 0 means "chunked, length unknown", which is what a stream is.
        exchange.sendResponseHeaders(200, 0L)
        write("retry: ${retry.inWholeMilliseconds}\n\n")
    }

    /**
     * Sends one event.
     *
     * [id] goes on every event rather than only on the ones that move the cursor,
     * so a reconnect resumes from the right place whichever event happened to be
     * last. `data` is JSON, which never contains a raw newline once escaped, so
     * the single `data:` line below is sufficient — but it is split defensively
     * anyway, because an event framing bug is silent and looks like data loss.
     */
    fun event(
        name: String,
        id: String?,
        data: Json,
    ) {
        val payload = data.render()
        val frame =
            buildString(payload.length + 64) {
                if (id != null) append("id: ").append(id.replace('\n', ' ').replace('\r', ' ')).append('\n')
                append("event: ").append(name).append('\n')
                for (line in payload.split('\n')) {
                    append("data: ").append(line).append('\n')
                }
                append('\n')
            }
        write(frame)
    }

    private fun write(text: String) {
        if (closed) throw StreamClosed()
        try {
            output.write(text.toByteArray(StandardCharsets.UTF_8))
            output.flush()
        } catch (failure: IOException) {
            // The client went away, or the registry closed this connection during
            // shutdown. Neither is a server fault; both mean stop.
            closed = true
            throw StreamClosed(failure)
        }
    }

    val open: Boolean get() = !closed && !registry.stopping

    override fun close() {
        closed = true
        try {
            exchange.close()
        } catch (_: IOException) {
            // Already gone.
        }
    }
}

/** Thrown when a stream cannot continue. Always caught by the stream loop; never a 500. */
internal class StreamClosed(
    cause: Throwable? = null,
) : RuntimeException("the event stream is closed", cause)

/**
 * How many streams are open, and how they are all stopped at once.
 *
 * The cap is a real resource bound rather than a formality: every open stream
 * polls the store on a timer, so N streams are N pollers, and an unbounded
 * number of them turns a dashboard left open on a wall display into load. When
 * the cap is reached a new stream is refused with a retryable 503 rather than
 * accepted and starved.
 */
internal class StreamRegistry(
    private val limit: Int,
) {
    private val open = ConcurrentHashMap<SseConnection, Unit>()
    private val count = AtomicInteger(0)

    @Volatile
    var stopping: Boolean = false
        private set

    /** Reserves a slot, or returns false when [limit] streams are already open. */
    fun tryAcquire(): Boolean {
        if (stopping) return false
        while (true) {
            val current = count.get()
            if (current >= limit) return false
            if (count.compareAndSet(current, current + 1)) return true
        }
    }

    fun register(connection: SseConnection) {
        open[connection] = Unit
    }

    /** Releases the slot [tryAcquire] reserved. Exactly once per successful acquire. */
    fun release(connection: SseConnection?) {
        connection?.let { open.remove(it) }
        count.decrementAndGet()
    }

    fun size(): Int = count.get()

    /**
     * Refuses new streams and closes every open one.
     *
     * Closing the exchange is what unblocks a loop parked in a socket write to a
     * client that stopped reading — the alternative is waiting out a shutdown
     * grace period for every stalled connection.
     */
    fun shutdown() {
        stopping = true
        open.keys.forEach { runCatching { it.close() } }
        open.clear()
    }
}
