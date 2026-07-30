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
import mcorch.store.Precondition
import mcorch.store.ResourceVersion
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

        override suspend fun listAll(): ServerListing =
            if (definitionReadable) {
                ServerListing(listOf(healthy, brokenObservation, brokenBefore), emptyList())
            } else {
                ServerListing(listOf(healthy, brokenObservation), listOf(brokenDefinition))
            }

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

        override suspend fun changesSince(
            cursor: StoreCursor?,
            limit: Int,
        ): ChangeFeed = ChangeFeed.Changes(emptyList(), StoreCursor("1"), more = false)

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
