package mcorch.store

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.test.runTest
import mcorch.schema.ControlEndpointSpec
import mcorch.schema.RconSpec
import mcorch.schema.SecretRef
import mcorch.schema.getOrThrow
import mcorch.store.logging.CapturedLogs
import mcorch.store.sqlite.EmbeddedStore
import mcorch.store.sqlite.EmbeddedStoreConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Secret material does not come out of the places it must not come out of.
 *
 * CLAUDE.md invariant 4 says the forwarding secret only ever travels through the
 * secret store: not a definition file, not a log line, not a test fixture. The
 * RCON password is the same shape of thing and arrives first. These tests are the
 * enforcement.
 *
 * The material is generated per test, never a literal — a literal in a test file
 * is how a real one eventually ends up in one.
 */
class SecretLeakageTest {
    private val directories = mutableListOf<Path>()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-07-26T10:15:30Z"), ZoneOffset.UTC)

    @BeforeEach
    fun clearLogs() {
        CapturedLogs.clear()
    }

    @AfterEach
    fun cleanUp() {
        directories.forEach { it.toFile().deleteRecursively() }
        directories.clear()
    }

    private fun directory(): Path = Files.createTempDirectory("mcorch-secret-leak").also { directories.add(it) }

    private fun reveal(value: SecretValue): String = value.use { String(it) }

    @Test
    fun `a secret value prints as a placeholder however it is printed`() {
        val value = SecretValue.random(40)
        val material = reveal(value)

        value.toString() shouldBe SecretValue.REDACTED
        "$value" shouldNotContain material
        listOf(value).toString() shouldNotContain material
        mapOf("password" to value).toString() shouldNotContain material
        IllegalStateException("rcon rejected $value").message.shouldNotBeNull() shouldNotContain material
    }

    @Test
    fun `the state surface has nowhere to put secret material`() {
        // An assertion about the shape of the interface rather than about today's
        // implementation: if somebody hangs a secret off Store or off one of the types
        // it returns, this fails before it has to be noticed in review.
        //
        // A suspend function's return type erases to Object, so reflection cannot see
        // it. What it can see is every parameter, every field of the returned types,
        // and every non-suspend return — and the behavioural tests below cover the
        // rest by looking at what actually comes out.
        val surface =
            listOf(
                Store::class.java,
                StoredDefinition::class.java,
                StoredStatus::class.java,
                StoredServer::class.java,
                ServerChange::class.java,
                WriteOutcome.Applied::class.java,
                WriteOutcome.Conflict::class.java,
            )
        val secret = SecretValue::class.java

        for (type in surface) {
            type.declaredFields
                .filter { secret.isAssignableFrom(it.type) }
                .map { "${type.simpleName}.${it.name}" }
                .shouldBe(emptyList())
            type.methods
                .filter { method ->
                    secret.isAssignableFrom(method.returnType) ||
                        method.parameterTypes.any { secret.isAssignableFrom(it) }
                }.map { "${type.simpleName}.${it.name}" }
                .shouldBe(emptyList())
        }
        // The two are separate interfaces, and neither is reachable from the other.
        SecretStore::class.java.isAssignableFrom(Store::class.java) shouldBe false
        Store::class.java.isAssignableFrom(SecretStore::class.java) shouldBe false
    }

    @Test
    fun `material stored under a reference never appears in what the state store hands back`() =
        runTest {
            val directory = directory()
            val value = SecretValue.random(48)
            val material = reveal(value)
            val definition = Fixtures.definition("full.yaml")
            val reference =
                (definition.spec.network.rcon as RconSpec.Enabled).passwordSecret

            EmbeddedStore.open(EmbeddedStoreConfig(directory = directory, clock = clock)).use { store ->
                store.secrets.put(reference, value)
                store.state.putDefinition(definition).getOrThrow()
                store.state.putStatus(Fixtures.fullStatus(definition.metadata.name.value)).getOrThrow()

                val server = store.state.getServer(definition.metadata.name).shouldNotBeNull()

                // The definition still names the secret — that is the whole point of a
                // reference — but nothing anywhere near it carries the value.
                server.toString() shouldContain reference.name.value
                server.toString() shouldNotContain material
                store.state.listServers().toString() shouldNotContain material
                store.state.changesSince(null).toString() shouldNotContain material
                // Including the read that reports what it could not decode: an
                // `Unreadable` quotes the stored form it rejected, so it is a state read
                // like any other and is held to the same rule.
                store.state.listAll().toString() shouldNotContain material
            }
        }

