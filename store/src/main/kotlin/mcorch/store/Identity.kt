package mcorch.store

import mcorch.schema.ResourceName
import mcorch.schema.Tier
import java.time.Instant

/**
 * An operator, and what they may do.
 *
 * Implements `spec/auth/02-model.md`. Until this exists, `OperatorAuth` holds a
 * single token digest and every credential in the system carries identical
 * authority — which is why `api/API.md` can currently say *"any authenticated
 * caller can do anything the API offers"*, and why nothing can attach a tier to a
 * caller or name one in an audit record.
 *
 * ## The digest, never the credential
 *
 * [credentialDigest] is SHA-256 hex, compared in constant time — the discipline
 * `OperatorAuth` already applies to the operator token, and sound here because
 * credentials are **generated tokens rather than chosen passwords**. That choice
 * is what keeps a fast hash appropriate: a ≥32-character random token has no
 * offline attack worth a KDF, and a password would have needed one.
 *
 * Nothing in this type, and nothing in the [Store] methods that carry it, accepts
 * or returns material. A store implementation cannot leak a credential it never
 * receives.
 *
 * ## Disabling is not deleting
 *
 * [enabled] exists so that revoking access does not require destroying the record
 * of what that access did. An audit entry names an identity, and a system where
 * revoking someone deletes the evidence is a system that rewards deleting the
 * evidence.
 *
 * Note what disabling does *not* do on its own: a session was resolved to an
 * identity when it was created, so live sessions survive until something sweeps
 * them. `spec/auth/05-api.md` requires that sweep — otherwise "disabled" means
 * "disabled at next login", which is not what an operator revoking a leaked
 * credential believes they did.
 */
public data class Identity(
    val name: ResourceName,
    val credentialDigest: String,
    val tier: Tier,
    val enabled: Boolean,
    val createdAt: Instant,
) {
    /**
     * Redacted, though a digest is not material.
     *
     * A digest is still a target for a precomputed lookup, and this type reaches
     * log lines through every generic `toString` in the JDK. The fields worth
     * seeing in a log — who, what tier, whether they are enabled — are all here.
     */
    override fun toString(): String =
        "Identity(name=$name, tier=$tier, enabled=$enabled, createdAt=$createdAt, credentialDigest=<redacted>)"
}
