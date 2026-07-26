package mcorch.store.sqlite

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import mcorch.schema.ResourceName
import mcorch.schema.SecretRef
import mcorch.schema.getOrThrow
import mcorch.store.SecretStore
import mcorch.store.SecretValue
import mcorch.store.StoreException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * The secret store's own contract.
 *
 * Written against [SecretStore] rather than the SQLite class, for the same reason
 * [mcorch.store.StoreConformanceSuite] is: the single-host implementation is one
 * of the ones this has to hold for. Values in these tests are generated, never
 * literals — CLAUDE.md invariant 4 rules out putting real secret material in a
 * fixture, and a made-up literal in a test file is exactly the habit that leads to
 * a real one turning up in a YAML.
 */
class SqliteSecretStoreTest {
    private val stores = TempStores()

    @AfterEach
    fun cleanUp() {
        stores.cleanUp()
    }

    private fun withSecrets(block: suspend (SecretStore) -> Unit) =
        runTest {
            stores.open(stores.directory()).use { store -> block(store.secrets) }
        }

    private fun ref(
        name: String,
        key: String,
    ): SecretRef = SecretRef.of(name, key).getOrThrow()

    private fun name(raw: String): ResourceName = ResourceName.of(raw).getOrThrow()

    @Test
    fun `a stored secret resolves to the same material`() =
        withSecrets { secrets ->
            val reference = ref("survival-02-rcon", "password")
            val value = SecretValue.random(48)

            secrets.put(reference, value)

            secrets.resolve(reference).shouldNotBeNull() shouldBe value
        }

    @Test
    fun `material survives a round trip through non-ascii and awkward characters`() =
        withSecrets { secrets ->
            // A generated secret is ASCII, but nothing stops an operator supplying one
            // that is not, and a store that mangles it fails at authentication time.
            val reference = ref("survival-02-rcon", "password")
            val value = SecretValue.of("pässwörd-æ世界- \t-end".toCharArray())

            secrets.put(reference, value)

            secrets.resolve(reference).shouldNotBeNull() shouldBe value
        }

    @Test
    fun `keys under one name are independent`() =
        withSecrets { secrets ->
            val password = ref("survival-02-rcon", "password")
            val other = ref("survival-02-rcon", "fallback")
            val first = SecretValue.random(32)
            val second = SecretValue.random(32)

            secrets.put(password, first)
            secrets.put(other, second)

            secrets.resolve(password).shouldNotBeNull() shouldBe first
            secrets.resolve(other).shouldNotBeNull() shouldBe second
        }

    @Test
    fun `writing again replaces the material`() =
        withSecrets { secrets ->
            val reference = ref("survival-02-rcon", "password")
            val replacement = SecretValue.random(32)
            secrets.put(reference, SecretValue.random(32))

            secrets.put(reference, replacement)

            secrets.resolve(reference).shouldNotBeNull() shouldBe replacement
        }

    @Test
    fun `an unknown reference resolves to nothing`() =
        withSecrets { secrets ->
            secrets.put(ref("survival-02-rcon", "password"), SecretValue.random(16))

            secrets.resolve(ref("survival-02-rcon", "missing")).shouldBeNull()
            secrets.resolve(ref("not-stored", "password")).shouldBeNull()
            secrets.contains(ref("not-stored", "password")) shouldBe false
        }

    @Test
    fun `contains answers without handing out material`() =
        withSecrets { secrets ->
            val reference = ref("survival-02-rcon", "password")
            secrets.put(reference, SecretValue.random(16))

            secrets.contains(reference) shouldBe true
        }

    @Test
    fun `removal takes one key or every key under a name`() =
        withSecrets { secrets ->
            secrets.put(ref("survival-02-rcon", "password"), SecretValue.random(16))
            secrets.put(ref("survival-02-rcon", "fallback"), SecretValue.random(16))
            secrets.put(ref("lobby-01-rcon", "password"), SecretValue.random(16))

            secrets.removeKey(ref("survival-02-rcon", "fallback")) shouldBe true
            secrets.removeKey(ref("survival-02-rcon", "fallback")) shouldBe false
            secrets.listKeys(name("survival-02-rcon")) shouldBe
                listOf("password")

            secrets.removeSecret(name("survival-02-rcon")) shouldBe 1
            secrets.listNames().map { it.value }.shouldContainExactly(listOf("lobby-01-rcon"))
        }

    @Test
    fun `listing returns coordinates and nothing else`() =
        withSecrets { secrets ->
            secrets.put(ref("survival-02-rcon", "password"), SecretValue.random(16))
            secrets.put(ref("lobby-01-rcon", "password"), SecretValue.random(16))

            secrets.listNames().map { it.value }.shouldContainExactly(listOf("lobby-01-rcon", "survival-02-rcon"))
            secrets
                .listKeys(name("lobby-01-rcon"))
                .shouldContainExactly(listOf("password"))
        }

    @Test
    fun `secrets survive a restart`() =
        runTest {
            val directory = stores.directory()
            val reference = ref("survival-02-rcon", "password")
            val value = SecretValue.random(48)
            stores.open(directory).use { first -> first.secrets.put(reference, value) }

            stores.open(directory).use { second ->
                second.secrets.resolve(reference).shouldNotBeNull() shouldBe value
            }
        }

    @Test
    fun `a closed secret store refuses to be used`() =
        runTest {
            val store = stores.open(stores.directory())
            store.close()

            val failure =
                runCatching { store.secrets.listNames() }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<StoreException.Closed>()

            failure.retryable shouldBe false
        }
}
