package mcorch.velocity.control

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * The seal is a routing refusal, not a deregistration, and it never disconnects
 * anybody.
 *
 * This is the requirement the whole plugin exists for. Velocity's obvious "stop
 * sending players here" is removing the backend from the registered-server map,
 * which is also drain step 6 — doing it at step 2 disconnects everyone still
 * connected (`failure-modes.md` item 3). Everything below is an assertion that
 * the two stayed apart.
 */
class SealTest {
    @Test
    fun `sealing leaves the registration intact and every connection untouched`() {
        val proxy = FakeProxy()
        val service = readyService(proxy)
        service.assertBackend("survival-01", "10.0.0.4:25565", admits = true)
        val backend = proxy.named("survival-01")
        val players =
            List(3) { index -> backend.join(FakePlayer("player$index", "uuid$index", "/198.51.100.$index:4000")) }

        val response = service.assertBackend("survival-01", "10.0.0.4:25565", admits = false)

        response.status shouldBe 200
        // Still registered. This is the assertion that separates a seal from a
        // deregistration, and the side-effect counter is what proves it: nothing
        // was removed and nothing was re-added to compensate.
        proxy.isRegistered("survival-01") shouldBe true
        proxy.deregisterCalls shouldBe 0
        proxy.registerCalls shouldBe 1
        response.json().boolean("registered") shouldBe true
        response.json().boolean("admitsNewPlayers") shouldBe false
        // Every player is still on it.
        backend.connected.size shouldBe 3
        players.forAll { it.connectedTo shouldBe backend }
        proxy.playerCount() shouldBe 3
    }

    @Test
    fun `the seal is read back, so core confirms it rather than assuming its write stuck`() {
        val proxy = FakeProxy()
        val service = readyService(proxy)
        service.assertBackend("survival-01", "10.0.0.4:25565", admits = true)

        service.readState().backend("survival-01").boolean("admitsNewPlayers") shouldBe true
        service.assertBackend("survival-01", "10.0.0.4:25565", admits = false)
        service.readState().backend("survival-01").boolean("admitsNewPlayers") shouldBe false
    }

    @Test
    fun `re-asserting admission lifts the seal, which is how an aborted drain recovers`() {
        val proxy = FakeProxy()
        val service = readyService(proxy)
        service.assertBackend("survival-01", "10.0.0.4:25565", admits = false)

        // There is deliberately no "unseal" operation. The next pass simply states
        // the truth again, which is what makes an abandoned drain self-correcting
        // instead of leaving a backend sealed with nothing knowing to lift it.
        service.assertBackend("survival-01", "10.0.0.4:25565", admits = true)

        service.readState().backend("survival-01").boolean("admitsNewPlayers") shouldBe true
        proxy.registerCalls shouldBe 1
    }

    @Test
    fun `seal state does not survive a restart, and that is the design`() {
        // A new AdmissionRegistry is what a restarted proxy has. If this ever starts
        // returning false, something began persisting the seal — and a backend
        // sealed by an orchestrator that then died would stay sealed forever.
        val admission = AdmissionRegistry()
        admission.assertAdmission("survival-01", admits = false)
        admission.admits("survival-01") shouldBe false

        AdmissionRegistry().admits("survival-01") shouldBe true
    }

    @Test
    fun `a joining player is deflected off a sealed backend rather than refused`() {
        val admission = AdmissionRegistry()
        admission.assertAdmission("survival-01", admits = false)

        val decision = SealPolicy.onInitialChoice("survival-01", listOf("lobby-01", "survival-01"), admission)

        decision.shouldBeInstanceOf<InitialChoice.Redirect>().backend shouldBe "lobby-01"
    }

    @Test
    fun `a joining player with nowhere else to go is let in and counted, never stranded`() {
        // Velocity's ServerPreConnectEvent.denied() on the login path leaves the
        // client on "Connecting to server" until it times out (PaperMC/Velocity
        // 689). A seal that used it there would disconnect the players it exists to
        // protect, so the worst case is an admitted join that is reported.
        val admission = AdmissionRegistry()
        admission.assertAdmission("survival-01", admits = false)

        SealPolicy.onInitialChoice("survival-01", listOf("survival-01"), admission) shouldBe
            InitialChoice.AdmitAnyway
        // And with every candidate sealed, which is a whole fleet draining at once.
        admission.assertAdmission("lobby-01", admits = false)
        SealPolicy.onInitialChoice("survival-01", listOf("lobby-01", "survival-01"), admission) shouldBe
            InitialChoice.AdmitAnyway
    }

