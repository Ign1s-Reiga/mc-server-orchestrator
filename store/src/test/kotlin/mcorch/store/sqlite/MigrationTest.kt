package mcorch.store.sqlite

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import mcorch.schema.DrainState
import mcorch.schema.PaperServerDefinition
import mcorch.schema.PaperServerStatus
import mcorch.schema.ServerPhase
import mcorch.store.ChangeFeed
import mcorch.store.ChangeKind
import mcorch.store.Fixtures
import mcorch.store.StoreException
import mcorch.store.codec.DefinitionCodec
import mcorch.store.codec.PropertyDocument
import mcorch.store.codec.StatusCodec
import mcorch.store.getOrThrow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

/**
 * The on-disk schema moves forward without losing anything.
 *
 * The old database in these tests is built by running the real migration list
 * stopped at version 1 and then inserting rows with version-1 shaped SQL —
 * carrying documents produced by the real codecs, so the *data* is exactly what a
 * version-1 store would have written. Everything is then read back through the
 * ordinary [mcorch.store.Store] interface: the assertions never look at a column.
 *
 * The store's own code always targets the newest schema, which is why the old
 * database is written by the test rather than by the store. That is the same
 * trade every schema-migration test makes, and the honest half of it is that the
 * *reading* side is the real thing.
 */
class MigrationTest {
    private val stores = TempStores()

    @AfterEach
    fun cleanUp() {
        stores.cleanUp()
    }

    @Test
    fun `data written at version 1 survives the migration to the current version`() =
        runTest {
            val directory = stores.directory()
            val plain = Fixtures.definitionNamed("survival-a")
            val draining = Fixtures.definitionNamed("survival-b")
            val terminating = Fixtures.definitionNamed("survival-c", example = "ephemeral-lobby.yaml")
            val plainStatus = Fixtures.fullStatus("survival-a", phase = ServerPhase.RUNNING)
            val drainingStatus = Fixtures.fullStatus("survival-b", drainState = DrainState.SAVING)
            val deletedAt = Instant.parse("2026-07-20T09:00:00Z")

            writeVersion1Database(directory) { legacy ->
                legacy.definition(plain, generation = 1L, revision = 1L)
                legacy.definition(draining, generation = 4L, revision = 2L)
                legacy.definition(terminating, generation = 2L, revision = 3L, deletedAt = deletedAt)
                legacy.status(plainStatus, revision = 4L)
                legacy.status(drainingStatus, revision = 5L)
            }
            appliedVersions(directory) shouldBe listOf(1)

            stores.open(directory).use { migrated ->
                appliedVersions(directory) shouldBe listOf(1, 2)

                val servers = migrated.state.listServers().associateBy { it.name.value }
                servers.keys shouldBe setOf("survival-a", "survival-b", "survival-c")

                // Definitions, whole objects.
                servers.getValue("survival-a").definition.definition shouldBe plain
                servers.getValue("survival-b").definition.definition shouldBe draining
                servers.getValue("survival-c").definition.definition shouldBe terminating

                // The store's own bookkeeping, which nothing but a migration could restore.
                servers.getValue("survival-a").definition.generation shouldBe 1L
                servers.getValue("survival-b").definition.generation shouldBe 4L
                servers.getValue("survival-c").definition.generation shouldBe 2L
                servers.getValue("survival-c").definition.terminating shouldBe true
                servers.getValue("survival-c").definition.deletedAt shouldBe deletedAt

                // Observations, including the drain that was in flight.
                servers
                    .getValue("survival-a")
                    .status
                    .shouldNotBeNull()
                    .status shouldBe plainStatus
                servers
                    .getValue("survival-b")
                    .status
                    .shouldNotBeNull()
                    .status shouldBe drainingStatus
                servers.getValue("survival-c").status shouldBe null
            }
        }

