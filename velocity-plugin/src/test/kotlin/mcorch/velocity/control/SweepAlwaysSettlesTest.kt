package mcorch.velocity.control

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A transfer sweep always reaches a settled tally, and a stuck one never absorbs
 * the retries.
 *
 * This is the second way the drain can be lost on the proxy side, and it is
 * quieter than a kick. The sweep record is published with its denominator
 * (`requested`) already set, and a repeat request *joins* a running record
 * without asking anybody to move. So a record that can never settle absorbs
 * every retry `SKILL.md` step 4 asks for: `remaining` never falls, `DELETE` keeps
 * refusing because players are still there, and the backend becomes permanently
 * undrainable — with players on it. Nobody is disconnected by that and no world
 * is lost, which is exactly why it would survive review: the pressure it
 * generates is toward stopping the container by hand, and *that* is the data-loss
 * event.
 */
class SweepAlwaysSettlesTest {
    private fun drainable(): Pair<FakeProxy, ControlService> {
        val proxy = FakeProxy()
        val service = readyService(proxy)
        service.assertBackend("survival-01", "10.0.0.4:25565", admits = false)
        service.assertBackend("lobby-01", "10.0.0.5:25565", admits = true)
        return proxy to service
    }

    @Test
    fun `a player who cannot be sent a notice is counted, not skipped`() {
        val (proxy, service) = drainable()
        val source = proxy.named("survival-01")
        val ok = source.join(FakePlayer("a", "ua", "/198.51.100.1:1"))
        val broken = source.join(FakePlayer("b", "ub", "/198.51.100.2:2").also { it.throwsOnNotify = true })
        val after = source.join(FakePlayer("c", "uc", "/198.51.100.3:3"))

        val body = service.transferBackend("survival-01", "lobby-01").json()

        // The throw does not escape and does not end the loop: the player after the
        // broken one was still asked.
        body.int("requested") shouldBe 3
        body.int("moved") shouldBe 2
        body.int("failed") shouldBe 1
        body.int("inFlight") shouldBe 0
        // And the sweep finished, which is what makes the next pass a retry rather
        // than a join.
        body.isNull("finishedAtEpochMs") shouldBe false
        ok.connectedTo shouldBe proxy.named("lobby-01")
        after.connectedTo shouldBe proxy.named("lobby-01")
        // The one that failed is still connected. A notice that would not send is
        // not a reason to disconnect anybody.
        broken.connectedTo shouldBe source
    }

    @Test
    fun `a player whose transfer request throws is counted, not skipped`() {
        val (proxy, service) = drainable()
        val source = proxy.named("survival-01")
        source.join(FakePlayer("a", "ua", "/198.51.100.1:1").also { it.throwsOnTransfer = true })
        source.join(FakePlayer("b", "ub", "/198.51.100.2:2"))

        val body = service.transferBackend("survival-01", "lobby-01").json()

        body.int("failed") shouldBe 1
        body.int("moved") shouldBe 1
        body.int("inFlight") shouldBe 0
        body.isNull("finishedAtEpochMs") shouldBe false
    }

    @Test
    fun `a sweep that never settles is superseded rather than joined forever`() {
        val proxy = FakeProxy()
        var now = 1_770_000_000_000L
        val service = readyService(proxy, clock = { now })
        service.assertBackend("survival-01", "10.0.0.4:25565", admits = false)
        service.assertBackend("lobby-01", "10.0.0.5:25565", admits = true)
        val source = proxy.named("survival-01")
        // A future nothing ever completes: the proxy accepted the request and never
        // answered. Without a staleness bound this record is permanent.
        val abandoned = CompletableFuture<TransferResult>()
        val player = source.join(FakePlayer("a", "ua", "/198.51.100.1:1").also { it.pending = abandoned })

        service.transferBackend("survival-01", "lobby-01").json().int("inFlight") shouldBe 1
        // While it is young, a repeat joins it and asks nobody again.
        service.transferBackend("survival-01", "lobby-01")
        player.transferRequests shouldBe 1

        now += ControlProtocol.SWEEP_MAX_AGE_MS + 1
        val retry = service.transferBackend("survival-01", "lobby-01").json()

        // Past the bound the retry starts a fresh sweep, so step 4 can make progress
        // again instead of inheriting a record that can never finish.
        player.transferRequests shouldBe 2
        retry.int("requested") shouldBe 1
        retry.int("moved") shouldBe 0
        retry.long("startedAtEpochMs") shouldBe now
    }

    @Test
    fun `a sweep on a backend that still admits is refused`() {
        val proxy = FakeProxy()
        val service = readyService(proxy)
        service.assertBackend("survival-01", "10.0.0.4:25565", admits = true)
        service.assertBackend("lobby-01", "10.0.0.5:25565", admits = true)
        val player = proxy.named("survival-01").join(FakePlayer("a", "ua", "/198.51.100.1:1"))

        val response = service.transferBackend("survival-01", "lobby-01")

        // Drain step 2 precedes step 4, made a property of the protocol. A sweep on
        // an unsealed backend refills behind itself and can never converge.
        response.shouldFailWith(ControlErrorCode.SOURCE_NOT_SEALED)
        player.transferRequests shouldBe 0
        player.connectedTo shouldBe proxy.named("survival-01")

        service.assertBackend("survival-01", "10.0.0.4:25565", admits = false)
        service.transferBackend("survival-01", "lobby-01").status shouldBe 200
    }

    @Test
    fun `a sweep settles correctly when its futures complete on other threads`() {
        // Every other test in this module completes futures inline on the calling
        // thread, which means the tally's concurrency is never exercised by them.
        val (proxy, service) = drainable()
        val source = proxy.named("survival-01")
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        val players =
            List(32) { index ->
                val pending = CompletableFuture<TransferResult>()
                pool.submit {
                    gate.await()
                    pending.complete(if (index % 4 == 0) TransferResult.FAILED else TransferResult.MOVED)
                }
                source.join(FakePlayer("p$index", "u$index", "/198.51.100.1:$index").also { it.pending = pending })
            }

        service.transferBackend("survival-01", "lobby-01")
        gate.countDown()
        pool.shutdown()
        pool.awaitTermination(20, TimeUnit.SECONDS) shouldBe true

        val settled = service.readState().backend("survival-01").obj("transfer")
        // Exactly one finisher, and every outcome counted exactly once.
        settled.int("moved") shouldBe 24
        settled.int("failed") shouldBe 8
        settled.int("inFlight") shouldBe 0
        settled.isNull("finishedAtEpochMs") shouldBe false
        players.count { it.connectedTo == source } shouldBe 8
    }

    @Test
    fun `concurrent asserts of the same new backend register it once`() {
        // FakeProxy throws on a second registerServer for a name it already has,
        // because Velocity does not document what that does. Without the lock in
        // ControlService this races.
        val proxy = FakeProxy()
        val service = readyService(proxy)
        val threads = 16
        val gate = CountDownLatch(1)
        val failures = AtomicInteger()
        val pool = Executors.newFixedThreadPool(threads)

        repeat(threads) {
            pool.submit {
                gate.await()
                val response = service.assertBackend("survival-01", "10.0.0.4:25565", admits = false)
                if (response.status != 200) failures.incrementAndGet()
            }
        }
        gate.countDown()
        pool.shutdown()
        pool.awaitTermination(20, TimeUnit.SECONDS) shouldBe true

        failures.get() shouldBe 0
        proxy.registerCalls shouldBe 1
        proxy.deregisterCalls shouldBe 0
        service.readState().backend("survival-01").boolean("admitsNewPlayers") shouldBe false
    }
}
