package mcorch.core.node

import mcorch.core.AssetMount
import mcorch.core.NodeException
import mcorch.core.NodeOperation
import mcorch.core.StorageRequest
import mcorch.core.WorkloadSpec
import mcorch.schema.NodeName
import mcorch.schema.ResourceName
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The host directories a workload needs, and the translation of everything the
 * filesystem can say about them.
 *
 * Its own object rather than a method on [LocalNode] for one reason: this is the
 * part of the node that fails without a runtime being involved at all — a full
 * disk, a read-only mount, a volume root owned by somebody else — and it is the
 * part that can be exercised without a containerd. What it must never do is let
 * an [IOException] out: [mcorch.core.Node] promises callers see nothing but a
 * [NodeException], and the reconcile loop's worker is built on that promise.
 */
internal object HostPaths {
    private val LOG = LoggerFactory.getLogger(HostPaths::class.java)

    /**
     * Creates the log directory, and the volume directory if it is not already
     * there.
     *
     * An existing volume directory is left exactly as it is. That is the point
     * of a persistent volume and it is the path a restart goes through: the
     * world in it is older than the container about to be created, and must
     * outlive the next one too.
     */
    fun prepare(
        node: NodeName,
        volumeRoot: Path,
        logRoot: Path,
        spec: WorkloadSpec,
    ) {
        try {
            Files.createDirectories(logDirectory(logRoot, spec.server))
            val storage = spec.storage
            if (storage is StorageRequest.Persistent) {
                val path = volumePath(volumeRoot, storage.volume)
                if (Files.notExists(path)) {
                    Files.createDirectories(path)
                    LOG.info("created persistent volume directory for volume={} on node={}", storage.volume, node)
                }
            }
        } catch (failure: IOException) {
            throw rejected(node, volumeRoot, logRoot, spec, failure)
        } catch (failure: SecurityException) {
            throw rejected(node, volumeRoot, logRoot, spec, failure)
        }
    }

    /**
     * Every host path that has to appear inside the container, derived in one
     * place.
     *
     * **This is the only derivation of a container's mounts**, and it lives here
     * rather than in [LocalNode] for the reason the rest of this object does: it
     * is a decision about the filesystem that can be exercised without a
     * containerd, and what [LocalNode] does with the result is a field copy into
     * a CRI type. It used to be a `when` inside the node, one of whose branches
     * returned `emptyList()` while discarding the path it was given — so the
     * proxy's control plugin was "mounted" by a planner and dropped one layer
     * down, in code no `:core` test may name (`mcorch.cri` types are
     * [LocalNode]'s alone). The whole point of moving it is that this function
     * *is* nameable from a test.
     *
     * ## It is also where the *plan* is checked, not just the filesystem
     *
     * Two rules about operator-supplied paths are enforced here rather than in
     * `WorkloadSpec`'s `init`, and the difference is which failures are survivable.
     * A `require` in the type throws out of `Reconciler`'s `Pass` construction,
     * which sits above the delete exemption, so it freezes the drain of a server
     * that may hold a world and have players on it. A refusal here fails the
     * *create* — which is the operation that is actually wrong — and leaves the
     * drain, the stop and the teardown able to run, because none of them asks this
     * function anything. See `StorageRequest.Persistent.mountPath`.
     *
     * @throws NodeException.Rejected if an asset the workload needs is not on
     *   this node, or if the mounts the spec asks for cannot be built coherently.
     *   Permanent: see [rejected].
     */
    fun mounts(
        node: NodeName,
        volumeRoot: Path,
        assetRoot: Path,
        spec: WorkloadSpec,
    ): List<HostMount> =
        buildList {
            when (val storage = spec.storage) {
                is StorageRequest.Persistent -> {
                    checkMountPlan(node, spec, storage)
                    add(
                        HostMount(
                            containerPath = storage.mountPath,
                            hostPath = volumePath(volumeRoot, storage.volume).toString(),
                            readOnly = false,
                        ),
                    )
                }

                // The one case with no mount, and the only one that may skip it.
                // It carries no path to drop: an ephemeral workload writes into
                // the container's own layer. See `StorageRequest`.
                StorageRequest.Ephemeral -> {
                    Unit
                }
            }
            for (mount in spec.assets) {
                add(
                    HostMount(
                        containerPath = mount.destination,
                        hostPath = assetFile(node, assetRoot, spec, mount).toString(),
                        // Never writable. See `AssetMount`.
                        readOnly = true,
                    ),
                )
            }
        }

