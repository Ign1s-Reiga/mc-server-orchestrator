package mcorch.schema

import java.time.Instant

/**
 * One record an observation did not carry and the instant read in its place.
 *
 * [field] is the path a dashboard or an investigator would name — not a store's
 * document key, which `:schema` does not know. [takenFrom] is the field the
 * substitute was derived from, so what is reported is *what was inferred and from
 * what* rather than only that something was.
 *
 * Nothing here is written back. The stored observation keeps whatever it holds;
 * this describes the difference between it and what the caller was handed.
 */
public data class ReconstructedRecord(
    val field: String,
    val value: Instant,
    val takenFrom: String,
)

/**
 * An observation with every side-effect record a current build depends on, and
 * the list of what had to be reconstructed to get there.
 *
 * [reconstructed] empty means [status] is the object that went in, unchanged and
 * un-copied.
 */
public data class ReconstructedStatus(
    val status: ServerStatus,
    val reconstructed: List<ReconstructedRecord>,
) {
    public val wasReconstructed: Boolean get() = reconstructed.isNotEmpty()
}

/**
 * The side-effect records a stored observation is required to carry, restored on
 * the way out when the build that wrote the row could not have written them.
 *
 * ## What this is for
 *
 * [DrainStatus.stopDispatchedAt] says a container stop request left the
 * orchestrator. The reconcile loop reads it to decide **not** to hand a workload
 * back to a proxy's routing table: the container has had its `SIGTERM` and is
 * inside its shutdown save, so admitting a player there loses that player's
 * session. It lives inside the status document rather than in a column, so no
 * on-disk schema version moved when it was added and no migration backfills it. A
 * row written by any build predating it therefore reads `null`, and null is
 * indistinguishable from *"nobody has signalled this container"*.
 *
 * That gap is reachable by the ordinary upgrade path and it costs exactly what the
 * field exists to prevent. A drain reaches [DrainState.STOPPING] and the row is
 * persisted; the binary is upgraded; the operator reverts the edit that asked for
 * the replacement, which is the documented lever available throughout the grace
 * period; the loop sees no dispatch, concludes nothing wants a drain, deletes the
 * whole drain record, and the proxy's next sweep re-registers a backend whose
 * shutdown save has already run.
 *
 * ## Why on the read and not in a migration
 *
 * The same four reasons [SpecBounds] gives, and one more that is specific to this
 * field.
 *
 * *It is a read-path rule, so an old row gets it whatever version wrote it.* A
 * migration only reaches rows that exist when it runs, and this rule has to hold
 * for a row an implementation with no migration ladder at all is holding.
 *
 * *Writing the stamp to disk would record as observed something no process
 * observed.* The row is not corrupt — it was exactly right for the build that
 * wrote it — and a value fabricated into storage cannot afterwards be told from
 * one a dispatch really produced. Reconstructing on the read keeps the inference
 * where it can be read, revised and reported.
 *
 * *A version number spent on a log line can never be reused.*
 *
 * *The guarantee belongs to the interface rather than to one backend.* This object
 * is public and lives here for the reason [SpecBounds] does: a second store
 * implementation owes the same promise, and `:store`'s conformance suite asks both
 * of its implementations for it.
 *
 * And the one that is particular to this field: **the reconcile loop backfills the
 * row itself on its very next pass.** It carries the previous observation's drain
 * record forward into the observation it writes, so the reconstructed stamp is
 * persisted by the first pass that acts on it. A migration would be doing, at the
 * cost of a version number, work that happens anyway one pass later.
 *
 * ## Why [DrainState.STOPPING] exactly
 *
 * A drain reaches `STOPPING` only after a stop request returned cleanly, so for a
 * document in that state the dispatch is not a guess: it is a fact the document
 * already implies, and [DrainStatus.enteredStateAt] is when it happened. This is
 * reconstruction rather than a conservative assumption.
 *
 * `state == STOPPING` was rejected as the *call-site* discriminator because it
 * **under**-reports — a stop whose deadline elapsed leaves the drain
 * [DrainState.DEREGISTERED] and the request still went out. That is an argument
 * against using the state where a real stamp is available, not against using it on
 * a document that has none. Rejecting a proxy for erring one way is not an
 * argument against using it where it errs the other.
 *
 * **[DrainState.DEREGISTERED] is deliberately not included**, and neither is the
 * lap back to [DrainState.SAVING] that can carry a dispatch. Both are states a
 * drain ordinarily sits in *before* any stop, so the common outcome of
 * reconstructing there is a stamp for a dispatch that never happened — which
 * disables `restoreRegistration`, the compensation that puts a parked drain's
 * backend back into routing, for the ordinary case rather than the exceptional
 * one.
 *
 * **[DrainState.DRAIN_FAILED] is excluded, and it is the exclusion that matters
 * most.** It is declared after `STOPPING` but it is not past it: a failed drain
 * has no edge to a stop and leaves the server running. So a stamp reconstructed
 * there can never be retired — nothing drives that container down, the workload is
 * never observed absent, and the record that would be cleared when it is stays for
 * ever. The safe direction the rest of this argument rests on is that
 * over-reporting a dispatch withholds a re-admission *and lets the drain run to a
 * stopped container*; in `DRAIN_FAILED` there is no such end, so the cost stops
 * being bounded. An ordinal comparison (`state >= STOPPING`) would include it by
 * accident of declaration order, which is why the test here names the state.
 *
 * ## The direction of the error
 *
 * Over-reporting a dispatch costs availability: the workload stays out of the
 * proxy's routing table until its drain ends, which it does on its own. Losing one
 * costs a player's session and no later pass repairs it. That asymmetry is the
 * whole argument, and it is the field's own — see [DrainStatus.stopDispatchedAt].
 */
