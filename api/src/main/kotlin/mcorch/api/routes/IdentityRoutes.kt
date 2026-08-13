package mcorch.api.routes

import mcorch.api.auth.OperatorAuth
import mcorch.api.auth.SessionRegistry
import mcorch.api.http.Access
import mcorch.api.http.ApiException
import mcorch.api.http.ErrorCode
import mcorch.api.http.HandlerResult
import mcorch.api.http.Request
import mcorch.api.http.Requests
import mcorch.api.http.Response
import mcorch.api.http.Route
import mcorch.api.json.Json
import mcorch.api.json.jsonObject
import mcorch.schema.ResourceName
import mcorch.schema.Tier
import mcorch.store.Identity
import mcorch.store.IdentityStore
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64

/**
 * Managing operators. Every route here is `Superuser`.
 *
 * ## Why the bodies are one word
 *
 * `:api` parses no JSON, and `api/build.gradle.kts` explains at length why. So
 * each verb takes a single value as a `text/plain` body — a tier, or `true` /
 * `false` — and the name is a path segment. That keeps the module's position
 * intact and keeps every field out of the query string, which
 * [SecretRoutes] already avoids for the same reason: a query string is logged by
 * every proxy in the world.
 *
 * It also makes each endpoint do one thing, which matters more here than
 * elsewhere. "Set the tier" and "disable" are different decisions with different
 * blast radii, and a combined `PATCH` would let one be made by accident while
 * making the other.
 *
 * ## The credential appears exactly once
 *
 * [create] and [rotate] are the only responses in this API that carry a secret,
 * and they carry it because there is no other channel — the system has no mail
 * and no side band. It is **not stored in recoverable form and cannot be shown
 * again**; a caller that loses it rotates.
 *
 * That is a deliberate exception to `api/API.md` §13's *"No secret material,
 * ever"*, and a different one from the console's: §13's sentence is about
 * material the *operator supplied*, which this API genuinely never returns. This
 * is material the API *generated*, and returning it once is the only way it can
 * ever be used.
 */
