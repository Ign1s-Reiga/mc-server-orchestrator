package mcorch.store

/**
 * A storage failure. Distinct from a [WriteOutcome.Conflict], which is an
 * expected outcome rather than a failure.
 *
 * Every failure is classified once, here, so the reconcile loop never has to
 * guess: [retryable] failures requeue with backoff, permanent ones surface on
 * the server's observed status and wait for a human. Nothing in this module
 * catches an exception without re-raising it as one of these.
 */
public sealed class StoreException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    /** Whether trying the same call again could plausibly succeed. */
    public abstract val retryable: Boolean

    /**
     * The backend could not be reached, or the operation lost a race for a lock or
     * a connection. Nothing is known to have been written. Requeue.
     */
    public class Unavailable(
        message: String,
        cause: Throwable? = null,
    ) : StoreException(message, cause) {
        override val retryable: Boolean get() = true
    }

    /**
     * Something on disk could not be read back as the type it claims to be. Retrying
     * will not fix it and reinterpreting it would be worse. Surface it.
     */
    public class Corrupt(
        message: String,
        cause: Throwable? = null,
    ) : StoreException(message, cause) {
        override val retryable: Boolean get() = false
    }

    /**
     * The backend refused the operation in a way that repeating it will not fix — a
     * violated constraint, an exhausted disk. Usually a bug here or something that
     * needs a human, and either way it must surface rather than spin.
     */
    public class Failed(
        message: String,
        cause: Throwable? = null,
    ) : StoreException(message, cause) {
        override val retryable: Boolean get() = false
    }

    /**
     * The on-disk schema is newer than this build understands, or the store was
     * asked for something this implementation does not provide. Refusing is the
     * point: an older binary must never reinterpret a newer layout.
     */
    public class Unsupported(
        message: String,
        cause: Throwable? = null,
    ) : StoreException(message, cause) {
        override val retryable: Boolean get() = false
    }

    /** A schema migration failed part-way. The store is not open and must not be used. */
    public class MigrationFailed(
        message: String,
        cause: Throwable? = null,
    ) : StoreException(message, cause) {
        override val retryable: Boolean get() = false
    }

    /** The store was used after [Store.close]. A lifecycle bug in the caller. */
    public class Closed(
        message: String,
    ) : StoreException(message) {
        override val retryable: Boolean get() = false
    }
}

/**
 * A [WriteOutcome.Conflict] raised as an exception by [getOrThrow]. Retryable:
 * re-read, recompute against what is stored now, and write again.
 */
public class StoreConflictException(
    public val conflict: WriteOutcome.Conflict,
) : StoreException(
        "conflicting write to `${conflict.name}`: ${conflict.reason}",
    ) {
    override val retryable: Boolean get() = true
}
