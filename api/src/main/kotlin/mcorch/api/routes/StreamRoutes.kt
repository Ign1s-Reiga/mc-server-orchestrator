package mcorch.api.routes

import com.sun.net.httpserver.HttpExchange
import kotlinx.coroutines.delay
import mcorch.api.ApiConfig
import mcorch.api.http.Access
import mcorch.api.http.ApiException
import mcorch.api.http.ErrorCode
import mcorch.api.http.HandlerResult
import mcorch.api.http.HeaderNames
import mcorch.api.http.Request
import mcorch.api.http.Route
import mcorch.api.json.jsonObject
import mcorch.api.render.ServerJson
import mcorch.api.stream.SseConnection
import mcorch.api.stream.StreamClosed
import mcorch.api.stream.StreamRegistry
import mcorch.schema.ResourceName
import mcorch.store.ChangeFeed
import mcorch.store.ResourceVersion
import mcorch.store.Store
import mcorch.store.StoreCursor
import mcorch.store.StoreException
import mcorch.store.StoredServer
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The live feed the dashboard follows instead of polling a list endpoint.
 *
 * ## Two cadences, because the store has two kinds of state
 *
 * [Store.changesSince] is a feed of *desired-state* changes and says so: the
 * reconcile loop is the only writer of observed state, so feeding observations
 * back through it would be a self-loop. That leaves this module to cover the
 * other half itself, and it does so with two timers on one loop:
 *
 * - every [ApiConfig.changePollInterval] — pull the change feed. This is the
 *   low-latency path: an operator's edit shows up in another tab in well under a
 *   second.
 * - every [ApiConfig.statusPollInterval] — re-read [Store.listServers] and
 *   compare observed-state versions. Slower, because a status moves on the
 *   loop's cadence and nobody is waiting on a keystroke, and it doubles as the
 *   repair path: anything the feed lost is picked up here.
 *
 * Both funnel through the same emit, which drops anything whose definition and
 * status versions are unchanged. So the feed and the resync cannot produce a
 * duplicate between them, and `ChangeFeed.Expired` costs one snapshot rather
 * than a resynchronisation protocol.
 *
 * ## The protocol
 *
 * Five event types, and a client that handles `snapshot`, `updated` and
 * `removed` is already correct:
 *
 * - `hello` — the connection's parameters. Always first.
 * - `snapshot` — every server, plus the cursor. Sent when the client did not
 *   resume, and again after an `expired`. A client that opens the stream with no
 *   cursor therefore needs no separate list call, and there is no window between
 *   listing and subscribing in which a change can be missed.
 * - `updated` — one server resource. Replace by name. Sent for a definition
 *   change and for an observed-state change alike; `reason` says which, for
 *   humans reading a network tab rather than for branching on.
 * - `removed` — `{name}`. The definition is gone: `:core` finished the drain and
 *   purged it. Delete by name.
 * - `expired` — the cursor is older than the store remembers. A `snapshot`
 *   follows immediately; a client that ignores this event still converges,
 *   because the snapshot re-states everything.
 * - `bye` — the connection reached [ApiConfig.maxStreamLifetime]. `EventSource`
 *   reconnects on its own with `Last-Event-ID`.
 *
 * Every event carries `id:` set to the current cursor, so a browser reconnect
 * resumes correctly whichever event arrived last.
 */
