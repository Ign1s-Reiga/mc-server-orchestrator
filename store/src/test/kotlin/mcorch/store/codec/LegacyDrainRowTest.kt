package mcorch.store.codec

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import mcorch.schema.DrainBlock
import mcorch.schema.DrainBlockReason
import mcorch.schema.DrainState
import mcorch.schema.DrainStatus
import mcorch.schema.FailureClass
import mcorch.schema.FailureReason
import mcorch.schema.FailureStatus
import mcorch.schema.PaperServerStatus
import mcorch.schema.ResourceName
import mcorch.schema.SchemaVersion
import mcorch.schema.ServerKind
import mcorch.schema.ServerPhase
import mcorch.schema.StatusReconstruction
import mcorch.store.StoreException
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import java.time.Instant

/**
 * Every field the drain record persists has a stated reading for a row that does
 * not carry it.
 *
 * ## The defect this exists for
 *
 * `DrainStatus.stopDispatchedAt` was added *inside* the status document rather than
 * as a column, so no on-disk schema version moved, no migration backfills it, and a
 * row written by any earlier build simply has no such key. Its absence was then read
 * at face value as "no container has been signalled", which is the one reading that
 * hands players to a process inside its shutdown save.
 *
 * Nothing reddened. The field was added, the codec wrote it, the round-trip tests
 * passed — because a round trip only ever sees documents *this* build wrote, and
 * this build writes the key. The hole was in the population no fixture covered: rows
 * that predate the field. [mcorch.store.sqlite.LegacyStopDispatchTest] closes it for
 * that one field, by hand.
 *
 * ## What this does about the next one
 *
 * It refuses to let the next such field be added without someone saying, in code,
 * what a row lacking it reads as. Every property of the drain record is enumerated
 * from the live type, and each must appear in [readings] with one of two answers:
 *
 * - [Reads] — the row decodes, and here is the record it decodes to and the
 *   reconstruction it reports. This is the "legacy row fixture": the declaration and
 *   the assertion are the same object.
 * - [Refused] — the row is not decodable without the key at all, and the store
 *   rejects it as corrupt rather than inventing a value.
 *
 * Both labels are *verified*, not trusted. A field declared [Refused] whose key
 * turns out to be droppable fails, and so does the reverse. So the registry cannot
 * drift into describing a codec that no longer behaves that way.
 *
 * A new field reddens this three ways over: it appears in the enumeration with no
 * entry, its entry cannot be written without naming the value the decode produces,
 * and the `copy` in that entry stops compiling if the field is later renamed.
 *
 * ## What it does not cover, deliberately
 *
 * The drain record and the two records it owns — nothing above them. The optional
 * fields of [PaperServerStatus] and `VelocityProxyStatus` are *observations*, where
 * absence honestly means "not observed" and no rule reads it as anything else. The
 * drain's fields are records of **side effects that already left this process**, so
 * absence there is a claim about the past that a legacy row cannot support — which
 * is what makes an undeclared one dangerous rather than untidy. Extending the sweep
 * upwards means adding a probe and a prefix to [records]; the machinery does not
 * care.
 *
 * It also catches only the *absence* class. The companion hole — a decode rule
 * keyed on a **state** whose producers were not all surveyed — is not a field and is
 * not visible from here; it is guarded in `:core`, where the producers are.
 */
class LegacyDrainRowTest {
    /**
     * The probe is a document, not a claim about the orchestrator.
     *
     * Every optional field is populated at once, including pairs a real drain never
     * holds together, because the only thing being asked of it is "which keys does
     * the codec write, and what happens when one is taken away". `the probe is a
     * document this build's codec agrees with` is the guard that keeps that
     * honest — if a future read-side rule fires on the probe, that test says so
     * before any of the per-field cases can blame the missing key for it.
     *
     * Values avoid `,` `=` and brackets so that [propertiesOf] can read the field
     * names back off `toString` unambiguously.
     */
    private val block =
        DrainBlock(
            reason = DrainBlockReason.AWAITING_ZERO_PLAYERS,
            message = "awaiting-zero-players",
            since = AT.minusSeconds(70),
            observations = 3,
        )

    private val failure =
        FailureStatus(
            reason = FailureReason.DRAIN_TRANSFER_FAILED,
            failureClass = FailureClass.RETRYABLE,
            message = "two-of-six-transfers-refused",
            occurredAt = AT.minusSeconds(60),
            attempts = 4,
        )