public object StatusReconstruction {
    /** The path a report names. Not a store's key: `:schema` does not know one. */
    public const val STOP_DISPATCHED_FIELD: String = "status.drain.stopDispatchedAt"

    /** Where the substitute comes from, named in the same vocabulary. */
    public const val STOP_DISPATCHED_SOURCE: String = "status.drain.enteredStateAt"

    /**
     * [status] with every side-effect record this build acts on present.
     *
     * Returns the argument itself when nothing had to be reconstructed, which is
     * every observation a current build wrote and therefore every read on a store
     * that has not been through the upgrade this exists for. The reconcile loop
     * reads the whole fleet each resync, so the ordinary path allocates nothing.
     */
    public fun reconstruct(status: ServerStatus): ReconstructedStatus {
        val drain = drainOf(status) ?: return ReconstructedStatus(status, emptyList())
        val dispatchedAt = missingStopDispatch(drain) ?: return ReconstructedStatus(status, emptyList())
        return ReconstructedStatus(
            status = withDrain(status, drain.copy(stopDispatchedAt = dispatchedAt)),
            reconstructed =
                listOf(
                    ReconstructedRecord(
                        field = STOP_DISPATCHED_FIELD,
                        value = dispatchedAt,
                        takenFrom = STOP_DISPATCHED_SOURCE,
                    ),
                ),
        )
    }

    /**
     * The instant to stamp on a drain that has stopped a container without
     * recording that it did, or null when there is nothing to reconstruct.
     *
     * The state test is spelled as an equality against one member rather than as a
     * comparison, for the reason the class note gives about `DRAIN_FAILED`: an
     * ordering here would take in whatever is declared after `STOPPING`, and what
     * is declared after it today is the one state where this must not fire.
     */
    private fun missingStopDispatch(drain: DrainStatus): Instant? =
        if (drain.state == DrainState.STOPPING && drain.stopDispatchedAt == null) drain.enteredStateAt else null

    private fun drainOf(status: ServerStatus): DrainStatus? =
        when (status) {
            is PaperServerStatus -> status.drain
            is VelocityProxyStatus -> status.drain
        }

    private fun withDrain(
        status: ServerStatus,
        drain: DrainStatus,
    ): ServerStatus =
        when (status) {
            is PaperServerStatus -> status.copy(drain = drain)
            is VelocityProxyStatus -> status.copy(drain = drain)
        }
}
