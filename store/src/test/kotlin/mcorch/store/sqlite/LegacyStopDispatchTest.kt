package mcorch.store.sqlite

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import mcorch.schema.DrainState
import mcorch.schema.PaperServerStatus
import mcorch.store.Fixtures
import mcorch.store.getOrThrow
import mcorch.store.logging.CapturedLogs
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * A status document written before `DrainStatus.stopDispatchedAt` existed.
 *
 * [StoreConformanceSuite][mcorch.store.StoreConformanceSuite] states the rule for
 * every implementation: a drain in `STOPPING` comes back carrying the record that
 * a container stop request left this process. What is here is the half only this
 * store can be asked about — the document has **no such key at all**, which is
 * what an older build actually wrote and what an implementation holding objects
 * cannot express.
 *
 * The gap is reachable by the ordinary upgrade path because the field arrived
 * inside the document rather than as a column: no on-disk version moved when it
 * was added, and none of V1..V5 backfills it. The cost of reading its absence at
 * face value is not a repeat side effect but a *reversal* — the pass concludes no
 * stop was dispatched, deletes the drain record when the operator reverts the edit
 * that asked for the replacement, and the proxy's sweep re-registers a backend
 * whose shutdown save has already run.
 *
 * Two properties the interface-level test cannot see:
 *
 * *The row is not rewritten.* The reconstruction is applied on the way out, so the
 * document is byte-for-byte what the older build wrote. Nothing is spent to fix it
 * and nothing on disk becomes a value no process observed — the reconcile loop
 * persists the record itself on the first pass that acts on it.
 *
 * *The reconstruction is said out loud.* A stored observation quietly reinterpreted
 * on a read is the silent reinterpretation of stored data this codec refuses.
 */
class LegacyStopDispatchTest {
    private val stores = TempStores()

    @AfterEach
    fun cleanUp() {
        stores.cleanUp()
    }

    @Test
    fun `a stopping drain with no dispatch key is served the record and left alone on disk`() =
        runTest {
            val directory = stores.directory()
            val status = Fixtures.fullStatus("survival-02", drainState = DrainState.STOPPING)
            val drain = status.drain.shouldNotBeNull()
            stores.open(directory).use { store ->
                store.state.putDefinition(Fixtures.definitionNamed("survival-01")).getOrThrow()
                store.state.putDefinition(Fixtures.definitionNamed("survival-02")).getOrThrow()
                store.state.putStatus(status).getOrThrow()
                store.state.putStatus(Fixtures.fullStatus("survival-01", drainState = DrainState.SAVING)).getOrThrow()
            }
            // The edit that turns a current document into one an older build wrote:
            // the key is gone, not blanked. Done on the encoded document rather than
            // by re-encoding a `:schema` object, because a re-encode can only produce
            // something this build's codec agrees with, and the row this is about was
            // written by something that is not this build.
            dropKey(directory, "survival-02", "drain.stopDispatchedAt")
            documentOf(directory, "survival-02") shouldNotContain "drain.stopDispatchedAt"

            stores.open(directory).use { store ->
                val served =
                    store.state
                        .getServer(Fixtures.resourceName("survival-02"))
                        .shouldNotBeNull()
                        .status
                        .shouldNotBeNull()
                        .status
                        .shouldBeInstanceOf<PaperServerStatus>()
                        .drain
                        .shouldNotBeNull()

                // Reconstructed, from the transition into `STOPPING` — the pass on
                // which the stop request returned cleanly. Not the instant the
                // document used to carry: that one is gone and inventing it back
                // would be a claim this build cannot make.
                served.stopDispatchedAt shouldBe drain.enteredStateAt
                served.stopDispatchedAt shouldNotBe drain.stopDispatchedAt
                // And nothing else about the record moved.
                served shouldBe drain.copy(stopDispatchedAt = drain.enteredStateAt)

                // Per row. The other server's drain is short of a stop and is served
                // exactly as stored — a reconstruction that fired on the read rather
                // than on the record would have stamped this one too.
                store.state
                    .getServer(Fixtures.resourceName("survival-01"))
                    .shouldNotBeNull()
                    .status
                    .shouldNotBeNull()
                    .status
                    .shouldBeInstanceOf<PaperServerStatus>()
                    .drain
                    .shouldNotBeNull()
                    .stopDispatchedAt
                    .shouldBeNull()
            }

            // Read from the file after the store has opened it and served the row: a
            // reconstruction that wrote itself back would be recording as observed
            // something no process observed.
            documentOf(directory, "survival-02") shouldNotContain "drain.stopDispatchedAt"
        }

