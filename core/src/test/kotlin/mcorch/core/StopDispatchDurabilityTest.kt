package mcorch.core

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import mcorch.schema.DrainState
import mcorch.schema.DrainStatus
import mcorch.schema.PaperServerStatus
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The loop must not un-stamp a stop it did not dispatch.
 *
 * **`:core` has two writers of observed state now.** `NodeForcedTermination`
 * stamps `DrainStatus.stopDispatchedAt` immediately before it issues a `SIGTERM`,
 * from an HTTP request; the loop writes the same field from a pass. They overlap
 * by construction, because the tombstone the forced path requires hits the change
 * feed and queues exactly the pass that will race it.
 *
 * `stopIsInFlight` is what that record answers, and its own KDoc says what losing
 * it costs: *"the loop converges over the top of its own stop and a proxy
 * re-admits players to a process whose shutdown save has run."* A pass that read
 * its snapshot before the stamp and lands after it puts the pre-stamp drain back,
 * and the next pass then walks the whole ladder — seal, destination, transfer,
 * `requireEmpty`, **save** — into a container already running its shutdown save.
 *
 * ## Why this needs an interleaving hook and not a second `putStatus`
 *
 * Writing the stamp *between* passes proves nothing: the next pass reads the newer
 * value and carries it forward for free. The bug lives entirely in the window
 * between a pass's read and its write, which is exactly where the real forced stop
 * lands — it spends a probe, a save and a seal in there. `TestStore.afterNextRead`
 * is the only way to put a writer in that window, and a test without it would be
 * green against the broken code.
 */
internal class StopDispatchDurabilityTest {
    @Test
    fun `a stop dispatched mid-pass survives the pass that did not see it`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            // Tombstoned, which is the only state the forced path can act from —
            // and the state that makes the pass below a drain rather than a
            // steady-state observation.
            harness.store.deleteDefinition(name)

            val dispatched = Instant.parse("2026-01-01T00:00:00Z")
            // Armed to fire after the pass has taken its snapshot, standing in for
            // the forced stop's own write landing mid-pass.
            harness.store.afterNextRead = {
                val current =
                    harness.store
                        .getServer(name)
                        ?.status
                        ?.status as? PaperServerStatus
                if (current != null) {
                    harness.store.putStatus(
                        current.copy(
                            drain =
                                current.drain?.copy(stopDispatchedAt = dispatched)
                                    ?: DrainStatus(
                                        state = DrainState.STOPPING,
                                        startedAt = dispatched,
                                        enteredStateAt = dispatched,
                                        stopDispatchedAt = dispatched,
                                    ),
                        ),
                    )
                }
            }

            harness.pass(name)

            // The pass wrote an observation built before the stamp existed. It must
            // not have taken it away: `restoreRegistration`'s note calls an un-stamp
            // "a second writer on it" and routes even a justified one through
            // drain-auditor. A pass dropping one silently is that second writer with
            // no justification at all.
            val drain =
                (
                    harness.store
                        .getServer(name)
                        ?.status
                        ?.status as? PaperServerStatus
                )?.drain
            drain shouldNotBe null
            drain?.stopDispatchedAt shouldBe dispatched
        }

    @Test
    fun `a re-issued stop's fresh window survives a pass holding a pre-retry snapshot`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.deleteDefinition(name)

            val first = Instant.parse("2026-01-01T00:00:00Z")
            val retried = Instant.parse("2026-01-01T02:00:00Z")
            // The state a failed forced stop leaves: both stamps at the first
            // attempt, container still running.
            harness.status(name)?.let {
                harness.store.putStatus(
                    it.copy(
                        drain =
                            DrainStatus(
                                state = DrainState.DRAIN_FAILED,
                                startedAt = first,
                                enteredStateAt = first,
                                stopDispatchedAt = first,
                                stopLastDispatchedAt = first,
                            ),
                    ),
                )
            }

            // The retry lands mid-pass: `stopDispatchedAt` is unchanged, because it
            // is set-once, and only `stopLastDispatchedAt` moves.
            harness.store.afterNextRead = {
                val current = harness.status(name)
                if (current != null) {
                    harness.store.putStatus(
                        current.copy(drain = current.drain?.copy(stopLastDispatchedAt = retried)),
                    )
                }
            }

            harness.pass(name)

            // **The old guard bailed on the unchanged stamp.** It returned as soon as
            // the draft carried a `stopDispatchedAt`, which a stale draft does — so
            // the pass wrote the pre-retry record back and the fresh window was lost.
            // The seal then anchors to an expired instant while the retried stop is
            // still inside its grace period: the proxy reopens the backend, and
            // another force does not see this one as overlapping.
            val drain =
                (
                    harness.store
                        .getServer(name)
                        ?.status
                        ?.status as? PaperServerStatus
                )?.drain
            drain?.stopLastDispatchedAt shouldBe retried
            // And the set-once half is still the first attempt, not the retry.
            drain?.stopDispatchedAt shouldBe first
        }

    @Test
    fun `a retry landing between the merge and the write is not overwritten`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)
            harness.store.deleteDefinition(name)

            val first = Instant.parse("2026-01-01T00:00:00Z")
            val retried = Instant.parse("2026-01-01T02:00:00Z")
            harness.status(name)?.let {
                harness.store.putStatus(
                    it.copy(
                        drain =
                            DrainStatus(
                                state = DrainState.DRAIN_FAILED,
                                startedAt = first,
                                enteredStateAt = first,
                                stopDispatchedAt = first,
                                stopLastDispatchedAt = first,
                            ),
                    ),
                )
            }

            // The merge closes the window between the pass's snapshot and its write.
            // It does not close the window between the merge's own re-read and that
            // write — two store calls, with a forced retry free to land between them.
            // So the writer is armed for the *second* read of the pass, which is
            // `preservingDispatch`'s.
            harness.store.afterNextRead = {
                harness.store.afterNextRead = {
                    harness.status(name)?.let {
                        harness.store.putStatus(
                            it.copy(drain = it.drain?.copy(stopLastDispatchedAt = retried)),
                        )
                    }
                }
            }

            harness.pass(name)

            // The merge picked the stale value because that was all it could see, so
            // the write has to be refused rather than trusted: conditional on the
            // version the merge was true of, and a conflict re-reads. Otherwise the
            // seal anchors to an expired instant while the retried stop is still
            // inside its grace period.
            val drain =
                (
                    harness.store
                        .getServer(name)
                        ?.status
                        ?.status as? PaperServerStatus
                )?.drain
            drain?.stopLastDispatchedAt shouldBe retried
        }

    @Test
    fun `a server that is not terminating pays nothing for the guard`() =
        coreTest {
            val harness = Harness()
            val definition = paperDefinition()
            val name = definition.metadata.name
            harness.declare(definition)
            harness.settle(name)

            // The forced path cannot run against a live definition — the API
            // tombstones before it calls the seam — so the re-read is gated on
            // `terminating` and the steady-state path pays for none of it.
            //
            // Counted rather than asserted in prose. "It only reads when
            // terminating" is the kind of claim that stays in a KDoc long after the
            // condition has been widened, and the loop reads the whole fleet every
            // resync: a per-pass round trip added here is a cost nobody would
            // notice going in.
            val before = harness.store.serverReads
            harness.pass(name)
            val added = harness.store.serverReads - before

            // Exactly the pass's own snapshot.
            added shouldBe 1
        }
}