    @Test
    fun `the drain projection added by version 2 is backfilled from what was already on disk`() =
        runTest {
            // Nothing wrote `drain_state` at version 1 — the column did not exist. If the
            // backfill is wrong, a drain that was in flight when the process died becomes
            // invisible to the query that is meant to find it and resume it.
            val directory = stores.directory()
            writeVersion1Database(directory) { legacy ->
                legacy.definition(Fixtures.definitionNamed("survival-a"), generation = 1L, revision = 1L)
                legacy.definition(Fixtures.definitionNamed("survival-b"), generation = 1L, revision = 2L)
                legacy.definition(Fixtures.definitionNamed("survival-c"), generation = 1L, revision = 3L)
                legacy.status(Fixtures.fullStatus("survival-a", drainState = DrainState.SAVING), revision = 4L)
                legacy.status(Fixtures.fullStatus("survival-b", drainState = DrainState.TRANSFERRING), revision = 5L)
                legacy.status(Fixtures.pendingStatus("survival-c", 1L), revision = 6L)
            }

            stores.open(directory).use { migrated ->
                val inFlight = migrated.state.listByDrainState(setOf(DrainState.SAVING, DrainState.TRANSFERRING))

                inFlight.map { it.name.value }.shouldContainExactly(listOf("survival-a", "survival-b"))
                migrated.state.listByDrainState(setOf(DrainState.SAVING)).map { it.name.value } shouldBe
                    listOf("survival-a")
            }
        }

    @Test
    fun `the change feed and the revision sequence survive the migration`() =
        runTest {
            val directory = stores.directory()
            writeVersion1Database(directory) { legacy ->
                legacy.definition(Fixtures.definitionNamed("survival-a"), generation = 1L, revision = 1L)
                legacy.definition(Fixtures.definitionNamed("survival-b"), generation = 1L, revision = 2L)
            }

            stores.open(directory).use { migrated ->
                val feed = migrated.state.changesSince(null).shouldBeInstanceOf<ChangeFeed.Changes>()
                feed.changes.map { it.name.value }.shouldContainExactly(listOf("survival-a", "survival-b"))

                // The sequence carried on rather than restarting and colliding.
                val next = migrated.state.putDefinition(Fixtures.definitionNamed("survival-c")).getOrThrow()
                next.resourceVersion.token.toLong() shouldBe 3L
            }
        }

    @Test
    fun `writing through a migrated store keeps the projection in step`() =
        runTest {
            val directory = stores.directory()
            writeVersion1Database(directory) { legacy ->
                legacy.definition(Fixtures.definitionNamed("survival-a"), generation = 1L, revision = 1L)
                legacy.status(Fixtures.fullStatus("survival-a", drainState = DrainState.SEALED), revision = 2L)
            }

            stores.open(directory).use { migrated ->
                migrated.state
                    .putStatus(Fixtures.fullStatus("survival-a", drainState = DrainState.SAVING))
                    .getOrThrow()

                migrated.state.listByDrainState(setOf(DrainState.SEALED)) shouldBe emptyList()
                migrated.state.listByDrainState(setOf(DrainState.SAVING)).map { it.name.value } shouldBe
                    listOf("survival-a")
            }
        }

    @Test
    fun `migrating an already-current store applies nothing`() =
        runTest {
            val directory = stores.directory()
            stores.open(directory).use { first ->
                first.state.putDefinition(Fixtures.definitionNamed("survival-a")).getOrThrow()
            }

            stores.open(directory).use { second ->
                appliedVersions(directory) shouldBe listOf(1, 2)
                second.state.listServers().map { it.name.value } shouldBe listOf("survival-a")
            }
        }

    @Test
    fun `a store written by a newer build is refused rather than reinterpreted`() {
        val directory = stores.directory()
        stores.open(directory).close()
        rawConnection(directory).use { connection ->
            connection.update(
                "INSERT INTO schema_migration (version, description, applied_at) VALUES (99, ?, ?)",
            ) {
                setString(1, "from the future")
                setString(2, "2027-01-01T00:00:00Z")
            }
            connection.commit()
        }

        val failure =
            runCatching { stores.open(directory) }
                .exceptionOrNull()
                .shouldBeInstanceOf<StoreException.Unsupported>()

        failure.retryable shouldBe false
        failure.message.shouldNotBeNull() shouldContain "version 99"
    }

