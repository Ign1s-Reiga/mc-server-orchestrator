package mcorch.core.node

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
