package mcorch.api.auth

import mcorch.schema.Tier
import mcorch.store.Identity

/**
 * Who a request is from, and what it may do.
 *
 * Deliberately not [Identity]: an identity is a stored record with a credential
 * digest on it, and nothing downstream of authentication needs the digest. A
 * request carries the two things a tier gate and an audit record ask for, and
 * carrying less is what keeps the digest out of every handler that happens to log
 * its credential.
 */
internal data class Principal(
    val name: String,
    val tier: Tier,
) {
    internal companion object {
        /**
         * `MCORCH_API_TOKEN`, which is outside the tier system entirely.
         *
         * Named rather than disguised as an identity that happens to be a
         * Superuser. `spec/auth/06-bootstrap.md` §2 is explicit that the risk this
         * credential carries is being misunderstood — the natural reading of "the
         * API has tiers now" is that tiers bound everyone, and they do not bound
         * this one. An audit record naming `<operator-token>` says so at a glance,
         * where an invented username would not.
         */
        val BOOTSTRAP: Principal = Principal(name = "<operator-token>", tier = Tier.SUPERUSER)

        fun of(identity: Identity): Principal = Principal(name = identity.name.value, tier = identity.tier)
    }
}
