package mcorch.core

import mcorch.schema.ResourceName
import mcorch.schema.ServerKind

/**
 * The labels every workload this orchestrator creates carries.
 *
 * They are the mechanism behind idempotency: a reconcile pass finds what it
 * created last time by listing by label, and adopts it, instead of creating a
 * second one. Nothing here is ever derived from an ID the loop remembered —
 * memory is lost on restart, labels are not.
 *
 * None of these values is player data. [SERVER] is a declared object's name.
 */
public object Labels {
    /** Distinguishes our containers from everything else on the node. */
    public const val MANAGED_BY: String = "mcorch.dev/managed-by"

    /** The value of [MANAGED_BY]. */
    public const val MANAGER: String = "mcorch"

    /** The declared server this workload belongs to. */
    public const val SERVER: String = "mcorch.dev/server"

    /** The server kind, so a future kind's workloads are distinguishable at a glance. */
    public const val KIND: String = "mcorch.dev/kind"

    /**
     * The [WorkloadSpec.specHash] the workload was created from. Recorded on
     * the workload itself so a pass can compare the running shape against the
     * desired one without a store round trip.
     */
    public const val SPEC_HASH: String = "mcorch.dev/spec-hash"

    /**
     * Whether this workload holds world data that has to be flushed before it
     * stops. `true` or `false`, and absent means "created before this label
     * existed" — which is not the same as `false`.
     *
     * Recorded on the workload rather than read from the definition because a
     * drain is conducted against the *container*. An operator who flips
     * `storage.mode` to `ephemeral` has changed the definition, not the
     * container that is running with a world in it, and a drain that believed
     * the definition would skip the save.
     */
    public const val WORLD_DATA: String = "mcorch.dev/world-data"

    /**
     * Whether this workload was created with a channel that can *confirm* a
     * completed save. Same reasoning as [WORLD_DATA]: enabling RCON in the
     * definition does nothing for a container that is already running without
     * it, and a drain that believed the definition would send a save request
     * into a socket that is not listening and then have to guess what happened.
     */
    public const val SAVE_CONFIRMABLE: String = "mcorch.dev/save-confirmable"

    /**
     * The persistent volume this workload was created to mount.
     *
     * Same reasoning as [WORLD_DATA] — a fact about *this* container, recorded on
     * it, because the definition beside it may have been edited since — and the
     * same "absent says nothing" convention. It is the name a recovery starts
     * from, and until this label existed it was recorded nowhere the loop could
     * read back: observed status carried whatever the definition said at the
     * moment it was drafted, which is not a memory of anything.
     *
     * **Written only when there is a volume, and absent rather than empty when
     * there is not.** Absent covers both "this workload has no volume" and "this
     * workload predates the label", and both have to mean *the previous record
     * stands* — because a workload that mounts nothing must not clear the record
     * of which volume still holds the world it stopped mounting, and that record
     * is the whole reason this exists.
     *
     * An empty value would be a value that says nothing, which is noise rather
     * than a claim: [volumeValue] rejects it along with anything else that is not
     * a resource name, so writing one would be defused by the reader rather than
     * by the writer. That is not a reason to write it. A sentinel spelling — an
     * empty string, `none` — is one careless parse away from becoming a positive
     * claim that clears a carried name, and the omission costs nothing.
     *
     * ## It is outside the spec hash, and that is what made it safe to add
     *
     * See the note at `PaperWorkloadPlanner.plan`'s label construction. Adding a
     * label does not recreate a single running container; what it does mean is
     * that containers created before it carry none, so the field it feeds
     * converges as the fleet turns over rather than at upgrade.
     *
     * ## What supersedes it, and what retiring it would cost
     *
     * `ContainerStatus.mounts` plumbed out through `ContainerView` and
     * `WorkloadObservation.Present` would answer this from the runtime's own view
     * of what is mounted, which is strictly better: it survives a container this
     * orchestrator did not create, and it cannot disagree with reality. Whoever
     * lands that should delete this label rather than keep both — two producers of
     * one fact is the shape this project has been bitten by repeatedly.
     *
     * The trade was weighed rather than assumed. Against: it is a third label the
     * drain-adjacent code reads, and the mounts plumbing would leave it redundant.
     * For: the retire cost is exactly one label and one read, [WORLD_DATA] has the
     * same character and is load-bearing, and the alternative was a field that no
     * pass could ever populate. The value cannot go stale while it is here because
     * `spec.storage.volume` is *in* the spec hash — renaming a volume already
     * recreates the container — so the label and the container it sits on cannot
     * disagree.
     */
    public const val VOLUME: String = "mcorch.dev/volume"

    /** Reads a boolean fact off a workload's labels. Null means the workload does not carry it. */
    public fun booleanValue(
        labels: Map<String, String>,
        key: String,
    ): Boolean? =
        when (labels[key]) {
            TRUE -> true
            FALSE -> false
            else -> null
        }

    /**
     * Reads [VOLUME] off a workload's labels. Null means the workload does not
     * carry it, **or** carries something that is not a resource name.
     *
     * The two are answered the same way on purpose. A value the runtime hands
     * back is not this build's to trust — it may have been written by another
     * build, or by hand — and the caller's response to "says nothing" is to keep
     * the record it already has, which is the right response to a name that
     * cannot be parsed too. The alternative is an exception thrown inside a
     * reconcile pass over a label, which would stop a server converging for a
     * reason that has nothing to do with it.
     */
    public fun volumeValue(labels: Map<String, String>): ResourceName? =
        labels[VOLUME]?.let { ResourceName.of(it).getOrNull() }

    /** Renders a boolean fact for a label value. */
    public fun booleanLabel(value: Boolean): String = if (value) TRUE else FALSE

    private const val TRUE: String = "true"
    private const val FALSE: String = "false"

    /** The labels identifying one server's workload. */
    public fun forServer(
        server: ResourceName,
        kind: ServerKind,
    ): Map<String, String> =
        mapOf(
            MANAGED_BY to MANAGER,
            SERVER to server.value,
            KIND to kind.wireValue,
        )

    /** The selector that finds one server's workload again. */
    public fun selectorFor(server: ResourceName): Map<String, String> =
        mapOf(
            MANAGED_BY to MANAGER,
            SERVER to server.value,
        )
}
