package mcorch.store.sqlite

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import mcorch.schema.PaperServerSpec
import mcorch.schema.SpecBounds
import mcorch.schema.VelocityProxySpec
import mcorch.store.Fixtures
import mcorch.store.getOrThrow
import mcorch.store.logging.CapturedLogs
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.time.Duration.Companion.hours

/**
 * A row on disk carrying a deadline no reader would have accepted.
 *
 * [StoreConformanceSuite][mcorch.store.StoreConformanceSuite] states the rule for
 * every implementation: a definition read back has every deadline inside its
 * ceiling. What is here is the half only this store can be asked about — the row
 * was written by *something else*. Nothing in this build produces a
 * `stopGracePeriod` of thirty hours, so the population the bound exists for is
 * reached the way it is reached in the field: raw SQL against the file.
 *
 * Two properties that the interface-level test cannot see:
 *
 * *The row is not rewritten.* The bound is applied on the way out, so the
 * operator's declared number stays on disk byte-for-byte. Nothing is lost, and a
 * ceiling raised later gives the value back rather than having to reconstruct it.
 *
 * *The clamp is said out loud.* A value quietly reinterpreted on every read is
 * exactly the silent reinterpretation of stored data this codec is written to
 * refuse. Reported like any other thing the store notices but does not raise.
 */
class BoundedDeadlineTest {
    private val stores = TempStores()

    @AfterEach
    fun cleanUp() {
        stores.cleanUp()
    }

    @Test
    fun `a hand-edited deadline is bounded on the way out and left alone on disk`() =
        runTest {
            val directory = stores.directory()
            stores.open(directory).use { store ->
                store.state.putDefinition(Fixtures.definitionNamed("survival-01")).getOrThrow()
                store.state.putDefinition(Fixtures.definitionNamed("survival-02")).getOrThrow()
            }
            // Thirty hours and twenty hours, in whole nanoseconds, written straight
            // into the document. The pair stays legal — the grace period is still
            // ten hours above the save timeout — so this is a row the schema itself
            // accepts and only the reader would have refused.
            val grace = 30.hours.inWholeNanoseconds
            val save = 20.hours.inWholeNanoseconds
            editDocument(directory, "survival-02", "lifecycle.stopGracePeriod", grace)
            editDocument(directory, "survival-02", "lifecycle.drain.saveTimeout", save)

            stores.open(directory).use { store ->
                val spec =
                    store.state
                        .getServer(Fixtures.resourceName("survival-02"))
                        .shouldNotBeNull()
                        .definition.definition.spec
                        .shouldBeInstanceOf<PaperServerSpec>()

                spec.lifecycle.stopGracePeriod shouldBe SpecBounds.MAX_STOP_GRACE_PERIOD
                spec.lifecycle.drain.saveTimeout shouldBe SpecBounds.MAX_SAVE_TIMEOUT

                // The other server is untouched: this is a per-row bound, and it
                // fires on exactly the row that carries the value.
                store.state
                    .getServer(Fixtures.resourceName("survival-01"))
                    .shouldNotBeNull()
                    .definition.definition shouldBe Fixtures.definitionNamed("survival-01")
            }

            // Read from the file, after the store has opened it and served the row:
            // a bound that rewrote what it read would have lost the declared value.
            val stored = documentOf(directory, "survival-02")
            stored shouldContain "lifecycle.stopGracePeriod=$grace"
            stored shouldContain "lifecycle.drain.saveTimeout=$save"
        }

    @Test
    fun `the proxy seal timeout is bounded from a hand-edited row too`() =
        runTest {
            val directory = stores.directory()
            stores.open(directory).use { store ->
                store.state.putDefinition(Fixtures.proxyDefinitionNamed("edge-01")).getOrThrow()
            }
            editDocument(directory, "edge-01", "backends.drain.sealTimeout", 30.hours.inWholeNanoseconds)

            stores.open(directory).use { store ->
                store.state
                    .getServer(Fixtures.resourceName("edge-01"))
                    .shouldNotBeNull()
                    .definition.definition.spec
                    .shouldBeInstanceOf<VelocityProxySpec>()
                    .backends.drain.sealTimeout shouldBe SpecBounds.MAX_HANDSHAKE_TIMEOUT
            }
        }

    @Test
    fun `the clamp names the server and the field an operator would edit`() =
        runTest {
            val directory = stores.directory()
            stores.open(directory).use { store ->
                store.state.putDefinition(Fixtures.definitionNamed("survival-02")).getOrThrow()
            }
            editDocument(directory, "survival-02", "lifecycle.stopGracePeriod", 30.hours.inWholeNanoseconds)

            stores.open(directory).use { store ->
                CapturedLogs.clear()

                store.state.listServers()

                val logs = CapturedLogs.text()
                logs shouldContain "server=survival-02"
                logs shouldContain "field=spec.lifecycle.stopGracePeriod"
                // The declared value and the one actually used, so the line says what
                // changed rather than only that something did.
                logs shouldContain "30h"
                logs shouldContain "2h"
            }
        }

    /**
     * Rewrites one key in a stored spec document to [nanos].
     *
     * Text substitution on the encoded document rather than a re-encode from a
     * `:schema` object, because a re-encode could only ever produce something this
     * build's codec agrees with — and the row this test is about was written by
     * something that is not this build.
     */
    private fun editDocument(
        directory: Path,
        name: String,
        key: String,
        nanos: Long,
    ) {
        val document = documentOf(directory, name)
        val pattern = Regex("^${Regex.escape(key)}=.*$", RegexOption.MULTILINE)
        require(pattern.containsMatchIn(document)) { "no `$key` in the stored spec of `$name`" }
        val edited = pattern.replace(document, "$key=$nanos")
        connection(directory).use { connection ->
            connection.update("UPDATE server_definition SET spec_doc = ? WHERE name = ?") {
                setString(1, edited)
                setString(2, name)
            }
            connection.commit()
        }
    }

    private fun documentOf(
        directory: Path,
        name: String,
    ): String =
        connection(directory).use { connection ->
            connection.query("SELECT spec_doc FROM server_definition WHERE name = ?", { setString(1, name) }) { rows ->
                require(rows.next()) { "no definition row for `$name`" }
                rows.getString("spec_doc")
            }
        }

    private fun connection(directory: Path): Connection =
        DriverManager
            .getConnection("jdbc:sqlite:${directory.resolve("state.db").toAbsolutePath()}")
            .also { it.autoCommit = false }
}
