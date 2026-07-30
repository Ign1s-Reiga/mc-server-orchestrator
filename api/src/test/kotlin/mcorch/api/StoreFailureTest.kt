package mcorch.api

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import mcorch.schema.DrainState
import mcorch.schema.PaperServerDefinition
import mcorch.schema.ResourceName
import mcorch.schema.ServerDefinition
import mcorch.schema.ServerStatus
import mcorch.schema.fixtures.ExampleDefinitions
import mcorch.store.ChangeFeed
import mcorch.store.ChangeKind
import mcorch.store.Precondition
import mcorch.store.ResourceVersion
import mcorch.store.ServerChange
import mcorch.store.ServerListing
import mcorch.store.StatePart
import mcorch.store.Store
import mcorch.store.StoreCursor
import mcorch.store.StoreException
import mcorch.store.StoredDefinition
import mcorch.store.StoredServer
import mcorch.store.StoredStatus
import mcorch.store.Unreadable
import mcorch.store.UnreadableServer
import mcorch.store.WriteOutcome
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A store failure becomes a status code by the store's own classification, never
 * by a guess made in a handler.
 *
 * The fake below implements `:store`'s interface rather than mocking anything in
 * this module — the point is to drive a real dispatcher, real routing and a real
 * response writer with a store that fails, which no real store will do on demand.
 */
class StoreFailureTest {
    private class FailingStore(
        private val failure: StoreException,
    ) : Store {
        override suspend fun putDefinition(
            definition: ServerDefinition,
            precondition: Precondition,
        ): WriteOutcome<StoredDefinition> = throw failure

        override suspend fun deleteDefinition(
            name: ResourceName,
            precondition: Precondition,
        ): WriteOutcome<StoredDefinition> = throw failure

        override suspend fun purge(
            name: ResourceName,
            precondition: Precondition,
        ): WriteOutcome<Unit> = throw failure

        override suspend fun putStatus(
            status: ServerStatus,
            precondition: Precondition,
            observedDefinition: ResourceVersion?,
        ): WriteOutcome<StoredStatus> = throw failure

        override suspend fun getServer(name: ResourceName): StoredServer? = throw failure

        override suspend fun listServers(): List<StoredServer> = throw failure

        override suspend fun listByDrainState(states: Set<DrainState>): List<StoredServer> = throw failure

        override suspend fun currentCursor(): StoreCursor = throw failure

        override suspend fun changesSince(
            cursor: StoreCursor?,
            limit: Int,
        ): ChangeFeed = throw failure

        override fun close() = Unit
    }

    private fun withStore(
        failure: StoreException,
        block: (TestApi) -> Unit,
    ) {
        // A real server over a failing store: TestApi owns the healthy store the
        // secrets side still needs, and the state side is swapped underneath.
        val healthy = TestApi.start()
        val config =
            ApiConfig(
                bindHost = "127.0.0.1",
                bindPort = 0,
                token = OperatorToken.of(healthy.token).getOrThrow(),
                clock = TestApi.CLOCK,
                authFailureDelay = kotlin.time.Duration.ZERO,
            )
        val broken = healthy.sharing(ApiServer.start(config, FailingStore(failure), healthy.secrets))
        try {
            block(broken)
        } finally {
            broken.close()
            healthy.close()
        }
    }

    @Test
    fun `a retryable store failure is a 503 that says it is retryable`() {
        withStore(StoreException.Unavailable("the database is locked")) { api ->
            val reply = api.call("GET", "/api/v1/servers")
            reply.status shouldBe 503
            reply.errorCode() shouldBe "STORE_UNAVAILABLE"
            (reply.json()["error"] as Map<*, *>)["retryable"] shouldBe true
            reply.header("Retry-After").shouldNotBeNull()

            // The store's own message is not forwarded: it can name a file path, a
            // SQL statement, or whatever the driver decided to put in it.
            reply.body shouldNotContain "the database is locked"
        }
    }

    @Test
    fun `a permanent store failure is a 500 and does not invite a retry`() {
        withStore(StoreException.Corrupt("row 12 does not decode")) { api ->
            val reply = api.call("POST", "/api/v1/servers", ExampleDefinitions.valid("minimal.yaml"))
            reply.status shouldBe 500
            reply.errorCode() shouldBe "INTERNAL"
            (reply.json()["error"] as Map<*, *>)["retryable"] shouldBe false
            reply.header("Retry-After") shouldBe null
            reply.body shouldNotContain "row 12"
        }
    }

