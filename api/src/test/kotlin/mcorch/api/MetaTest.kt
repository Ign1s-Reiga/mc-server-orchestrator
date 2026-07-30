package mcorch.api

import io.kotest.matchers.shouldBe
import mcorch.schema.ConditionStatus
import mcorch.schema.ConditionType
import mcorch.schema.DrainPolicy
import mcorch.schema.DrainState
import mcorch.schema.FailureClass
import mcorch.schema.FailureReason
import mcorch.schema.SchemaVersion
import mcorch.schema.ServerKind
import mcorch.schema.ServerPhase
import mcorch.schema.StorageMode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `/api/v1/meta` carries every closed set, so the dashboard hard-codes none.
 *
 * The point of the endpoint is that a value added in `:schema` reaches a
 * dashboard's filters and forms without a frontend release. That only holds if
 * the enumerations here are read from the enums themselves — which is what these
 * assertions check, by comparing against those enums rather than against a list
 * written out by hand.
 */
class MetaTest {
    private lateinit var api: TestApi

    @BeforeEach
    fun start() {
        api = TestApi.start()
    }

    @AfterEach
    fun stop() {
        api.close()
    }

    private fun enums(): Map<*, *> = api.call("GET", "/api/v1/meta").json()["enums"] as Map<*, *>

    @Test
    fun `observed-state enums are listed by their Kotlin names`() {
        val enums = enums()
        enums["phase"] shouldBe ServerPhase.entries.map { it.name }
        enums["drainState"] shouldBe DrainState.entries.map { it.name }
        enums["conditionType"] shouldBe ConditionType.entries.map { it.name }
        enums["conditionStatus"] shouldBe ConditionStatus.entries.map { it.name }
        enums["failureReason"] shouldBe FailureReason.entries.map { it.name }
        enums["failureClass"] shouldBe FailureClass.entries.map { it.name }
        enums["displayState"] shouldBe ServerJsonStates.all()
    }

    @Test
    fun `definition enums are listed by their wire values, because they go back into a document`() {
        // The split is not cosmetic. `spec.storage.mode` is `persistent` in a
        // document and a form that offered `PERSISTENT` would build one the parser
        // rejects. These are the two a create form needs and used to have to
        // hard-code.
        val enums = enums()
        enums["storageMode"] shouldBe StorageMode.supported()
        enums["storageMode"] shouldBe listOf("persistent", "ephemeral")
        enums["drainPolicy"] shouldBe DrainPolicy.supported()
        enums["drainPolicy"] shouldBe listOf("waitForZeroPlayers")
    }

    @Test
    fun `versions, kinds and limits come from the schema too`() {
        val meta = api.call("GET", "/api/v1/meta").json()
        meta["apiVersions"] shouldBe SchemaVersion.supported()
        meta["currentApiVersion"] shouldBe SchemaVersion.CURRENT.wireValue
        meta["kinds"] shouldBe ServerKind.supported()

        val limits = meta["limits"] as Map<*, *>
        limits["maxBodyBytes"] shouldBe (1 shl 20)
        limits["maxStreams"] shouldBe 16
    }

    @Test
    fun `every enum a response can carry is advertised`() {
        // The failure mode this guards is an enum added to a response and not to
        // meta — which is what happened to storageMode and drainPolicy, and was
        // only found when a dashboard had to hard-code them.
        //
        // Kept as an explicit roster rather than reflection over :schema: not
        // every enum there reaches the wire, so a reflective version would fail on
        // internal ones and teach people to add exclusions.
        val advertised = enums().keys.map { it.toString() }.toSet()
        advertised shouldBe
            setOf(
                "phase",
                "drainState",
                "conditionType",
                "conditionStatus",
                "failureReason",
                "failureClass",
                "displayState",
                "storageMode",
                "drainPolicy",
            )
    }
}

/** Reaches the internal display enum without widening its visibility for production code. */
private object ServerJsonStates {
    fun all(): List<String> =
        mcorch.api.render.ServerJson.DisplayState.entries
            .map { it.name }
}