    // `STOPPING` on purpose: it is the state the one reconstruction rule keys on, so
    // a probe in any other state could not exercise it.
    private val drain =
        DrainStatus(
            state = DrainState.STOPPING,
            startedAt = AT.minusSeconds(120),
            enteredStateAt = AT.minusSeconds(20),
            playersEvacuated = true,
            sealRequestedAt = AT.minusSeconds(115),
            saveRequestedAt = AT.minusSeconds(110),
            worldSavedAt = AT.minusSeconds(100),
            resaveForcedAt = AT.minusSeconds(95),
            deregisteredAt = AT.minusSeconds(90),
            // Distinct from `enteredStateAt`, so a case that drops the key can tell
            // the reconstruction apart from the stored value.
            stopDispatchedAt = AT.minusSeconds(8),
            transferStartedAt = AT.minusSeconds(85),
            transferAttempts = 4,
            destination = name("lobby-01"),
            blocked = block,
            failure = failure,
            // Non-zero, and above the default escalation threshold on purpose. Zero
            // is what a stripped row reads, so a probe carrying zero would make the
            // case below pass against a codec that does not persist the field at
            // all — and the value being one that *escalates* is what makes the
            // stripped reading a visible loss rather than a cosmetic one.
            faultLedger = 7,
        )

    private val status =
        PaperServerStatus(
            name = name("survival-02"),
            observedGeneration = 1L,
            phase = ServerPhase.DRAINING,
            observedAt = AT,
            lastTransitionAt = AT.minusSeconds(30),
            drain = drain,
        )

    /**
     * What a row missing each key reads as.
     *
     * The `why` on each is the part a future reader needs: not that the field is
     * optional, but what a store that serves the row without it is asserting about
     * the world.
     */
    private val readings: Map<String, Reading>
        get() =
            mapOf(
                // -------------------------------------------------------- DrainStatus
                "DrainStatus.state" to
                    Reads(
                        why =
                            "the presence marker for the whole record: `readDrain` answers null when it is " +
                                "absent. A drain cannot be half a document, and a state guessed from the " +
                                "other keys would resume the protocol at a step nobody reached",
                        expected = { null },
                    ),
                "DrainStatus.startedAt" to
                    Refused(
                        why =
                            "every deadline the drain is judged against is measured from it. Defaulting it " +
                                "to the read's own clock restarts a drain's whole allowance on each restart",
                    ),
                "DrainStatus.enteredStateAt" to
                    Refused(
                        why =
                            "the current step's clock, and the instant the stop reconstruction reads. " +
                                "Inventing it would put a fabricated dispatch at a fabricated time",
                    ),
                "DrainStatus.playersEvacuated" to
                    Refused(
                        why =
                            "false is a real answer here, not an absence — it is the difference between a " +
                                "drain that has swept its players and one that has not, so a default would " +
                                "be indistinguishable from a fact",
                    ),
                "DrainStatus.sealRequestedAt" to
                    Reads(
                        why =
                            "null means the proxy was never asked to shut this login path, and the drain " +
                                "asks again. A repeated seal is idempotent at the proxy",
                        expected = { it.copy(sealRequestedAt = null) },
                    ),
                "DrainStatus.saveRequestedAt" to
                    Reads(
                        why =
                            "null means no save request went out and the drain issues one. Real work on a " +
                                "live server, but the safe direction: the alternative is stopping a " +
                                "container whose world was never flushed",
                        expected = { it.copy(saveRequestedAt = null) },
                    ),
                "DrainStatus.worldSavedAt" to
                    Reads(
                        why =
                            "null means no save has been confirmed, so the stop stays gated and the drain " +
                                "waits for a confirmation. Erring towards saving again",
                        expected = { it.copy(worldSavedAt = null) },
                    ),
                "DrainStatus.resaveForcedAt" to
                    Reads(
                        why =
                            "null restarts the count of a drain that keeps having to save again. It costs " +
                                "the visibility of a cycle, not a player's session",
                        expected = { it.copy(resaveForcedAt = null) },
                    ),
                "DrainStatus.deregisteredAt" to
                    Reads(
                        why =
                            "null means the backend was never taken out of routing, and the drain " +
                                "deregisters again. Idempotent at the proxy",
                        expected = { it.copy(deregisteredAt = null) },
                    ),
                "DrainStatus.stopDispatchedAt" to
                    Reads(
                        why =
                            "the field this whole test exists for, and the one absence that is *not* read " +
                                "at face value. Null would mean no container was signalled, which " +
                                "re-admits players to a process inside its shutdown save, so a drain in " +
                                "`STOPPING` is served the record reconstructed from its own transition. " +
                                "`mcorch.schema.StatusReconstruction` owns that argument",
                        expected = { it.copy(stopDispatchedAt = it.enteredStateAt) },
                        reports = listOf(StatusReconstruction.STOP_DISPATCHED_FIELD),
                    ),
                "DrainStatus.transferStartedAt" to
                    Reads(
                        why =
                            "null hands drain step 4 its full allowance again. One extra allowance for a " +
                                "drain caught mid-transfer, which is the safe direction",
                        expected = { it.copy(transferStartedAt = null) },
                    ),
                "DrainStatus.transferAttempts" to
                    Refused(
                        why =
                            "zero is a real answer — no transfer has been tried — and defaulting to it " +
                                "would silently reset the count that bounds the retries",
                    ),
                "DrainStatus.destination" to
                    Reads(
                        why =
                            "null means no destination has been chosen and the scheduler picks one. " +
                                "Choosing is cheap and repeatable; a wrong one is not",
                        expected = { it.copy(destination = null) },
                    ),
                "DrainStatus.blocked" to
                    Reads(
                        why =
                            "null means nothing is holding this drain up. A block is re-derived from the " +
                                "next observation, so losing it costs one pass of an operator seeing why " +
                                "the drain is waiting",
                        expected = { it.copy(blocked = null) },
                    ),
                "DrainStatus.failure" to
                    Reads(
                        why =
                            "null means no failure has been recorded and the drain retries from where it " +
                                "is. It loses the record telling an operator the world may not have been " +
                                "flushed, which is why the loop carries it forward rather than re-deriving",
                        expected = { it.copy(failure = null) },
                    ),
                "DrainStatus.faultLedger" to
                    Reads(
                        why =
                            "zero means no fault has yet outlasted a recovery, which is the value that " +
                                "cannot escalate. It is the one field here whose absence is *expected* " +
                                "rather than tolerated — every document written before it existed has no " +
                                "such key — so a refusal would make an upgrade unreadable, and V6 stamps " +
                                "the explicit zero afterwards so that later absences mean something. What " +
                                "is lost is evidence: a drain that came back at zero has to re-earn a " +
                                "pattern that takes hours to build, and it under-reports, which is the " +
                                "safe direction for a flag",
                        expected = { it.copy(faultLedger = 0) },
                    ),
                // --------------------------------------------------------- DrainBlock
                "DrainBlock.reason" to
                    Reads(
                        why = "the block's presence marker: `readBlock` answers null when it is absent",
                        expected = { it.copy(blocked = null) },
                    ),
                "DrainBlock.message" to
                    Refused(why = "a block with no reason to show an operator is not a block"),
                "DrainBlock.since" to
                    Refused(why = "how long the drain has been held up is the whole content of a block"),
                "DrainBlock.observations" to
                    Refused(
                        why =
                            "the count of passes that saw the block, which bounds how long it may hold. A " +
                                "default restarts that bound on every restart",
                    ),
                // ------------------------------------------------------ FailureStatus
                "FailureStatus.reason" to
                    Reads(
                        why = "the failure's presence marker: `readFailure` answers null when it is absent",
                        expected = { it.copy(failure = null) },
                    ),
                "FailureStatus.failureClass" to
                    Refused(
                        why =
                            "retryable and permanent are opposite instructions to the loop, and there is " +
                                "no safe default: one wedges a drain that would have recovered, the other " +
                                "retries one a human has to look at",
                    ),
                "FailureStatus.message" to
                    Refused(why = "the failure's only human-readable content"),
                "FailureStatus.occurredAt" to
                    Refused(why = "when it happened decides whether a backoff has elapsed"),
                "FailureStatus.attempts" to
                    Refused(why = "the count a backoff is derived from; a default restarts the backoff"),
            )

