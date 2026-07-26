package mcorch.cri

/** Which namespace a sandbox or container joins. CRI's `TARGET` mode is not exposed. */
public enum class NamespaceMode {
    /** Shared across every container in the sandbox. */
    POD,

    /** Private to the container. */
    CONTAINER,

    /** The host's namespace. */
    NODE,
}

/** Namespace configuration for a sandbox. */
public data class NamespaceSpec(
    /** CRI accepts only `POD` or `NODE` here. */
    val network: NamespaceMode = NamespaceMode.POD,
    val pid: NamespaceMode = NamespaceMode.CONTAINER,
    /** CRI accepts only `POD` or `NODE` here. */
    val ipc: NamespaceMode = NamespaceMode.POD,
) {
    init {
        require(network != NamespaceMode.CONTAINER) { "network namespace must be POD or NODE, not CONTAINER" }
        require(ipc != NamespaceMode.CONTAINER) { "ipc namespace must be POD or NODE, not CONTAINER" }
    }
}

/** Transport protocol for a published port. */
public enum class PortProtocol { TCP, UDP, SCTP }

/**
 * A sandbox port published on the host.
 *
 * `hostPort == 0` means "do not publish" — CRI gives it that explicit meaning,
 * it is not a request for a dynamically allocated port.
 */
public data class PortMapping(
    val containerPort: Int,
    val hostPort: Int = 0,
    val protocol: PortProtocol = PortProtocol.TCP,
    val hostIp: String = "",
) {
    init {
        require(containerPort in 1..65535) { "containerPort must be in 1..65535, got: $containerPort" }
        require(hostPort in 0..65535) { "hostPort must be in 0..65535, got: $hostPort" }
    }

    /** Host IPs are redacted from [toString] — structured logging must not carry IP addresses. */
    override fun toString(): String =
        "PortMapping(containerPort=$containerPort, hostPort=$hostPort, protocol=$protocol, hostIp=<redacted>)"
}

/** DNS configuration for a sandbox. */
public data class DnsConfig(
    val servers: List<String> = emptyList(),
    val searches: List<String> = emptyList(),
    val options: List<String> = emptyList(),
) {
    /** Nameserver addresses are redacted — structured logging must not carry IP addresses. */
    override fun toString(): String =
        "DnsConfig(servers=<${servers.size} redacted>, searches=$searches, options=$options)"
}

/** Linux security configuration for the sandbox itself, not for containers in it. */
public data class LinuxSandboxSecurityContext(
    val namespaces: NamespaceSpec = NamespaceSpec(),
    val runAsUser: Long? = null,
    /** CRI requires [runAsUser] to be set whenever this is. */
    val runAsGroup: Long? = null,
    val supplementalGroups: List<Long> = emptyList(),
    val readOnlyRootfs: Boolean = false,
    /**
     * Whether the sandbox may host a privileged container. A Minecraft server
     * never needs this.
     */
    val privileged: Boolean = false,
) {
    init {
        require(runAsGroup == null || runAsUser != null) {
            "runAsGroup requires runAsUser; CRI specifies the runtime MUST error otherwise"
        }
    }
}

/** Linux-specific sandbox configuration. */
public data class LinuxSandboxSpec(
    val cgroupParent: String? = null,
    val sysctls: Map<String, String> = emptyMap(),
    val securityContext: LinuxSandboxSecurityContext = LinuxSandboxSecurityContext(),
)

/**
 * Everything CRI needs to create a pod sandbox.
 *
 * Keep the value that was passed to [CriClient.runSandbox]. CRI requires the
 * *same* sandbox config to be passed again to [CriClient.createContainer]; it is
 * immutable for the sandbox's whole lifetime, and reconstructing a
 * not-quite-identical one is a source of subtle runtime misbehaviour.
 */
public data class SandboxSpec(
    /** Sandbox name. Together with [uid], [namespace] and [attempt] it identifies the sandbox. */
    val name: String,
    /** A stable unique ID chosen by the caller. Not a player UUID — never put one here. */
    val uid: String,
    /** Logical grouping. This is not a Kubernetes namespace; nothing resolves it against an apiserver. */
    val namespace: String,
    /** Incremented when recreating a sandbox for the same logical pod. */
    val attempt: UInt = 0u,
    /** Required unless the network namespace is [NamespaceMode.NODE]. */
    val hostname: String = "",
    /** Host directory for container logs. Paths in `ContainerSpec.logPath` are relative to it. */
    val logDirectory: String = "",
    val portMappings: List<PortMapping> = emptyList(),
    val dnsConfig: DnsConfig? = null,
    /** Used to find this sandbox again on a later reconcile pass. */
    val labels: Map<String, String> = emptyMap(),
    val annotations: Map<String, String> = emptyMap(),
    val linux: LinuxSandboxSpec = LinuxSandboxSpec(),
    /** Named runtime handler, or `null` for containerd's default. An unknown handler is rejected. */
    val runtimeHandler: String? = null,
) {
    init {
        require(name.isNotBlank()) { "sandbox name must not be blank" }
        require(uid.isNotBlank()) { "sandbox uid must not be blank" }
        require(namespace.isNotBlank()) { "sandbox namespace must not be blank" }
        require(hostname.isNotBlank() || linux.securityContext.namespaces.network == NamespaceMode.NODE) {
            "hostname may only be empty when the sandbox network namespace is NODE"
        }
        require(runtimeHandler == null || runtimeHandler.isNotBlank()) {
            "runtimeHandler must be null (containerd default) or a non-blank handler name"
        }
    }
}
