package mcorch.api

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import mcorch.schema.ResourceName
import mcorch.schema.fixtures.ExampleDefinitions
import mcorch.schema.getOrThrow
import mcorch.store.getOrThrow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * The live feed, read the way a browser reads it.
 *
 * Over a real socket rather than through an in-process pipe, because the framing
 * is the part that has to be right: a missing blank line or a stray newline in a
 * `data:` payload produces a stream that a server-side test is perfectly happy
 * with and `EventSource` silently drops.
 */
class EventStreamTest {
    private lateinit var api: TestApi
    private val executor = Executors.newCachedThreadPool()

    @BeforeEach
    fun start() {
        api = TestApi.start()
    }

    @AfterEach
    fun stop() {
        executor.shutdownNow()
        api.close()
    }

    private val minimal: String get() = ExampleDefinitions.valid("minimal.yaml")

    /**
     * Opens a stream on another thread and hands back a future of its events.
     *
     * The latch is released by the named event, so the caller can mutate state at
     * a point where the stream is definitely already listening — a sleep here
     * would be the thing that makes this suite flaky at three in the morning.
     */
    private fun listen(
        limit: Int,
        query: String = "",
        headers: List<Pair<String, String>> = emptyList(),
        readyOn: String = "snapshot",
    ): Pair<Future<List<TestApi.SseEvent>>, CountDownLatch> {
        val ready = CountDownLatch(1)
        val future =
            executor.submit<List<TestApi.SseEvent>> {
                api.stream(query = query, limit = limit, headers = headers) { event ->
                    if (event.name == readyOn) ready.countDown()
                    true
                }
            }
        check(ready.await(20, TimeUnit.SECONDS)) { "the stream never sent `$readyOn`" }
        return future to ready
    }

    @Test
    fun `a stream with no cursor opens with a snapshot, so there is no gap to a list call`() {
        api.call("POST", "/api/v1/servers", minimal).status shouldBe 201

        val events = api.stream(limit = 2)
        events.map { it.name } shouldBe listOf("hello", "snapshot")

        val hello = events[0].json()
        (hello["cursor"] as String).isNotEmpty() shouldBe true
        hello["resumed"] shouldBe false

        val snapshot = events[1].json()
        snapshot["count"] shouldBe 1
        @Suppress("UNCHECKED_CAST")
        val items = snapshot["items"] as List<Map<String, Any?>>
        items.single()["name"] shouldBe "survival-01"
        // Every event carries the cursor as its id, so a browser reconnect resumes
        // from the right place whichever event happened to arrive last.
        events.forEach { it.id.shouldNotBeNull() }
    }

    @Test
    fun `a definition change arrives as an update without a poll`() {
        val (future, _) = listen(limit = 3)
        api.call("POST", "/api/v1/servers", minimal).status shouldBe 201

        val events = future.get(20, TimeUnit.SECONDS)
        val update = events.last { it.name == "updated" }
        val body = update.json()
        body["name"] shouldBe "survival-01"
        // The whole resource, so the dashboard needs no follow-up GET.
        val server = body["server"] as Map<*, *>
        (server["metadata"] as Map<*, *>)["generation"] shouldBe 1
        (server["display"] as Map<*, *>)["state"] shouldBe "PENDING"
    }

    @Test
    fun `an observation arrives even though the change feed does not carry observed state`() {
        api.call("POST", "/api/v1/servers", minimal).status shouldBe 201
        val (future, _) = listen(limit = 3)

        // Written the way the reconcile loop writes it. The store's change feed is
        // desired-state only and says so, so this can only reach a client through
        // the slower resync cadence — which is exactly what is being checked.
        kotlinx.coroutines.runBlocking {
            api.store
                .putStatus(
                    mcorch.schema.PaperServerStatus
                        .pending(
                            name = ResourceName.of("survival-01").getOrThrow(),
                            observedGeneration = 1,
                            at = TestApi.CLOCK.instant(),
                        ),
                ).getOrThrow()
        }

        val events = future.get(20, TimeUnit.SECONDS)
        val update = events.last { it.name == "updated" }
        update.json()["reason"] shouldBe "status"
        val server = update.json()["server"] as Map<*, *>
        (server["status"] as Map<*, *>)["phase"] shouldBe "PENDING"
        server["caughtUp"] shouldBe true
    }