    /**
     * The types swept, each with the document prefix its fields live under.
     *
     * The probe carries one instance of each, so a single encode serves all three.
     */
    private val records: List<Record>
        get() =
            listOf(
                Record("DrainStatus", "drain", drain),
                Record("DrainBlock", "drain.blocked", block),
                Record("FailureStatus", "drain.failure", failure),
            )

    @Test
    fun `every persisted drain field has a declared legacy reading`() {
        val fields = records.flatMap { record -> record.fields().map { "${record.type}.$it" } }

        // The whole point of the file. A field added to one of these records and left
        // out of `readings` lands here, and the message is the instruction.
        val undeclared = fields.filterNot { it in readings }
        withClue(
            "these persisted drain fields have no stated reading for a row that predates them. Add each to " +
                "`readings` saying what a store serves when the key is absent — `Reads` with the record the " +
                "decode produces, or `Refused` if the row must be rejected instead. A field whose absence " +
                "nobody has priced is the defect this file exists for",
        ) {
            undeclared.shouldBeEmpty()
        }

        // And the other direction, so the registry cannot outlive the fields. A stale
        // entry would keep asserting against a key nothing writes.
        val stale = readings.keys.filterNot { it in fields }
        withClue("`readings` names fields these records no longer have") { stale.shouldBeEmpty() }
    }