internal class StreamRoutes(
    private val store: Store,
    private val config: ApiConfig,
    private val registry: StreamRegistry,
) {
    fun routes(): List<Route> =
        listOf(
            Route("GET", STREAM, Access.OPERATOR) { request, exchange -> stream(request, exchange) },
        )

    private suspend fun stream(
        request: Request,
        exchange: HttpExchange,
    ): HandlerResult {
        if (!registry.tryAcquire()) {
            throw ApiException(
                ErrorCode.STREAM_LIMIT,
                "${config.maxStreams} event streams are already open. Close one, or retry",
                headers = listOf("Retry-After" to "5"),
            )
        }
        var connection: SseConnection? = null
        try {
            connection = SseConnection(exchange, registry)
            registry.register(connection)
            connection.begin(retry = RECONNECT_DELAY)
            run(connection, resumeCursor(request))
        } catch (_: StreamClosed) {
            // Ordinary: the client navigated away, or shutdown closed us.
        } catch (failure: StoreException) {
            LOG.warn("event stream stopped: the store failed retryable={}", failure.retryable, failure)
        } finally {
            registry.release(connection)
            connection?.close()
        }
        return HandlerResult.Handled
    }

    /**
     * `?cursor=` wins over `Last-Event-ID`, so a client can force a full snapshot
     * by reconnecting without one even though the browser would have replayed the
     * header. Null means "no resume": send a snapshot.
     */
    private fun resumeCursor(request: Request): StoreCursor? {
        val explicit = request.queryValue("cursor")?.trim()
        if (explicit == "") return null
        val raw = explicit ?: request.header(HeaderNames.LAST_EVENT_ID)?.trim()
        return raw?.takeIf { it.isNotEmpty() }?.let(::StoreCursor)
    }

    private suspend fun run(
        connection: SseConnection,
        resume: StoreCursor?,
    ) {
        val seen = HashMap<ResourceName, Versions>()
        var cursor = resume ?: store.currentCursor()

        connection.event(
            "hello",
            cursor.token,
            jsonObject {
                put("cursor", cursor.token)
                put("resumed", resume != null)
                put("changePollMillis", config.changePollInterval.inWholeMilliseconds)
                put("statusPollMillis", config.statusPollInterval.inWholeMilliseconds)
                put("keepAliveMillis", config.streamKeepAlive.inWholeMilliseconds)
                put("maxLifetimeMillis", config.maxStreamLifetime.inWholeMilliseconds)
            },
        )
        if (resume == null) {
            cursor = snapshot(connection, seen)
        }

        val started = TimeSource.Monotonic.markNow()
        var lastStatusPoll = started
        var lastKeepAlive = started
        while (connection.open) {
            if (started.elapsedNow() >= config.maxStreamLifetime) {
                connection.event(
                    "bye",
                    cursor.token,
                    jsonObject {
                        put("reason", "MAX_LIFETIME")
                        put("cursor", cursor.token)
                    },
                )
                return
            }

            cursor = pullChanges(connection, cursor, seen)

            if (lastStatusPoll.elapsedNow() >= config.statusPollInterval) {
                lastStatusPoll = TimeSource.Monotonic.markNow()
                resync(connection, cursor, seen)
            }
            if (lastKeepAlive.elapsedNow() >= config.streamKeepAlive) {
                lastKeepAlive = TimeSource.Monotonic.markNow()
                connection.keepAlive()
            }
            delay(config.changePollInterval)
        }
    }

    /** Everything, as one event, and the cursor it is consistent with. */
    private suspend fun snapshot(
        connection: SseConnection,
        seen: MutableMap<ResourceName, Versions>,
    ): StoreCursor {
        // Cursor first, then the read — the same ordering as the list endpoint and
        // for the same reason: overlap is absorbed by the client, a gap is not.
        val cursor = store.currentCursor()
        val servers = store.listServers().sortedBy { it.name.value }
        seen.clear()
        servers.forEach { seen[it.name] = Versions.of(it) }
        connection.event(
            "snapshot",
            cursor.token,
            jsonObject {
                put("cursor", cursor.token)
                put("count", servers.size)
                putArray("items", servers, ServerJson::server)
            },
        )
        return cursor
    }

    private suspend fun pullChanges(
        connection: SseConnection,
        cursor: StoreCursor,
        seen: MutableMap<ResourceName, Versions>,
    ): StoreCursor =
        when (val feed = store.changesSince(cursor)) {
            is ChangeFeed.Expired -> {
                LOG.info("event stream cursor expired; re-sending a snapshot")
                connection.event(
                    "expired",
                    feed.cursor.token,
                    jsonObject {
                        put("cursor", feed.cursor.token)
                        put("message", "the cursor is older than the store remembers; a snapshot follows")
                    },
                )
                snapshot(connection, seen)
            }

            is ChangeFeed.Changes -> {
                // The names, de-duplicated: a server written three times in one
                // interval is one read and one event, not three.
                for (name in feed.changes.map { it.name }.distinct()) {
                    emit(connection, feed.cursor, name, "definition", seen)
                }
                feed.cursor
            }
        }

    /**
     * Re-reads everything and emits what moved.
     *
     * This is where observed-state changes come from, and where anything the
     * change feed dropped is recovered. It is also the only thing that can notice
     * a purge the feed did not carry, so `removed` is derived from absence here
     * rather than from a `PURGED` entry alone.
     */
    private suspend fun resync(
        connection: SseConnection,
        cursor: StoreCursor,
        seen: MutableMap<ResourceName, Versions>,
    ) {
        val servers = store.listServers()
        val present = HashSet<ResourceName>(servers.size)
        for (server in servers) {
            present += server.name
            send(connection, cursor, server, "status", seen)
        }
        val gone = seen.keys.filter { it !in present }
        for (name in gone) {
            seen.remove(name)
            connection.event(
                "removed",
                cursor.token,
                jsonObject {
                    put("name", name.value)
                    put("reason", "PURGED")
                },
            )
        }
    }

    private suspend fun emit(
        connection: SseConnection,
        cursor: StoreCursor,
        name: ResourceName,
        reason: String,
        seen: MutableMap<ResourceName, Versions>,
    ) {
        val server = store.getServer(name)
        if (server == null) {
            if (seen.remove(name) != null) {
                connection.event(
                    "removed",
                    cursor.token,
                    jsonObject {
                        put("name", name.value)
                        put("reason", "PURGED")
                    },
                )
            }
            return
        }
        send(connection, cursor, server, reason, seen)
    }

    /** Sends [server] unless its versions are exactly what this connection last sent. */
    private fun send(
        connection: SseConnection,
        cursor: StoreCursor,
        server: StoredServer,
        reason: String,
        seen: MutableMap<ResourceName, Versions>,
    ) {
        val versions = Versions.of(server)
        if (seen[server.name] == versions) return
        seen[server.name] = versions
        connection.event(
            "updated",
            cursor.token,
            jsonObject {
                put("name", server.name.value)
                put("reason", reason)
                put("server", ServerJson.server(server))
            },
        )
    }

    /** What was last sent for one server. Equality is the whole de-duplication rule. */
    private data class Versions(
        val definition: ResourceVersion,
        val status: ResourceVersion?,
    ) {
        companion object {
            fun of(server: StoredServer): Versions =
                Versions(server.definition.resourceVersion, server.status?.resourceVersion)
        }
    }

    companion object {
        const val STREAM: String = "/api/v1/stream"

        /** What `EventSource` waits before reconnecting. Sent as the `retry:` field. */
        private val RECONNECT_DELAY: Duration = 3.seconds

        private val LOG = LoggerFactory.getLogger(StreamRoutes::class.java)
    }
}
