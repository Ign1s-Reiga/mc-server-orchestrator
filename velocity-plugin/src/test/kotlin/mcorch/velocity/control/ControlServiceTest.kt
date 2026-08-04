package mcorch.velocity.control

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * The control surface `:core` will be written against: routing, the version
 * handshake, and the registry assertions.
 *
 * The registration tests assert on [FakeProxy.registerCalls] and
 * [FakeProxy.deregisterCalls] rather than on returned statuses, because the
 * invariant is about what was done to the proxy. A status is a report, and a
 * wrong report is what these exist to catch.
 */
class ControlServiceTest {
    @Test
    fun `the version handshake answers before the proxy is ready`() {
        // ControlEndpointStatus has to distinguish a proxy that is still starting
        // from one that is not there. If the handshake waited for readiness the two
        // would be the same silence, and only one of them is a failure.
        val service = ControlService(FakeProxy(), AdmissionRegistry())

        val response = service.handle("GET", ControlProtocol.PATH_VERSION, "")

        response.status shouldBe 200
        response.json().string("pluginApiVersion") shouldBe ControlProtocol.VERSION
        response.json().boolean("ready") shouldBe false
    }

    @Test
    fun `every other route refuses until the proxy has started`() {
        val service = ControlService(FakeProxy(), AdmissionRegistry())

        service.readState().shouldFailWith(ControlErrorCode.NOT_READY)
        service
            .assertBackend("survival-01", "10.0.0.4:25565", admits = true)
            .shouldFailWith(ControlErrorCode.NOT_READY)
    }

    @Test
    fun `the handshake advertises the version it speaks and the set it accepts`() {
        val service = readyService(FakeProxy())

        val body = service.handle("GET", ControlProtocol.PATH_VERSION, "").json()

        body.string("plugin") shouldBe ControlProtocol.PLUGIN_ID
        body.string("pluginApiVersion") shouldBe ControlProtocol.VERSION
        body.array("supported").map { (it as JsonString).value } shouldBe ControlProtocol.SUPPORTED
        // The rule `:core` applies, stated here so a change to it fails a test
        // rather than only failing in a fleet.
        ControlProtocol.isCompatibleWith(ControlProtocol.SUPPORTED) shouldBe true
        ControlProtocol.isCompatibleWith(listOf("0")) shouldBe false
        ControlProtocol.isCompatibleWith(emptyList()) shouldBe false
        // Membership, not ordering: a newer plugin serving an older core is the
        // case a `>=` rule would get wrong.
        ControlProtocol.isCompatibleWith(listOf("0", ControlProtocol.VERSION, "99")) shouldBe true
    }

    @Test
    fun `asserting a backend registers it exactly once however many times it is asserted`() {
        val proxy = FakeProxy()
        val service = readyService(proxy)

        repeat(5) {
            service.assertBackend("survival-01", "10.0.0.4:25565", admits = true).status shouldBe 200
        }

        // The side effect, not the status: FakeProxy throws on a second
        // registerServer for the same name, so a regression here fails loudly.
        proxy.registerCalls shouldBe 1
        proxy.deregisterCalls shouldBe 0
        proxy.isRegistered("survival-01") shouldBe true
    }

    @Test
    fun `re-asserting a backend at a different address is refused rather than re-registered`() {
        val proxy = FakeProxy()
        val service = readyService(proxy)
        service.assertBackend("survival-01", "10.0.0.4:25565", admits = true)

        val response = service.assertBackend("survival-01", "10.0.0.9:25565", admits = true)

        response.shouldFailWith(ControlErrorCode.ADDRESS_CONFLICT)
        // The whole point: the only way Velocity can move a registration is
        // unregister-then-register, and an unregister here is drain step 6 run at
        // step 2. Nothing was removed and nothing was re-added.
        proxy.deregisterCalls shouldBe 0
        proxy.registerCalls shouldBe 1
        proxy.named("survival-01").address shouldBe "10.0.0.4:25565"
    }