    @Test
    fun `the reconstruction names the server, the field and where the instant came from`() =
        runTest {
            val directory = stores.directory()
            stores.open(directory).use { store ->
                store.state.putDefinition(Fixtures.definitionNamed("survival-02")).getOrThrow()
                store.state
                    .putStatus(Fixtures.fullStatus("survival-02", drainState = DrainState.STOPPING))
                    .getOrThrow()
            }
            dropKey(directory, "survival-02", "drain.stopDispatchedAt")

            stores.open(directory).use { store ->
                CapturedLogs.clear()

                store.state.listServers()

                val logs = CapturedLogs.text()
                logs shouldContain "server=survival-02"
                logs shouldContain "field=status.drain.stopDispatchedAt"
                // Where the substitute came from, so the line says what was inferred
                // and from what rather than only that something was.
                logs shouldContain "status.drain.enteredStateAt"

                // The row's condition, never its provenance. `STOPPING` has a second
                // producer that dispatches nothing and stamps nothing, so the current
                // build writes rows this fires on: a line claiming the observation
                // came from an older build would be false most times it printed.
                logs shouldNotContain "was not recorded by the build"

                // And `info`, not `warn`, for the same reason — see the note on
                // `SqliteStore.decodeStatus`. That routine population makes a
                // recurring line ordinary rather than a fault, and warning on an
                // ordinary path only teaches a reader to ignore warnings. Not
                // `debug` either: a reinterpreted read that an ordinary deployment
                // cannot see is the silent reinterpretation the codec refuses.
                val line = CapturedLogs.snapshot().single { it.contains("field=status.drain.stopDispatchedAt") }
                line shouldStartWith "INFO "
            }
        }

    /**
     * A legacy row must not become one nobody can get rid of.
     *
     * The reconstruction reports a stop that may not have happened, which is the
     * safe direction for routing and the dangerous one for a lifecycle: a rule that
     * made a populated, world-holding server undeletable is what ends in a manual
     * `crictl stop`. `:store` holds no drain-state guard on either call — the
     * decision lives in `:core`, where the container observation is — and this is
     * the assertion that says so for the row the reconstruction fires on.
     */
    @Test
    fun `a terminating server carrying a legacy stopping drain still deletes and purges`() =
        runTest {
            val directory = stores.directory()
            stores.open(directory).use { store ->
                store.state.putDefinition(Fixtures.definitionNamed("survival-02")).getOrThrow()
                store.state
                    .putStatus(Fixtures.fullStatus("survival-02", drainState = DrainState.STOPPING))
                    .getOrThrow()
            }
            dropKey(directory, "survival-02", "drain.stopDispatchedAt")

            stores.open(directory).use { store ->
                val name = Fixtures.resourceName("survival-02")
                store.state.deleteDefinition(name).getOrThrow()

                // Still readable while tombstoned, still carrying the record: the
                // drain that is running has the spec it needs and the loop still
                // knows a stop is outstanding.
                val tombstoned = store.state.getServer(name).shouldNotBeNull()
                tombstoned.definition.terminating shouldBe true
                tombstoned.status
                    .shouldNotBeNull()
                    .status
                    .shouldBeInstanceOf<PaperServerStatus>()
                    .drain
                    .shouldNotBeNull()
                    .stopDispatchedAt
                    .shouldNotBeNull()

                store.state.purge(name).getOrThrow()
                store.state.getServer(name).shouldBeNull()
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
        val edited = pattern.replace(document, "")
        connection(directory).use { connection ->
            connection.update("UPDATE server_status SET status_doc = ? WHERE name = ?") {
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
