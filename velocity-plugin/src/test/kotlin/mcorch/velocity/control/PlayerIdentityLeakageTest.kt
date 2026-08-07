package mcorch.velocity.control

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * A player's name, UUID or address never leaves this plugin.
 *
 * CLAUDE.md bans logging them anywhere in the system. A proxy is where the ban
 * matters most: it is the one component that sees every player's name, UUID and
 * IP address, all the time, and it is the component the orchestrator asks
 * questions about occupancy. So the answer is always a count, and this test is
 * the enforcement — the same shape as `:store`'s `SecretLeakageTest`, including
 * its control assertions.
 *
 * The identities are generated per run and are never literals. A literal in a
 * test file is how a real one eventually ends up in one, and it would also make
 * the needle findable in the test's own source.
 */
class PlayerIdentityLeakageTest {
    private val token = "control-token-for-this-test-only-0123456789"

    private fun populatedProxy(): Pair<FakeProxy, List<String>> {
        val proxy = FakeProxy()
        val survival = proxy.add("survival-01", "10.0.0.4:25565")
        val lobby = proxy.add("lobby-01", "10.0.0.5:25565")
        val needles = mutableListOf<String>()
        repeat(3) {
            val username = "player-" + UUID.randomUUID().toString().replace("-", "")
            val uniqueId = UUID.randomUUID().toString()
            // TEST-NET-3, with a random host and ephemeral port, so the needle is
            // shaped exactly like what Velocity would hand over.
            val address = "/203.0.113.${(1..254).random()}:${(20000..60000).random()}"
            survival.join(FakePlayer(username, uniqueId, address))
            needles += listOf(username, uniqueId, address)
        }
        lobby.join(
            FakePlayer(
                "player-" + UUID.randomUUID().toString().replace("-", ""),
                UUID.randomUUID().toString(),
                "/203.0.113.${(1..254).random()}:${(20000..60000).random()}",
            ).also { needles += listOf(it.username, it.uniqueId, it.remoteAddress) },
        )
        return proxy to needles
    }

    @Test
    fun `no response from any route carries a player identity`() {
        val (proxy, needles) = populatedProxy()

        TestEndpoint(proxy, token).use { endpoint ->
            val responses = endpoint.exerciseEverything(token, "survival-01", "lobby-01")

            val everything = responses.joinToString("\n") { "${it.statusCode()} ${it.body()}" }

            // Control: the bodies are real answers about the very backends those
            // players are on, not empty or error-only. Without this the assertion
            // below could pass because there was nothing to search.
            everything shouldContain "survival-01"
            everything shouldContain "lobby-01"
            everything shouldContain ControlProtocol.VERSION
            responses.first().statusCode() shouldBe 200

            for (needle in needles) {
                everything shouldNotContain needle
            }
        }
    }

    @Test
    fun `nothing the endpoint logs carries a player identity`() {
        val (proxy, needles) = populatedProxy()

        TestEndpoint(proxy, token).use { endpoint ->
            endpoint.exerciseEverything(token, "survival-01", "lobby-01")
            // Including the paths that fail, since a failure message is the string
            // least likely to have been read by anybody.
            endpoint.call("GET", "/v1/nothing", bearer = token)
            endpoint.call("PUT", ControlProtocol.PATH_BACKEND + "survival-01", "{ not json", token)
            endpoint.call("GET", ControlProtocol.PATH_STATE, bearer = "the-wrong-token")

            val logs = endpoint.logs.joinToString("\n")

            // Control: the endpoint did log during all that, so the assertion below
            // is about redaction rather than about an empty buffer.
            logs shouldContain "control endpoint listening"
            for (needle in needles) {
                logs shouldNotContain needle
            }
        }
    }

    @Test
    fun `the identities were findable, so the assertions above mean something`() {
        // The control for the whole file. If the fake did not really hold the
        // needles — because a refactor made it hold counts — every assertion above
        // would pass while proving nothing.
        val (proxy, needles) = populatedProxy()

        val revealed = proxy.revealEverything()

        for (needle in needles) {
            revealed shouldContain needle
        }
        proxy.everyone().size shouldBe 4
    }

    @Test
    fun `the state surface has nowhere to put an identity`() {
        // A shape assertion rather than a behavioural one, in the spirit of
        // SecretLeakageTest's: if somebody hangs a player, a name or a UUID off one
        // of the types a read returns, this fails before it has to be noticed in
        // review.
        val surface =
            listOf(
                ControlResponse::class.java,
                TransferOperation::class.java,
                TransferTally::class.java,
                SealCounters::class.java,
                BackendAddress::class.java,
            )
        val identity = PlayerHandle::class.java

        for (type in surface) {
            type.declaredFields
                .filter { identity.isAssignableFrom(it.type) || Iterable::class.java.isAssignableFrom(it.type) }
                .map { "${type.simpleName}.${it.name}" }
                .shouldBe(emptyList())
            type.methods
                .filter { method ->
                    identity.isAssignableFrom(method.returnType) ||
                        method.parameterTypes.any { identity.isAssignableFrom(it) }
                }.map { "${type.simpleName}.${it.name}" }
                .shouldBe(emptyList())
        }
        // Control: the reflection is looking at a type that genuinely does carry
        // identity, so an empty result above is "none of these do" rather than
        // "the check does not work".
        identity.methods.map { it.name }.shouldInclude("getUsername")
    }

    private fun List<String>.shouldInclude(name: String) {
        check(name in this) { "expected `$name` among $this" }
    }
}
