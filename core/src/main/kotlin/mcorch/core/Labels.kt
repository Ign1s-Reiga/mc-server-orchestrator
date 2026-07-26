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
