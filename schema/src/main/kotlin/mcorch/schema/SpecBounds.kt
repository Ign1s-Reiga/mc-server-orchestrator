package mcorch.schema

import kotlin.time.Duration

/**
 * One duration that arrived above its ceiling and the value put in its place.
 *
 * [field] is the path an operator would write in YAML, so the thing that reports
 * this can name something they can go and edit. [declared] is what the record
 * actually holds — it is *not* overwritten anywhere, and reporting it is what
 * keeps a clamp from being a silent reinterpretation.
 */
public data class ClampedDuration(
    val field: String,
    val declared: Duration,
    val applied: Duration,
)

/**
 * A definition with every deadline-bearing duration inside its ceiling, and the
 * list of what had to be moved to get there.
 *
 * [clamped] empty means [definition] is the object that went in, unchanged and
 * un-copied.
 */
public data class BoundedDefinition(
    val definition: ServerDefinition,
    val clamped: List<ClampedDuration>,
) {
    public val wasClamped: Boolean get() = clamped.isNotEmpty()
}

/**
 * The ceilings that apply to a definition which did **not** come through a
 * reader.
 *
 * ## What this is for
 *
 * Three durations on a spec stop being numbers and become *transport deadlines*:
 * `spec.lifecycle.stopGracePeriod` becomes `stopContainer`'s gRPC deadline,
 * `spec.lifecycle.drain.saveTimeout` becomes `execSync`'s, and
 * `spec.backends.drain.sealTimeout` becomes the deadline on a blocking,
 * uncancellable HTTP call to the proxy's control endpoint. CLAUDE.md requires
 * every call crossing the `:cri` boundary to have a timeout; each of these three
 * is only as bounded as the value in the field.
 *
 * Each is bounded by its YAML reader and by nothing else. `LifecycleSpec.init`
 * checks only the save-timeout relation, `ProxyLifecycleSpec` and
 * [BackendDrainSpec] have no `init` at all, and a definition that did not come
 * through a reader — a hand-edited store row, a migration, a fixture — therefore
 * carries whatever the field can express. Thirty hours in a column is a reconcile
 * worker parked with no effective timeout, and enough parked workers is a loop
 * that reconciles nothing and a process that will not shut down.
 *
 * This object is the *one* bound, applied where a definition is rebuilt from a
 * stored record, rather than N ceilings at N consumers. `StopGraceCeiling` and
 * `ExecTimeoutCeiling` in `:core` stay where they are and stay load-bearing for
 * anything that never went through a store — a fixture handed straight to a
 * `Node` — but for a stored definition they become unreachable rather than the
 * only thing standing between the loop and a parked worker.
 *
 * ## Why an `init` on the spec types would have been wrong
 *
 * A `require` in `LifecycleSpec` or [BackendDrainSpec] is the obvious fix and it
 * has been ruled out twice. It makes the whole definition unbuildable, so the row
 * stops decoding; `Reconciler.rejectDefinition` records that as `PERMANENT`
 * without exempting a `terminating` server, so a populated, world-holding server
 * with one bad field becomes a server nobody can drain, stop or delete. That is
 * the state that ends in a manual `crictl stop`. A clamp is charged to the same
 * one server and costs it nothing it can notice.
 *
 * ## Why it clamps rather than refusing the row
 *
 * `:store` can refuse a single record — it has [ClampedDuration]'s opposite
 * number, an unreadable entry, and it is the right answer for a row whose *kind*
 * or *encoding* this build does not know. It is the wrong answer here for the
 * same reason a spec-level `require` is: an unreadable definition is one the loop
 * cannot act on at all, so the container it describes keeps running and keeps its
 * players, and the delete that would retire it has no spec to drain against. A
 * refusal trades a conditional harm for a certain one.
 *
 * Clamping is safe because of *what* is being shortened. [MAX_SAVE_TIMEOUT] and
 * the handshake ceiling bound how long **this orchestrator waits** for an
 * acknowledgement; cutting that short can only withhold a confirmation, and an
 * unconfirmed save is a container this orchestrator will not stop (CLAUDE.md
 * invariant 3).
 *
 * **One site now falls outside that argument, deliberately.**
 * `NodeForcedTermination` stops without `mayStop`, so on that path clamping
 * `saveTimeout` withholds a confirmation *and* the container stops anyway. It does
 * not restore the finding this clamp was reasoned against, because that path does
 * not derive its grace floor from `saveTimeout` when no save was sent — it uses a
 * shutdown-save allowance instead, precisely because `saveTimeout` then describes
 * an RCON exec that never ran. The exception is written here rather than left to be
 * rediscovered: an argument with an unrecorded exception is the shape this codebase
 * keeps having to unpick.
 *
 * [MAX_STOP_GRACE_PERIOD] bounds a stop that `mayStop` has already
 * gated on a confirmed save, so the grace period there is the last-resort net and
 * not the save path. And every ceiling is borrowed from the widest value a reader
 * accepts, so no definition an operator could legitimately write is shortened by
 * a single second.
 *
 * ## Ceilings only — a floor would invert the pair
 *
 * The readers bound these fields as a *range*, `1s..max`. Only the upper half is
 * reproduced here, and that is deliberate rather than half-finished:
 * `stopGracePeriod` and `saveTimeout` are a validated pair
 * ([SpecInvariants.stopGraceProblem]), and **raising** `saveTimeout` raises the
 * minimum the grace period has to clear. A row holding `saveTimeout = 0` and
 * `stopGracePeriod = 30s` satisfies the schema exactly; floor the save timeout up
 * to one second and the pair inverts, which is a container SIGKILLed part-way
 * through its save. Lowering `saveTimeout` can only ever lower that minimum, so
 * ceilings compose with the pair and floors do not.
 *
 * The same reasoning is why [bound] takes both halves of the pair together. A
 * ceiling applied to one half by something that cannot see the other half is what
 * the thirtieth drain audit found in `:core`, and the reason it prefers this
 * location is that a decode has both.
 *
 * ## Two fields are deliberately not here
 *
 * `startupTimeout` and `spec.lifecycle.drain.playerTransferTimeout` are wall-clock
 * comparisons — the loop records an instant and compares against it on a later
 * pass — not deadlines on a call, so an absurd value there parks nothing. They
 * were examined and cleared. Do not "make it consistent" by adding them: a bound
 * on a field nothing waits on is a behaviour change with no defect behind it.
 */