    /**
     * The two rules about where a persistent world lands, checked where refusing
     * costs a create rather than a delete.
     *
     * **Absolute.** A relative `containerPath` is not something the runtime can
     * bind; it reaches CRI and fails there, with a message about a mount rather
     * than about a definition.
     *
     * **Not shadowed by an artefact, and not shadowing one.** `AssetMount` and
     * `StorageRequest.Persistent` both carry paths that are now genuinely honoured,
     * and nothing had ever checked one against the other. Overlapping bind mounts
     * are resolved by an ordering this code does not choose, so the outcomes are a
     * read-only artefact sitting on top of a world directory, or a writable world
     * hiding the control plugin — and the second is the silent one, a proxy that
     * starts perfectly and has no control endpoint. Neither planner can spell it
     * today, which is exactly why the rule is written down before a third one is.
     */
    private fun checkMountPlan(
        node: NodeName,
        spec: WorkloadSpec,
        storage: StorageRequest.Persistent,
    ) {
        val world = storage.mountPath
        if (!world.startsWith("/")) {
            throw NodeException.Rejected(
                node,
                NodeOperation.CREATE,
                "`${spec.server}` asks for its world at `$world`, which is not an absolute path. Nothing can be " +
                    "mounted there, so no container is created — the definition's `spec.storage.mountPath` has " +
                    "to start with `/`. Any container that already exists is unaffected and can still be drained",
            )
        }
        val shadowed = spec.assets.map { it.destination }.filter { it == world || it.startsWith("$world/") }
        if (shadowed.isNotEmpty()) {
            throw NodeException.Rejected(
                node,
                NodeOperation.CREATE,
                "`${spec.server}` would have $shadowed mounted at or under its world mount `$world`. One of the " +
                    "two would hide the other and which one is not this orchestrator's decision, so no container " +
                    "is created: either the artefact or `spec.storage.mountPath` has to move",
            )
        }
    }

    /**
     * The file on this host backing an asset, refusing rather than mounting a
     * hole.
     *
     * A missing artefact has to be a failure *here*. The runtime would happily
     * create the mount point as an empty directory, the container would start,
     * the proxy would come up serving players — and the plugin would not be
     * there. That failure is silent, fleet-wide and only observable when
     * somebody tries to drain a backend, which is the worst moment to discover
     * it.
     */
    private fun assetFile(
        node: NodeName,
        assetRoot: Path,
        spec: WorkloadSpec,
        mount: AssetMount,
    ): Path {
        val path = assetRoot.resolve(mount.asset.fileName)
        val usable =
            try {
                Files.isRegularFile(path) && Files.isReadable(path)
            } catch (denied: SecurityException) {
                throw missingAsset(node, spec, mount, path, "it could not be read: ${denied.message}")
            }
        if (!usable) {
            throw missingAsset(node, spec, mount, path, "there is no readable file there")
        }
        return path
    }

    private fun missingAsset(
        node: NodeName,
        spec: WorkloadSpec,
        mount: AssetMount,
        path: Path,
        problem: String,
    ): NodeException =
        NodeException.Rejected(
            node,
            NodeOperation.CREATE,
            "`${spec.server}` needs the ${mount.asset} artefact at `${mount.destination}` and node `$node` does " +
                "not have it: $problem at `$path`. The container would start without it — a Velocity proxy with " +
                "no control plugin has no control endpoint, and every backend behind it is undrainable — so this " +
                "refuses instead. Put the artefact `:velocity-plugin:pluginJar` builds in that directory",
        )

    fun volumePath(
        volumeRoot: Path,
        volume: ResourceName,
    ): Path = volumeRoot.resolve(volume.value)

    fun logDirectory(
        logRoot: Path,
        server: ResourceName,
    ): Path = logRoot.resolve(server.value)

    /**
     * Permanent, not retryable.
     *
     * Nothing this loop does will empty a disk or make a read-only mount
     * writable, and a retryable classification would show `RETRYABLE` on the
     * dashboard for ever while never asking anybody to look at the host.
     */
    private fun rejected(
        node: NodeName,
        volumeRoot: Path,
        logRoot: Path,
        spec: WorkloadSpec,
        cause: Exception,
    ): NodeException =
        NodeException.Rejected(
            node,
            NodeOperation.CREATE,
            "the host directories for `${spec.server}` could not be prepared under `$volumeRoot` and " +
                "`$logRoot`: ${cause::class.simpleName}: ${cause.message}",
            cause,
        )
}

/**
 * One host path, bound at one place inside the container.
 *
 * A `:core` type on purpose, even though its only consumer builds a
 * `mcorch.cri.VolumeMount` out of it: the derivation that produces these is the
 * part worth testing, and a test in this module may not name the CRI type. What
 * is left in [LocalNode] is a field copy.
 */
internal data class HostMount(
    val containerPath: String,
    val hostPath: String,
    val readOnly: Boolean,
)
