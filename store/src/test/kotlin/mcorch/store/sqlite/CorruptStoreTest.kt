package mcorch.store.sqlite

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import mcorch.store.Fixtures
import mcorch.store.StoreException
import mcorch.store.getOrThrow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * What happens when the disk says something this build cannot make sense of.
 *
 * The rule is the same one the schema-version discipline exists for: refuse, with
 * a permanent failure the loop will surface rather than spin on. Every case here
 * would otherwise be a silent reinterpretation of stored data.
 */
class CorruptStoreTest {
    private val stores = TempStores()

    @AfterEach
    fun cleanUp() {
        stores.cleanUp()
    }

    @Test
    fun `a document written with a newer encoding is refused`() =
        runTest {
            val directory = stores.directory()
            stores.open(directory).use { store ->
                store.state.putDefinition(Fixtures.definitionNamed("survival-02")).getOrThrow()
            }
            mutate(directory, "UPDATE server_definition SET doc_encoding = 99")

            stores.open(directory).use { store ->
                val failure =
                    runCatching { store.state.listServers() }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<StoreException.Unsupported>()

                failure.retryable shouldBe false
                failure.message.shouldNotBeNull() shouldContain "encoding 99"
            }
        }

    @Test
    fun `a kind this build does not know is refused`() =
        runTest {
            val directory = stores.directory()
            stores.open(directory).use { store ->
                store.state.putDefinition(Fixtures.definitionNamed("survival-02")).getOrThrow()
            }
            mutate(directory, "UPDATE server_definition SET kind = 'VelocityProxy'")

            stores.open(directory).use { store ->
                val failure =
                    runCatching { store.state.listServers() }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<StoreException.Unsupported>()

                failure.message.shouldNotBeNull() shouldContain "VelocityProxy"
            }
        }

    @Test
    fun `an unreadable status document is a permanent failure, not a crash mid-loop`() =
        runTest {
            val directory = stores.directory()
            stores.open(directory).use { store ->
                store.state.putDefinition(Fixtures.definitionNamed("survival-02")).getOrThrow()
                store.state.putStatus(Fixtures.fullStatus("survival-02")).getOrThrow()
            }
            mutate(directory, "UPDATE server_status SET status_doc = 'phase=NOT_A_PHASE'")

            stores.open(directory).use { store ->
                val failure =
                    runCatching { store.state.listServers() }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<StoreException.Corrupt>()

                failure.retryable shouldBe false
                failure.message.shouldNotBeNull() shouldContain "survival-02"
            }
        }

    private fun mutate(
        directory: Path,
        sql: String,
    ) {
        connection(directory).use { connection ->
            connection.update(sql)
            connection.commit()
        }
    }

    private fun connection(directory: Path): Connection =
        DriverManager
            .getConnection("jdbc:sqlite:${directory.resolve("state.db").toAbsolutePath()}")
            .also { it.autoCommit = false }
}