    /**
     * A store holding one good server, one whose observation will not decode, and
     * one whose definition will not decode.
     *
     * The strict reads behave exactly as `:store` documents — `getServer` fails
     * for either kind of bad row, `listServers` fails only for the bad
     * *definition* — so this exercises the fallbacks rather than pretending they
     * are not needed.
     */
    private class PartlyUnreadableStore(
        private val healthy: StoredServer,
    ) : Store {
        val brokenObservation: StoredServer =
            healthy.copy(
                definition = healthy.definition.copy(definition = renamed(healthy, MARKED)),
                status = null,
                unreadable =
                    Unreadable(
                        part = StatePart.OBSERVED,
                        reason =
                            "`$MARKED`: stored observation names phase `TELEPORTING`, which this build " +
                                "does not know. Refusing to guess",
                        retryable = false,
                    ),
            )

        val brokenDefinition: UnreadableServer =
            UnreadableServer(
                name = BROKEN,
                unreadable =
                    Unreadable(
                        part = StatePart.DESIRED,
                        reason = "`$BROKEN`: encoded document is missing the required key `spec.image`",
                        retryable = false,
                    ),
            )

        /** The same row while it still decodes, so the transition can be driven. */
        private val brokenBefore: StoredServer =
            healthy.copy(definition = healthy.definition.copy(definition = renamed(healthy, BROKEN)))

        /**
         * Flipped by a test to make [BROKEN] stop decoding.
         *
         * The transition is the case worth driving: a row moving from `servers`
         * into `unreadable` must not read as absence, because absence is how a
         * purge is reported.
         */
        @Volatile
        var definitionReadable: Boolean = true

        /**
         * A row the store holds with no name at all.
         *
         * Not hypothetical: SQLite permits NULL in a rowid table's primary key, so
         * a hand-written row without one is possible, and `:store` reports it
         * honestly rather than inventing a placeholder identity.
         */
        val nameless: UnreadableServer =
            UnreadableServer(
                name = null,
                unreadable =
                    Unreadable(
                        part = StatePart.DESIRED,
                        reason = "a stored definition has no name",
                        retryable = false,
                    ),
            )

        /** When set, the listing also carries [nameless]. */
        @Volatile
        var namelessPresent: Boolean = false

        /** When set, the healthy server is gone from the listing, as a purge would leave it. */
        @Volatile
        var healthyPurged: Boolean = false

        override suspend fun listAll(): ServerListing =
            ServerListing(
                servers =
                    buildList {
                        if (!healthyPurged) add(healthy)
                        add(brokenObservation)
                        if (definitionReadable) add(brokenBefore)
                    },
                unreadable =
                    buildList {
                        if (!definitionReadable) add(brokenDefinition)
                        if (namelessPresent) add(nameless)
                    },
            )

        override suspend fun listServers(): List<StoredServer> =
            throw StoreException.Corrupt(brokenDefinition.unreadable.reason)

        override suspend fun getServer(name: ResourceName): StoredServer? =
            when (name.value) {
                healthy.name.value -> {
                    healthy
                }

                MARKED -> {
                    throw StoreException.Corrupt(brokenObservation.unreadable?.reason.orEmpty())
                }

                BROKEN -> {
                    if (definitionReadable) {
                        brokenBefore
                    } else {
                        throw StoreException.Corrupt(brokenDefinition.unreadable.reason)
                    }
                }

                else -> {
                    null
                }
            }

        override suspend fun currentCursor(): StoreCursor = StoreCursor("1")

        /** Every cursor the stream has fed back, in order. Proves the feed advances. */
        val cursorsRead: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

        /**
         * When set, the feed reports a change for a name whose row will not decode.
         *
         * That is the shape of the tenth audit's warning 4: the documented way to
         * unwedge a stuck server is an operator editing its definition, which is
         * precisely what puts a name into the change feed — so the poisoned entry
         * arrives at the worst possible moment.
         */
        @Volatile
        var poisonedName: String? = null

        override suspend fun changesSince(
            cursor: StoreCursor?,
            limit: Int,
        ): ChangeFeed {
            val from = cursor?.token.orEmpty()
            cursorsRead += from
            val poisoned = poisonedName
            // Reported once, from the cursor the stream opened on. If the stream
            // fails to advance past it, it will be handed the same change for ever.
            if (poisoned == null || from != "1") {
                return ChangeFeed.Changes(emptyList(), StoreCursor(from.ifEmpty { "1" }), more = false)
            }
            return ChangeFeed.Changes(
                changes =
                    listOf(
                        ServerChange(
                            name = ResourceName.of(poisoned).getOrThrow(),
                            kind = ChangeKind.WRITTEN,
                            resourceVersion = ResourceVersion("2"),
                            at = TestApi.CLOCK.instant(),
                        ),
                    ),
                cursor = StoreCursor("2"),
                more = false,
            )
        }

        override suspend fun putDefinition(
            definition: ServerDefinition,
            precondition: Precondition,
        ): WriteOutcome<StoredDefinition> = WriteOutcome.Applied(healthy.definition)

        override suspend fun deleteDefinition(
            name: ResourceName,
            precondition: Precondition,
        ): WriteOutcome<StoredDefinition> = WriteOutcome.Applied(healthy.definition)

        override suspend fun purge(
            name: ResourceName,
            precondition: Precondition,
        ): WriteOutcome<Unit> = WriteOutcome.Applied(Unit)

        override suspend fun putStatus(
            status: ServerStatus,
            precondition: Precondition,
            observedDefinition: ResourceVersion?,
        ): WriteOutcome<StoredStatus> = throw StoreException.Failed("not used")

        override suspend fun listByDrainState(states: Set<DrainState>): List<StoredServer> = emptyList()

        override fun close() = Unit

        companion object {
            const val MARKED = "survival-marked"
            const val BROKEN = "survival-broken"

            private fun renamed(
                from: StoredServer,
                name: String,
            ): ServerDefinition {
                val definition = from.definition.definition as PaperServerDefinition
                return definition.copy(
                    metadata = definition.metadata.copy(name = ResourceName.of(name).getOrThrow()),
                )
            }
        }
    }