internal class IdentityRoutes(
    private val identities: IdentityStore,
    private val sessions: SessionRegistry,
    private val clock: Clock,
) {
    private val random = SecureRandom()

    fun routes(): List<Route> =
        listOf(
            Route("GET", IDENTITIES, Access.AtLeast(Tier.SUPERUSER)) { _, _ ->
                HandlerResult.Send(list())
            },
            Route("POST", IDENTITY, Access.AtLeast(Tier.SUPERUSER)) { request, _ ->
                HandlerResult.Send(create(request))
            },
            Route("PUT", IDENTITY, Access.AtLeast(Tier.SUPERUSER)) { request, _ ->
                HandlerResult.Send(setTier(request))
            },
            Route("DELETE", IDENTITY, Access.AtLeast(Tier.SUPERUSER)) { request, _ ->
                HandlerResult.Send(remove(request))
            },
            Route("PUT", IDENTITY_ENABLED, Access.AtLeast(Tier.SUPERUSER)) { request, _ ->
                HandlerResult.Send(setEnabled(request))
            },
            Route("POST", IDENTITY_CREDENTIAL, Access.AtLeast(Tier.SUPERUSER)) { request, _ ->
                HandlerResult.Send(rotate(request))
            },
        )

    private suspend fun list(): Response {
        val rendered =
            identities
                .list()
                .sortedBy { it.name.value }
                .map { identity ->
                    jsonObject {
                        put("name", identity.name.value)
                        put("tier", identity.tier.wireValue)
                        put("enabled", identity.enabled)
                        put("createdAt", identity.createdAt)
                        // No digest. It is not material, and it is still a lookup
                        // target that nothing here needs.
                    }
                }
        return Response.json(200, jsonObject { put("identities", Json.Arr(rendered)) })
    }

    private suspend fun create(request: Request): Response {
        val name = Requests.name(request)
        val tier = tierFrom(request)
        if (identities.get(name) != null) {
            // POST never overwrites, the same rule POST /servers follows. Rotating a
            // credential and re-tiering are separate verbs precisely so neither can
            // happen by accident on a name collision.
            throw ApiException(ErrorCode.IDENTITY_EXISTS, "an identity named `${name.value}` already exists")
        }
        val credential = generateCredential()
        identities.put(
            Identity(
                name = name,
                credentialDigest = OperatorAuth.hex(OperatorAuth.digest(credential)),
                tier = tier,
                enabled = true,
                createdAt = clock.instant(),
            ),
        )
        LOG.info("identity created name={} tier={}", name.value, tier)
        return credentialResponse(201, name, tier, credential)
    }

    private suspend fun setTier(request: Request): Response {
        val name = Requests.name(request)
        val tier = tierFrom(request)
        val existing = require(name)
        guardLastSuperuser(existing, tier = tier, enabled = existing.enabled)
        identities.put(existing.copy(tier = tier))
        LOG.info("identity re-tiered name={} tier={}", name.value, tier)
        return Response.json(
            200,
            jsonObject {
                put("name", name.value)
                put("tier", tier.wireValue)
                put("enabled", existing.enabled)
            },
        )
    }

    private suspend fun setEnabled(request: Request): Response {
        val name = Requests.name(request)
        val enabled = booleanFrom(request)
        val existing = require(name)
        guardLastSuperuser(existing, tier = existing.tier, enabled = enabled)
        identities.put(existing.copy(enabled = enabled))
        // Disabling sweeps. A session resolved its principal when it was issued, so
        // without this "disabled" would mean "disabled at next login" — which is not
        // what an operator revoking a leaked credential believes they did.
        val revoked = if (enabled) 0 else sessions.revokeFor(name.value)
        LOG.info("identity {} name={} sessionsRevoked={}", if (enabled) "enabled" else "disabled", name.value, revoked)
        return Response.json(
            200,
            jsonObject {
                put("name", name.value)
                put("tier", existing.tier.wireValue)
                put("enabled", enabled)
                put("sessionsRevoked", revoked)
            },
        )
    }

    private suspend fun rotate(request: Request): Response {
        val name = Requests.name(request)
        val existing = require(name)
        val credential = generateCredential()
        identities.put(existing.copy(credentialDigest = OperatorAuth.hex(OperatorAuth.digest(credential))))
        // Rotation sweeps for the same reason disabling does, and more urgently: an
        // operator rotating a credential is usually responding to it having leaked,
        // so the live sessions are the thing they most want gone.
        val revoked = sessions.revokeFor(name.value)
        LOG.info("identity credential rotated name={} sessionsRevoked={}", name.value, revoked)
        return credentialResponse(200, name, existing.tier, credential, revoked)
    }

    private suspend fun remove(request: Request): Response {
        val name = Requests.name(request)
        val existing = require(name)
        guardLastSuperuser(existing, tier = existing.tier, enabled = false)
        identities.remove(name)
        val revoked = sessions.revokeFor(name.value)
        LOG.info("identity removed name={} sessionsRevoked={}", name.value, revoked)
        return Response.empty(204)
    }

    private suspend fun require(name: ResourceName): Identity =
        identities.get(name)
            ?: throw ApiException(ErrorCode.IDENTITY_NOT_FOUND, "there is no identity named `${name.value}`")

    /**
     * Refuses a change that would leave nobody able to manage identities.
     *
     * Not because it is unrecoverable — `MCORCH_API_TOKEN` gets you back in, which
     * is half of why it exists — but because that recovery needs the host's
     * environment, and an operator who does not realise it is one click from an
     * orchestrator they can only fix by shell.
     *
     * The check reads the current set and then writes, which is racy in the honest
     * sense: two concurrent demotions could each see the other as the survivor.
     * `spec/auth/05-api.md` records that the store write is the real serialisation
     * point and that this belongs there eventually.
     */
    private suspend fun guardLastSuperuser(
        existing: Identity,
        tier: Tier,
        enabled: Boolean,
    ) {
        val staysSuperuser = enabled && tier == Tier.SUPERUSER
        if (existing.enabled && existing.tier == Tier.SUPERUSER && !staysSuperuser) {
            val others = identities.list().count { it.enabled && it.tier == Tier.SUPERUSER && it.name != existing.name }
            if (others == 0) {
                throw ApiException(
                    ErrorCode.LAST_SUPERUSER,
                    "`${existing.name.value}` is the only enabled superuser; create or enable another before " +
                        "changing this one",
                )
            }
        }
    }

    private fun credentialResponse(
        status: Int,
        name: ResourceName,
        tier: Tier,
        credential: String,
        revoked: Int? = null,
    ): Response =
        Response.json(
            status,
            jsonObject {
                put("name", name.value)
                put("tier", tier.wireValue)
                put("credential", credential)
                put(
                    "warning",
                    "this credential is not stored in recoverable form and cannot be shown again. " +
                        "If it is lost, rotate it",
                )
                revoked?.let { put("sessionsRevoked", it) }
            },
        )

    private fun tierFrom(request: Request): Tier {
        val raw = request.bodyText().trim()
        if (raw.isEmpty()) {
            throw ApiException.badRequest(
                "send the tier as the request body: one of ${Tier.entries.joinToString(", ") { it.wireValue }}",
            )
        }
        return Tier.parse(raw)
            ?: throw ApiException.badRequest(
                "`$raw` is not a tier. Expected one of ${Tier.entries.joinToString(", ") { it.wireValue }}",
            )
    }

    private fun booleanFrom(request: Request): Boolean =
        when (request.bodyText().trim().lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw ApiException.badRequest("send `true` or `false` as the request body")
        }

    /** 32 bytes from [SecureRandom], base64url. The same shape `MCORCH_API_TOKEN` is documented to be. */
    private fun generateCredential(): String {
        val bytes = ByteArray(CREDENTIAL_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        const val IDENTITIES: String = "/api/v1/identities"
        const val IDENTITY: String = "/api/v1/identities/{name}"
        const val IDENTITY_ENABLED: String = "/api/v1/identities/{name}/enabled"
        const val IDENTITY_CREDENTIAL: String = "/api/v1/identities/{name}/credential"

        private const val CREDENTIAL_BYTES = 32

        private val LOG = LoggerFactory.getLogger(IdentityRoutes::class.java)
    }
}
