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
import mcorch.store.UnreadableServer
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
 * - every [ApiConfig.statusPollInterval] — re-read [Store.listAll] and compare
 *   observed-state versions. Slower, because a status moves on the loop's
 *   cadence and nobody is waiting on a keystroke, and it doubles as the repair
 *   path: anything the feed lost is picked up here, and so is anything the
 *   strict single-row read could not decode.
 *
 * Both reads are the *tolerant* ones. One row that would not decode used to
 * abort the whole listing, which blanked an operator's fleet table at the same
 * instant it silently stopped the reconcile loop queueing work — one bad row
 * taking out both halves of the system at once.
 *
 * Both funnel through the same emit, which drops anything whose definition and
 * status versions are unchanged. So the feed and the resync cannot produce a
 * duplicate between them, and `ChangeFeed.Expired` costs one snapshot rather
 * than a resynchronisation protocol.
 *
 * ## The protocol
 *
 * Seven event types, and a client that handles `snapshot`, `updated` and
 * `removed` is already correct:
 *
 * - `hello` — the connection's parameters. Always first.
 * - `snapshot` — every server, plus the cursor. Sent when the client did not
 *   resume, and again after an `expired`. A client that opens the stream with no
 *   cursor therefore needs no separate list call, and there is no window between
 *   listing and subscribing in which a change can be missed.
 * - `updated` — one server resource. Replace by name. Sent for a definition
 *   change and for an observed-state change alike; `reason` says which, derived
 *   from the version pair rather than from whichever cadence noticed.
 * - `removed` — `{name}`. The definition is gone: `:core` finished the drain and
 *   purged it. Delete by name.
 * - `unreadable` — `{name, part, reason, retryable}`. The store holds this row
 *   and cannot decode its *definition*, so there is no resource to send. It is
 *   emphatically **not** `removed`: the server was declared, it may well have a
 *   container running with players on it, and reporting absence would be a
 *   deletion that never happened.
 * - `ping` — liveness, every [ApiConfig.streamKeepAlive]. See below.
 * - `expired` — the cursor is older than the store remembers. A `snapshot`
 *   follows immediately; a client that ignores this event still converges,
 *   because the snapshot re-states everything.
 * - `bye` — the connection reached [ApiConfig.maxStreamLifetime]. `EventSource`
 *   reconnects on its own with `Last-Event-ID`.
 *
 * Every event carries `id:` set to the current cursor, so a browser reconnect
 * resumes correctly whichever event arrived last.
 *
 * ## Why `ping` is an event and not a comment frame
 *
 * The conventional SSE keep-alive is a comment (`: keep-alive`), and this used to
 * send one. **`EventSource` does not expose comment frames to script**, and on an
 * idle fleet the keep-alive is the only traffic between the opening snapshot and
 * the lifetime cycle half an hour later. A half-open socket — a NAT timeout, a
 * sleeping laptop, a middlebox dropping the connection silently — therefore
 * leaves an `EventSource` client rendering half-hour-old state with
 * `readyState === OPEN`, believing it is live. It has no way to notice.
 *
 * A named event costs the same bytes, keeps the same proxies alive, and is
 * visible to every client, so the client can run a watchdog: no `ping` within
 * about two and a half keep-alive intervals means the connection is dead
 * whatever the browser thinks. That turns a silent staleness bug into something
 * a dashboard can show. It carries the cursor as well, so a watchdog that
 * reconnects resumes from the right place.
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
            connection.begin(retry = config.streamReconnectDelay)
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
        val seen = Seen()
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
                // Also sent as the SSE `retry:` field, which `EventSource` honours
                // silently. Here as well so a client that owns its own backoff can
                // see what it is overriding instead of discovering it in a capture.
                put("reconnectMillis", config.streamReconnectDelay.inWholeMilliseconds)
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
                connection.event(
                    "ping",
                    cursor.token,
                    jsonObject {
                        put("at", config.clock.instant())
                        put("cursor", cursor.token)
                    },
                )
            }
            delay(config.changePollInterval)
        }
    }

    /** Everything, as one event, and the cursor it is consistent with. */
    private suspend fun snapshot(
        connection: SseConnection,
        seen: Seen,
    ): StoreCursor {
        // Cursor first, then the read — the same ordering as the list endpoint and
        // for the same reason: overlap is absorbed by the client, a gap is not.
        val cursor = store.currentCursor()
        // `listAll`, not `listServers`. One row whose definition will not decode
        // used to abort the whole read, which blanked an operator's fleet table at
        // the same instant it silently stopped the reconcile loop queueing work.
        val listing = store.listAll()
        val servers = listing.servers.sortedBy { it.name.value }
        val unreadable = listing.unreadable.sortedBy { it.name }
        seen.reset(servers, unreadable)
        connection.event(
            "snapshot",
            cursor.token,
            jsonObject {
                put("cursor", cursor.token)
                put("count", servers.size)
                putArray("items", servers, ServerJson::server)
                put("unreadableCount", unreadable.size)
                putArray("unreadable", unreadable, ServerJson::unreadableServer)
            },
        )
        return cursor
    }

    private suspend fun pullChanges(
        connection: SseConnection,
        cursor: StoreCursor,
        seen: Seen,
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
                    emit(connection, feed.cursor, name, seen)
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
        seen: Seen,
    ) {
        val listing = store.listAll()
        val present = HashSet<String>(listing.servers.size + listing.unreadable.size)

        for (server in listing.servers) {
            present += server.name.value
            send(connection, cursor, server, seen)
        }

        for (row in listing.unreadable) {
            val name = row.name ?: continue
            present += name
            // Drop any resource previously sent for this name, so that if the row
            // becomes readable again the next resync re-sends it rather than
            // deduplicating against versions the client no longer has.
            row.resourceName?.let(seen::forgetServer)
            if (seen.markUnreadable(row)) {
                connection.event("unreadable", cursor.token, ServerJson.unreadableServer(row))
            }
        }

        // Rows the store holds with no name at all. They cannot join `present`,
        // so they are reported on their own terms and then taken account of below.
        for (row in seen.newlyNameless(listing.unreadable)) {
            connection.event("unreadable", cursor.token, ServerJson.unreadableServer(row))
        }

        // Absence, and only real absence. A row that moved into `unreadable` is in
        // `present` above, so it never reaches here — reporting it as `removed`
        // would tell a dashboard the server was deleted when it is very probably
        // still running with players on it.
        //
        // A nameless row breaks that reasoning outright, so the whole derivation
        // is suspended while one exists. A record with no name may be *any*
        // previously-seen server whose name column was nulled; nothing here can
        // tell which, so every name this connection has sent is now un-eliminable.
        // Deriving absence anyway would emit `removed` for a server that was never
        // deleted, which is the exact hazard this correction exists for.
        //
        // The cost is the other direction of wrongness and it is the acceptable
        // one: a genuinely purged server lingers in the client until the nameless
        // row is repaired or the connection cycles into a fresh snapshot, which
        // re-states everything. A row that should be gone and lingers is a stale
        // dashboard; a row that is running and reported gone is an operator
        // assuming a server is stopped when it has players on it.
        val nameless = listing.unreadable.count { it.name == null }
        if (nameless > 0) {
            LOG.warn(
                "{} stored row(s) have no name; not deriving removals this pass because a nameless row " +
                    "could be any server and reporting a false deletion is worse than reporting one late",
                nameless,
            )
            return
        }

        for (name in seen.namesAbsentFrom(present)) {
            seen.forget(name)
            connection.event(
                "removed",
                cursor.token,
                jsonObject {
                    put("name", name)
                    put("reason", "PURGED")
                },
            )
        }
    }

    /**
     * The fast path, for one name out of the change feed.
     *
     * [Store.getServer] is strict — it fails when either half of the row will not
     * decode — so a corrupt row reached through here would end the whole stream,
     * which is the failure this round exists to remove. A permanent decode failure
     * is therefore logged and left to the next [resync], which reads tolerantly
     * and reports the row properly. The cost is one status-poll interval of
     * latency on a row nobody can read anyway.
     *
     * A *retryable* failure is not caught: that is the store being unreachable
     * rather than a row being corrupt, the resync would fail the same way, and the
     * stream should end so the client reconnects.
     */
    private suspend fun emit(
        connection: SseConnection,
        cursor: StoreCursor,
        name: ResourceName,
        seen: Seen,
    ) {
        val server =
            try {
                store.getServer(name)
            } catch (failure: StoreException) {
                if (failure.retryable) throw failure
                LOG.warn(
                    "`{}` did not decode on the change-feed path; the next resync will report it",
                    name,
                    failure,
                )
                return
            }
        if (server == null) {
            if (seen.forget(name.value)) {
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
        send(connection, cursor, server, seen)
    }

    /**
     * Sends [server] unless its versions are exactly what this connection last
     * sent, with a `reason` derived from *what actually moved*.
     *
     * Derived rather than passed in by the call site, because the call site does
     * not know. The resync cadence is the repair path for anything the change
     * feed lost, so it re-sends definition changes as well as observations; when
     * it named its own reason it labelled a recovered definition change
     * `"status"`, which was simply wrong. The version pair is the only thing that
     * knows, so it is what decides.
     *
     * There are two values and no third. A `"resync"` reason was declared in the
     * contract and never emitted — a variant a client must handle and can never
     * receive is dead weight that reads as a gap in their code.
     */
    private fun send(
        connection: SseConnection,
        cursor: StoreCursor,
        server: StoredServer,
        seen: Seen,
    ) {
        val previous = seen.record(server) ?: return
        val reason = previous
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

    /**
     * What this connection has already sent, and the whole de-duplication rule.
     *
     * Keyed by the *raw* name rather than by [ResourceName], because a row whose
     * definition will not decode may have a stored name that is not a valid one —
     * and that row still has to be tracked, or it would read as absent and be
     * reported as a deletion that never happened.
     */
    private class Seen {
        private val servers = HashMap<String, Versions>()
        private val unreadable = HashMap<String, ServerJsonUnreadable>()

        /**
         * Marks for rows the store holds with no name at all.
         *
         * A set rather than a map because there is no key to use: two nameless
         * rows failing for the same reason are indistinguishable to everything
         * outside the store, so they collapse into one mark and are reported
         * once. That is honest — nothing here can tell them apart either.
         */
        private val nameless = HashSet<ServerJsonUnreadable>()

        /** Replaces everything, for a snapshot. */
        fun reset(
            current: List<StoredServer>,
            broken: List<UnreadableServer>,
        ) {
            servers.clear()
            unreadable.clear()
            nameless.clear()
            current.forEach { servers[it.name.value] = Versions.of(it) }
            broken.forEach { row ->
                val mark = ServerJsonUnreadable(row.unreadable.part.name, row.unreadable.reason)
                val name = row.name
                if (name == null) nameless += mark else unreadable[name] = mark
            }
        }

        /**
         * Reconciles the nameless rows against [rows] and returns the ones this
         * connection has not been told about.
         *
         * Rebuilt from the listing each pass rather than accumulated, so a
         * nameless row that gets repaired stops being remembered and a later one
         * with the same reason is reported again.
         */
        fun newlyNameless(rows: List<UnreadableServer>): List<UnreadableServer> {
            val current = rows.filter { it.name == null }
            val marks = current.map { ServerJsonUnreadable(it.unreadable.part.name, it.unreadable.reason) }
            val fresh = current.filterIndexed { index, _ -> marks[index] !in nameless }
            nameless.clear()
            nameless += marks
            return fresh
        }

        /**
         * Records [server] and returns why it should be sent, or null when nothing
         * about it has moved since this connection last sent it.
         *
         * The reason is derived here because this is the only place that knows: it
         * compares the version pair. A call site naming its own reason got it
         * wrong once already — the resync labelled a recovered definition change
         * `"status"`.
         */
        fun record(server: StoredServer): String? {
            val versions = Versions.of(server)
            val previous = servers.put(server.name.value, versions)
            unreadable.remove(server.name.value)
            if (previous == versions) return null
            return if (previous == null || previous.definition != versions.definition) "definition" else "status"
        }

        /**
         * Marks a *named* [row] unreadable. True when that is new information.
         *
         * Nameless rows go through [newlyNameless]: they have no key to be put
         * under, and inventing one would be inventing an identity the store does
         * not have.
         */
        fun markUnreadable(row: UnreadableServer): Boolean {
            val name = row.name ?: return false
            val mark = ServerJsonUnreadable(row.unreadable.part.name, row.unreadable.reason)
            return unreadable.put(name, mark) != mark
        }

        /** Forgets the resource sent for a name, so a recovery re-sends it in full. */
        fun forgetServer(name: ResourceName) {
            servers.remove(name.value)
        }

        /** Drops a name entirely. True if it was known, which is what makes `removed` idempotent. */
        fun forget(name: String): Boolean = (servers.remove(name) != null) or (unreadable.remove(name) != null)

        /** Every name this connection has sent that is not in [present]. Real absence only. */
        fun namesAbsentFrom(present: Set<String>): List<String> =
            (servers.keys + unreadable.keys).filter { it !in present }
    }

    /** The part of an unreadable mark that decides whether the client has been told. */
    private data class ServerJsonUnreadable(
        val part: String,
        val reason: String,
    )

    /** The version pair for one server. */
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

        private val LOG = LoggerFactory.getLogger(StreamRoutes::class.java)
    }
}
