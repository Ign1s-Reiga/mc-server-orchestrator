package mcorch.store.sqlite

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import mcorch.schema.ControlCredential
import mcorch.schema.VelocityProxyStatus
import mcorch.store.Fixtures
import mcorch.store.StatePart
import mcorch.store.getOrThrow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * A proxy status document written before `ControlEndpointStatus.credential`
 * existed.
 *
 * The field arrived inside the status *document* rather than as a column, so no
 * on-disk version moved when it was added and no migration backfills it — the
 * ordinary upgrade path serves rows that have no such key, for as long as a proxy
 * goes without a pass. What those rows recorded is `UNTESTED`, and that is not a
 * default chosen for tidiness: a build with no credential observation made none,
 * and both alternatives invent one. `ACCEPTED` would light a green lamp nobody
 * lit — on precisely the surface the field exists to stop lighting — and
 * `REJECTED` would raise a credential alarm on every stored proxy in the fleet at
 * the instant of an upgrade.
 *
 * The other half is the row that carries a verdict this build does not know. That
 * one is refused, per the codec's standing rule, and refused *per row*: it costs
 * one server its observation and leaves the fleet read intact.
 */
class LegacyControlCredentialTest {
    private val stores = TempStores()

    @AfterEach
    fun cleanUp() {
        stores.cleanUp()
    }

    @Test
    fun `a control record with no credential key reads as untested and is left alone on disk`() =
        runTest {
            val directory = stores.directory()
            val status = Fixtures.fullProxyStatus("edge-01")
            val control = status.control.shouldNotBeNull()
            stores.open(directory).use { store ->
                store.state.putDefinition(Fixtures.proxyDefinitionNamed("edge-01")).getOrThrow()
                store.state.putStatus(status).getOrThrow()
            }
            // The edit that turns a current document into one an older build wrote:
            // the key is gone, not blanked. Done on the encoded document rather than
            // by re-encoding a `:schema` object, because a re-encode can only produce
            // something this build's codec agrees with.
            dropKey(directory, "edge-01", "control.credential")
            documentOf(directory, "edge-01") shouldNotContain "control.credential"

            stores.open(directory).use { store ->
                val served =
                    store.state
                        .getServer(Fixtures.resourceName("edge-01"))
                        .shouldNotBeNull()
                        .status
                        .shouldNotBeNull()
                        .status
                        .shouldBeInstanceOf<VelocityProxyStatus>()
                        .control
                        .shouldNotBeNull()

                // The stored verdict was a different one, so what is read back is the
                // absence rather than the fixture's own value surviving.
                control.credential shouldBe ControlCredential.ACCEPTED
                served.credential shouldBe ControlCredential.UNTESTED
                // And nothing else about the record moved: the two fields that were
                // observed are still what the older build observed.
                served shouldBe control.copy(credential = ControlCredential.UNTESTED)
                // `usable` follows: an untested credential is not a refused one, so
                // an upgrade does not flip every proxy's control badge to red.
                served.usable shouldBe true
            }

            // Read after the store has served the row: a default filled in on the way
            // out must not be written back, or the row starts asserting an
            // observation no process made.
            documentOf(directory, "edge-01") shouldNotContain "control.credential"
        }

    @Test
    fun `a credential verdict this build does not know costs that proxy its observation, not the list`() =
        runTest {
            val directory = stores.directory()
            stores.open(directory).use { store ->
                for (name in listOf("edge-01", "edge-02")) {
                    store.state.putDefinition(Fixtures.proxyDefinitionNamed(name)).getOrThrow()
                    store.state.putStatus(Fixtures.fullProxyStatus(name)).getOrThrow()
                }
            }
            setKey(directory, "edge-02", "control.credential", "PROVISIONALLY_ACCEPTED")

            stores.open(directory).use { store ->
                val servers = store.state.listServers()

                servers.map { it.name.value } shouldBe listOf("edge-01", "edge-02")
                servers.single { it.name.value == "edge-01" }.status.shouldNotBeNull()

                val bad = servers.single { it.name.value == "edge-02" }
                val unreadable = bad.unreadable.shouldNotBeNull()
                unreadable.part shouldBe StatePart.OBSERVED
                unreadable.reason shouldContain "PROVISIONALLY_ACCEPTED"
            }
        }

    /** Removes a whole key line from a stored status document. */
    private fun dropKey(
        directory: Path,
        name: String,
        key: String,
    ) {
        val document = documentOf(directory, name)
        val pattern = Regex("^${Regex.escape(key)}=.*\n?", RegexOption.MULTILINE)
        require(pattern.containsMatchIn(document)) { "no `$key` in the stored status of `$name`" }
        write(directory, name, pattern.replace(document, ""))
    }

    /** Replaces the value of an existing key in a stored status document. */
    private fun setKey(
        directory: Path,
        name: String,
        key: String,
        value: String,
    ) {
        val document = documentOf(directory, name)
        val pattern = Regex("^${Regex.escape(key)}=.*$", RegexOption.MULTILINE)
        require(pattern.containsMatchIn(document)) { "no `$key` in the stored status of `$name`" }
        write(directory, name, pattern.replace(document, "$key=$value"))
    }

    private fun write(
        directory: Path,
        name: String,
        document: String,
    ) {
        connection(directory).use { connection ->
            connection.update("UPDATE server_status SET status_doc = ? WHERE name = ?") {
                setString(1, document)
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
            connection.query("SELECT status_doc FROM server_status WHERE name = ?", { setString(1, name) }) { rows ->
                require(rows.next()) { "no status row for `$name`" }
                rows.getString("status_doc")
            }
        }

    private fun connection(directory: Path): Connection =
        DriverManager
            .getConnection("jdbc:sqlite:${directory.resolve("state.db").toAbsolutePath()}")
            .also { it.autoCommit = false }
}