    private fun withPartlyUnreadableStore(block: (TestApi, PartlyUnreadableStore) -> Unit) {
        val healthy = TestApi.start()
        try {
            healthy.call("POST", "/api/v1/servers", ExampleDefinitions.valid("minimal.yaml")).status shouldBe 201
            val stored = runBlocking { healthy.store.getServer(ResourceName.of("survival-01").getOrThrow()) }
            val config =
                ApiConfig(
                    bindHost = "127.0.0.1",
                    bindPort = 0,
                    token = OperatorToken.of(healthy.token).getOrThrow(),
                    clock = TestApi.CLOCK,
                    authFailureDelay = kotlin.time.Duration.ZERO,
                    changePollInterval = kotlin.time.Duration.parse("50ms"),
                    statusPollInterval = kotlin.time.Duration.parse("100ms"),
                )
            val store = PartlyUnreadableStore(stored.shouldNotBeNull())
            val partial = healthy.sharing(ApiServer.start(config, store, healthy.secrets))
            try {
                block(partial, store)
            } finally {
                partial.close()
            }
        } finally {
            healthy.close()
        }
    }

    @Test
    fun `one unreadable row costs its own server and not the fleet`() {
        withPartlyUnreadableStore { api, store ->
            store.definitionReadable = false
            val listed = api.call("GET", "/api/v1/servers")
            listed.status shouldBe 200
            val body = listed.json()

            // The good server and the marked one are both here. This is the whole
            // point: a row that would not decode used to abort the read, blanking
            // an operator's fleet table from one bad row.
            body["count"] shouldBe 2
            @Suppress("UNCHECKED_CAST")
            val items = body["items"] as List<Map<String, Any?>>
            items.map { it["name"] } shouldBe listOf("survival-01", "survival-marked")

            // And the row with no readable definition is reported rather than
            // dropped. Dropping it is indistinguishable from a purge, and a
            // dashboard derives removal from absence.
            body["unreadableCount"] shouldBe 1
            @Suppress("UNCHECKED_CAST")
            val broken = (body["unreadable"] as List<Map<String, Any?>>).single()
            broken["name"] shouldBe "survival-broken"
            broken["part"] shouldBe "DESIRED"
            broken["retryable"] shouldBe false
        }
    }