public object SpecBounds {
    /** Two hours: the widest `stopGracePeriod` `PaperServerReader` accepts. */
    public val MAX_STOP_GRACE_PERIOD: Duration = PaperServerDefaults.MAX_STOP_GRACE_PERIOD

    /** One hour: the widest lifecycle timeout any reader accepts. */
    public val MAX_SAVE_TIMEOUT: Duration = PaperServerDefaults.MAX_TIMEOUT

    /**
     * One hour: what `VelocityProxyReader` caps the proxy's own `stopGracePeriod`
     * at. Tighter than [MAX_STOP_GRACE_PERIOD] because a proxy holds no world and
     * its grace period is only Velocity closing listeners on a drain that already
     * emptied it.
     */
    public val MAX_PROXY_STOP_GRACE_PERIOD: Duration = VelocityProxyDefaults.MAX_TIMEOUT

    /** One hour: `VelocityProxyReader.handshakeTimeout`'s cap, shared by all three steps. */
    public val MAX_HANDSHAKE_TIMEOUT: Duration = VelocityProxyDefaults.MAX_TIMEOUT

    // The property that makes clamping both halves of the pair safe, checked here
    // rather than asserted in prose.
    //
    // After a clamp the largest save timeout possible is MAX_SAVE_TIMEOUT and the
    // smallest grace period a clamp can produce is MAX_STOP_GRACE_PERIOD. If the
    // first plus the schema's margin ever exceeded the second, clamping would
    // *create* the inversion SpecInvariants.stopGraceProblem exists to prevent — on
    // rows that satisfied it perfectly well on disk. The two constants are borrowed
    // from two different objects, so nothing but this stops one of them moving.
    init {
        val floor = MAX_SAVE_TIMEOUT + PaperServerDefaults.MIN_STOP_GRACE_MARGIN
        require(MAX_STOP_GRACE_PERIOD >= floor) {
            "clamping would invert the stop-grace invariant: a save timeout capped at " +
                "${DurationFormat.render(MAX_SAVE_TIMEOUT)} needs a grace period of at least " +
                "${DurationFormat.render(floor)}, but the grace ceiling is " +
                "${DurationFormat.render(MAX_STOP_GRACE_PERIOD)}"
        }
    }

    /**
     * [definition] with every deadline-bearing duration inside its ceiling.
     *
     * Returns the argument itself when nothing needed moving, which is every
     * definition a reader produced and therefore every read on a healthy store.
     * The reconcile loop reads the whole fleet each resync, so the ordinary path
     * here allocates nothing.
     *
     * When something *does* move, the clamped spec is rebuilt through the ordinary
     * constructors, so `LifecycleSpec.init` re-runs on the result. That is a free
     * standing proof that a clamp never inverts the pair: if it ever did, the
     * rebuild would throw where the caller already handles a spec that does not
     * satisfy the schema.
     */
    public fun bound(definition: ServerDefinition): BoundedDefinition =
        when (definition) {
            is PaperServerDefinition -> {
                val clamped = mutableListOf<ClampedDuration>()
                val spec = boundPaper(definition.spec, clamped)
                if (clamped.isEmpty()) {
                    BoundedDefinition(definition, emptyList())
                } else {
                    BoundedDefinition(definition.copy(spec = spec), clamped)
                }
            }

            is VelocityProxyDefinition -> {
                val clamped = mutableListOf<ClampedDuration>()
                val spec = boundProxy(definition.spec, clamped)
                if (clamped.isEmpty()) {
                    BoundedDefinition(definition, emptyList())
                } else {
                    BoundedDefinition(definition.copy(spec = spec), clamped)
                }
            }
        }

