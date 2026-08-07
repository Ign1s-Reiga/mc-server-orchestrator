package mcorch.velocity.control

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * A transfer never becomes a kick — `failure-modes.md` item 4.
 *
 * Three independent guards, because the failure is silent and permanent: kicking
 * players to reach zero looks like nobody was lost, and their last minutes of
 * play go unsaved.
 *
 * 1. **Behavioural.** After a sweep where every single move fails, every player
 *    is still connected to the source backend.
 * 2. **Structural.** [PlayerHandle] has no method that could disconnect anybody,
 *    so the sweep — which is written against it — has nothing to call.
 * 3. **Textual.** The Velocity adapter *does* hold a `Player`, which has
 *    `disconnect`. A source scan asserts it, and the two connection methods that
 *    can disconnect a player on failure, appear nowhere in this module.
 */
class TransferNeverKicksTest {
    @Test
    fun `when every transfer fails, nobody is disconnected and everybody stays put`() {
        val proxy = FakeProxy()
        val service = readyService(proxy)
        service.assertBackend("survival-01", "10.0.0.4:25565", admits = false)
        service.assertBackend("lobby-01", "10.0.0.5:25565", admits = true)
        val source = proxy.named("survival-01")
        val players =
            List(4) { index ->
                source
                    .join(FakePlayer("player$index", "uuid$index", "/198.51.100.$index:40000"))
                    .also { it.outcome = TransferResult.FAILED }
            }

        val response = service.transferBackend("survival-01", "lobby-01")

        response.status shouldBe 200
        response.json().int("failed") shouldBe 4
        response.json().int("moved") shouldBe 0
        // The number the drain actually waits on, read live off the backend.
        response.json().int("remaining") shouldBe 4
        // Nobody was disconnected to make that number smaller.
        players.forEach { it.connectedTo shouldBe source }
        source.connected.size shouldBe 4
        proxy.playerCount() shouldBe 4
        proxy.isRegistered("survival-01") shouldBe true
    }

    @Test
    fun `a partial failure moves who it can and abandons nobody`() {
        val proxy = FakeProxy()
        val service = readyService(proxy)
        service.assertBackend("survival-01", "10.0.0.4:25565", admits = false)
        service.assertBackend("lobby-01", "10.0.0.5:25565", admits = true)
        val source = proxy.named("survival-01")
        val destination = proxy.named("lobby-01")
        val moving = source.join(FakePlayer("a", "ua", "/198.51.100.1:40000"))
        val stuck =
            source.join(
                FakePlayer("b", "ub", "/198.51.100.2:40000").also { it.outcome = TransferResult.FAILED },
            )
        val busy =
            source.join(
                FakePlayer("c", "uc", "/198.51.100.3:40000").also { it.outcome = TransferResult.REFUSED },
            )

        val body = service.transferBackend("survival-01", "lobby-01").json()

        body.int("moved") shouldBe 1
        body.int("failed") shouldBe 1
        body.int("refused") shouldBe 1
        body.int("remaining") shouldBe 2
        moving.connectedTo shouldBe destination
        // Neither of the two that did not move lost their connection, and the
        // successful one was not rolled back.
        stuck.connectedTo shouldBe source
        busy.connectedTo shouldBe source
        proxy.playerCount() shouldBe 3
    }

    @Test
    fun `nothing on the player port can disconnect anybody`() {
        // A shape assertion rather than a behavioural one: if somebody adds a
        // disconnect to the port so a sweep can "make progress", this fails before
        // any code has a chance to call it.
        val forbidden = listOf("disconnect", "kick", "close", "shutdown", "remove", "drop")

        val offered = PlayerHandle::class.java.methods.map { it.name }

        offered.filter { method -> forbidden.any { method.contains(it, ignoreCase = true) } }.shouldBeEmpty()
        // Control: the search looks at a list that is genuinely populated, so an
        // empty result above means "no such method" rather than "no methods".
        offered shouldContain "requestTransfer"
        offered shouldContain "notify"
    }

    @Test
    fun `no source in this module names a Velocity call that can disconnect a player`() {
        val sources = mainSources()

        // Control first: the scan reads real Kotlin that really does drive Velocity
        // connections. Without this the assertions below could pass over an empty
        // file list, which is how a leak test ends up searching for nothing.
        check(sources.size >= 3) { "expected the module's main sources, found ${sources.size} files" }
        val everything = sources.joinToString("\n") { it.readText() }
        everything shouldContain "createConnectionRequest"

        for (path in sources) {
            val text = path.readText()
            val code = text.lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
            for (banned in BANNED_CALLS) {
                val offending = code.filter { it.contains(banned) }
                check(offending.isEmpty()) {
                    "${path.name} calls `$banned`, which can disconnect a player: ${offending.first().trim()}"
                }
            }
        }
    }

    @Test
    fun `re-requesting a running transfer joins it instead of asking anybody twice`() {
        val proxy = FakeProxy()
        val service = readyService(proxy)
        service.assertBackend("survival-01", "10.0.0.4:25565", admits = false)
        service.assertBackend("lobby-01", "10.0.0.5:25565", admits = true)
        val source = proxy.named("survival-01")
        val held = CompletableFuture<TransferResult>()
        val player = source.join(FakePlayer("a", "ua", "/198.51.100.1:40000").also { it.pending = held })

        val first = service.transferBackend("survival-01", "lobby-01").json()
        val second = service.transferBackend("survival-01", "lobby-01").json()
        val third = service.transferBackend("survival-01", "lobby-01").json()

        // The reconcile loop re-enters this state on every pass. A duplicated
        // connection request per player is a real side effect on a live server.
        player.transferRequests shouldBe 1
        player.notices.size shouldBe 1
        first.int("inFlight") shouldBe 1
        second.int("inFlight") shouldBe 1
        third.isNull("finishedAtEpochMs") shouldBe true

        held.complete(TransferResult.MOVED)

        val settled = service.readState().backend("survival-01").obj("transfer")
        settled.int("moved") shouldBe 1
        settled.int("inFlight") shouldBe 0
        settled.isNull("finishedAtEpochMs") shouldBe false
    }

