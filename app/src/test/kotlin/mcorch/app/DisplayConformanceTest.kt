package mcorch.app

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import mcorch.api.ApiConfig
import mcorch.api.ApiServer
import mcorch.api.OperatorToken
import mcorch.core.Reconciler
import mcorch.core.SingleNodeScheduler
import mcorch.core.StaticNodeRegistry
import mcorch.schema.RconSpec
import mcorch.schema.StorageSpec
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
 * ## The properties
 *
 * **1. If the badge says `TERMINATING` or `DRAINING` while the server is still
 * reported joinable, then either the drain is making progress, or it is blocked,
 * or `needsAttention` is true.**
 *
 * The badge ranks `TERMINATING` above everything on purpose — a server showing
 * `READY` while its name is being reclaimed is the one wrong answer that
 * matters — so a permanently failed drain, whose container is still up and still
 * has players on it, renders as though it were on its way out. The flag is what
 * stops that reading being a lie, and this asserts the two cannot drift apart
 * again.
 *
 * **2. A server carrying a `PERMANENT` failure is flagged. No badge, no phase, no
 * drain is exempt.**
 *
 * The first property is scoped to two badges and to `ready`, so it structurally
 * cannot see the case that motivated the second: a refused `storage.mode` edit
 * leaves the phase at `RUNNING` with no drain at all, and the loop then stops
 * observing that server entirely. The badge lie points the *opposite* way from
 * the one above — `TERMINATING` at least makes somebody look; `RUNNING` makes
 * them look away — and it is the worse of the two for that reason.
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
     * ## Why "blocked" is one of the arms
     *
     * The property was first written as *progressing or flagged*, and the
     * players-online case failed it: that drain sits in `DRAIN_FAILED` — it is
     * parked — is not progressing, and is deliberately never flagged. It is also
     * completely fine. It is waiting for people to log off, which is the protocol
     * working, and alarming on it every backoff interval is how operators learn
     * the signal means nothing.
     *
     * So `DRAIN_FAILED` is not a usable proxy for *stuck*. What separates the two
     * used to be inferred here from `playersOnline`, which was a guess about
     * *why* a drain was parked, and only correct as long as the one block reason
     * happened to be about players. It is now a rendered fact: `drainBlocked` is
     * derived from the drain's own record, and using it means this property is
     * red if `:core` records a stuck drain as a healthy wait — which the player
     * count could not have caught, since a stuck drain usually has players on it
     * too.
     */
    private fun assertNothingIsSilentlyStuck(display: Map<*, *>) {
        val state = display["state"]
        val ready = display["ready"] == true
        if (state !in setOf("TERMINATING", "DRAINING") || !ready) return
        val progressing = display["drainState"] != "DRAIN_FAILED"
        val blocked = display["drainBlocked"] == true
        val flagged = display["needsAttention"] == true
        withClue(display) { (progressing || blocked || flagged) shouldBe true }
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

    /**
     * The second property, applied to a rendered server whatever state it is in.
     *
     * Reads `status.failure` rather than a badge, because the whole point is that
     * no badge distinguishes these servers. A `PERMANENT` failure means the loop
     * has stopped acting: it will not probe, will not observe and will not record
     * anything further until a person changes something. There is no phase, no
     * drain and no badge for which that is a state to leave unflagged.
     */
    private fun assertPermanentFailureIsFlagged(
        status: Map<*, *>?,
        display: Map<*, *>,
    ) {
        val failure = status?.get("failure") as? Map<*, *> ?: return
        if (failure["failureClass"] != "PERMANENT") return
        if (display["needsAttention"] == true) return
        throw AssertionError(
            "a server carries a permanent failure — the loop has stopped acting on it and only a person can " +
                "move it — and it is rendered with no attention flag. Its badge is " +
                "\"${display["state"]}\", which an operator reads as an ordinary server: $display / $failure",
        )
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

                // The identity this case exists to guard, asserted in the
                // direction that can actually break.
                //
                // `Reconciler.drain` writes `status.failure = progress.drain.failure`
                // as a *copy*, and three consumers discriminate "this failure is the
                // drain's own" by comparing the two values. `:api`'s `detail()` is
                // one of them: it ranks a *pass* failure above the drain's, so the
                // moment that assignment becomes a `?.copy(...)` the two stop being
                // equal, every aborted drain routes through the pass arm, and this
                // sentence silently becomes "the loop cannot complete a pass". Every
                // other assertion above still passes — `needsAttention` is true
                // either way, the badge is unchanged, and the phase is unchanged.
                //
                // `DrainBlockRenderingTest` asserts the negative half against a
                // hand-built status (`shouldNotContain` on a blocked drain), which
                // cannot see this: it never runs `Reconciler`, so it cannot observe
                // the assignment that makes the positive half true.
                (display["detail"] as String) shouldContain "the drain aborted"

                assertPermanentFailureIsFlagged(api.status("stuck-01"), display)
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
                assertPermanentFailureIsFlagged(api.status("healthy-01"), display)
                assertNothingIsSilentlyStuck(display)
            }
        }
    }

    /**
     * A drain that is waiting for people to log off satisfies the property by the
     * *blocked* arm — neither progressing nor flagged, and correct.
     *
     * Without this the rule could be met by flagging every draining server, which
     * is the failure mode the whole blocked/failed split exists to prevent.
     *
     * The assertions go end to end on purpose. A real `Reconciler` drives a real
     * drain into the block, writes it through a real `EmbeddedStore`, and a real
     * `ApiServer` renders it over a socket — so `drainBlocked` being true here is
     * evidence that the rule in `:core`, the codec in `:store` and the derivation
     * in `:api` agree. Nothing inside any one module can say that.
     */
    @Test
    fun `a drain waiting for players is shown as blocked rather than as needing a human`() {
        val directory = directory()
        // Players online: the drain has nowhere to send them, so it blocks. It
        // records no failure at all, and by design it is never escalated.
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
                val drain = api.drain("busy-01")

                display["state"] shouldBe "TERMINATING"
                // People are playing. That is the protocol working, and calling
                // a human about it every backoff interval is how the signal
                // stops meaning anything.
                display["needsAttention"] shouldBe false
                // The fact that replaced the guess. The dashboard is told *why*
                // this drain is parked rather than being left to infer it from a
                // player count.
                display["drainBlocked"] shouldBe true
                (display["detail"] as String) shouldContain "waiting, not stuck"

                // Nothing anywhere in the record calls this a failure — the
                // property the whole change turns on, checked on the bytes that
                // actually left the process.
                (drain["blocked"] as Map<*, *>)["reason"] shouldBe "AWAITING_ZERO_PLAYERS"
                drain["failure"] shouldBe null

                assertPermanentFailureIsFlagged(api.status("busy-01"), display)
                assertNothingIsSilentlyStuck(display)
            }

            // Three players are connected to a server somebody asked to delete.
            // There is no proxy to move them through, so the drain blocks — and
            // the one thing that must never happen is the loop stopping the
            // container to make progress (`failure-modes.md` item 4).
            node.stops.shouldBeEmpty()
        }
    }

    /**
     * The case the first property structurally cannot see, and the reason the flag
     * stopped being a drain flag.
     *
     * A persistent server is running, holding a world, and somebody edits
     * `storage.mode` to `ephemeral`. Applying that edit means draining and
     * replacing the container that is holding the world right now, so
     * `Reconciler.forbiddenTransition` refuses it: a **permanent** failure, no
     * drain, phase `RUNNING`. From that pass on the loop does not observe this
     * server again — no probe, no occupancy, nothing — until somebody reverts the
     * edit.
     *
     * The badge is `RUNNING` and it is not going to change, so an operator's own
     * eyes are not going to find this one. The flag is the only thing that does.
     */
    @Test
    fun `a server whose definition edit was refused is flagged even though its badge says running`() {
        val directory = directory()
        val node = StubNode()
        EmbeddedStore.open(EmbeddedStoreConfig(directory = directory)).use { embedded ->
            val registry = StaticNodeRegistry(listOf(node))
            val reconciler = Reconciler(embedded.state, registry, SingleNodeScheduler(registry))

            val definition = paperServer(name = "frozen-01")
            val name = definition.metadata.name
            runBlocking {
                embedded.state.putDefinition(definition).getOrThrow()
                repeat(5) { reconciler.reconcile(name) }
                // The refused edit. `storage.mode` is in the spec hash, so it asks
                // for a recreate, and the recreate would drain the container that
                // holds the world.
                embedded.state
                    .putDefinition(paperServer(name = "frozen-01", storage = StorageSpec.Ephemeral()))
                    .getOrThrow()
                repeat(3) { reconciler.reconcile(name) }
            }

            serving(embedded) { api ->
                val display = api.display("frozen-01")
                val status = api.status("frozen-01")

                // An ordinary running server, as far as every badge goes.
                display["state"] shouldBe "RUNNING"
                display["drainState"] shouldBe null
                // And the loop has stopped.
                display["needsAttention"] shouldBe true

                // The discriminator agreement, which is the whole reason this is
                // an `:app` test. `:core` decided this failure is the *pass*'s
                // rather than a drain's and worded its condition accordingly;
                // `:api` asks the identical question to choose its sentence. Two
                // derivations of one fact in modules with no shared dependency.
                (display["detail"] as String) shouldContain "refusing to change storage.mode"

                assertPermanentFailureIsFlagged(status, display)
                assertNothingIsSilentlyStuck(display)
            }

            // The refusal exists to stop a container being drained under rules it
            // never ran under. If it ever starts stopping the thing it is
            // protecting, this is what says so.
            node.stops.shouldBeEmpty()
        }
    }

    /**
     * A drain that is waiting on players, on a node that has stopped answering.
     *
     * Both facts are true and neither may be reported as the other. The server
     * needs a human, because nothing moves until the node comes back; the *drain*
     * has nothing wrong with it and is still shown as blocked.
     *
     * This is the case that ends "`drainBlocked` and `needsAttention` are never
     * both true". They were only ever disjoint because the attention flag could
     * not see a failure that was not the drain's — which is precisely the blind
     * spot that got fixed. `API.md` still documents the old claim; the `:api`
     * change to withdraw it is called out in the change's report.
     */
    @Test
    fun `a blocked drain on a node that stopped answering is flagged and still shown as blocked`() {
        val directory = directory()
        val node = StubNode(online = 3)
        EmbeddedStore.open(EmbeddedStoreConfig(directory = directory)).use { embedded ->
            val registry = StaticNodeRegistry(listOf(node))
            val reconciler = Reconciler(embedded.state, registry, SingleNodeScheduler(registry))
            val definition = paperServer(name = "marooned-01")
            val name = definition.metadata.name
            runBlocking {
                embedded.state.putDefinition(definition).getOrThrow()
                repeat(4) { reconciler.reconcile(name) }
                embedded.state.deleteDefinition(name).getOrThrow()
                repeat(3) { reconciler.reconcile(name) }
                node.stopAnswering()
                reconciler.reconcile(name)
            }

            serving(embedded) { api ->
                val display = api.display("marooned-01")
                val status = api.status("marooned-01")

                // The drain is still parked on players and still says so.
                display["drainBlocked"] shouldBe true
                // And somebody has to act, because the loop cannot get to the node
                // that would let the drain finish.
                display["needsAttention"] shouldBe true

                // The condition about the *drain* must not have been worded from
                // the widened flag: this drain is waiting, not failing.
                val draining =
                    (status?.get("conditions") as? List<*>)
                        .orEmpty()
                        .filterIsInstance<Map<*, *>>()
                        .single { it["type"] == "DRAINING" }
                (draining["message"] as String) shouldNotContain "not recovering on its own"

                // The drain record itself still carries no failure at all.
                api.drain("marooned-01")["failure"] shouldBe null

                assertPermanentFailureIsFlagged(status, display)
                assertNothingIsSilentlyStuck(display)
            }

            // Three people are playing on a server somebody asked to delete, on a
            // node the loop cannot reach. Nothing in that may produce a stop.
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

        fun display(name: String): Map<*, *> =
            // A sibling of `status`, not a field inside it: the badge fuses the
            // status, the drain and the tombstone, and the tombstone is not part
            // of observed state.
            server(name)["display"] as? Map<*, *> ?: error("no display for $name")

        /** The drain record as it left the process, for the assertions the badge cannot carry. */
        fun drain(name: String): Map<*, *> {
            val status = server(name)["status"] as? Map<*, *> ?: error("no status for $name")
            return status["drain"] as? Map<*, *> ?: error("no drain for $name")
        }

        /** Observed status, for the property that is about a failure rather than a badge. */
        fun status(name: String): Map<*, *>? = server(name)["status"] as? Map<*, *>

        private fun server(name: String): Map<*, *> {
            val request =
                HttpRequest
                    .newBuilder(URI.create("$base/api/v1/servers/$name"))
                    .timeout(JavaDuration.ofSeconds(20))
                    .header("Authorization", "Bearer $token")
                    .GET()
                    .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            check(response.statusCode() == 200) { "GET $name returned ${response.statusCode()}: ${response.body()}" }
            return Load(LoadSettings.builder().build()).loadFromString(response.body()) as Map<*, *>
        }
    }
}