    private fun boundPaper(
        spec: PaperServerSpec,
        clamped: MutableList<ClampedDuration>,
    ): PaperServerSpec {
        val saveTimeout =
            capWaiting("spec.lifecycle.drain.saveTimeout", spec.lifecycle.drain.saveTimeout, MAX_SAVE_TIMEOUT, clamped)
        val stopGracePeriod =
            capStop("spec.lifecycle.stopGracePeriod", spec.lifecycle.stopGracePeriod, MAX_STOP_GRACE_PERIOD, clamped)
        if (clamped.isEmpty()) return spec
        return spec.copy(
            lifecycle =
                spec.lifecycle.copy(
                    drain = spec.lifecycle.drain.copy(saveTimeout = saveTimeout),
                    stopGracePeriod = stopGracePeriod,
                ),
        )
    }

    private fun boundProxy(
        spec: VelocityProxySpec,
        clamped: MutableList<ClampedDuration>,
    ): VelocityProxySpec {
        val drain = spec.backends.drain
        val seal = capWaiting("spec.backends.drain.sealTimeout", drain.sealTimeout, MAX_HANDSHAKE_TIMEOUT, clamped)
        val destination =
            capWaiting(
                "spec.backends.drain.destinationTimeout",
                drain.destinationTimeout,
                MAX_HANDSHAKE_TIMEOUT,
                clamped,
            )
        val deregister =
            capWaiting(
                "spec.backends.drain.deregisterTimeout",
                drain.deregisterTimeout,
                MAX_HANDSHAKE_TIMEOUT,
                clamped,
            )
        val stopGracePeriod =
            capStop(
                "spec.lifecycle.stopGracePeriod",
                spec.lifecycle.stopGracePeriod,
                MAX_PROXY_STOP_GRACE_PERIOD,
                clamped,
            )
        if (clamped.isEmpty()) return spec
        // `drain.copy`, not `BackendDrainSpec(...)`. Naming all three of today's
        // fields is correct today and stops being correct the day a fourth is
        // added: the new field would be reset to its default on every proxy row
        // where any of these clamps fired — silently, with no compile error, on
        // exactly the population the clamp selects for. That is the "one of three
        // identically-shaped siblings" recurrence this object exists to end, and
        // it is not to be reproduced inside it. Same reason `boundPaper` copies.
        return spec.copy(
            backends =
                spec.backends.copy(
                    drain =
                        drain.copy(
                            sealTimeout = seal,
                            destinationTimeout = destination,
                            deregisterTimeout = deregister,
                        ),
                ),
            lifecycle = spec.lifecycle.copy(stopGracePeriod = stopGracePeriod),
        )
    }

    /**
     * The ceiling for a duration that authorises **waiting**, non-finite included.
     *
     * Mirrors `ExecTimeoutCeiling.bound` in `:core`, and for its reason: cutting a
     * wait short can never do more than withhold a confirmation, so there is no
     * value — `Duration.INFINITE` included — that is safer left alone than capped.
     */
    private fun capWaiting(
        field: String,
        requested: Duration,
        ceiling: Duration,
        clamped: MutableList<ClampedDuration>,
    ): Duration {
        if (requested <= ceiling) return requested
        clamped += ClampedDuration(field, requested, ceiling)
        return ceiling
    }

    /**
     * The ceiling for a stop grace period, which passes a non-finite value
     * through untouched.
     *
     * Mirrors `StopGraceCeiling.bound` in `:core`, and for its reason: a grace
     * period that is merely too long is a number somebody meant, and shortening it
     * costs nothing because a confirmed save is already behind it —
     * `Duration.INFINITE` is not a number anybody meant, and turning it into a
     * plausible-looking stop is worse than leaving it to be refused by name at the
     * runtime edge, where the message an operator reads is written. Nothing that
     * reaches this from a store can be non-finite in any case: the document format
     * refuses to write one.
     */
    private fun capStop(
        field: String,
        requested: Duration,
        ceiling: Duration,
        clamped: MutableList<ClampedDuration>,
    ): Duration {
        if (!requested.isFinite() || requested <= ceiling) return requested
        clamped += ClampedDuration(field, requested, ceiling)
        return ceiling
    }
}
