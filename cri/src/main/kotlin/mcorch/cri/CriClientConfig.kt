package mcorch.cri

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Per-call deadlines. Every outward CRI call gets one — a hung containerd must
 * not hang the reconcile loop.
 *
 * These are transport deadlines. When one elapses the call fails with
 * [CriException.Timeout], which is retryable. They are separate from, and always
 * larger than, the semantic timeouts a caller passes in
 * ([StopGracePeriod], the `ExecSync` command timeout).
 */
public data class CriTimeouts(
    /** Reads that containerd answers from memory: version, status, list, per-object status. */
    val query: Duration = 15.seconds,
    /** Sandbox create/teardown, including CNI setup. */
    val sandboxLifecycle: Duration = 2.minutes,
    /** Container create/start/remove. Not stop — that is derived from the grace period. */
    val containerLifecycle: Duration = 2.minutes,
    /**
     * Image pull. Long by design: a Paper server image over a slow link is
     * minutes, and failing a pull early only makes the loop retry the whole
     * download.
     */
    val imagePull: Duration = 30.minutes,
    /** Image removal. */
    val imageLifecycle: Duration = 2.minutes,
    /**
     * Added on top of a caller-supplied semantic timeout to get the transport
     * deadline. A `StopContainer` with a 120s grace period gets a 120s + this
     * deadline, so the kill fires before the transport gives up and the caller
     * learns the container actually stopped rather than getting an ambiguous
     * timeout.
     */
    val deadlineSlack: Duration = 30.seconds,
) {
    init {
        require(query.isPositive()) { "query timeout must be positive" }
        require(sandboxLifecycle.isPositive()) { "sandboxLifecycle timeout must be positive" }
        require(containerLifecycle.isPositive()) { "containerLifecycle timeout must be positive" }
        require(imagePull.isPositive()) { "imagePull timeout must be positive" }
        require(imageLifecycle.isPositive()) { "imageLifecycle timeout must be positive" }
        require(deadlineSlack.isPositive()) { "deadlineSlack must be positive" }
    }
}

/**
 * Everything needed to open a CRI connection.
 *
 * [endpoint] has no default on purpose: defaulting it would let a misconfigured
 * deployment quietly talk to the dev containerd. Resolve it from configuration,
 * or from [CriEndpoint.fromEnvironment], and decide explicitly what "unset"
 * means.
 */
public data class CriClientConfig(
    val endpoint: CriEndpoint,
    val timeouts: CriTimeouts = CriTimeouts(),
    /**
     * gRPC's default 4 MiB inbound limit is too small for CRI: `ExecSync` is
     * specified to return up to 16 MiB of captured output, and a `ListContainers`
     * on a busy node is not small either. 32 MiB leaves headroom for both.
     */
    val maxInboundMessageSizeBytes: Int = DEFAULT_MAX_INBOUND_MESSAGE_SIZE_BYTES,
) {
    init {
        require(maxInboundMessageSizeBytes >= MIN_INBOUND_MESSAGE_SIZE_BYTES) {
            "maxInboundMessageSizeBytes must be at least $MIN_INBOUND_MESSAGE_SIZE_BYTES " +
                "(ExecSync alone may return 16 MiB), got: $maxInboundMessageSizeBytes"
        }
    }

    public companion object {
        /** 32 MiB. */
        public const val DEFAULT_MAX_INBOUND_MESSAGE_SIZE_BYTES: Int = 32 * 1024 * 1024

        /** 16 MiB — the cap CRI puts on a single `ExecSync` response. */
        public const val MIN_INBOUND_MESSAGE_SIZE_BYTES: Int = 16 * 1024 * 1024
    }
}
