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
 * ## Why [DrainState.STOPPING] exactly, and what the state does *not* prove
 *
 * `STOPPING` has **two producers in the reconcile loop, and only one of them
 * dispatches anything.**
 *
 * `DrainController.letGoAndStop` reaches it after a container stop request returned
 * cleanly, and stamps the field on the way through `dispatchingStop`. Those are the
 * rows this object exists for: on one of them a null is a build that had no key to
 * write, [DrainStatus.enteredStateAt] is when the dispatch happened, and the
 * reconstruction is exact.
 *
 * The second is `DrainController.advanceOnce`'s container-is-already-down branch. It
 * is taken *before* the state machine runs, for any drain whose workload the runtime
 * reports absent, exited, merely created, or sandbox-only having never had a
 * container — and it moves the record to `STOPPING` having **dispatched nothing**.
 * The teardown persists that record. So the current build writes `state == STOPPING`
 * with no stamp on the ordinary path of every drain of a container that was already
 * down, this rule fires on those rows, and the stamp it synthesises for them is for
 * a dispatch that never happened. They are false positives, and nothing in a
 * document tells them from the rows above.
 *
 * **What makes them harmless is the observation gate, not the paragraph above it.**
 * The stamp has one reader, `stopIsInFlight`, and it answers `true` only for a stamp
 * *together with* an observation. Every observation the second producer can be
 * reached from either fails that test outright — an absent workload is not a
 * `Present` one, and `CREATED` and never-had-a-container `SANDBOX_ONLY` answer
 * false — or is `EXITED`, which is a container genuinely gone and so the evidence
 * that *retires* the record, not a running process a fabricated stamp could withhold
 * players from. Runtime states do not run backwards, so none of those workloads
 * comes back up underneath the stamp.
 *
 * That makes `stopIsInFlight`'s observation half load-bearing **for this rule** and
 * not only for its own. Reading the stamp alone as authoritative — dropping the
 * observation half for `EXITED`, say, on the grounds that a document in `STOPPING`
 * has already proved a dispatch — would not be a local simplification of that
 * function. It would remove the only thing neutralising this rule's false positives.
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
 * **[DrainState.DRAIN_FAILED] is excluded on the same ground, and the ground is
 * *not* that it has no way out.** It has one, and a reader who goes looking will
 * find it: `advanceOnce` asks the container-is-already-down question above on every
 * pass for every state, `DRAIN_FAILED` included and before the state machine gets a
 * say. A drain that really did signal its container and then aborted is carried to
 * `STOPPING` by that branch when the container reaches the end of its grace period
 * and exits, and the teardown retires the record. There is no state here in which a
 * stamp is structurally immortal.
 *
 * What excludes it is its **false-positive rate**, which is the argument
 * `DEREGISTERED` already rests on and is sufficient on its own. `DRAIN_FAILED` is
 * where a drain parks after aborting, and a drain can abort at any step, so the
 * overwhelming majority of records sitting there never dispatched anything.
 * Reconstructing there stamps the common case and disables `restoreRegistration`
 * for it. It is worse than `DEREGISTERED` on this measure rather than better,
 * because a current build already stamps every `DRAIN_FAILED` record that *did*
 * dispatch — `dispatchingStop` runs before the stop call, so the abort that follows
 * a refused stop carries the field — which leaves reconstruction there with almost
 * nothing true to do.
 *
 * The unbounded cost is real but it is a property of those false positives rather
 * than of the state: a record that never dispatched has no container being driven
 * down, so nothing observes the workload absent and nothing retires the invented
 * stamp. That is what the rest of this argument's "it errs the safe way and then
 * ends" depends on, and it is the false positives that do not end.
 *
 * An ordinal comparison (`state >= STOPPING`) would include the state by accident of
 * declaration order, which is why the test here names it.
 *
 * One residual is knowingly left open: a row written *before* the field existed,
 * which dispatched and then aborted, sits in `DRAIN_FAILED` with no stamp and is not
 * reconstructed. Any build old enough to have written it also predates
 * `restoreRegistration`'s guard and `clearedDrainRecord`, so it had already lost the
 * record by other means; widening the rule to catch it would buy that row at the
 * price of every false positive above.
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
     * ordering here would take in whatever is declared after `STOPPING`, and what is
     * declared after it today is a state this must not fire in — the only such state
     * an ordering would reach, the others all being declared earlier.
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
