package mcorch.app

import mcorch.core.node.LocalNodeConfig
import mcorch.schema.NodeName
import java.nio.file.Path

/**
 * Where this process keeps its state and how it reaches its container runtime.
 *
 * Read from the environment rather than a file for now: there is one deployment
 * shape and every value below has to be known before anything is opened, so a
 * config format would be a parser to get wrong. [fromEnvironment] is total —
 * it either returns a usable configuration or says which variable is missing.
 */
public data class OrchestratorConfig(
    /** How this node is addressed. Not a hostname lookup: the operator names it. */
    val nodeName: NodeName,
    /**
     * The containerd CRI socket, in the form containerd tooling uses.
     *
     * Deliberately has no default. Guessing `/run/containerd/containerd.sock`
     * would point a fresh deployment at whatever containerd the host already
     * runs — Docker's, or Kubernetes' — and the first thing this orchestrator
     * would do is list *its* sandboxes.
     */
    val runtimeEndpoint: String,
    /** The state and secret databases live here. */
    val dataDirectory: Path,
    /** Persistent volumes live here, and outlive every container (CLAUDE.md invariant 2). */
    val volumeRoot: Path,
    /** Container logs live here. */
    val logRoot: Path,
    /**
     * Where the deployment put the artefacts this orchestrator mounts into
     * containers — today just the Velocity control plugin JAR, under the name
     * [mcorch.core.WorkloadAsset.VELOCITY_CONTROL_PLUGIN] expects.
     *
     * Separate from [dataDirectory] as a matter of what it is: state belongs to
     * the deployment, artefacts belong to the *build* that produced this
     * process, and the two have different lifetimes on upgrade. It is still
     * defaulted underneath the data directory, because a single-host install has
     * one place to put things and a missing artefact is refused loudly at create
     * time rather than producing a proxy with no control endpoint.
     */
    val assetRoot: Path,
    val sandboxNamespace: String = DEFAULT_SANDBOX_NAMESPACE,
    /** See [LocalNodeConfig.cgroupParent]: its shape depends on the runtime's cgroup driver. */
    val cgroupParent: String? = LocalNodeConfig.DEFAULT_CGROUP_PARENT,
) {
    public companion object {
        public const val ENDPOINT_VARIABLE: String = "MCORCH_CRI_ENDPOINT"
        public const val DATA_VARIABLE: String = "MCORCH_DATA_DIR"
        public const val NODE_VARIABLE: String = "MCORCH_NODE_NAME"
        public const val CGROUP_VARIABLE: String = "MCORCH_CGROUP_PARENT"
        public const val ASSET_VARIABLE: String = "MCORCH_ASSET_DIR"

        public const val DEFAULT_SANDBOX_NAMESPACE: String = "mcorch"
        public const val DEFAULT_NODE_NAME: String = "local"
        public const val DEFAULT_DATA_DIR: String = "/var/lib/mcorch"

        /**
         * Builds a configuration from [environment], or fails with a message
         * naming the variable at fault.
         *
         * @throws IllegalArgumentException if a required variable is missing or
         *   a supplied one cannot be used.
         */
        public fun fromEnvironment(environment: Map<String, String>): OrchestratorConfig {
            val endpoint =
                environment[ENDPOINT_VARIABLE]?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException(
                        "$ENDPOINT_VARIABLE is not set. It is the containerd CRI socket this orchestrator " +
                            "manages, such as `unix:///run/mcorch-dev/containerd.sock`. There is no default on " +
                            "purpose: pointing at the host's own containerd would put this process in charge of " +
                            "containers it did not create",
                    )
            val nodeName =
                NodeName
                    .of(environment[NODE_VARIABLE]?.takeIf { it.isNotBlank() } ?: DEFAULT_NODE_NAME)
                    .getOrElse { failure ->
                        throw IllegalArgumentException("$NODE_VARIABLE is not a usable node name: ${failure.message}")
                    }
            val data = Path.of(environment[DATA_VARIABLE]?.takeIf { it.isNotBlank() } ?: DEFAULT_DATA_DIR)
            return OrchestratorConfig(
                nodeName = nodeName,
                runtimeEndpoint = endpoint,
                dataDirectory = data,
                volumeRoot = data.resolve("volumes"),
                logRoot = data.resolve("logs"),
                assetRoot =
                    environment[ASSET_VARIABLE]?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
                        ?: data.resolve("assets"),
                cgroupParent =
                    environment[CGROUP_VARIABLE]?.takeIf { it.isNotBlank() }
                        ?: LocalNodeConfig.DEFAULT_CGROUP_PARENT,
            )
        }
    }
}