    @Test
    fun `a marked server is not shown as one that has simply not been observed`() {
        withPartlyUnreadableStore { api, store ->
            store.definitionReadable = false
            @Suppress("UNCHECKED_CAST")
            val items = api.call("GET", "/api/v1/servers").json()["items"] as List<Map<String, Any?>>
            val marked = items.single { it["name"] == "survival-marked" }
            val display = marked["display"] as Map<*, *>

            // PENDING is a state an operator waits out. Saying it here would mean
            // waiting out a corrupt row for ever.
            display["state"] shouldBe "UNREADABLE"
            display["unreadable"] shouldBe true
            marked["neverObserved"] shouldBe false
            marked["status"] shouldBe null
            (marked["unreadable"] as Map<*, *>)["part"] shouldBe "OBSERVED"
            (display["detail"] as String) shouldContain "could not be read"

            // Control: the genuinely unobserved server in the same response still
            // says PENDING, so the distinction is real and not a blanket rename.
            val fresh = items.single { it["name"] == "survival-01" }
            (fresh["display"] as Map<*, *>)["state"] shouldBe "PENDING"
            (fresh["display"] as Map<*, *>)["unreadable"] shouldBe false
            fresh["neverObserved"] shouldBe true
            fresh["unreadable"] shouldBe null
        }
    }

    @Test
    fun `fetching one marked server answers rather than failing, and a broken definition says why`() {
        withPartlyUnreadableStore { api, store ->
            store.definitionReadable = false
            // getServer is strict by design, so this is the fallback working: the
            // list shows the row, and clicking it must not be a 500.
            val marked = api.call("GET", "/api/v1/servers/survival-marked")
            marked.status shouldBe 200
            (marked.json()["display"] as Map<*, *>)["state"] shouldBe "UNREADABLE"

            // There is no resource for a row with no readable definition, so this
            // one is an error — but a specific one, never NOT_FOUND, which would
            // say the server is gone when it may still be running.
            val broken = api.call("GET", "/api/v1/servers/survival-broken")
            broken.status shouldBe 500
            broken.errorCode() shouldBe "SERVER_UNREADABLE"
            val detail = (broken.json()["error"] as Map<*, *>)["unreadable"] as Map<*, *>
            detail["name"] shouldBe "survival-broken"
            detail["part"] shouldBe "DESIRED"
            (broken.json()["error"] as Map<*, *>)["message"].toString() shouldContain "may still be running"

            // The observation sub-resource cannot serve an observation it cannot
            // read, and says so rather than reporting "not observed yet".
            val status = api.call("GET", "/api/v1/servers/survival-marked/status")
            status.status shouldBe 500
            status.errorCode() shouldBe "SERVER_UNREADABLE"

            // A name that is genuinely absent is still a 404.
            api.call("GET", "/api/v1/servers/survival-absent").status shouldBe 404
        }
    }

    @Test
    fun `the snapshot carries both lists`() {
        withPartlyUnreadableStore { api, store ->
            store.definitionReadable = false
            val events = api.stream(limit = 2)
            events[0].name shouldBe "hello"

            val snapshot = events[1].json()
            snapshot["count"] shouldBe 2
            snapshot["unreadableCount"] shouldBe 1
            @Suppress("UNCHECKED_CAST")
            val broken = (snapshot["unreadable"] as List<Map<String, Any?>>).single()
            broken["name"] shouldBe "survival-broken"
            broken["part"] shouldBe "DESIRED"
        }
    }

    @Test
    fun `a row that stops decoding streams as unreadable and never as removed`() {
        // The case the whole tolerant read exists for, and the one that would
        // otherwise be silent: a definition that stops decoding disappears from
        // the strict listing, and a stream deriving `removed` from absence would
        // tell the dashboard a server was deleted while its container is very
        // probably still running with players on it.
        withPartlyUnreadableStore { api, store ->
            val ready = CountDownLatch(1)
            val events =
                api.stream(limit = 4) { event ->
                    if (event.name == "snapshot") {
                        // Break it only once the client has the readable version,
                        // so what follows is a transition and not the initial state.
                        store.definitionReadable = false
                        ready.countDown()
                    }
                    true
                }
            check(ready.await(20, TimeUnit.SECONDS))

            // It was readable when the snapshot went out.
            events[1].json()["count"] shouldBe 3
            events[1].json()["unreadableCount"] shouldBe 0

            val reported = events.first { it.name == "unreadable" }.json()
            reported["name"] shouldBe "survival-broken"
            reported["part"] shouldBe "DESIRED"
            reported["retryable"] shouldBe false

            // The assertion that matters.
            events.none { it.name == "removed" } shouldBe true
        }
    }

