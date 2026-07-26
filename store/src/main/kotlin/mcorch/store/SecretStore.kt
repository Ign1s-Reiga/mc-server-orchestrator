package mcorch.store

import mcorch.schema.ResourceName
import mcorch.schema.SecretRef

/**
 * Secret material, keyed by the [SecretRef] coordinates a definition carries.
 *
 * ## Why this is not part of [Store]
 *
 * Bolting `getSecret` onto [Store] would put secret material one autocomplete
 * away from every call that reads a definition, and would guarantee that some
 * future "read everything for the dashboard" method returns it. Keeping it
 * separate makes the guarantee structural instead of a convention: there is no
 * method on [Store] that can return a [SecretValue], so no ordinary state read
 * can leak one.
 *
 * The two also want different things. State is read constantly and dumped into
 * logs and API responses; secrets are read at the moment they are used and never
 * displayed. State will plausibly move to a distributed backend; secrets will
 * plausibly move to something with a completely different shape — a KMS, an
 * agent, a mounted file — while state stays where it is. Two interfaces, two
 * migrations.
 *
 * The embedded implementation puts them in *different database files* for the
 * same reason, so that copying the state database around does not copy secrets.
 *
 * ## Rules for implementations
 *
 * - Never log, wrap, or include material in an exception message. [SecretValue]
 *   makes accidental interpolation harmless; do not undo it by pulling material
 *   out with [SecretValue.use] to build a message.
 * - [listNames] and [listKeys] return coordinates only. There is deliberately no
 *   "list everything with values" operation, and there will not be one.
 */
public interface SecretStore : AutoCloseable {
    /** Stores or replaces the material at [ref]. */
    public suspend fun put(
        ref: SecretRef,
        value: SecretValue,
    )

    /**
     * Reads the material at [ref], or null if that name or key is not stored.
     *
     * Called at use time, by whoever is about to hand the value to the thing that
     * needs it. Do not resolve early and carry the result around.
     */
    public suspend fun resolve(ref: SecretRef): SecretValue?

    /** Whether [ref] resolves. For validating a definition without touching material. */
    public suspend fun contains(ref: SecretRef): Boolean

    /** Removes one key. Returns whether it was there. */
    public suspend fun removeKey(ref: SecretRef): Boolean

    /** Removes every key under [name]. Returns how many were removed. */
    public suspend fun removeSecret(name: ResourceName): Int

    /** The stored secret names. Coordinates only. */
    public suspend fun listNames(): List<ResourceName>

    /** The keys stored under [name]. Coordinates only. */
    public suspend fun listKeys(name: ResourceName): List<String>
}
