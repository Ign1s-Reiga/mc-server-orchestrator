package mcorch.app

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import mcorch.api.ApiConfig
import mcorch.api.ApiServer
import mcorch.api.OperatorToken
import mcorch.core.Reconciler
import mcorch.core.SingleNodeScheduler
import mcorch.core.StaticNodeRegistry
import mcorch.schema.RconSpec
import mcorch.store.getOrThrow
import mcorch.store.sqlite.EmbeddedStore
import mcorch.store.sqlite.EmbeddedStoreConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration as JavaDuration

/**
 * What the dashboard is told, about a status `:core` actually drafted.
 *
 * ## Why this cannot live in `:core` or in `:api`
 *
 * Two defects have now been found in [mcorch.schema.ConditionType.NEEDS_ATTENTION]
 * from outside `:core`, and neither was about the escalation rule's arithmetic —
 * both were about what the flag *means to the thing that renders it*. A `:core`
 * unit test structurally cannot see that, because it stops at the status object.
 *
 * `:api` cannot see it either, and deliberately: `api/build.gradle.kts` has no
 * `:core` dependency, not even for tests, so an `:api` test can only assert
 * against a `conditions` list somebody typed by hand. Such a test **would have
 * passed against the inverted rule** — it would have been asserting that
 * `ServerJson` can read a list, which was never in doubt.
 *
 * `:app` is the one module that depends on both, so this is the only place the
 * two halves meet. It goes over a real socket into a real [ApiServer] reading a
 * real [EmbeddedStore] that a real [Reconciler] wrote, because the defect was
 * found in the rendered JSON and that is the artefact worth pinning.
 *
 * ## The property
 *
 * **If the badge says `TERMINATING` or `DRAINING` while the server is still
 * reported joinable, then either the drain is making progress or
 * `needsAttention` is true.**
 *
 * The badge ranks `TERMINATING` above everything on purpose — a server showing
 * `READY` while its name is being reclaimed is the one wrong answer that
 * matters — so a permanently failed drain, whose container is still up and still
 * has players on it, renders as though it were on its way out. The flag is what
 * stops that reading being a lie, and this asserts the two cannot drift apart
 * again.
 */
class DisplayConformanceTest {
    private val directories = mutableListOf<Path>()

    @AfterEach
    fun cleanUp() {
        directories.forEach { it.toFile().deleteRecursively() }
        directories.clear()
    }

    private fun directory(): Path = Files.createTempDirectory("mcorch-display").also { directories.add(it) }

    /**
     * The invariant, applied to one rendered server.
     *
     * Deliberately shaped as an implication rather than an equality, so it can be
     * applied to every server in every state a test produces — including the ones
     * where it is vacuously true. A rule that only holds for the case it was
     * written against is not the rule.
     *
     * ## Why "no players online" is part of the antecedent
     *
     * The property was first written as *progressing or flagged*, and the
     * players-online case failed it: that drain sits in `DRAIN_FAILED` — it
     * aborted — is not progressing, and is deliberately never flagged. It is also
     * completely fine. It is waiting for people to log off, which is the protocol
     * working, and alarming on it every backoff interval is how operators learn
     * the signal means nothing.
     *
     * So `DRAIN_FAILED` is not a usable proxy for *stuck*. What separates the two
     * from the dashboard's side is whether anybody is on the server, and
     * `playersOnline` is rendered, so the consumer can tell them apart without
     * being told the failure reason. A server showing players is self-evidently
     * not abandoned; one showing none, with a drain that has given up and a badge
     * saying it is on its way out, is the case an operator will walk past.
     */
    private fun assertNothingIsSilentlyStuck(display: Map<*, *>) {
        val state = display["state"]
        val ready = display["ready"] == true
        if (state !in setOf("TERMINATING", "DRAINING") || !ready) return
        val progressing = display["drainState"] != "DRAIN_FAILED"
        val waitingOnPlayers = (display["playersOnline"] as? Int ?: 0) > 0
        val flagged = display["needsAttention"] == true
        withClue(display) { (progressing || waitingOnPlayers || flagged) shouldBe true }
    }

    private fun withClue(
        clue: Any?,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (failure: AssertionError) {
            throw AssertionError(
                "a server is shown as on its way out, is still reported joinable, has a drain that has given " +
                    "up, and carries no attention flag — an operator reading a fleet table sees " +
                    "\"nothing to do\" on a server that needs them: $clue",
                failure,
            )
        }
    }