    @Test
    fun `an unsealed choice and an absent choice are both left alone`() {
        val admission = AdmissionRegistry()

        SealPolicy.onInitialChoice("survival-01", listOf("survival-01"), admission) shouldBe InitialChoice.Keep
        SealPolicy.onInitialChoice(null, listOf("survival-01"), admission) shouldBe InitialChoice.Keep
    }

    @Test
    fun `an in-game switch onto a sealed backend is refused, which leaves the player where they are`() {
        val admission = AdmissionRegistry()
        admission.assertAdmission("survival-01", admits = false)

        SealPolicy.onServerSwitch("survival-01", playerIsConnected = true, admission) shouldBe SwitchDecision.Refuse
        // The same target for somebody with no server yet is allowed, because
        // refusing it there is the login-path strand rather than a safe no-op — and
        // it is a *distinct* answer from Allow so the listener counts it. A seal that
        // leaks silently is a seal `:core` cannot reason about.
        SealPolicy.onServerSwitch("survival-01", playerIsConnected = false, admission) shouldBe
            SwitchDecision.AllowSealed
        // An unsealed target is always allowed, and is not a leak.
        SealPolicy.onServerSwitch("lobby-01", playerIsConnected = true, admission) shouldBe SwitchDecision.Allow
        SealPolicy.onServerSwitch("lobby-01", playerIsConnected = false, admission) shouldBe SwitchDecision.Allow
    }

    @Test
    fun `the seal matches names the way Velocity resolves them`() {
        val admission = AdmissionRegistry()
        admission.assertAdmission("Survival-01", admits = false)

        // A seal keyed on the exact string :core happened to send is a seal that
        // misses whenever something spells the same backend differently.
        admission.admits("survival-01") shouldBe false
        admission.admits("SURVIVAL-01") shouldBe false
        SealPolicy.onInitialChoice("SURVIVAL-01", listOf("survival-01", "lobby-01"), admission) shouldBe
            InitialChoice.Redirect("lobby-01")
    }

    @Test
    fun `what the seal actually did is observable`() {
        val proxy = FakeProxy()
        val admission = AdmissionRegistry()
        val service = readyService(proxy, admission)
        service.assertBackend("survival-01", "10.0.0.4:25565", admits = false)

        admission.recordRefusedSwitch("survival-01")
        admission.recordRefusedSwitch("survival-01")
        admission.recordDeflectedJoin("survival-01")
        admission.recordAdmittedWithoutAlternative("survival-01")

        val seal = service.readState().backend("survival-01").obj("seal")
        seal.int("refusedSwitches") shouldBe 2
        seal.int("deflectedJoins") shouldBe 1
        // The seal admitting it leaked. Reported rather than hidden: `:core` needs
        // to tell "sealed and holding" from "sealed and still taking players" when
        // it decides whether a transfer sweep has converged.
        seal.int("admittedWithoutAlternative") shouldBe 1
    }

    @Test
    fun `deregistering is refused while anybody is still connected`() {
        val proxy = FakeProxy()
        val service = readyService(proxy)
        service.assertBackend("survival-01", "10.0.0.4:25565", admits = false)
        val backend = proxy.named("survival-01")
        val player = backend.join(FakePlayer("someone", "uuid", "/198.51.100.4:40000"))

        val response = service.deregisterBackend("survival-01")

        // failure-modes item 3, refused at the far end. There is no force flag:
        // reaching this means :core ran step 6 before step 4 finished.
        response.shouldFailWith(ControlErrorCode.BACKEND_OCCUPIED)
        proxy.deregisterCalls shouldBe 0
        proxy.isRegistered("survival-01") shouldBe true
        player.connectedTo shouldBe backend
    }

    @Test
    fun `deregistering an empty backend succeeds and forgets its seal`() {
        val proxy = FakeProxy()
        val service = readyService(proxy)
        service.assertBackend("survival-01", "10.0.0.4:25565", admits = false)

        val response = service.deregisterBackend("survival-01")

        response.status shouldBe 200
        response.json().boolean("deregistered") shouldBe true
        proxy.isRegistered("survival-01") shouldBe false

        // A stale seal would silently apply to a later backend of the same name.
        service.assertBackend("survival-01", "10.0.0.4:25565", admits = true)
        service.readState().backend("survival-01").boolean("admitsNewPlayers") shouldBe true
    }

    @Test
    fun `deregistering something already gone is a success, because the loop re-enters every state`() {
        val proxy = FakeProxy()
        val service = readyService(proxy)

        val response = service.deregisterBackend("never-existed")

        response.status shouldBe 200
        response.json().boolean("deregistered") shouldBe false
        response.json().boolean("registered") shouldBe false
    }

    private fun <T> List<T>.forAll(assertion: (T) -> Unit) = forEach(assertion)
}
