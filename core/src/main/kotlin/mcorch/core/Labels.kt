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