    @Test
    fun `a refused address does not silently refuse the seal that came with it`() {
        // PUT carries two independent facts and the seal has nothing to do with the
        // address. If :core ever computes the PUT address from the desired
        // definition rather than the running container — the natural thing to
        // write — then the first seal of a replacement drain returns 409, and a
        // seal that did not apply means step 4 transfers players out of a backend
        // that is still admitting new ones.
        val proxy = FakeProxy()
        val service = readyService(proxy)
        service.assertBackend("survival-01", "10.0.0.4:25565", admits = true)

        service
            .assertBackend("survival-01", "10.0.0.9:25565", admits = false)
            .shouldFailWith(ControlErrorCode.ADDRESS_CONFLICT)

        service.readState().backend("survival-01").boolean("admitsNewPlayers") shouldBe false
    }

    @Test
    fun `a backend asserted as sealed is never registered in an admitting state first`() {
        // The window between register and a later assertAdmission is one Velocity
        // will route a joining player through. The fake records the order.
        val proxy = FakeProxy()
        val admission = AdmissionRegistry()
        val ordering = mutableListOf<String>()
        val recording =
            object : ProxyControl by proxy {
                override fun register(
                    name: String,
                    host: String,
                    port: Int,
                ) {
                    ordering += "register admits=${admission.admits(name)}"
                    proxy.register(name, host, port)
                }
            }
        val service = ControlService(recording, admission).also { it.markReady() }

        service.assertBackend("survival-01", "10.0.0.4:25565", admits = false)

        ordering shouldBe listOf("register admits=false")
    }

    @Test
    fun `the proxy incarnation is reported, so core can see a restart lifted its seals`() {
        // Seal state is soft by design. Because :core asserts and then reads, the
        // read alone cannot tell a seal that held from one lifted moments ago —
        // both answer false. This is the signal that tells them apart.
        val proxy = FakeProxy()
        val first = ControlService(proxy, AdmissionRegistry(), clock = { 111_000L }).also { it.markReady() }
        val restarted = ControlService(proxy, AdmissionRegistry(), clock = { 222_000L }).also { it.markReady() }

        first
            .readState()
            .json()
            .obj("proxy")
            .long("startedAtEpochMs") shouldBe 111_000L
        restarted
            .readState()
            .json()
            .obj("proxy")
            .long("startedAtEpochMs") shouldBe 222_000L
    }

    @Test
    fun `a backend is addressed case-insensitively, as Velocity addresses it`() {
        val proxy = FakeProxy()
        val service = readyService(proxy)
        service.assertBackend("Survival-01", "10.0.0.4:25565", admits = true)

        // A second assert under a different spelling must find the same backend, or
        // it would register a duplicate and seal only one of them.
        service.assertBackend("survival-01", "10.0.0.4:25565", admits = false).status shouldBe 200

        proxy.registerCalls shouldBe 1
        service.readState().backend("Survival-01").boolean("admitsNewPlayers") shouldBe false
    }

    @Test
    fun `a malformed body is refused and changes nothing`() {
        val proxy = FakeProxy()
        val service = readyService(proxy)

        service
            .handle("PUT", ControlProtocol.PATH_BACKEND + "a", "not json")
            .shouldFailWith(ControlErrorCode.MALFORMED_REQUEST)
        // A missing field is never defaulted: a body whose `admitsNewPlayers` was
        // dropped in transit must not read as "admits".
        service
            .handle("PUT", ControlProtocol.PATH_BACKEND + "a", """{"address":"10.0.0.4:25565"}""")
            .shouldFailWith(ControlErrorCode.MALFORMED_REQUEST)
        service
            .handle("PUT", ControlProtocol.PATH_BACKEND + "a", """{"admitsNewPlayers":true}""")
            .shouldFailWith(ControlErrorCode.MALFORMED_REQUEST)
        service
            .handle("PUT", ControlProtocol.PATH_BACKEND + "a", """{"address":"nonsense","admitsNewPlayers":true}""")
            .shouldFailWith(ControlErrorCode.MALFORMED_REQUEST)

        proxy.registerCalls shouldBe 0
    }

