package mcorch.cri

import java.time.Instant

/**
 * Sandbox state as containerd reports it.
 *
 * [READY] means the sandbox exists and its network is set up. It says nothing
 * about the containers inside it.
 */
public enum class SandboxState {
    READY,
    NOT_READY,

    /** containerd reported a state this build of the wrapper does not know. */
    UNKNOWN,
}

/**
 * Container state as containerd reports it.
 *
 * [RUNNING] means containerd started the process and has not reaped it. It is
 * **not** a health or readiness signal: a Paper server is `RUNNING` for the
 * whole of world generation, and stays `RUNNING` while deadlocked. Judging
 * readiness is the caller's job, from its own probe.
 */
public enum class ContainerState {
    CREATED,
    RUNNING,
    EXITED,

    /** containerd itself does not know, or reported a state this build does not recognise. */
    UNKNOWN,
}

/** The identity CRI assigns a sandbox, echoed back from the spec. */
public data class SandboxMetadata(
    val name: String,
    val uid: String,
    val namespace: String,
    val attempt: UInt,
)

/** The identity CRI assigns a container, echoed back from the spec. */
public data class ContainerMetadata(
    val name: String,
    val attempt: UInt,
)

/** Full sandbox status. */
public data class SandboxStatus(
    val id: SandboxId,
    val metadata: SandboxMetadata,
    val state: SandboxState,
    val createdAt: Instant,
    /**
     * Sandbox IPs, primary first. Redacted from [toString]; do not log these.
     */
    val ips: List<String>,
    val labels: Map<String, String>,
    val annotations: Map<String, String>,
    /** Empty string means containerd's default handler. */
    val runtimeHandler: String,
    /**
     * Statuses of the containers in this sandbox, as containerd returned them
     * alongside the sandbox status. Saves a round trip per container.
     */
    val containerStatuses: List<ContainerStatus>,
) {
    override fun toString(): String =
        "SandboxStatus(id=$id, metadata=$metadata, state=$state, createdAt=$createdAt, " +
            "ips=<${ips.size} redacted>, labels=$labels, annotations=$annotations, " +
            "runtimeHandler=$runtimeHandler, containerStatuses=$containerStatuses)"
}

/** The subset of sandbox information a list call returns. */
public data class SandboxSummary(
    val id: SandboxId,
    val metadata: SandboxMetadata,
    val state: SandboxState,
    val createdAt: Instant,
    val labels: Map<String, String>,
    val annotations: Map<String, String>,
    val runtimeHandler: String,
)

/**
 * Full container status, exactly as containerd sees it.
 *
 * Nothing here is a readiness verdict. [state] is containerd's view of the
 * process; whether the server is accepting players is something the caller
 * establishes separately.
 */
public data class ContainerStatus(
    val id: ContainerId,
    val metadata: ContainerMetadata,
    val state: ContainerState,
    val createdAt: Instant,
    /** `null` until the container has been started. */
    val startedAt: Instant?,
    /** `null` until the container has exited. */
    val finishedAt: Instant?,
    /** `null` until the container has exited. `0` means a clean exit. */
    val exitCode: Int?,
    /** The image as requested when the container was created. */
    val image: ImageName,
    /** The image containerd actually resolved. Compare against a pull result to detect drift. */
    val imageId: ImageId,
    /** Short CamelCase explanation, e.g. `OOMKilled`. Empty when containerd gave none. */
    val reason: String,
    /** Human-readable detail. Empty when containerd gave none. */
    val message: String,
    val labels: Map<String, String>,
    val annotations: Map<String, String>,
    val mounts: List<VolumeMount>,
    /** Host log path, or empty. */
    val logPath: String,
)

/** The subset of container information a list call returns. */
public data class ContainerSummary(
    val id: ContainerId,
    val sandboxId: SandboxId,
    val metadata: ContainerMetadata,
    val state: ContainerState,
    val createdAt: Instant,
    val image: ImageName,
    val imageId: ImageId,
    val labels: Map<String, String>,
    val annotations: Map<String, String>,
)

/** An image present on the node. */
public data class ImageInfo(
    val id: ImageId,
    val repoTags: List<String>,
    val repoDigests: List<String>,
    val sizeBytes: Long,
    /** containerd's recommendation that this image be exempt from garbage collection. */
    val pinned: Boolean,
)

/** Which containerd, and which CRI API version, is on the other end. */
public data class RuntimeVersion(
    /** Version of the kubelet runtime API this response speaks. */
    val version: String,
    /** e.g. `containerd`. */
    val runtimeName: String,
    /** e.g. `2.3.3`. */
    val runtimeVersion: String,
    val runtimeApiVersion: String,
)

/** One condition from the runtime's self-report. */
public data class RuntimeCondition(
    val type: String,
    val status: Boolean,
    val reason: String,
    val message: String,
)

/**
 * The runtime's self-report. Use this as the startup health check: a
 * [CriClient.status] that returns with [runtimeReady] and [networkReady] true
 * means containerd is up, the CRI plugin is loaded, and CNI is configured.
 *
 * A sandbox will not start without a usable CNI network, and `networkReady`
 * false is the cheapest way to find that out.
 */
public data class RuntimeStatus(
    val conditions: List<RuntimeCondition>,
    val runtimeHandlers: List<RuntimeHandlerInfo>,
) {
    /** True when containerd reports it can accept containers. */
    public val runtimeReady: Boolean
        get() = conditions.any { it.type == RUNTIME_READY_CONDITION && it.status }

    /** True when containerd reports its CNI network is configured and usable. */
    public val networkReady: Boolean
        get() = conditions.any { it.type == NETWORK_READY_CONDITION && it.status }

    public companion object {
        public const val RUNTIME_READY_CONDITION: String = "RuntimeReady"
        public const val NETWORK_READY_CONDITION: String = "NetworkReady"
    }
}

/** A runtime handler containerd offers, and what it supports. */
public data class RuntimeHandlerInfo(
    /** Empty string is containerd's default handler. */
    val name: String,
    val recursiveReadOnlyMounts: Boolean,
    val userNamespaces: Boolean,
)
