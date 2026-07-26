package mcorch.store

import java.security.SecureRandom

/**
 * Secret material, wrapped so it cannot get out by accident.
 *
 * The RCON password today, the Velocity forwarding secret later. CLAUDE.md
 * invariant 4 says the forwarding secret only ever travels through the secret
 * store — never a definition file, never a log line, never a test fixture. That
 * is a rule about *code paths*, and this type is what makes the rule hold when
 * someone forgets it:
 *
 * - There is no accessor that returns the material as a [String]. The only way in
 *   is [use], which hands out a copy and wipes it afterwards, so `"$secret"` in a
 *   log line or a message template cannot reach the value.
 * - [toString] is a fixed placeholder, so interpolating a value — or a data class
 *   that happens to contain one — prints nothing useful.
 * - [equals] compares in constant time, so a comparison in a hot path does not
 *   leak the value one character at a time.
 *
 * Material is held as a [CharArray] rather than a [String] so it can be wiped;
 * secrets in this system are text.
 *
 * A value is shared: the reconcile loop resolves one, hands it to whatever needs
 * it, and destroys it in a `finally`. So [use] and [destroy] can run at the same
 * time on different threads, and all of this is safe to call from any of them.
 */
public class SecretValue private constructor(
    private val material: CharArray,
) {
    /**
     * Held for exactly as long as it takes to copy the material or to wipe it, and
     * never across [use]'s block.
     *
     * A flag alone — even an atomically compare-and-set one — would make the wipe
     * happen exactly once but would not stop a [use] that had already passed the
     * check from copying a buffer that [destroy] is part-way through zeroing. The
     * caller would then be handed material that is half real and half wipe
     * characters, which is worse than either: an RCON login built from it fails in a
     * way nothing here explains. Copying and wiping have to exclude each other, so
     * they take a lock.
     */
    private val lock = Any()

    /**
     * Written only under [lock]; volatile so [isDestroyed] can be read from any
     * thread without taking it.
     */
    @Volatile
    private var destroyed: Boolean = false

    /** Characters of material. Not secret in itself, and useful for validation. */
    public val length: Int get() = material.size

    /** Whether [destroy] has already run. A later [use] will fail. */
    public val isDestroyed: Boolean get() = destroyed

    /**
     * Runs [block] on a private copy of the material and wipes the copy afterwards.
     *
     * The copy is only valid inside [block]. Whatever [block] returns escapes, so do
     * not return the material itself — build the thing that needs it (an env var, a
     * config line, an auth frame) inside the block and hand the value straight to
     * its consumer.
     *
     * Racing a [destroy] either gives an intact copy or throws, never a partly wiped
     * one. [block] runs outside the lock, so a slow consumer does not hold up a
     * destroy on another thread — it just keeps working from its own copy.
     */
    public fun <T> use(block: (CharArray) -> T): T {
        val copy = copyMaterial()
        try {
            return block(copy)
        } finally {
            copy.fill(WIPE)
        }
    }

    private fun copyMaterial(): CharArray =
        synchronized(lock) {
            check(!destroyed) { "secret value has been destroyed" }
            material.copyOf()
        }

    /**
     * Wipes the material. Any later [use] fails.
     *
     * Idempotent and safe from any thread: the wipe happens exactly once, and calls
     * that arrive after it return without touching anything.
     */
    public fun destroy() {
        synchronized(lock) {
            if (destroyed) return
            material.fill(WIPE)
            destroyed = true
        }
    }

    /**
     * Constant-time content equality. Two destroyed values of the same length compare equal; that is harmless.
     *
     * Not synchronised, on purpose: locking two values here would let `a == b` and
     * `b == a` on different threads deadlock. Comparing against a value that is being
     * destroyed can therefore report unequal — the answer a caller deserves for
     * racing a destroy, and the only thing that leaks out is that boolean.
     */
    override fun equals(other: Any?): Boolean {
        if (other !is SecretValue) return false
        if (material.size != other.material.size) return false
        var difference = 0
        for (index in material.indices) {
            difference = difference or (material[index].code xor other.material[index].code)
        }
        return difference == 0
    }

    /**
     * Constant, on purpose. A hash derived from the material would put a
     * distinguisher for it in every heap dump and every log line that prints a
     * default `toString`. Secrets are not map keys.
     */
    override fun hashCode(): Int = REDACTED.hashCode()

    override fun toString(): String = REDACTED

    public companion object {
        /** What a secret prints as, everywhere, always. */
        public const val REDACTED: String = "SecretValue(redacted)"

        /** What wiped material is overwritten with. */
        private const val WIPE: Char = '\u0000'

        private val RANDOM: SecureRandom = SecureRandom()

        private const val ALPHABET: String = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

        /** Copies [material]; the caller keeps ownership of its array and should wipe it. */
        public fun of(material: CharArray): SecretValue = SecretValue(material.copyOf())

        /**
         * Convenience for a value that is already a [String] — a password an operator
         * typed into the API, for instance. Prefer [of]: a [String] cannot be wiped and
         * lives until the garbage collector gets to it.
         */
        public fun ofString(material: String): SecretValue = SecretValue(material.toCharArray())

        /**
         * A fresh random secret of [length] characters. This is how the Velocity
         * forwarding secret should come into existence: generated here, stored here,
         * resolved at use time, and never written down anywhere a human can copy it
         * into a YAML file.
         */
        public fun random(length: Int): SecretValue {
            require(length > 0) { "secret length must be positive, found $length" }
            val chars = CharArray(length) { ALPHABET[RANDOM.nextInt(ALPHABET.length)] }
            return SecretValue(chars)
        }
    }
}