    @Test
    fun `a finished sweep is retried for whoever is left, because step 4 says to retry`() {
        val proxy = FakeProxy()
        val service = readyService(proxy)
        service.assertBackend("survival-01", "10.0.0.4:25565", admits = false)
        service.assertBackend("lobby-01", "10.0.0.5:25565", admits = true)
        val source = proxy.named("survival-01")
        val player =
            source.join(
                FakePlayer("a", "ua", "/198.51.100.1:40000").also { it.outcome = TransferResult.FAILED },
            )

        service.transferBackend("survival-01", "lobby-01").json().int("failed") shouldBe 1

        // Whoever moved is no longer on this backend to be asked again, so a fresh
        // sweep is a retry of exactly the ones that are left.
        player.outcome = TransferResult.MOVED
        val retry = service.transferBackend("survival-01", "lobby-01").json()

        retry.int("moved") shouldBe 1
        retry.int("remaining") shouldBe 0
        player.transferRequests shouldBe 2
        player.connectedTo shouldBe proxy.named("lobby-01")
    }

    @Test
    fun `players are told before they are moved`() {
        val proxy = FakeProxy()
        val service = readyService(proxy)
        service.assertBackend("survival-01", "10.0.0.4:25565", admits = false)
        service.assertBackend("lobby-01", "10.0.0.5:25565", admits = true)
        val player = proxy.named("survival-01").join(FakePlayer("a", "ua", "/198.51.100.1:40000"))

        service.transferBackend("survival-01", "lobby-01", message = "Moving you now")

        player.notices shouldBe listOf("Moving you now")
    }

    @Test
    fun `a sweep with nobody on the backend finishes immediately`() {
        val proxy = FakeProxy()
        val service = readyService(proxy)
        service.assertBackend("survival-01", "10.0.0.4:25565", admits = false)
        service.assertBackend("lobby-01", "10.0.0.5:25565", admits = true)

        val body = service.transferBackend("survival-01", "lobby-01").json()

        body.int("requested") shouldBe 0
        body.int("remaining") shouldBe 0
        body.isNull("finishedAtEpochMs") shouldBe false
        // Epoch milliseconds survive the wire intact. `:core` times the drain's
        // steps off these, and a timestamp that came back rounded is a step that
        // appears to have taken a different length of time than it did.
        body.long("startedAtEpochMs") shouldBe 1_770_000_000_000L
        body.long("finishedAtEpochMs") shouldBe 1_770_000_000_000L
    }

    @Test
    fun `a destination that cannot take players is refused rather than transferred to`() {
        val proxy = FakeProxy()
        val service = readyService(proxy)
        service.assertBackend("survival-01", "10.0.0.4:25565", admits = false)
        service.assertBackend("draining-02", "10.0.0.6:25565", admits = false)
        val player = proxy.named("survival-01").join(FakePlayer("a", "ua", "/198.51.100.1:40000"))

        // Moving a draining server's players onto another draining server is a
        // destination without capacity in the only sense that matters.
        service.transferBackend("survival-01", "draining-02").shouldFailWith(ControlErrorCode.DESTINATION_SEALED)
        service.transferBackend("survival-01", "not-a-server").shouldFailWith(ControlErrorCode.DESTINATION_UNKNOWN)
        service.transferBackend("survival-01", "survival-01").shouldFailWith(ControlErrorCode.TRANSFER_TO_SELF)
        service.transferBackend("not-a-server", "survival-01").shouldFailWith(ControlErrorCode.BACKEND_UNKNOWN)

        // Every refusal above left the player alone: not moved, and not disconnected
        // to make the drain look like it progressed.
        player.transferRequests shouldBe 0
        player.connectedTo shouldBe proxy.named("survival-01")
    }

    @Test
    fun `an over-long notice is refused before anybody is moved`() {
        val proxy = FakeProxy()
        val service = readyService(proxy)
        service.assertBackend("survival-01", "10.0.0.4:25565", admits = false)
        service.assertBackend("lobby-01", "10.0.0.5:25565", admits = true)
        val player = proxy.named("survival-01").join(FakePlayer("a", "ua", "/198.51.100.1:40000"))

        service
            .transferBackend("survival-01", "lobby-01", message = "x".repeat(ControlProtocol.MAX_MESSAGE_LENGTH + 1))
            .shouldFailWith(ControlErrorCode.MALFORMED_REQUEST)

        player.transferRequests shouldBe 0
        player.notices.shouldBeEmpty()
    }

    private companion object {
        /**
         * `disconnect` is Velocity's kick. `connectWithIndication` applies Velocity's
         * own error handling to a failed connection and `fireAndForget` discards the
         * result — neither reports what happened, and this plugin has to.
         */
        val BANNED_CALLS = listOf("disconnect(", "connectWithIndication", "fireAndForget")

        fun mainSources(): List<Path> {
            val root = Path.of("src/main/kotlin")
            check(Files.isDirectory(root)) { "expected to run with the module directory as the working directory" }
            Files.walk(root).use { walk ->
                return walk.filter { it.extension == "kt" }.toList()
            }
        }
    }
}
