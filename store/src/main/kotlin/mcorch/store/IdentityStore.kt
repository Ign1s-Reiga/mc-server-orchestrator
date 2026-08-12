package mcorch.store

import mcorch.schema.ResourceName

/**
 * Who may talk to this orchestrator, and with what authority.
 *
 * Implements `spec/auth/04-store.md`. A sibling of [SecretStore] rather than a
 * set of methods on [Store]: [Store] holds *what the operator declared and what
 * the loop observed*, and an identity is neither. The split already exists for
 * secrets and this follows it, so `EmbeddedStore` exposes three narrow stores
 * instead of one that answers unrelated questions.
 *
 * ## The digest, never the credential
 *
 * Nothing here accepts or returns material. [Identity.credentialDigest] is a
 * SHA-256 hex digest and the only thing that crosses this boundary, which means
 * **an implementation cannot leak a credential it never receives** — the same
 * property [SecretStore] gets from the opposite direction by refusing to hand
 * material back out.
 *
 * ## No query language
 *
 * [list] returns everything. There are tens of operators at most, `api/API.md`
 * §11 already declines pagination at this scale, and a filter parameter is the
 * first half of a query interface that a distributed store would then have to
 * satisfy.
 *
 * ## An empty store means nobody has been created yet
 *
 * It does **not** mean "make a default administrator". A credential minted by
 * the store is one nobody asked for and nobody holds, and a lost administrator
 * credential is one nothing can revoke. Bootstrapping is
 * `MCORCH_API_TOKEN`'s job — see `spec/auth/06-bootstrap.md`.
 */
public interface IdentityStore : AutoCloseable {
    /**
     * Creates [identity], or replaces the one holding its name.
     *
     * Replacing is total: tier, enabled and digest all come from [identity].
     * A caller changing one field reads first and writes the whole record back,
     * which is the same shape `putDefinition` has and keeps this interface from
     * growing a patch verb per field.
     */
    public suspend fun put(identity: Identity)

    /** The identity holding [name], or null if there is none. */
    public suspend fun get(name: ResourceName): Identity?

    /** Every identity. Order is unspecified; callers that render a list sort it. */
    public suspend fun list(): List<Identity>

    /**
     * Removes [name], returning whether it was there.
     *
     * Prefer setting [Identity.enabled] to false. Deleting drops the record that
     * an audit entry naming this identity refers to, and a system where revoking
     * access destroys the evidence of what that access did is one that rewards
     * destroying the evidence.
     */
    public suspend fun remove(name: ResourceName): Boolean
}
