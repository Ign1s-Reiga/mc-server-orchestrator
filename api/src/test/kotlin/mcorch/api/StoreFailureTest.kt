package mcorch.api

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import mcorch.schema.DrainState
import mcorch.schema.ResourceName
import mcorch.schema.ServerDefinition
import mcorch.schema.ServerStatus
import mcorch.schema.fixtures.ExampleDefinitions
import mcorch.store.ChangeFeed
import mcorch.store.Precondition
import mcorch.store.ResourceVersion
import mcorch.store.Store
import mcorch.store.StoreCursor
import mcorch.store.StoreException
import mcorch.store.StoredDefinition
import mcorch.store.StoredServer
import mcorch.store.StoredStatus
import mcorch.store.WriteOutcome
import org.junit.jupiter.api.Test

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
}