    @Test
    fun `a corrupt row named by the change feed does not kill the stream or wedge the cursor`() {
        // The tenth audit's warning 4. `emit` reaches the store through the strict
        // single-row read, and an uncaught failure there ends the stream — which
        // would be recoverable if it ended there. It does not: the browser
        // reconnects with the same Last-Event-ID, the feed replays the same change
        // because the cursor never advanced past it, and the stream dies again.
        // A fleet-wide blind loop, at the exact moment somebody is repairing.
        //
        // And the trigger is not exotic. The documented way to unwedge a stuck
        // server is an operator editing its definition, which is exactly what puts
        // its name into the change feed.
        withPartlyUnreadableStore { api, store ->
            store.definitionReadable = false
            store.poisonedName = PartlyUnreadableStore.MARKED

            val events = api.stream(limit = 5)

            // Alive well past the poisoned change: hello and snapshot alone would
            // be what a stream that died on the first poll produced.
            events.size shouldBe 5
            events[0].name shouldBe "hello"
            events[1].name shouldBe "snapshot"

            // And the cursor moved. Without this the stream could be "alive" and
            // still re-reading the same poisoned entry for ever.
            store.cursorsRead.any { it == "2" } shouldBe true

            // Control: the poisoned entry really was delivered, so the assertions
            // above are about surviving it rather than about it never arriving.
            store.cursorsRead.first() shouldBe "1"
        }
    }

    @Test
    fun `a retryable failure on the change-feed path still ends the stream`() {
        // The other half, and the reason the catch is narrow. A store that cannot
        // be reached is not a corrupt row: the resync would fail the same way, so
        // carrying on would mean a stream that silently stops reflecting reality.
        // Ending it makes the client reconnect, which is the recovery.
        withStore(StoreException.Unavailable("gone")) { api ->
            val events = api.stream(limit = 5)
            events.none { it.name == "snapshot" } shouldBe true
        }
    }

    @Test
    fun `an unreadable server is flagged for a human as well as described`() {
        withPartlyUnreadableStore { api, store ->
            store.definitionReadable = false

            @Suppress("UNCHECKED_CAST")
            val items = api.call("GET", "/api/v1/servers").json()["items"] as List<Map<String, Any?>>
            val marked = (items.single { it["name"] == "survival-marked" }["display"]) as Map<*, *>

            // Two flags, two audiences. `unreadable` says what is wrong and is what
            // a dashboard filters on; `needsAttention` says somebody must act and
            // is what an alert fires on. A row the store cannot decode reads the
            // same on every pass, so the loop cannot move it and only a person
            // can — which is the charter of NEEDS_ATTENTION exactly.
            marked["unreadable"] shouldBe true
            marked["needsAttention"] shouldBe true

            // Control: the healthy server raises neither, so this is not a blanket
            // true.
            val fresh = items.single { it["name"] == "survival-01" }["display"] as Map<*, *>
            fresh["unreadable"] shouldBe false
            fresh["needsAttention"] shouldBe false
        }
    }

    @Test
    fun `a new badge value reaches a dashboard filter with no frontend release`() {
        // The cost of UNREADABLE being its own state rather than a reuse of
        // UNKNOWN is one new enum value. This is the mechanism that makes that
        // cost nil, and it is the reason `/meta` serves `displayState` at all —
        // so it is worth pinning rather than assuming.
        //
        // A dashboard that builds its filter chips from `meta.enums.displayState`
        // and passes the chosen value straight to `?state=` gets a working
        // UNREADABLE filter with no code change. Both halves are asserted, because
        // advertising a value the filter would reject is the failure that would
        // make the promise hollow.
        withPartlyUnreadableStore { api, store ->
            store.definitionReadable = false

            val advertised = (api.call("GET", "/api/v1/meta").json()["enums"] as Map<*, *>)["displayState"] as List<*>
            advertised.contains("UNREADABLE") shouldBe true

            for (chip in advertised) {
                val filtered = api.call("GET", "/api/v1/servers?state=$chip")
                withClue("?state=$chip") { filtered.status shouldBe 200 }
            }

            @Suppress("UNCHECKED_CAST")
            val marked = api.call("GET", "/api/v1/servers?state=UNREADABLE").json()["items"] as List<Map<String, Any?>>
            marked.map { it["name"] } shouldBe listOf("survival-marked")

            // Control: the chip selects, rather than every chip returning
            // everything. The healthy server is PENDING and is not in the answer
            // above; it is in this one.
            @Suppress("UNCHECKED_CAST")
            val pending = api.call("GET", "/api/v1/servers?state=PENDING").json()["items"] as List<Map<String, Any?>>
            pending.map { it["name"] } shouldBe listOf("survival-01")
        }
    }

