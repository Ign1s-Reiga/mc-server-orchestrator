package mcorch.cri

/**
 * Filter for [CriClient.listSandboxes]. All set fields are ANDed.
 *
 * Listing by label is how an idempotent reconcile pass finds what it created
 * last time without keeping IDs anywhere but the store.
 */
public data class SandboxFilter(
    val id: SandboxId? = null,
    /** [SandboxState.UNKNOWN] is a wrapper-side value only and cannot be filtered on. */
    val state: SandboxState? = null,
    val labelSelector: Map<String, String> = emptyMap(),
) {
    init {
        require(state != SandboxState.UNKNOWN) {
            "SandboxState.UNKNOWN is not a CRI state and cannot be used as a filter"
        }
    }

    public companion object {
        /** No filtering. */
        public val ALL: SandboxFilter = SandboxFilter()

        /** Every sandbox carrying all of [labels]. */
        public fun byLabels(labels: Map<String, String>): SandboxFilter = SandboxFilter(labelSelector = labels)
    }
}

/**
 * Filter for [CriClient.listContainers]. All set fields are ANDed.
 */
public data class ContainerFilter(
    val id: ContainerId? = null,
    val sandboxId: SandboxId? = null,
    val state: ContainerState? = null,
    val labelSelector: Map<String, String> = emptyMap(),
) {
    public companion object {
        /** No filtering. */
        public val ALL: ContainerFilter = ContainerFilter()

        /** Every container carrying all of [labels]. */
        public fun byLabels(labels: Map<String, String>): ContainerFilter = ContainerFilter(labelSelector = labels)

        /** Every container in [sandboxId], whatever its state. */
        public fun inSandbox(sandboxId: SandboxId): ContainerFilter = ContainerFilter(sandboxId = sandboxId)
    }
}

/**
 * Credentials for a registry pull.
 *
 * Never logged: [toString] keeps only the server address. Nothing in this module
 * writes any other field anywhere.
 */
public data class RegistryAuth(
    val username: String? = null,
    val password: String? = null,
    /** Base64 `username:password`, as in a Docker config. */
    val auth: String? = null,
    val serverAddress: String? = null,
    val identityToken: String? = null,
    val registryToken: String? = null,
) {
    override fun toString(): String = "RegistryAuth(serverAddress=$serverAddress, credentials=<redacted>)"
}