    @Test
    fun `a server whose drain gave up is flagged, not just shown as terminating`() {
        val directory = directory()
        val node = StubNode()
        EmbeddedStore.open(EmbeddedStoreConfig(directory = directory)).use { embedded ->
            val registry = StaticNodeRegistry(listOf(node))
            val reconciler = Reconciler(embedded.state, registry, SingleNodeScheduler(registry))

            // Persistent world data and no RCON: nothing can ever report that a
            // save completed, so this drain aborts permanently and the container
            // is deliberately left running. It is the state whose whole remedy is
            // "a human resolves this".
            val definition = paperServer(name = "stuck-01", rcon = RconSpec.Disabled)
            val name = definition.metadata.name
            runBlocking {
                embedded.state.putDefinition(definition).getOrThrow()
                repeat(4) { reconciler.reconcile(name) }
                embedded.state.deleteDefinition(name).getOrThrow()
                repeat(8) { reconciler.reconcile(name) }
            }

            serving(embedded) { api ->
                val display = api.display("stuck-01")

                // The exact combination the dashboard author hit.
                display["state"] shouldBe "TERMINATING"
                display["ready"] shouldBe true
                display["drainState"] shouldBe "DRAIN_FAILED"
                display["needsAttention"] shouldBe true

                assertNothingIsSilentlyStuck(display)
            }

            // The assertion this file exists for as much as the rendering one.
            // `stuck-01` holds a world and has no channel that could ever report
            // a completed save, so the whole posture is that it is *never*
            // stopped — CLAUDE.md invariant 1 and 3 together. This is the only
            // place in the codebase where a real `Reconciler` drives a real
            // drain against a real store end to end, so it is the best place to
            // say so. Without it a regression that stopped the container would
            // surface as a 404 from the purge, which reads as broken plumbing
            // rather than as a container with an unsaved world being stopped.
            node.stops.shouldBeEmpty()
        }
    }

    /**
     * The control, and it is not decoration.
     *
     * The implication above is vacuously true for a server that is not draining,
     * so a suite containing only the case above would still pass if
     * `needsAttention` were hard-wired to `true`. This pins that the flag is
     * *false* on a healthy server, which is what makes the assertion above mean
     * something — and it is the property that keeps the signal worth acting on.
     */
    @Test
    fun `a healthy server carries no attention flag`() {
        val directory = directory()
        val node = StubNode()
        EmbeddedStore.open(EmbeddedStoreConfig(directory = directory)).use { embedded ->
            val registry = StaticNodeRegistry(listOf(node))
            val reconciler = Reconciler(embedded.state, registry, SingleNodeScheduler(registry))
            val definition = paperServer(name = "healthy-01")
            val name = definition.metadata.name
            runBlocking {
                embedded.state.putDefinition(definition).getOrThrow()
                repeat(5) { reconciler.reconcile(name) }
            }

            serving(embedded) { api ->
                val display = api.display("healthy-01")

                display["state"] shouldBe "READY"
                display["needsAttention"] shouldBe false
                assertNothingIsSilentlyStuck(display)
            }
        }
    }

    /**
     * A drain that is getting on with it satisfies the property by the *other*
     * arm — progressing rather than flagged.
     *
     * Without this the rule could be met by flagging every draining server, which
     * is the failure mode the exclusion for players-online exists to prevent.
     */
    @Test
    fun `a drain that is progressing is not flagged`() {
        val directory = directory()
        // Players online: the drain has nowhere to send them, so it blocks —
        // retryably, and by design this is never escalated.
        val node = StubNode(online = 3)
        EmbeddedStore.open(EmbeddedStoreConfig(directory = directory)).use { embedded ->
            val registry = StaticNodeRegistry(listOf(node))
            val reconciler = Reconciler(embedded.state, registry, SingleNodeScheduler(registry))
            val definition = paperServer(name = "busy-01")
            val name = definition.metadata.name
            runBlocking {
                embedded.state.putDefinition(definition).getOrThrow()
                repeat(4) { reconciler.reconcile(name) }
                embedded.state.deleteDefinition(name).getOrThrow()
                repeat(3) { reconciler.reconcile(name) }
            }

            serving(embedded) { api ->
                val display = api.display("busy-01")

                display["state"] shouldBe "TERMINATING"
                // People are playing. That is the protocol working, and calling
                // a human about it every backoff interval is how the signal
                // stops meaning anything.
                display["needsAttention"] shouldBe false
                assertNothingIsSilentlyStuck(display)
            }

            // Three players are connected to a server somebody asked to delete.
            // There is no proxy to move them through, so the drain blocks — and
            // the one thing that must never happen is the loop stopping the
            // container to make progress (`failure-modes.md` item 4).
            node.stops.shouldBeEmpty()
        }
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    private fun serving(
        embedded: EmbeddedStore,
        block: (ApiClient) -> Unit,
    ) {
        val token = "operator-token-for-a-unit-test-0123456789"
        val config =
            ApiConfig(
                bindHost = "127.0.0.1",
                bindPort = 0,
                token = OperatorToken.of(token).getOrThrow(),
                authFailureDelay = kotlin.time.Duration.ZERO,
            )
        ApiServer.start(config, embedded.state, embedded.secrets).use { server ->
            block(ApiClient("http://127.0.0.1:${server.port}", token))
        }
    }

    private class ApiClient(
        private val base: String,
        private val token: String,
    ) {
        private val client: HttpClient =
            HttpClient.newBuilder().connectTimeout(JavaDuration.ofSeconds(5)).build()

        fun display(name: String): Map<*, *> {
            val request =
                HttpRequest
                    .newBuilder(URI.create("$base/api/v1/servers/$name"))
                    .timeout(JavaDuration.ofSeconds(20))
                    .header("Authorization", "Bearer $token")
                    .GET()
                    .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            check(response.statusCode() == 200) { "GET $name returned ${response.statusCode()}: ${response.body()}" }
            val document = Load(LoadSettings.builder().build()).loadFromString(response.body()) as Map<*, *>
            // A sibling of `status`, not a field inside it: the badge fuses the
            // status, the drain and the tombstone, and the tombstone is not part
            // of observed state.
            return document["display"] as? Map<*, *> ?: error("no display in $document")
        }
    }
}