    @Test
    fun `the probe is a document this build's codec agrees with`() {
        val decoded = decode(StatusCodec.encode(status))

        // Every per-field case below reads its result as a consequence of the key it
        // dropped. That inference is only sound while the untouched document decodes
        // to exactly what went in — so if a new read-side rule starts firing on the
        // probe, it is said here once rather than blamed on fifteen missing keys.
        decoded.status shouldBe status
        decoded.reconstructed.shouldBeEmpty()
    }

    @TestFactory
    fun `a row written without each field reads as declared`(): List<DynamicTest> =
        records.flatMap { record ->
            record.fields().map { field ->
                DynamicTest.dynamicTest("${record.type}.$field") {
                    val key = "${record.prefix}.$field"
                    val encoded = StatusCodec.encode(status)
                    val stripped = drop(encoded, key)

                    // A key that was never there means the probe stopped populating
                    // the field or the codec stopped writing it — either way the case
                    // below would pass without testing anything.
                    withClue(
                        "no `$key` key to drop: the probe does not populate $field, or the codec does not write it",
                    ) {
                        (encoded.lines() - stripped.lines().toSet()).shouldNotBeEmpty()
                    }

                    when (val reading = readings.getValue("${record.type}.$field")) {
                        is Refused -> {
                            withClue("a row with no `$key` must be refused, not defaulted: ${reading.why}") {
                                assertThrows<StoreException.Corrupt> { decode(stripped) }
                            }
                        }

                        is Reads -> {
                            val decoded = decode(stripped)
                            withClue(reading.why) {
                                decoded.status shouldBe status.copy(drain = reading.expected(drain))
                            }
                            // A reinterpretation nobody can report is the silent
                            // rewriting of stored data the codec refuses, so what is
                            // reconstructed is pinned as tightly as what is read.
                            decoded.reconstructed.map { it.field } shouldContainExactly reading.reports
                        }
                    }
                }
            }
        }

    private fun decode(encoded: String) =
        StatusCodec.decode(
            name = name("survival-02"),
            apiVersion = SchemaVersion.CURRENT,
            kind = ServerKind.PAPER_SERVER,
            encoded = encoded,
            what = "legacy drain row",
        )

    /**
     * The document an older build would have written: the key is **gone**, not
     * blanked, and so is anything nested under it.
     *
     * The nesting matters for the two fields that are whole records — dropping
     * `drain.blocked` has to take `drain.blocked.reason` with it, or the block would
     * still decode.
     */
    private fun drop(
        encoded: String,
        key: String,
    ): String =
        encoded
            .lineSequence()
            .filterNot { it.startsWith("$key=") || it.startsWith("$key.") }
            .joinToString("\n")

    private data class Record(
        val type: String,
        val prefix: String,
        val probe: Any,
    ) {
        fun fields(): List<String> = propertiesOf(probe)
    }

    private sealed interface Reading {
        val why: String
    }

    /** The row decodes without the key, to [expected], reporting [reports]. */
    private class Reads(
        override val why: String,
        val expected: (DrainStatus) -> DrainStatus?,
        val reports: List<String> = emptyList(),
    ) : Reading

    /** The row is not decodable without the key, and is rejected rather than defaulted. */
    private class Refused(
        override val why: String,
    ) : Reading

    private fun name(value: String): ResourceName = ResourceName.of(value).getOrThrow()

    private companion object {
        val AT: Instant = Instant.parse("2026-07-26T12:00:00Z")

        /**
         * The property names of a data class, read off the live instance.
         *
         * Deliberately not reflection: `kotlin-reflect` is on this module's test
         * classpath only transitively and at a different version from the compiler,
         * so an instrument built on it would be one a dependency bump could silently
         * remove. A data class `toString` lists every constructor property, in order,
         * and grows the day one is added — which is the only property needed here.
         *
         * Read at bracket depth zero so that a nested record's own fields are not
         * mistaken for the outer one's. The probe values contain no `,` `=` or
         * brackets, which is what makes that unambiguous, and a value that did would
         * surface as a field name nobody declared rather than as a silent pass.
         */
        fun propertiesOf(record: Any): List<String> {
            val text = record.toString()
            val open = text.indexOf('(')
            require(open > 0 && text.endsWith(")")) { "not a data class toString: $text" }
            val body = text.substring(open + 1, text.length - 1)
            val names = mutableListOf<String>()
            var depth = 0
            var start = 0

            fun take(end: Int) {
                val segment = body.substring(start, end)
                val separator = segment.indexOf('=')
                require(separator > 0) { "no `name=` in `$segment` of $text" }
                names += segment.substring(0, separator).trim()
            }
            for (index in body.indices) {
                when (body[index]) {
                    '(' -> {
                        depth++
                    }

                    ')' -> {
                        depth--
                    }

                    ',' -> {
                        if (depth == 0) {
                            take(index)
                            start = index + 1
                        }
                    }

                    else -> {
                        Unit
                    }
                }
            }
            take(body.length)
            return names
        }
    }
}