    @Test
    fun `a row with no name is served as one, and is not made up an identity`() {
        withPartlyUnreadableStore { api, store ->
            store.namelessPresent = true

            val body = api.call("GET", "/api/v1/servers").json()
            body["unreadableCount"] shouldBe 1

            @Suppress("UNCHECKED_CAST")
            val row = (body["unreadable"] as List<Map<String, Any?>>).single()
            // Null, not "" and not a placeholder. `:store` refuses to invent an
            // identity it does not have, and neither does this.
            row.containsKey("name") shouldBe true
            row["name"] shouldBe null
            row["part"] shouldBe "DESIRED"
            (row["reason"] as String).isNotEmpty() shouldBe true
        }
    }

    @Test
    fun `a nameless row suspends removal reporting rather than risking a false one`() {
        // The `present` set is keyed by name, and a nameless row cannot join it.
        // Worse, it may *be* any previously-seen server whose name column was
        // nulled — nothing here can tell which — so every name this connection has
        // sent becomes un-eliminable. Deriving absence anyway would emit `removed`
        // for a server that was never deleted, on a server that may have players
        // on it. So the derivation is suspended while one exists.
        withPartlyUnreadableStore { api, store ->
            store.definitionReadable = false

            val ready = CountDownLatch(1)
            val events =
                api.stream(limit = 6) { event ->
                    if (event.name == "snapshot") {
                        // A nameless row appears, and the healthy server vanishes
                        // from the listing exactly as a completed purge would leave
                        // it. Only one of those two facts is trustworthy.
                        store.namelessPresent = true
                        store.healthyPurged = true
                        ready.countDown()
                    }
                    true
                }
            check(ready.await(20, TimeUnit.SECONDS))

            events.none { it.name == "removed" } shouldBe true

            // The nameless row is still reported — suspended removals are not
            // suspended reporting.
            val reported = events.first { it.name == "unreadable" && it.json()["name"] == null }.json()
            reported["part"] shouldBe "DESIRED"
        }
    }

    @Test
    fun `with every row nameable, a vanished server is still reported removed`() {
        // The control for the test above. Without it, "no removed event" would
        // pass just as well if removal reporting were broken outright.
        withPartlyUnreadableStore { api, store ->
            store.definitionReadable = false

            val ready = CountDownLatch(1)
            val events =
                api.stream(limit = 6) { event ->
                    if (event.name == "snapshot") {
                        store.healthyPurged = true
                        ready.countDown()
                    }
                    true
                }
            check(ready.await(20, TimeUnit.SECONDS))

            val removed = events.first { it.name == "removed" }.json()
            removed["name"] shouldBe "survival-01"
            removed["reason"] shouldBe "PURGED"
        }
    }

    @Test
    fun `an unreadable reason reaches the operator without carrying internals`() {
        withPartlyUnreadableStore { api, store ->
            store.definitionReadable = false
            val bodies =
                listOf(
                    api.call("GET", "/api/v1/servers").body,
                    api.call("GET", "/api/v1/servers/survival-marked").body,
                    api.call("GET", "/api/v1/servers/survival-broken").body,
                    api.call("GET", "/api/v1/servers/survival-marked/status").body,
                ) + api.stream(limit = 3).map { it.data }
            val haystack = bodies.joinToString("\n")

            // Control: the reason really is served — it is operator-facing text, on
            // the same terms as FailureStatus.message, and withholding it would
            // leave an operator with a broken row and no clue which one.
            haystack shouldContain "does not know. Refusing to guess"
            haystack shouldContain "missing the required key"

            // What must not be there: a stack trace, a class name, a driver
            // message, a file path. :store does not put them in the value, and
            // nothing here reaches past it to the exception to add them.
            for (internal in listOf(
                "StoreException",
                "mcorch.store",
                "java.sql",
                "SQLite",
                "org.sqlite",
                "at mcorch.",
                api.directory.toString(),
            )) {
                withClue(internal) { haystack shouldNotContain internal }
            }
            // Control: the search finds these when they are present.
            (haystack + "org.sqlite") shouldContain "org.sqlite"
        }
    }

    @Test
    fun `a store that fails while a stream is open closes it rather than hanging`() {
        withStore(StoreException.Unavailable("gone")) { api ->
            // The stream opens (the head is written before the first store call) and
            // then ends. What must not happen is a connection that stays open for
            // ever writing nothing.
            val events = api.stream(limit = 5)
            events.none { it.name == "snapshot" } shouldBe true
        }
    }

    private fun withClue(
        clue: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (failure: AssertionError) {
            throw AssertionError("$clue: ${failure.message}", failure)
        }
    }
}