    /**
     * The same rule for the proxy kind, which is where it matters most.
     *
     * Invariant 4: the Velocity forwarding secret only ever travels through the
     * secret store. A proxy spec is the one document that names it, and it names a
     * *coordinate* — so this asserts the coordinate survives the round trip and
     * the material appears nowhere. The control token is the second reference on
     * the same document and gets the same treatment.
     */
    @Test
    fun `neither proxy secret leaks material into what the state store hands back`() =
        runTest {
            val directory = directory()
            val forwarding = SecretValue.random(48)
            val token = SecretValue.random(32)
            val forwardingMaterial = reveal(forwarding)
            val tokenMaterial = reveal(token)
            val parsed = Fixtures.proxyDefinitionNamed("edge-01")
            val tokenSecret = SecretRef(name = Fixtures.resourceName("edge-control"), key = "token")
            val definition =
                parsed.copy(
                    spec =
                        parsed.spec.copy(
                            control = ControlEndpointSpec(port = 8375, hostPort = 18375, tokenSecret = tokenSecret),
                        ),
                )
            val forwardingRef = definition.spec.forwarding.secret

            EmbeddedStore.open(EmbeddedStoreConfig(directory = directory, clock = clock)).use { store ->
                store.secrets.put(forwardingRef, forwarding)
                store.secrets.put(tokenSecret, token)
                store.state.putDefinition(definition).getOrThrow()
                store.state.putStatus(Fixtures.fullProxyStatus("edge-01")).getOrThrow()

                val server = store.state.getServer(definition.metadata.name).shouldNotBeNull()

                // The control assertions: both coordinates *are* there, so a search
                // over this text is capable of finding something and the two
                // assertions below can actually fail.
                server.toString() shouldContain forwardingRef.name.value
                server.toString() shouldContain forwardingRef.key
                server.toString() shouldContain tokenSecret.name.value
                server.toString() shouldContain tokenSecret.key

                server.toString() shouldNotContain forwardingMaterial
                server.toString() shouldNotContain tokenMaterial
                store.state.listServers().toString() shouldNotContain forwardingMaterial
                store.state.listServers().toString() shouldNotContain tokenMaterial
                store.state.listAll().toString() shouldNotContain forwardingMaterial
                store.state.listAll().toString() shouldNotContain tokenMaterial
            }
        }

    @Test
    fun `material is not written into the state database file`() =
        runTest {
            // The strongest form of "ordinary state reads cannot leak it": it is not in
            // that file at all, so no query, join or backup of it can produce the value.
            val directory = directory()
            val value = SecretValue.random(48)
            val material = reveal(value)
            val definition = Fixtures.definition("full.yaml")
            val reference = (definition.spec.network.rcon as RconSpec.Enabled).passwordSecret

            EmbeddedStore.open(EmbeddedStoreConfig(directory = directory, clock = clock)).use { store ->
                store.secrets.put(reference, value)
                store.state.putDefinition(definition).getOrThrow()
                store.state.putStatus(Fixtures.fullStatus(definition.metadata.name.value)).getOrThrow()
            }

            val needle = material.toByteArray(Charsets.UTF_8)
            val files = directory.toFile().listFiles().orEmpty()
            for (file in files.filter { it.name.startsWith("state.db") }) {
                contains(file.readBytes(), needle) shouldBe false
            }
            // Control: the search would have found it. Without this the assertion above
            // could pass because the needle was never findable in the first place.
            files.filter { it.name.startsWith("secrets.db") }.any { contains(it.readBytes(), needle) } shouldBe true
        }

    @Test
    fun `nothing the store logs contains secret material`() =
        runTest {
            val directory = directory()
            val value = SecretValue.random(48)
            val material = reveal(value)
            val definition = Fixtures.definition("full.yaml")
            val reference = (definition.spec.network.rcon as RconSpec.Enabled).passwordSecret

            EmbeddedStore.open(EmbeddedStoreConfig(directory = directory, clock = clock)).use { store ->
                store.secrets.put(reference, value)
                store.secrets.resolve(reference)
                store.secrets.resolve(SecretRef.of("survival-02-rcon", "absent").getOrThrow())
                store.secrets.removeKey(reference)
                store.state.putDefinition(definition).getOrThrow()
                store.state.putStatus(Fixtures.fullStatus(definition.metadata.name.value)).getOrThrow()
                store.state.deleteDefinition(definition.metadata.name).getOrThrow()
                store.state.purge(definition.metadata.name).getOrThrow()
            }

            val logs = CapturedLogs.text()
            // Control: the store did log during all that, so the assertion below is
            // about redaction rather than about an empty buffer.
            logs shouldContain "opened embedded store"
            logs shouldNotContain material
        }

    @Test
    fun `a destroyed value refuses to be used again`() {
        val value = SecretValue.random(16)

        value.destroy()

        val failure = runCatching { value.use { it.size } }.exceptionOrNull()
        failure.shouldNotBeNull()
        failure.message.shouldNotBeNull() shouldContain "destroyed"
    }

    @Test
    fun `use hands out a copy and wipes it afterwards`() {
        // Returning the array out of `use` is exactly what the KDoc says not to do.
        // Doing it here is how the wipe gets proved.
        val value = SecretValue.random(16)
        val escaped = value.use { it }

        escaped.all { it == '\u0000' } shouldBe true
        // The original is untouched: only the copy was wiped.
        value.use { copy -> copy.none { it == '\u0000' } } shouldBe true
    }

    private fun contains(
        haystack: ByteArray,
        needle: ByteArray,
    ): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        outer@ for (start in 0..haystack.size - needle.size) {
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) continue@outer
            }
            return true
        }
        return false
    }
}