    @Test
    fun `unknown routes and wrong methods are refused distinctly`() {
        val service = readyService(FakeProxy())

        service.handle("GET", "/v1/nothing", "").shouldFailWith(ControlErrorCode.NO_SUCH_ROUTE)
        service.handle("POST", ControlProtocol.PATH_STATE, "").shouldFailWith(ControlErrorCode.METHOD_NOT_ALLOWED)
        service.handle("GET", ControlProtocol.PATH_PROXY, "").shouldFailWith(ControlErrorCode.METHOD_NOT_ALLOWED)
        service
            .handle(
                "GET",
                ControlProtocol.PATH_BACKEND + "a",
                "",
            ).shouldFailWith(ControlErrorCode.METHOD_NOT_ALLOWED)
        service.handle("POST", ControlProtocol.PATH_VERSION, "").shouldFailWith(ControlErrorCode.METHOD_NOT_ALLOWED)
        // A path that names no single backend is not a backend route.
        service.handle("PUT", ControlProtocol.PATH_BACKEND, "{}").shouldFailWith(ControlErrorCode.NO_SUCH_ROUTE)
        service.handle("PUT", ControlProtocol.PATH_BACKEND + "a/b", "{}").shouldFailWith(ControlErrorCode.NO_SUCH_ROUTE)
    }

    @Test
    fun `the proxy's own admission is asserted and read back`() {
        val proxy = FakeProxy()
        val service = readyService(proxy)

        service
            .readState()
            .json()
            .obj("proxy")
            .boolean("admitsNewPlayers") shouldBe true

        service.handle("PUT", ControlProtocol.PATH_PROXY, """{"admitsNewPlayers":false}""").status shouldBe 200
        service
            .readState()
            .json()
            .obj("proxy")
            .boolean("admitsNewPlayers") shouldBe false

        // Level-triggered in both directions: there is no "unseal", only a restated
        // assertion, so an aborted proxy drain recovers by the ordinary path.
        service.handle("PUT", ControlProtocol.PATH_PROXY, """{"admitsNewPlayers":true}""").status shouldBe 200
        service
            .readState()
            .json()
            .obj("proxy")
            .boolean("admitsNewPlayers") shouldBe true
    }

    @Test
    fun `state reports counts for every backend and never a player identity field`() {
        val proxy = FakeProxy()
        val service = readyService(proxy)
        service.assertBackend("survival-01", "10.0.0.4:25565", admits = true)
        service.assertBackend("lobby-01", "10.0.0.5:25565", admits = true)
        proxy.named("survival-01").join(FakePlayer("someone", "uuid", "/198.51.100.9:41234"))
        proxy.named("survival-01").join(FakePlayer("another", "uuid2", "/198.51.100.10:41235"))

        val state = service.readState()

        state.json().obj("proxy").int("players") shouldBe 2
        state.backend("survival-01").int("players") shouldBe 2
        state.backend("lobby-01").int("players") shouldBe 0
        state.backend("survival-01").string("address") shouldBe "10.0.0.4:25565"
        state.backend("survival-01").boolean("registered") shouldBe true
        state.backend("survival-01").isNull("transfer") shouldBe true
        // Backends come back in a deterministic order so a dashboard and a diff of
        // two passes do not churn.
        state.json().array("backends").map { (it as JsonObject).string("name") } shouldBe
            listOf("lobby-01", "survival-01")
    }

    @Test
    fun `an unexpected failure below the service is reported, not swallowed`() {
        // ControlService turns a ControlFailure into a response; anything else has
        // to reach the transport, which classifies it as INTERNAL and logs the type.
        // Asserting it here keeps `handle` from growing a catch-all of its own.
        val exploding =
            object : ProxyControl by FakeProxy() {
                override fun backends(): List<BackendHandle> = throw IllegalStateException("the proxy fell over")
            }
        val service = ControlService(exploding, AdmissionRegistry()).also { it.markReady() }

        val thrown = runCatching { service.readState() }.exceptionOrNull()

        thrown.let { it as? IllegalStateException }?.message shouldContain "fell over"
    }
}
