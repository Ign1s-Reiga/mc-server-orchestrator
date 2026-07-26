package mcorch.cri

/** How mount events propagate between host and container. */
public enum class MountPropagation {
    /** `rprivate`. */
    PRIVATE,

    /** `rslave` — host to container only. */
    HOST_TO_CONTAINER,

    /** `rshared` — both directions. */
    BIDIRECTIONAL,
}

/**
 * A host path mounted into the container.
 *
 * This is the mechanism behind "servers with world data get a volume that
 * outlives the container": the host path is independent of the container, so
 * removing and recreating the container keeps the world. Deciding *whether* a
 * given server gets one is a `:core` policy call, not this module's.
 */
public data class VolumeMount(
    /** Path inside the container. */
    val containerPath: String,
    /** Path on the host. CRI errors if it does not exist. */
    val hostPath: String,
    val readOnly: Boolean = false,
    val selinuxRelabel: Boolean = false,
    val propagation: MountPropagation = MountPropagation.PRIVATE,
    /**
     * Recursive read-only. CRI requires [readOnly] to be true and [propagation]
     * to be [MountPropagation.PRIVATE] when this is set, and the runtime handler
     * has to support it (`RuntimeHandlerFeatures.recursiveReadOnlyMounts`).
     */
    val recursiveReadOnly: Boolean = false,
) {
    init {
        require(containerPath.startsWith("/")) { "containerPath must be absolute, got: $containerPath" }
        require(hostPath.startsWith("/")) { "hostPath must be absolute, got: $hostPath" }
        if (recursiveReadOnly) {
            require(readOnly) { "recursiveReadOnly requires readOnly" }
            require(propagation == MountPropagation.PRIVATE) { "recursiveReadOnly requires PRIVATE propagation" }
        }
    }
}

/** Linux resource limits. `null` means "not specified" — CRI's own default. */
public data class LinuxResources(
    val cpuPeriodMicros: Long? = null,
    val cpuQuotaMicros: Long? = null,
    val cpuShares: Long? = null,
    val memoryLimitBytes: Long? = null,
    val memorySwapLimitBytes: Long? = null,
    val oomScoreAdj: Long? = null,
    val cpusetCpus: String? = null,
    val cpusetMems: String? = null,
    /** Raw cgroup v2 keys, e.g. `"memory.max" to "6937202688"`. */
    val unified: Map<String, String> = emptyMap(),
)

/** Linux security configuration for a container. */
public data class LinuxSecurityContext(
    val runAsUser: Long? = null,
    /** CRI requires [runAsUser] or [runAsUsername] to be set whenever this is. */
    val runAsGroup: Long? = null,
    /** Must already exist in the image's `/etc/passwd`, or the runtime errors. */
    val runAsUsername: String? = null,
    val supplementalGroups: List<Long> = emptyList(),
    val readOnlyRootFilesystem: Boolean = false,
    val noNewPrivileges: Boolean = false,
    val addCapabilities: List<String> = emptyList(),
    val dropCapabilities: List<String> = emptyList(),
    /** A Minecraft server never needs this. */
    val privileged: Boolean = false,
) {
    init {
        require(runAsUser == null || runAsUsername == null) {
            "runAsUser and runAsUsername are mutually exclusive"
        }
        require(runAsGroup == null || runAsUser != null || runAsUsername != null) {
            "runAsGroup requires runAsUser or runAsUsername; CRI specifies the runtime MUST error otherwise"
        }
    }
}

/** Linux-specific container configuration. */
public data class LinuxContainerSpec(
    val resources: LinuxResources = LinuxResources(),
    val securityContext: LinuxSecurityContext = LinuxSecurityContext(),
)

/**
 * Everything CRI needs to create a container inside an existing sandbox.
 *
 * [toString] redacts environment values. Environment is where a Velocity
 * forwarding secret would end up if one were ever passed this way, and a spec
 * that stringifies itself into a log line is exactly how such a secret leaks.
 * Keys are kept because they are useful and are not secret.
 */
public data class ContainerSpec(
    /** Container name. Unique together with [attempt] within the sandbox, for the sandbox's whole lifetime. */
    val name: String,
    /** Incremented when recreating a container with the same name in the same sandbox. */
    val attempt: UInt = 0u,
    /**
     * The image to run. Pull it first — [CriClient.createContainer] does not
     * pull, and reports a missing image as a create failure rather than as a
     * pull failure.
     */
    val image: ImageName,
    /** Overrides the image entrypoint when non-empty. */
    val command: List<String> = emptyList(),
    /** Overrides the image command when non-empty. */
    val args: List<String> = emptyList(),
    val workingDir: String? = null,
    /** Iteration order is preserved. Values are redacted from [toString]. */
    val env: Map<String, String> = emptyMap(),
    val mounts: List<VolumeMount> = emptyList(),
    /** Used to find this container again on a later reconcile pass. */
    val labels: Map<String, String> = emptyMap(),
    val annotations: Map<String, String> = emptyMap(),
    /** Relative to the sandbox's `logDirectory`. */
    val logPath: String = "",
    val stdin: Boolean = false,
    val stdinOnce: Boolean = false,
    val tty: Boolean = false,
    val linux: LinuxContainerSpec = LinuxContainerSpec(),
) {
    init {
        require(name.isNotBlank()) { "container name must not be blank" }
        require(workingDir == null || workingDir.startsWith("/")) {
            "workingDir must be absolute when set, got: $workingDir"
        }
        require(env.keys.none { it.isBlank() }) { "environment variable names must not be blank" }
    }

    override fun toString(): String =
        "ContainerSpec(name=$name, attempt=$attempt, image=$image, command=$command, args=$args, " +
            "workingDir=$workingDir, env=${env.keys.sorted()}=<redacted>, mounts=$mounts, labels=$labels, " +
            "annotations=$annotations, logPath=$logPath, stdin=$stdin, stdinOnce=$stdinOnce, tty=$tty, linux=$linux)"
}
