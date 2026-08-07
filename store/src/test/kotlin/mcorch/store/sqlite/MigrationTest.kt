package mcorch.store.sqlite

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import mcorch.schema.ConditionStatus
import mcorch.schema.ConditionType
import mcorch.schema.DrainBlockReason
import mcorch.schema.DrainState
import mcorch.schema.FailureClass
import mcorch.schema.FailureReason
import mcorch.schema.PaperServerDefinition
import mcorch.schema.PaperServerSpec
import mcorch.schema.PaperServerStatus
import mcorch.schema.SchemaVersion
import mcorch.schema.ServerKind
import mcorch.schema.ServerPhase
import mcorch.schema.SpecBounds
import mcorch.store.ChangeFeed
import mcorch.store.ChangeKind
import mcorch.store.Fixtures
import mcorch.store.StoreException
import mcorch.store.codec.DefinitionCodec
import mcorch.store.codec.PropertyDocument
import mcorch.store.codec.StatusCodec
import mcorch.store.getOrThrow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import kotlin.time.Duration.Companion.hours

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
                appliedVersions(directory) shouldBe listOf(1, 2, 3, 4, 5, 6)

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
                appliedVersions(directory) shouldBe listOf(1, 2, 3, 4, 5, 6)
                second.state.listServers().map { it.name.value } shouldBe listOf("survival-a")
            }
        }

    /**
     * A row that was already out of range when the upgrade found it.
     *
     * Deadlines on a stored spec are bounded on the way *out*
     * ([mcorch.schema.SpecBounds]), and nothing on the way in rewrites them. That
     * is the answer to "what happens to an existing row that is out of range", and
     * it is deliberate on both halves:
     *
     * *No migration rewrites the value.* Capping it on disk would discard the
     * number the operator declared, and a migration must not lose data. The bound
     * needs no on-disk change to reach an old row — the decode applies it whatever
     * version wrote the row — so a migration here would spend a version number to
     * destroy information.
     *
     * *No migration refuses the store either.* A store that will not open is worse
     * than one that opens with one server's timeout shortened, and the row is not
     * corrupt: it satisfies the schema, it just declares a wait this build will not
     * perform.
     *
     * So this asserts the upgrade path end to end: written at version 1, migrated
     * through every version, still on disk unchanged, and served bounded.
     */
    @Test
    fun `a deadline already out of range at version 1 survives the migration and is bounded on read`() =
        runTest {
            val directory = stores.directory()
            val grace = 30.hours
            val save = 20.hours
            val running = Fixtures.unboundedDefinition("survival-a", stopGracePeriod = grace, saveTimeout = save)
            val terminating = Fixtures.unboundedDefinition("survival-b", stopGracePeriod = grace, saveTimeout = save)
            val deletedAt = Instant.parse("2026-07-20T09:00:00Z")

            writeVersion1Database(directory) { legacy ->
                legacy.definition(running, generation = 1L, revision = 1L)
                legacy.definition(terminating, generation = 1L, revision = 2L, deletedAt = deletedAt)
                legacy.status(Fixtures.fullStatus("survival-b", drainState = DrainState.SAVING), revision = 3L)
            }
            appliedVersions(directory) shouldBe listOf(1)

            stores.open(directory).use { migrated ->
                appliedVersions(directory) shouldBe listOf(1, 2, 3, 4, 5, 6)

                val spec =
                    migrated.state
                        .getServer(Fixtures.resourceName("survival-a"))
                        .shouldNotBeNull()
                        .definition.definition.spec
                        .shouldBeInstanceOf<PaperServerSpec>()
                spec.lifecycle.stopGracePeriod shouldBe SpecBounds.MAX_STOP_GRACE_PERIOD
                spec.lifecycle.drain.saveTimeout shouldBe SpecBounds.MAX_SAVE_TIMEOUT
                // Not bounded, and this is the half a "tidy it up" change would break:
                // these two are compared against a wall clock, never waited on.
                spec.lifecycle.startupTimeout shouldBe running.spec.lifecycle.startupTimeout
                spec.lifecycle.drain.playerTransferTimeout shouldBe
                    running.spec.lifecycle.drain.playerTransferTimeout

                // The tombstoned one is still deletable, which is the point of a clamp
                // rather than a refusal: the drain it is in the middle of has a spec to
                // work from, and the purge that ends it goes through.
                val tombstoned = migrated.state.getServer(Fixtures.resourceName("survival-b")).shouldNotBeNull()
                tombstoned.definition.terminating shouldBe true
                tombstoned.definition.deletedAt shouldBe deletedAt
                migrated.state.deleteDefinition(Fixtures.resourceName("survival-b")).getOrThrow()
                migrated.state.purge(Fixtures.resourceName("survival-b")).getOrThrow()
            }

            // No data loss: the declared values are on disk exactly as version 1 wrote
            // them, after five migrations and a read that served them bounded.
            val document = specDocumentOf(directory, "survival-a")
            document shouldContain "lifecycle.stopGracePeriod=${grace.inWholeNanoseconds}"
            document shouldContain "lifecycle.drain.saveTimeout=${save.inWholeNanoseconds}"
        }

    /**
     * A drain that was mid-stop when the field recording the stop was introduced.
     *
     * `DrainStatus.stopDispatchedAt` lives inside the status document rather than in
     * a column, so no on-disk version moved when it was added and none of V1..V5
     * backfills it. The answer is the same shape as the deadline case above — the
     * record is restored on the way *out*
     * ([mcorch.schema.StatusReconstruction]) — and it is deliberate on both halves
     * for one reason each of its own:
     *
     * *No migration writes the stamp.* The row is not wrong; it was exactly right
     * for the build that wrote it. Writing an instant no process observed into
     * storage makes an inference indistinguishable from an observation for ever
     * after, and the reconcile loop persists the reconstruction itself on the first
     * pass that acts on it — so a migration would spend a version number on work
     * that happens one pass later anyway.
     *
     * *No migration refuses the store either.* An orchestrator that will not open is
     * every drain stalled, and the document satisfies the schema in full.
     *
     * So this asserts the upgrade path end to end: written at version 1, migrated
     * through every version, still keyless on disk, and served with the record.
     */
    @Test
    fun `a drain stopping since before the dispatch field survives the migration and is served the record`() =
        runTest {
            val directory = stores.directory()
            val running = Fixtures.definitionNamed("survival-a")
            val terminating = Fixtures.definitionNamed("survival-b")
            val deletedAt = Instant.parse("2026-07-20T09:00:00Z")

            writeVersion1Database(directory) { legacy ->
                legacy.definition(running, generation = 1L, revision = 1L)
                legacy.definition(terminating, generation = 1L, revision = 2L, deletedAt = deletedAt)
                legacy.status(stoppingBeforeTheField("survival-a"), revision = 3L)
                legacy.status(stoppingBeforeTheField("survival-b"), revision = 4L)
            }
            appliedVersions(directory) shouldBe listOf(1)
            // The fixture's premise, asserted rather than assumed: these documents
            // are built by the current encoder from a null, which is byte-identical
            // to what a build with no such field wrote. An encoder that started
            // emitting the key for a null would leave this test passing while it
            // stopped testing anything.
            statusDocument(directory, "survival-a") shouldNotContain "drain.stopDispatchedAt"

            stores.open(directory).use { migrated ->
                appliedVersions(directory) shouldBe listOf(1, 2, 3, 4, 5, 6)

                val drain =
                    migrated.state
                        .getServer(Fixtures.resourceName("survival-a"))
                        .shouldNotBeNull()
                        .status
                        .shouldNotBeNull()
                        .status
                        .shouldBeInstanceOf<PaperServerStatus>()
                        .drain
                        .shouldNotBeNull()
                drain.state shouldBe DrainState.STOPPING
                drain.stopDispatchedAt shouldBe drain.enteredStateAt
                // The projection the migrations built is keyed on the drain state,
                // which the reconstruction does not touch, so a resumed drain is
                // still found by the query that finds it.
                migrated.state.listByDrainState(setOf(DrainState.STOPPING)).map { it.name.value } shouldBe
                    listOf("survival-a", "survival-b")

                // The tombstoned one still goes. Reporting a dispatch that may not
                // have happened is the safe direction for routing and the dangerous
                // one for a lifecycle: a rule that made a populated, world-holding
                // server undeletable is what ends in a manual `crictl stop`.
                val tombstoned = migrated.state.getServer(Fixtures.resourceName("survival-b")).shouldNotBeNull()
                tombstoned.definition.terminating shouldBe true
                tombstoned.definition.deletedAt shouldBe deletedAt
                migrated.state.deleteDefinition(Fixtures.resourceName("survival-b")).getOrThrow()
                migrated.state.purge(Fixtures.resourceName("survival-b")).getOrThrow()
                migrated.state.getServer(Fixtures.resourceName("survival-b")).shouldBeNull()
            }

            // No data loss and no data invented: the document is what version 1 held,
            // after five migrations and a read that served the record.
            statusDocument(directory, "survival-a") shouldBe
                StatusCodec.encode(stoppingBeforeTheField("survival-a"))
        }

    /**
     * A `STOPPING` drain recorded the way a build with no `stopDispatchedAt` field
     * recorded one: every other key, and that key absent.
     */
    private fun stoppingBeforeTheField(name: String): PaperServerStatus {
        val status = Fixtures.fullStatus(name, drainState = DrainState.STOPPING)
        return status.copy(drain = status.drain?.copy(stopDispatchedAt = null))
    }

    // ------------------------------------------------------------------ version 4

    /**
     * Version 4 rejects a row with no name — and must not cost anything to get
     * there.
     *
     * The documented way to add `NOT NULL` in SQLite is to rebuild the table, and
     * on this schema `DROP TABLE server_definition` with foreign keys on runs an
     * implicit `DELETE FROM` that cascades into `server_status` and empties it.
     * That would delete every stored observation on every existing store — which
     * is where "the save request already went out" lives. So the check is a
     * trigger, and this test is the proof that the data is all still there
     * afterwards, statuses most of all.
     */
    @Test
    fun `version 4 rejects unnamed rows without touching the data already stored`() =
        runTest {
            val directory = stores.directory()
            val definition = Fixtures.definitionNamed("survival-a")
            val status = Fixtures.fullStatus("survival-a", drainState = DrainState.SAVING)
            writeVersion3Database(directory) { legacy ->
                legacy.definition(definition, generation = 3L, revision = 1L)
                legacy.statusWithProjection(status, revision = 2L)
            }
            appliedVersions(directory) shouldBe listOf(1, 2, 3)

            stores.open(directory).use { migrated ->
                appliedVersions(directory) shouldBe listOf(1, 2, 3, 4, 5, 6)

                // Nothing was dropped, renamed or cascaded away.
                val server = migrated.state.getServer(definition.metadata.name).shouldNotBeNull()
                server.definition.definition shouldBe definition
                server.definition.generation shouldBe 3L
                server.status.shouldNotBeNull().status shouldBe status
                migrated.state.listByDrainState(setOf(DrainState.SAVING)).map { it.name.value } shouldBe
                    listOf("survival-a")
            }

            // And the hole is shut: the insert that used to succeed now does not.
            val refused =
                runCatching {
                    rawConnection(directory).use { connection ->
                        connection.update(
                            """
                            INSERT INTO server_definition (
                                name, api_version, kind, generation, resource_version,
                                created_at, updated_at, deleted_at, metadata_doc, spec_doc, doc_encoding
                            )
                            SELECT NULL, api_version, kind, generation, resource_version + 1000,
                                   created_at, updated_at, deleted_at, metadata_doc, spec_doc, doc_encoding
                              FROM server_definition
                            """.trimIndent(),
                        )
                        connection.commit()
                    }
                }
            refused
                .exceptionOrNull()
                .shouldNotBeNull()
                .message
                .shouldNotBeNull() shouldContain "must not be NULL"
        }

    /**
     * A store that *already* holds an unnamed row still opens.
     *
     * This is the reason the migration does not rebuild the table with a real
     * `NOT NULL`: the copy would refuse exactly the row the migration exists
     * because of, and the store written to be repaired would be the one that
     * stops opening. The row is left where it is — reported as unreadable on
     * every read, which is how an operator finds out about it — and everything
     * else carries on.
     */
    @Test
    fun `a store that already holds an unnamed row still migrates and still opens`() =
        runTest {
            val directory = stores.directory()
            writeVersion3Database(directory) { legacy ->
                legacy.definition(Fixtures.definitionNamed("survival-a"), generation = 1L, revision = 1L)
                legacy.statusWithProjection(
                    Fixtures.fullStatus("survival-a", drainState = DrainState.SAVING),
                    revision = 2L,
                )
            }
            rawConnection(directory).use { connection ->
                connection.update(
                    """
                    INSERT INTO server_definition (
                        name, api_version, kind, generation, resource_version,
                        created_at, updated_at, deleted_at, metadata_doc, spec_doc, doc_encoding
                    )
                    SELECT NULL, api_version, kind, generation, resource_version + 1000,
                           created_at, updated_at, deleted_at, metadata_doc, spec_doc, doc_encoding
                      FROM server_definition
                    """.trimIndent(),
                )
                connection.commit()
            }

            stores.open(directory).use { migrated ->
                appliedVersions(directory) shouldBe listOf(1, 2, 3, 4, 5, 6)

                val listing = migrated.state.listAll()
                listing.servers.map { it.name.value } shouldBe listOf("survival-a")
                listing.servers
                    .single()
                    .status
                    .shouldNotBeNull()
                listing.unreadable
                    .single()
                    .name
                    .shouldBeNull()
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

    /**
     * The inversion version 3 exists to prevent.
     *
     * Before it, a completed save was recorded as `worldSaved=true` plus
     * `saveRequestedAt=T`. The new code does not look at the flag, so the same
     * bytes read as *a request went out at T and was never confirmed* — the
     * drain's permanent-wedge condition. Every in-flight drain holding a
     * confirmed save would come back from the upgrade needing a human, and the
     * operator would be told to go and verify a world already on disk.
     *
     * The old document is written **by hand**, key by key, not by today's codec.
     * Round-tripping through the current encoder would write `worldSavedAt` and
     * the test could not see the inversion at all.
     */
    @Test
    fun `a save confirmed before version 3 still reads as confirmed, not as an outstanding request`() =
        runTest {
            val directory = stores.directory()
            val confirmedAt = Instant.parse("2026-07-20T08:30:00Z")

            writeVersion2Database(directory) { legacy ->
                legacy.definition(Fixtures.definitionNamed("survival-a"), generation = 1L, revision = 1L)
                legacy.definition(Fixtures.definitionNamed("survival-b"), generation = 1L, revision = 2L)
                legacy.legacyStatus(
                    name = "survival-a",
                    revision = 3L,
                    drainState = DrainState.DEREGISTERED,
                    worldSaved = true,
                    saveRequestedAt = confirmedAt,
                )
                // The wedge: a request that went out and never came back. It has
                // to survive untouched, or the next pass sends a second
                // `save-all flush` to a live server.
                legacy.legacyStatus(
                    name = "survival-b",
                    revision = 4L,
                    drainState = DrainState.SAVING,
                    worldSaved = false,
                    saveRequestedAt = confirmedAt,
                )
            }
            appliedVersions(directory) shouldBe listOf(1, 2)

            stores.open(directory).use { migrated ->
                appliedVersions(directory) shouldBe listOf(1, 2, 3, 4, 5, 6)

                val confirmed = drainOf(migrated, "survival-a")
                confirmed.worldSavedAt shouldBe confirmedAt
                confirmed.worldSaved shouldBe true
                // The old key must not linger as an outstanding request beside
                // the confirmation, or the next `SAVING` wedges on it.
                confirmed.saveRequestedAt.shouldBeNull()
                confirmed.state shouldBe DrainState.DEREGISTERED

                val wedged = drainOf(migrated, "survival-b")
                wedged.saveRequestedAt shouldBe confirmedAt
                wedged.worldSavedAt.shouldBeNull()
                wedged.worldSaved shouldBe false
                wedged.state shouldBe DrainState.SAVING
            }
        }

    @Test
    fun `version 3 leaves every other field of a drain alone`() =
        runTest {
            val directory = stores.directory()
            writeVersion2Database(directory) { legacy ->
                legacy.definition(Fixtures.definitionNamed("survival-a"), generation = 1L, revision = 1L)
                legacy.legacyStatus(
                    name = "survival-a",
                    revision = 2L,
                    drainState = DrainState.SAVING,
                    worldSaved = false,
                    saveRequestedAt = null,
                )
            }

            stores.open(directory).use { migrated ->
                val drain = drainOf(migrated, "survival-a")

                // Everything the hand-written document carried, still there. A
                // rewrite that dropped unrelated keys would be silent otherwise.
                drain.startedAt shouldBe LEGACY_DRAIN_STARTED_AT
                drain.enteredStateAt shouldBe LEGACY_DRAIN_ENTERED_AT
                drain.sealRequestedAt shouldBe LEGACY_SEAL_REQUESTED_AT
                drain.playersEvacuated shouldBe true
                drain.transferAttempts shouldBe 4
                drain.destination.shouldNotBeNull().value shouldBe "lobby-01"
                drain.saveRequestedAt.shouldBeNull()
                drain.worldSavedAt.shouldBeNull()
            }
        }

    /**
     * The documented degradation: `worldSaved=true` with no instant beside it.
     *
     * There is no timestamp to carry, so the confirmation is dropped rather than
     * invented. The drain saves again, which costs one `save-all flush` on a
     * server it has already confirmed empty — the safe direction. Inventing a
     * `worldSavedAt` here would date the evidence to whenever the upgrade
     * happened and let a stop proceed on it, which is invariant 3 broken by a
     * migration.
     *
     * No combination of the code that wrote these rows produces this shape, so
     * the fixture is hand-built like the others. That is exactly why it needs a
     * test: nothing else exercises the branch, and the file it lives in may
     * never be edited again.
     */
    @Test
    fun `a version 2 confirmation with no timestamp is dropped rather than dated by the migration`() =
        runTest {
            val directory = stores.directory()
            writeVersion2Database(directory) { legacy ->
                legacy.definition(Fixtures.definitionNamed("survival-a"), generation = 1L, revision = 1L)
                legacy.legacyStatus(
                    name = "survival-a",
                    revision = 2L,
                    drainState = DrainState.SAVING,
                    worldSaved = true,
                    saveRequestedAt = null,
                )
            }
            appliedVersions(directory) shouldBe listOf(1, 2)

            stores.open(directory).use { migrated ->
                appliedVersions(directory) shouldBe listOf(1, 2, 3, 4, 5, 6)
                val drain = drainOf(migrated, "survival-a")

                // Undated evidence is no evidence. Nothing may appear here.
                drain.worldSavedAt.shouldBeNull()
                drain.worldSaved shouldBe false
                // Nor may it reappear one field over as a request that was never
                // issued: that would wedge the drain instead of re-saving it.
                drain.saveRequestedAt.shouldBeNull()

                // Degrading toward saving again means the drain is still where it
                // was, with everything it needs to run the save once more.
                drain.state shouldBe DrainState.SAVING
                drain.startedAt shouldBe LEGACY_DRAIN_STARTED_AT
                drain.playersEvacuated shouldBe true
            }
        }

    /**
     * The unrecognised-encoding refusal, the other branch nothing reaches by
     * accident.
     *
     * Skipping a row it could not read would leave that drain's confirmed save
     * looking like an outstanding request — the whole inversion version 3 exists
     * to prevent, reintroduced quietly for the rows the migration understood
     * least. So it refuses, before writing anything.
     */
    @Test
    fun `a status document at an encoding version 3 does not understand is refused, and nothing is rewritten`() {
        val directory = stores.directory()
        val confirmedAt = Instant.parse("2026-07-20T08:30:00Z")
        writeVersion2Database(directory) { legacy ->
            legacy.definition(Fixtures.definitionNamed("survival-a"), generation = 1L, revision = 1L)
            legacy.legacyStatus(
                name = "survival-a",
                revision = 2L,
                drainState = DrainState.DEREGISTERED,
                worldSaved = true,
                saveRequestedAt = confirmedAt,
                encoding = UNREADABLE_ENCODING,
            )
        }

        val failure =
            runCatching { stores.open(directory) }
                .exceptionOrNull()
                .shouldBeInstanceOf<StoreException.MigrationFailed>()

        failure.retryable shouldBe false
        failure.cause
            .shouldBeInstanceOf<StoreException.Corrupt>()
            .message
            .shouldNotBeNull()
            .shouldContain("survival-a")

        // Refusing has to be free. One transaction per migration means the store
        // is still at version 2 with the row exactly as it was, so the operator
        // can downgrade the binary and open it again.
        appliedVersions(directory) shouldBe listOf(1, 2)
        val document = statusDocument(directory, "survival-a")
        document shouldContain "drain.worldSaved=true"
        document shouldContain "drain.saveRequestedAt=$confirmedAt"
    }

    /**
     * A hand-edited row carrying the old flag *and* a new `worldSavedAt`.
     *
     * Nothing that wrote these rows produces it, so it is only reachable from a
     * database somebody edited. The migration refuses either way — but it has to
     * refuse in the store's own vocabulary. `:core` classifies failures by
     * [StoreException.retryable] and has nothing to do with a raw
     * `IllegalArgumentException` escaping from a `require` inside the document
     * writer, which is the "classify failures, do not let them leak" rule.
     */
    @Test
    fun `a hand-edited row holding both the old flag and a confirmation fails as a store error`() {
        val directory = stores.directory()
        val requestedAt = Instant.parse("2026-07-20T08:30:00Z")
        writeVersion2Database(directory) { legacy ->
            legacy.definition(Fixtures.definitionNamed("survival-a"), generation = 1L, revision = 1L)
            legacy.legacyStatus(
                name = "survival-a",
                revision = 2L,
                drainState = DrainState.DEREGISTERED,
                worldSaved = true,
                saveRequestedAt = requestedAt,
                worldSavedAt = Instant.parse("2026-07-20T08:45:00Z"),
            )
        }

        val failure =
            runCatching { stores.open(directory) }
                .exceptionOrNull()
                .shouldBeInstanceOf<StoreException.MigrationFailed>()

        failure.retryable shouldBe false
        // Enough to find the row and to know which two keys disagree.
        val corrupt =
            failure.cause
                .shouldBeInstanceOf<StoreException.Corrupt>()
                .message
                .shouldNotBeNull()
        corrupt shouldContain "survival-a"
        corrupt shouldContain "drain.worldSaved"
        corrupt shouldContain "drain.worldSavedAt"

        appliedVersions(directory) shouldBe listOf(1, 2)
    }

    /**
     * The change of meaning version 5 exists to absorb.
     *
     * Before it, a drain waiting for players to log off recorded
     * `drain.failure.reason=DRAIN_NO_DESTINATION`. That reason now means
     * something else — *the search ran and the fleet had no capacity* — which
     * escalates once it is older than `drainAttentionAfter`. So the same bytes,
     * read by the new code, turn every drain that was quietly waiting out a busy
     * evening into a call for a human, and the operator is sent to look at a
     * server where people are simply playing. That is the alert fatigue this whole
     * change removes, delivered by the upgrade that removes it.
     *
     * `survival-b` carries `DRAIN_AWAITING_ZERO_PLAYERS`, which no released build
     * wrote but a dev store opened against one commit of the branch could hold.
     * Its failure mode is louder — the value is not in the enum any more, so the
     * row would not decode at all — and covering it costs one entry in a set.
     */
    @Test
    fun `a drain blocked on players before version 5 comes back blocked, not failed`() =
        runTest {
            val directory = stores.directory()
            val blockedSince = Instant.parse("2026-07-20T08:05:00Z")
            val message = "blocked: no drain destination. 3 of 20 player slots are in use"

            writeVersion4Database(directory) { legacy ->
                legacy.definition(Fixtures.definitionNamed("survival-a"), generation = 1L, revision = 1L)
                legacy.definition(Fixtures.definitionNamed("survival-b"), generation = 1L, revision = 2L)
                legacy.blockedLegacyStatus(
                    name = "survival-a",
                    revision = 3L,
                    reason = "DRAIN_NO_DESTINATION",
                    occurredAt = blockedSince,
                    attempts = 37,
                    message = message,
                )
                legacy.blockedLegacyStatus(
                    name = "survival-b",
                    revision = 4L,
                    reason = "DRAIN_AWAITING_ZERO_PLAYERS",
                    occurredAt = blockedSince,
                    attempts = 4,
                    message = message,
                )
            }
            appliedVersions(directory) shouldBe listOf(1, 2, 3, 4)

            stores.open(directory).use { migrated ->
                appliedVersions(directory) shouldBe listOf(1, 2, 3, 4, 5, 6)

                for (name in listOf("survival-a", "survival-b")) {
                    val status =
                        migrated.state
                            .getServer(Fixtures.resourceName(name))
                            .shouldNotBeNull()
                            .status
                            .shouldNotBeNull()
                            .status
                            .shouldBeInstanceOf<PaperServerStatus>()
                    val drain = status.drain.shouldNotBeNull()

                    val blocked = drain.blocked.shouldNotBeNull()
                    blocked.reason shouldBe DrainBlockReason.AWAITING_ZERO_PLAYERS
                    // A rename, not a re-derivation. "Waiting since" must not jump
                    // to the moment of the upgrade.
                    blocked.since shouldBe blockedSince
                    blocked.message shouldBe message
                    // The assertions the migration exists for.
                    drain.failure.shouldBeNull()
                    // The reconciler mirrors a drain's failure one level up, so
                    // the same fact was on disk twice and both copies have to go.
                    status.failure.shouldBeNull()
                }

                // The count carries across as the number of times the loop has
                // looked, so it does not restart at one.
                drainOf(migrated, "survival-a").blocked.shouldNotBeNull().observations shouldBe 37
                drainOf(migrated, "survival-b").blocked.shouldNotBeNull().observations shouldBe 4

                // No data loss: everything the hand-written document carried that
                // has nothing to do with the failure is still there. A rewrite
                // that dropped unrelated keys would otherwise be silent.
                val status =
                    migrated.state
                        .getServer(Fixtures.resourceName("survival-a"))
                        .shouldNotBeNull()
                        .status
                        .shouldNotBeNull()
                        .status
                        .shouldBeInstanceOf<PaperServerStatus>()
                status.phase shouldBe ServerPhase.RUNNING
                status.ready shouldBe true
                status.players.shouldNotBeNull().online shouldBe 3
                status.players.shouldNotBeNull().max shouldBe 20
                status.storage.shouldNotBeNull().lastSaveConfirmedAt shouldBe LEGACY_SEAL_REQUESTED_AT
                status.storage
                    .shouldNotBeNull()
                    .volumeName
                    .shouldNotBeNull()
                    .value shouldBe "survival-a-world"
                status.conditions.single().type shouldBe ConditionType.DRAINING
                status.conditions.single().lastTransitionAt shouldBe LEGACY_DRAIN_ENTERED_AT

                val drain = status.drain.shouldNotBeNull()
                // Still parked. `DRAIN_FAILED` means *not advancing*, and only the
                // record beside it says whether that is bad news — so the state and
                // the projection column keep their value.
                drain.state shouldBe DrainState.DRAIN_FAILED
                drain.startedAt shouldBe LEGACY_DRAIN_STARTED_AT
                drain.enteredStateAt shouldBe LEGACY_DRAIN_ENTERED_AT
                drain.sealRequestedAt shouldBe LEGACY_SEAL_REQUESTED_AT
                drain.playersEvacuated shouldBe false
                drain.transferAttempts shouldBe 0
            }
        }

    /**
     * The control, and it is what makes the test above a test of the *reason*
     * rather than of "version 5 deletes failures".
     *
     * A drain that genuinely failed keeps its failure, its class, its attempt
     * count and its first-occurrence time. Nothing about it is a block.
     */
    @Test
    fun `version 5 leaves a drain failure it was not written about completely alone`() =
        runTest {
            val directory = stores.directory()
            val occurredAt = Instant.parse("2026-07-20T08:05:00Z")
            writeVersion4Database(directory) { legacy ->
                legacy.definition(Fixtures.definitionNamed("survival-a"), generation = 1L, revision = 1L)
                legacy.blockedLegacyStatus(
                    name = "survival-a",
                    revision = 2L,
                    reason = FailureReason.DRAIN_SAVE_TIMEOUT.name,
                    occurredAt = occurredAt,
                    attempts = 9,
                    message = "save completion not confirmed within the save timeout",
                )
            }

            stores.open(directory).use { migrated ->
                appliedVersions(directory) shouldBe listOf(1, 2, 3, 4, 5, 6)

                val status =
                    migrated.state
                        .getServer(Fixtures.resourceName("survival-a"))
                        .shouldNotBeNull()
                        .status
                        .shouldNotBeNull()
                        .status
                        .shouldBeInstanceOf<PaperServerStatus>()
                val failure =
                    status.drain
                        .shouldNotBeNull()
                        .failure
                        .shouldNotBeNull()
                failure.reason shouldBe FailureReason.DRAIN_SAVE_TIMEOUT
                failure.failureClass shouldBe FailureClass.RETRYABLE
                failure.occurredAt shouldBe occurredAt
                failure.attempts shouldBe 9
                status.drain
                    .shouldNotBeNull()
                    .blocked
                    .shouldBeNull()
                // And the mirrored copy, which is only dropped for a retired reason.
                status.failure.shouldNotBeNull().reason shouldBe FailureReason.DRAIN_SAVE_TIMEOUT
            }
        }

    /**
     * Version 6, whose whole job is to make a *later* absence mean something.
     *
     * The decode already answers zero for a document with no `drain.faultLedger`
     * key, so this migration changes no behaviour at all — which is exactly why
     * the assertion has to be on the **stored bytes**. Read through the store, a
     * migration that did nothing whatsoever would produce an identical result and
     * this test would pass having measured nothing.
     *
     * The second half is the guard: a status with no drain on it must not come
     * back with a stray `drain.faultLedger`, because `readDrain` decides a drain
     * exists by looking for `drain.state` and would answer null for a document
     * that now has drain keys in it. A future reader would have every reason to
     * call that corrupt.
     *
     * And the ledger is **not** derived from the failure sitting on the row. The
     * drain below has nine recorded attempts and still migrates to zero: those
     * attempts are the count of one standing failure, which every recovery resets,
     * and reading them as a fault history would hand a drain that had been
     * retrying honestly a head start toward an escalation nobody observed.
     */
    @Test
    fun `version 6 stamps an explicit zero ledger on a stored drain and leaves everything else alone`() =
        runTest {
            val directory = stores.directory()
            val occurredAt = Instant.parse("2026-07-20T08:05:00Z")
            writeVersion5Database(directory) { legacy ->
                legacy.definition(Fixtures.definitionNamed("survival-a"), generation = 1L, revision = 1L)
                legacy.definition(Fixtures.definitionNamed("survival-b"), generation = 1L, revision = 2L)
                legacy.blockedLegacyStatus(
                    name = "survival-a",
                    revision = 3L,
                    reason = FailureReason.DRAIN_SAVE_TIMEOUT.name,
                    occurredAt = occurredAt,
                    attempts = 9,
                    message = "save completion not confirmed within the save timeout",
                )
                // No drain at all. `StatusCodec` writes no `drain.*` key for one,
                // so this is the shape the guard is about.
                legacy.status(
                    Fixtures.fullStatus("survival-b", phase = ServerPhase.RUNNING).copy(drain = null),
                    revision = 4L,
                )
            }

            // The premise: version 5 wrote no such key. Without this the assertions
            // below would pass against a migration that never ran.
            statusDocument(directory, "survival-a") shouldNotContain "drain.faultLedger"

            stores.open(directory).use { migrated ->
                appliedVersions(directory) shouldBe listOf(1, 2, 3, 4, 5, 6)

                statusDocument(directory, "survival-a") shouldContain "drain.faultLedger=0"
                statusDocument(directory, "survival-b") shouldNotContain "faultLedger"

                val drained =
                    migrated.state
                        .getServer(Fixtures.resourceName("survival-a"))
                        .shouldNotBeNull()
                        .status
                        .shouldNotBeNull()
                        .status
                        .shouldBeInstanceOf<PaperServerStatus>()
                val drain = drained.drain.shouldNotBeNull()
                drain.faultLedger shouldBe 0
                // Zero from a row whose one failure has been retried nine times.
                drain.failure.shouldNotBeNull().attempts shouldBe 9
                drain.failure.shouldNotBeNull().occurredAt shouldBe occurredAt

                val undrained =
                    migrated.state
                        .getServer(Fixtures.resourceName("survival-b"))
                        .shouldNotBeNull()
                        .status
                        .shouldNotBeNull()
                        .status
                        .shouldBeInstanceOf<PaperServerStatus>()
                undrained.drain.shouldBeNull()
            }
        }

    /**
     * The same refusal version 3 makes, for the same reason.
     *
     * A row at an encoding this migration was not written against is refused
     * before anything is written. Skipping it would leave that drain's healthy
     * wait reading as a fleet-capacity failure — the change of meaning version 5
     * exists to absorb, reintroduced quietly for the rows it understood least.
     */
    @Test
    fun `a status document at an encoding version 5 does not understand is refused, and nothing is rewritten`() {
        val directory = stores.directory()
        writeVersion4Database(directory) { legacy ->
            legacy.definition(Fixtures.definitionNamed("survival-a"), generation = 1L, revision = 1L)
            legacy.blockedLegacyStatus(
                name = "survival-a",
                revision = 2L,
                reason = "DRAIN_NO_DESTINATION",
                occurredAt = Instant.parse("2026-07-20T08:05:00Z"),
                attempts = 2,
                message = "3 of 20 player slots are in use",
                encoding = UNREADABLE_ENCODING,
            )
        }

        val failure =
            runCatching { stores.open(directory) }
                .exceptionOrNull()
                .shouldBeInstanceOf<StoreException.MigrationFailed>()

        failure.retryable shouldBe false
        failure.cause
            .shouldBeInstanceOf<StoreException.Corrupt>()
            .message
            .shouldNotBeNull()
            .shouldContain("survival-a")

        // One transaction per migration, so the store is still at version 4 with
        // the row exactly as it was and the operator can downgrade and reopen.
        appliedVersions(directory) shouldBe listOf(1, 2, 3, 4)
        statusDocument(directory, "survival-a") shouldContain "drain.failure.reason=DRAIN_NO_DESTINATION"
    }

    /**
     * A row that already carries a whole `drain.blocked` record *and* a retired
     * failure. The copy loop keeps the former and the rewrite would write the
     * latter on top, so the two would collide.
     *
     * The refusal is the point: nothing that wrote these documents could produce
     * both, so the row was edited by hand and the migration cannot tell which of
     * the two the operator meant. Refusing in the store's own vocabulary is what
     * lets `:core` classify it — a bare `IllegalArgumentException` from the
     * document writer crosses the store boundary as a type nothing above it
     * knows how to read.
     */
    @Test
    fun `a hand-edited row that is both blocked and failed fails as a store error`() {
        val directory = stores.directory()
        writeVersion4Database(directory) { legacy ->
            legacy.definition(Fixtures.definitionNamed("survival-a"), generation = 1L, revision = 1L)
            legacy.blockedLegacyStatus(
                name = "survival-a",
                revision = 2L,
                reason = "DRAIN_NO_DESTINATION",
                occurredAt = Instant.parse("2026-07-20T08:05:00Z"),
                attempts = 2,
                message = "3 of 20 player slots are in use",
                extra =
                    listOf(
                        "drain.blocked.reason" to "AWAITING_ZERO_PLAYERS",
                        "drain.blocked.message" to "hand-written",
                        "drain.blocked.since" to "2026-07-19T08:05:00Z",
                        "drain.blocked.observations" to "1",
                    ),
            )
        }

        expectRefusedAtVersion4(directory)
    }

    /**
     * The same collision reached through *any* of the four keys, not just the one
     * the guard used to name.
     *
     * The eleventh drain audit found the guard asked `has("drain.blocked.reason")`
     * while the rewrite writes four keys. A row carrying only, say,
     * `drain.blocked.message` slipped past it and collided inside
     * `DocumentWriter`, throwing `IllegalArgumentException` — precisely the
     * outcome the guard's own comment says it exists to prevent, and the second
     * time this shape has been found in a migration.
     *
     * Every key is exercised, because a guard that names a subset is exactly the
     * defect: `reason` alone is what shipped, and the next partial set would slip
     * through again.
     */
    @TestFactory
    fun `a hand-edited row carrying any part of a blocked record fails as a store error`() =
        listOf(
            "drain.blocked.reason" to "AWAITING_ZERO_PLAYERS",
            "drain.blocked.message" to "hand-written",
            "drain.blocked.since" to "2026-07-19T08:05:00Z",
            "drain.blocked.observations" to "1",
        ).map { (key, value) ->
            DynamicTest.dynamicTest("$key alone") {
                val directory = stores.directory()
                writeVersion4Database(directory) { legacy ->
                    legacy.definition(Fixtures.definitionNamed("survival-a"), generation = 1L, revision = 1L)
                    legacy.blockedLegacyStatus(
                        name = "survival-a",
                        revision = 2L,
                        reason = "DRAIN_NO_DESTINATION",
                        occurredAt = Instant.parse("2026-07-20T08:05:00Z"),
                        attempts = 2,
                        message = "3 of 20 player slots are in use",
                        extra = listOf(key to value),
                    )
                }

                expectRefusedAtVersion4(directory)
            }
        }

    /**
     * Refused in the store's vocabulary, and refused *before* anything is
     * written: one transaction per migration, so the row is still exactly as it
     * was and an operator can downgrade, repair it and reopen.
     */
    private fun expectRefusedAtVersion4(directory: Path) {
        val failure =
            runCatching { stores.open(directory) }
                .exceptionOrNull()
                .shouldBeInstanceOf<StoreException.MigrationFailed>()

        failure.retryable shouldBe false
        val corrupt =
            failure.cause
                .shouldBeInstanceOf<StoreException.Corrupt>()
                .message
                .shouldNotBeNull()
        // Enough to find the row and to know which two records disagree.
        corrupt shouldContain "survival-a"
        corrupt shouldContain "drain.blocked"
        corrupt shouldContain "drain.failure"

        appliedVersions(directory) shouldBe listOf(1, 2, 3, 4)
        statusDocument(directory, "survival-a") shouldContain "drain.failure.reason=DRAIN_NO_DESTINATION"
    }

    /**
     * A store that predates the proxy kind holds one with no migration.
     *
     * The claim handed over with this work was "no on-disk migration is needed —
     * the `kind` column is plain TEXT with no CHECK". This is that claim tested
     * rather than taken: a database written at version 1, migrated up, and then
     * asked to hold a kind that did not exist when it was created. If the column
     * ever grows a constraint, a foreign key or an index that enumerates kinds,
     * this fails and the migration that was said to be unnecessary becomes
     * necessary.
     */
    @Test
    fun `a database written before the proxy kind existed can hold one after migrating`() =
        runTest {
            val directory = stores.directory()
            val existing = Fixtures.definitionNamed("survival-a")
            writeVersion1Database(directory) { legacy ->
                legacy.definition(existing, generation = 1L, revision = 1L)
                legacy.status(Fixtures.fullStatus("survival-a", drainState = DrainState.SAVING), revision = 2L)
            }
            appliedVersions(directory) shouldBe listOf(1)

            val proxy = Fixtures.proxyDefinitionNamed("edge-01")
            val proxyStatus = Fixtures.fullProxyStatus("edge-01", drainState = DrainState.SEALED)
            stores.open(directory).use { migrated ->
                appliedVersions(directory) shouldBe listOf(1, 2, 3, 4, 5, 6)

                migrated.state.putDefinition(proxy).getOrThrow()
                migrated.state.putStatus(proxyStatus).getOrThrow()
            }

            // Reopened, so the assertions are about what reached the disk.
            stores.open(directory).use { reopened ->
                val stored = reopened.state.getServer(proxy.metadata.name).shouldNotBeNull()
                stored.definition.definition shouldBe proxy
                stored.status.shouldNotBeNull().status shouldBe proxyStatus
                // The pre-existing server is untouched, and one drain query finds both
                // kinds — the projection column does not care which wrote it.
                reopened.state.getServer(existing.metadata.name).shouldNotBeNull()
                reopened.state
                    .listByDrainState(setOf(DrainState.SAVING, DrainState.SEALED))
                    .map { it.name.value }
                    .shouldContainExactly(listOf("edge-01", "survival-a"))
            }
        }

    private suspend fun drainOf(
        store: EmbeddedStore,
        name: String,
    ) = store.state
        .getServer(Fixtures.resourceName(name))
        .shouldNotBeNull()
        .status
        .shouldNotBeNull()
        .status
        .shouldBeInstanceOf<PaperServerStatus>()
        .drain
        .shouldNotBeNull()

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

    private fun writeVersion2Database(
        directory: Path,
        block: (LegacyWriter) -> Unit,
    ) {
        rawConnection(directory).use { connection ->
            Migrations.migrate(connection, stores.clock, upTo = 2)
            val writer = LegacyWriter(connection, hasDrainStateColumn = true)
            block(writer)
            writer.finish()
            connection.commit()
        }
    }

    /**
     * A database stopped at version 3, for the version-4 cases.
     *
     * Version 3 changed documents rather than columns, so the row shapes here are
     * version 2's — which is why the same writer serves both.
     */
    private fun writeVersion3Database(
        directory: Path,
        block: (LegacyWriter) -> Unit,
    ) {
        rawConnection(directory).use { connection ->
            Migrations.migrate(connection, stores.clock, upTo = 3)
            val writer = LegacyWriter(connection, hasDrainStateColumn = true)
            block(writer)
            writer.finish()
            connection.commit()
        }
    }

    /**
     * A database stopped at version 4, for the version-5 cases.
     *
     * Versions 3 and 5 change documents and version 4 adds triggers, so the row
     * shapes are still version 2's and the same writer serves this too.
     */
    private fun writeVersion4Database(
        directory: Path,
        block: (LegacyWriter) -> Unit,
    ) {
        rawConnection(directory).use { connection ->
            Migrations.migrate(connection, stores.clock, upTo = 4)
            val writer = LegacyWriter(connection, hasDrainStateColumn = true)
            block(writer)
            writer.finish()
            connection.commit()
        }
    }

    private fun writeVersion5Database(
        directory: Path,
        block: (LegacyWriter) -> Unit,
    ) {
        rawConnection(directory).use { connection ->
            Migrations.migrate(connection, stores.clock, upTo = 5)
            val writer = LegacyWriter(connection, hasDrainStateColumn = true)
            block(writer)
            writer.finish()
            connection.commit()
        }
    }

    /** Inserts rows shaped the way an older version of the schema shaped them. */
    private class LegacyWriter(
        private val connection: Connection,
        private val hasDrainStateColumn: Boolean = false,
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

        /**
         * A status row as versions 2 and 3 held it: the document, plus the
         * `drain_state` projection already sitting beside it.
         *
         * [status] writes the version-1 shape, which has no such column. A test
         * that starts at version 2 or later needs this one, or the projection is
         * empty for a reason that has nothing to do with what it is testing.
         */
        fun statusWithProjection(
            status: PaperServerStatus,
            revision: Long,
        ) {
            connection.update(
                """
                INSERT INTO server_status (
                    name, api_version, kind, resource_version, recorded_at, status_doc, doc_encoding, drain_state
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ) {
                setString(1, status.name.value)
                setString(2, status.apiVersion.wireValue)
                setString(3, status.kind.wireValue)
                setLong(4, revision)
                setString(5, CREATED_AT.toString())
                setString(6, StatusCodec.encode(status))
                setInt(7, PropertyDocument.ENCODING_VERSION)
                val drain = StatusCodec.drainStateOf(status)
                if (drain == null) setNull(8, java.sql.Types.VARCHAR) else setString(8, drain.name)
            }
            highest = maxOf(highest, revision)
        }

        /**
         * A status document written key by key, the way version 2 wrote it —
         * `drain.worldSaved` as a boolean, and the confirmation instant sharing
         * `drain.saveRequestedAt`.
         *
         * Deliberately not `StatusCodec.encode`. The current encoder writes
         * `drain.worldSavedAt` and no `drain.worldSaved` at all, so a test built
         * on it would migrate a document that never needed migrating and would
         * pass whatever version 3 did.
         *
         * [worldSavedAt] and [encoding] exist only to build rows version 2 could
         * never have written — a hand-edited database and an unrecognised
         * encoding. Both are refusals, so a fixture that cannot occur naturally
         * is the only way to reach them.
         */
        fun legacyStatus(
            name: String,
            revision: Long,
            drainState: DrainState,
            worldSaved: Boolean,
            saveRequestedAt: Instant?,
            worldSavedAt: Instant? = null,
            encoding: Int = PropertyDocument.ENCODING_VERSION,
        ) {
            val fields =
                buildList {
                    add("apiVersion" to SchemaVersion.CURRENT.wireValue)
                    add("kind" to ServerKind.PAPER_SERVER.wireValue)
                    add("name" to name)
                    add("observedGeneration" to "1")
                    add("phase" to ServerPhase.DRAINING.name)
                    add("observedAt" to CREATED_AT.toString())
                    add("lastTransitionAt" to CREATED_AT.toString())
                    add("ready" to "false")
                    add("drain.state" to drainState.name)
                    add("drain.startedAt" to LEGACY_DRAIN_STARTED_AT.toString())
                    add("drain.enteredStateAt" to LEGACY_DRAIN_ENTERED_AT.toString())
                    add("drain.playersEvacuated" to "true")
                    add("drain.worldSaved" to worldSaved.toString())
                    add("drain.sealRequestedAt" to LEGACY_SEAL_REQUESTED_AT.toString())
                    saveRequestedAt?.let { add("drain.saveRequestedAt" to it.toString()) }
                    worldSavedAt?.let { add("drain.worldSavedAt" to it.toString()) }
                    add("drain.transferAttempts" to "4")
                    add("drain.destination" to "lobby-01")
                }
            val document = fields.sortedBy { it.first }.joinToString("\n") { "${it.first}=${it.second}" }
            connection.update(
                """
                INSERT INTO server_status (
                    name, api_version, kind, resource_version, recorded_at, status_doc, doc_encoding, drain_state
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ) {
                setString(1, name)
                setString(2, SchemaVersion.CURRENT.wireValue)
                setString(3, ServerKind.PAPER_SERVER.wireValue)
                setLong(4, revision)
                setString(5, CREATED_AT.toString())
                setString(6, document)
                setInt(7, encoding)
                setString(8, drainState.name)
            }
            highest = maxOf(highest, revision)
        }

        /**
         * A status document written key by key, the way version 4 wrote a drain
         * that was blocked on players: a `FailureStatus` under `drain.failure`,
         * mirrored onto the status by the reconciler.
         *
         * Deliberately not `StatusCodec.encode`. The current encoder writes
         * `drain.blocked` and no failure at all for this case, so a test built on
         * it would migrate a document that never needed migrating and would pass
         * whatever version 5 did.
         *
         * The unrelated keys are not padding. A rewrite that dropped them would
         * otherwise be silent, and losing `storage.lastSaveConfirmedAt` or a
         * condition's `lastTransitionAt` is data an operator reads.
         *
         * [extra] carries keys no released build wrote — a hand-edited row. It is
         * the only way to reach version 5's refusals, which by construction
         * nothing that wrote these documents could produce.
         */
        @Suppress("LongParameterList")
        fun blockedLegacyStatus(
            name: String,
            revision: Long,
            reason: String,
            occurredAt: Instant,
            attempts: Int,
            message: String,
            mirrorOnStatus: Boolean = true,
            encoding: Int = PropertyDocument.ENCODING_VERSION,
            extra: List<Pair<String, String>> = emptyList(),
        ) {
            val fields =
                buildList {
                    addAll(extra)
                    add("apiVersion" to SchemaVersion.CURRENT.wireValue)
                    add("kind" to ServerKind.PAPER_SERVER.wireValue)
                    add("name" to name)
                    add("observedGeneration" to "1")
                    // A blocked drain leaves the server running and joinable, and
                    // that is what version 4 recorded too.
                    add("phase" to ServerPhase.RUNNING.name)
                    add("observedAt" to CREATED_AT.toString())
                    add("lastTransitionAt" to CREATED_AT.toString())
                    add("ready" to "true")
                    add("players.online" to "3")
                    add("players.max" to "20")
                    add("players.observedAt" to CREATED_AT.toString())
                    add("storage.persistent" to "true")
                    add("storage.volumeName" to "$name-world")
                    add("storage.bound" to "true")
                    add("storage.lastSaveConfirmedAt" to LEGACY_SEAL_REQUESTED_AT.toString())
                    add("drain.state" to DrainState.DRAIN_FAILED.name)
                    add("drain.startedAt" to LEGACY_DRAIN_STARTED_AT.toString())
                    add("drain.enteredStateAt" to LEGACY_DRAIN_ENTERED_AT.toString())
                    add("drain.playersEvacuated" to "false")
                    add("drain.sealRequestedAt" to LEGACY_SEAL_REQUESTED_AT.toString())
                    add("drain.transferAttempts" to "0")
                    add("drain.failure.reason" to reason)
                    add("drain.failure.failureClass" to FailureClass.RETRYABLE.name)
                    add("drain.failure.message" to message)
                    add("drain.failure.occurredAt" to occurredAt.toString())
                    add("drain.failure.attempts" to attempts.toString())
                    if (mirrorOnStatus) {
                        add("failure.reason" to reason)
                        add("failure.failureClass" to FailureClass.RETRYABLE.name)
                        add("failure.message" to message)
                        add("failure.occurredAt" to occurredAt.toString())
                        add("failure.attempts" to attempts.toString())
                    }
                    add("conditions.count" to "1")
                    add("conditions.0.type" to ConditionType.DRAINING.name)
                    add("conditions.0.status" to ConditionStatus.FALSE.name)
                    add("conditions.0.message" to "drain state DRAIN_FAILED")
                    add("conditions.0.lastTransitionAt" to LEGACY_DRAIN_ENTERED_AT.toString())
                }
            val document = fields.sortedBy { it.first }.joinToString("\n") { "${it.first}=${it.second}" }
            connection.update(
                """
                INSERT INTO server_status (
                    name, api_version, kind, resource_version, recorded_at, status_doc, doc_encoding, drain_state
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ) {
                setString(1, name)
                setString(2, SchemaVersion.CURRENT.wireValue)
                setString(3, ServerKind.PAPER_SERVER.wireValue)
                setLong(4, revision)
                setString(5, CREATED_AT.toString())
                setString(6, document)
                setInt(7, encoding)
                setString(8, DrainState.DRAIN_FAILED.name)
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

    private fun specDocumentOf(
        directory: Path,
        name: String,
    ): String =
        rawConnection(directory).use { connection ->
            connection.query("SELECT spec_doc FROM server_definition WHERE name = ?", { setString(1, name) }) { rows ->
                require(rows.next()) { "no definition row for `$name`" }
                rows.getString("spec_doc")
            }
        }

    private fun appliedVersions(directory: Path): List<Int> =
        rawConnection(directory).use { connection ->
            connection.query("SELECT version FROM schema_migration ORDER BY version") { rows ->
                val versions = mutableListOf<Int>()
                while (rows.next()) versions += rows.getInt("version")
                versions
            }
        }

    /** The raw stored bytes, so "nothing was rewritten" is checked on disk rather than through a decode. */
    private fun statusDocument(
        directory: Path,
        name: String,
    ): String =
        rawConnection(directory).use { connection ->
            connection.query(
                sql = "SELECT status_doc FROM server_status WHERE name = ?",
                bind = { setString(1, name) },
            ) { rows -> if (rows.next()) rows.getString("status_doc") else "" }
        }

    private fun jdbcUrl(directory: Path): String = "jdbc:sqlite:${directory.resolve("state.db").toAbsolutePath()}"

    private companion object {
        val LEGACY_DRAIN_STARTED_AT: Instant = Instant.parse("2026-07-20T08:10:00Z")
        val LEGACY_DRAIN_ENTERED_AT: Instant = Instant.parse("2026-07-20T08:20:00Z")
        val LEGACY_SEAL_REQUESTED_AT: Instant = Instant.parse("2026-07-20T08:12:00Z")

        /**
         * A `doc_encoding` version 3 was not written against — the *next* one,
         * pinned as a literal for the same reason version 3 pins the encoding it
         * understands.
         *
         * That choice is what will make this a guard on the pin and not only on
         * the refusal — but not yet. While `PropertyDocument.ENCODING_VERSION` is
         * still 1, replacing version 3's literal with it fails nothing, here or
         * anywhere else, so from inside the test suite the pin reads as untested.
         * It is not untested so much as not yet testable: the day the constant is
         * bumped to 2, an un-pinned version 3 starts accepting this document and
         * the test fails here, instead of the change of meaning surfacing on an
         * operator's disk as a store that will not open. Neither literal is dead
         * for looking unused — see `PropertyDocument.ENCODING_VERSION`, which
         * carries what a bump is expected to break.
         */
        const val UNREADABLE_ENCODING = 2
    }
}