    @Test
    fun `a delete streams as an update and a purge streams as a removal`() {
        api.call("POST", "/api/v1/servers", minimal).status shouldBe 201

        // Two sync points rather than one, and the second is the interesting one.
        // Delete and purge coalesce if they land inside one poll interval — the
        // stream only ever sends current state, so the intermediate TERMINATING
        // would be dropped exactly as designed. That is correct behaviour and a
        // useless test, so this waits for the operator-visible state to arrive
        // before playing the part of the reconcile loop.
        val ready = CountDownLatch(1)
        val terminating = CountDownLatch(1)
        val future =
            executor.submit<List<TestApi.SseEvent>> {
                api.stream(limit = 4) { event ->
                    if (event.name == "snapshot") ready.countDown()
                    if (event.name == "updated" && event.data.contains("\"terminating\":true")) {
                        terminating.countDown()
                    }
                    true
                }
            }
        check(ready.await(20, TimeUnit.SECONDS))

        api.call("DELETE", "/api/v1/servers/survival-01").status shouldBe 202
        check(terminating.await(20, TimeUnit.SECONDS)) { "the delete never streamed as an update" }

        // The purge is :core's to make, never the API's — so the test plays :core.
        kotlinx.coroutines.runBlocking {
            api.store.purge(ResourceName.of("survival-01").getOrThrow()).getOrThrow()
        }

        val events = future.get(20, TimeUnit.SECONDS)
        val names = events.map { it.name }
        names shouldContainAll listOf("hello", "snapshot", "updated", "removed")

        // The delete is an update showing TERMINATING, not a disappearance: a
        // dashboard that removed the row here would be showing a stop that has not
        // happened, on a server that may still have players on it.
        val tombstoned = events.first { it.name == "updated" }.json()["server"] as Map<*, *>
        (tombstoned["metadata"] as Map<*, *>)["terminating"] shouldBe true
        (tombstoned["display"] as Map<*, *>)["state"] shouldBe "TERMINATING"

        events.last { it.name == "removed" }.json()["name"] shouldBe "survival-01"
    }

    @Test
    fun `resuming from a cursor skips the snapshot and replays what was missed`() {
        val cursor = api.call("GET", "/api/v1/servers").json()["cursor"] as String
        api.call("POST", "/api/v1/servers", minimal).status shouldBe 201

        val events = api.stream(query = "?cursor=$cursor", limit = 2)
        events[0].name shouldBe "hello"
        events[0].json()["resumed"] shouldBe true
        // No snapshot: the client already had one. The change made after the
        // cursor was taken is replayed instead.
        events[1].name shouldBe "updated"
        events[1].json()["name"] shouldBe "survival-01"
    }

    @Test
    fun `Last-Event-ID resumes, which is what a browser reconnect sends`() {
        val cursor = api.call("GET", "/api/v1/servers").json()["cursor"] as String
        api.call("POST", "/api/v1/servers", minimal).status shouldBe 201

        val events = api.stream(limit = 2, headers = listOf("Last-Event-ID" to cursor))
        events[0].json()["resumed"] shouldBe true
        events[1].name shouldBe "updated"
    }

    @Test
    fun `an expired cursor is reported and followed by a snapshot`() {
        // A long-lived browser tab will hit this: the change log is bounded, and a
        // connection that was asleep through enough writes cannot be told what it
        // missed. Retention of one makes that happen in three writes instead of ten
        // thousand.
        val small = TestApi.start(changeLogRetention = 1)
        try {
            val stale = small.call("GET", "/api/v1/servers").json()["cursor"] as String
            small.call("POST", "/api/v1/servers", minimal).status shouldBe 201
            small.call("POST", "/api/v1/servers", ExampleDefinitions.valid("full.yaml")).status shouldBe 201
            small.call("POST", "/api/v1/servers", ExampleDefinitions.valid("ephemeral-lobby.yaml")).status shouldBe 201

            val events = small.stream(query = "?cursor=$stale", limit = 3)
            events.map { it.name } shouldBe listOf("hello", "expired", "snapshot")
            // The snapshot re-states everything, so a client that ignores `expired`
            // still converges rather than drifting silently.
            (events[2].json()["count"] as Int) shouldBe 3
        } finally {
            small.close()
        }
    }

    @Test
    fun `the stream count is capped and the refusal is retryable`() {
        val capped = TestApi.start { it.copy(maxStreams = 1) }
        try {
            val ready = CountDownLatch(1)
            val held =
                executor.submit {
                    capped.stream(limit = 100) { event ->
                        if (event.name == "snapshot") ready.countDown()
                        true
                    }
                }
            check(ready.await(20, TimeUnit.SECONDS))

            val refused = capped.call("GET", "/api/v1/stream")
            refused.status shouldBe 503
            refused.errorCode() shouldBe "STREAM_LIMIT"
            // Retryable, and it says so in the body as well as in the header, so a
            // client does not have to know which 503s are worth retrying.
            (refused.json()["error"] as Map<*, *>)["retryable"] shouldBe true
            refused.header("Retry-After").shouldNotBeNull()

            held.cancel(true)
        } finally {
            capped.close()
        }
    }
}