    @Test
    fun `state written before a restart is still there afterwards`() =
        runTest {
            // The plainest durability claim there is, and the one a drain depends on:
            // whatever the loop recorded before the process died is what it reads back.
            val directory = stores.directory()
            val definition = Fixtures.definitionNamed("survival-02")
            val status = Fixtures.fullStatus("survival-02", drainState = DrainState.DEREGISTERED)
            stores.open(directory).use { first ->
                first.state.putDefinition(definition).getOrThrow()
                first.state.putStatus(status).getOrThrow()
            }

            stores.open(directory).use { second ->
                val server = second.state.getServer(definition.metadata.name).shouldNotBeNull()

                server.definition.definition shouldBe definition
                server.status.shouldNotBeNull().status shouldBe status
            }
        }

    // ---------------------------------------------------------------- the old disk

    private fun writeVersion1Database(
        directory: Path,
        block: (LegacyWriter) -> Unit,
    ) {
        rawConnection(directory).use { connection ->
            Migrations.migrate(connection, stores.clock, upTo = 1)
            val writer = LegacyWriter(connection)
            block(writer)
            writer.finish()
            connection.commit()
        }
    }

    /** Inserts rows shaped the way version 1 of the schema shaped them. */
    private class LegacyWriter(
        private val connection: Connection,
    ) {
        private var highest = 0L

        fun definition(
            definition: PaperServerDefinition,
            generation: Long,
            revision: Long,
            deletedAt: Instant? = null,
        ) {
            connection.update(
                """
                INSERT INTO server_definition (
                    name, api_version, kind, generation, resource_version,
                    created_at, updated_at, deleted_at, metadata_doc, spec_doc, doc_encoding
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ) {
                setString(1, definition.metadata.name.value)
                setString(2, definition.apiVersion.wireValue)
                setString(3, definition.kind.wireValue)
                setLong(4, generation)
                setLong(5, revision)
                setString(6, CREATED_AT.toString())
                setString(7, CREATED_AT.toString())
                setInstant(8, deletedAt)
                setString(9, DefinitionCodec.encodeMetadata(definition.metadata))
                setString(10, DefinitionCodec.encodeSpec(definition.spec))
                setInt(11, PropertyDocument.ENCODING_VERSION)
            }
            connection.update("INSERT INTO definition_change (revision, name, change_kind, at) VALUES (?, ?, ?, ?)") {
                setLong(1, revision)
                setString(2, definition.metadata.name.value)
                setString(3, if (deletedAt == null) ChangeKind.WRITTEN.name else ChangeKind.DELETED.name)
                setString(4, CREATED_AT.toString())
            }
            highest = maxOf(highest, revision)
        }

        fun status(
            status: PaperServerStatus,
            revision: Long,
        ) {
            // No `drain_state` column: version 1 did not have one. That is the point.
            connection.update(
                """
                INSERT INTO server_status (
                    name, api_version, kind, resource_version, recorded_at, status_doc, doc_encoding
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ) {
                setString(1, status.name.value)
                setString(2, status.apiVersion.wireValue)
                setString(3, status.kind.wireValue)
                setLong(4, revision)
                setString(5, CREATED_AT.toString())
                setString(6, StatusCodec.encode(status))
                setInt(7, PropertyDocument.ENCODING_VERSION)
            }
            highest = maxOf(highest, revision)
        }

        fun finish() {
            connection.update("UPDATE store_sequence SET next_revision = ? WHERE id = 0") { setLong(1, highest) }
        }

        private companion object {
            val CREATED_AT: Instant = Instant.parse("2026-07-20T08:00:00Z")
        }
    }

    private fun rawConnection(directory: Path): Connection =
        DriverManager.getConnection(jdbcUrl(directory)).also { connection ->
            connection.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
            connection.autoCommit = false
        }

    private fun appliedVersions(directory: Path): List<Int> =
        rawConnection(directory).use { connection ->
            connection.query("SELECT version FROM schema_migration ORDER BY version") { rows ->
                val versions = mutableListOf<Int>()
                while (rows.next()) versions += rows.getInt("version")
                versions
            }
        }

    private fun jdbcUrl(directory: Path): String = "jdbc:sqlite:${directory.resolve("state.db").toAbsolutePath()}"
}
