package mcorch.api.auth

import mcorch.api.http.ApiException
import mcorch.api.http.ErrorCode
import mcorch.api.http.HeaderNames
import mcorch.api.http.Request
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/**
 * Who is allowed to talk to this API, and how they prove it.
 *
 * ## Threat model
 *
 * This is a single-host operational tool with one class of user — the operator —
 * and no tenancy. What it is defending against, in the order it matters:
 *
 * 1. **Anything else that can reach the port.** The API can stop and replace
 *    Minecraft servers, so an unauthenticated request that reaches a mutating
 *    endpoint is a data-loss bug, not an access-control nicety. Every route
 *    except the liveness probe and the CORS preflight requires a credential, and
 *    the default bind address is loopback.
 * 2. **A hostile page in the operator's browser.** The dashboard holds a session
 *    cookie, and a cookie is attached by the browser to whatever request the
 *    browser is willing to make. Three independent things stop that being a
 *    write: `SameSite`, the CORS origin allow-list ([mcorch.api.http.Cors]), and
 *    a double-submit CSRF token on every mutating request that authenticated by
 *    cookie.
 * 3. **A stolen page context.** The session cookie is `HttpOnly`, so script in
 *    the SPA — injected or otherwise — cannot read it and cannot exfiltrate a
 *    long-lived credential. The CSRF token *is* readable by script, on purpose:
 *    it is not a credential on its own, and script that can read it can already
 *    make requests.
 * 4. **Brute force of the operator token.** The token is required to be at least
 *    32 characters, is compared as a SHA-256 digest in constant time, and every
 *    failure costs a fixed delay. There is deliberately no lockout: a lockout on
 *    a shared credential is a denial-of-service anyone who can reach the port
 *    can trigger.
 *
 * What it is **not** defending against, and these are stated so nobody assumes
 * otherwise: an attacker with read access to the host's environment or process
 * table (the token is an env var), transport interception (run it behind TLS or
 * on loopback — this server speaks plain HTTP), or an operator who is themselves
 * hostile. There are no roles: any authenticated caller can do anything the API
 * offers.
 *
 * ## Two credentials, one of which needs CSRF
 *
 * - `Authorization: Bearer <operator token>` — for scripts and for `curl`. No
 *   CSRF token is required with it, because a browser never attaches an
 *   `Authorization` header on its own; a cross-site request simply cannot carry
 *   one.
 * - `Cookie: mcorch_session=<id>` — for the SPA, and the only thing an
 *   `EventSource` can send, since that API cannot set headers. Mutating requests
 *   authenticated this way must also send `X-CSRF-Token`.
 */
internal class OperatorAuth(
    tokenDigest: ByteArray,
    private val sessions: SessionRegistry,
    /** Fixed cost of a rejected credential. Blunts guessing without offering a lockout to trigger. */
    private val failureDelay: Duration,
) {
    private val expected: ByteArray = tokenDigest.copyOf()

    /**
     * Establishes who is calling, or throws.
     *
     * The CSRF check lives here rather than in a separate layer because it is
     * conditional on *how* the caller authenticated, and separating the two is
     * how a mechanism ends up protected in one place and not another.
     */
    fun authenticate(request: Request): Credential {
        val bearer = bearerToken(request)
        if (bearer != null) {
            if (!matchesOperatorToken(bearer)) reject("the bearer token is not the operator token")
            return Credential.OperatorToken
        }
        val cookie = request.cookie(SESSION_COOKIE) ?: reject("no operator credential was supplied")
        val session = sessions.lookup(cookie) ?: reject("the session is unknown or has expired")
        if (request.mutating) {
            val supplied =
                request.header(HeaderNames.CSRF)
                    ?: throw ApiException(
                        ErrorCode.CSRF_REQUIRED,
                        "a mutating request authenticated by session cookie must also send the " +
                            "${HeaderNames.CSRF} header. Read the current token from GET /api/v1/auth/session",
                    )
            if (!constantTimeEquals(supplied, session.csrfToken)) {
                throw ApiException(ErrorCode.CSRF_INVALID, "the ${HeaderNames.CSRF} header does not match the session")
            }
        }
        return Credential.Session(session)
    }

    /** Whether [candidate] is the configured operator token. Constant time in the digest. */
    fun matchesOperatorToken(candidate: String): Boolean = MessageDigest.isEqual(expected, digest(candidate))

    fun bearerToken(request: Request): String? {
        val raw = request.header(HeaderNames.AUTHORIZATION)?.trim() ?: return null
        if (!raw.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) return null
        return raw.substring(7).trim().takeIf { it.isNotEmpty() }
    }

    /** Fails an authentication attempt after a fixed delay. */
    fun reject(problem: String): Nothing {
        if (failureDelay.isPositive()) {
            // A virtual thread parks here; it costs no platform thread.
            Thread.sleep(failureDelay.inWholeMilliseconds)
        }
        throw ApiException(ErrorCode.UNAUTHENTICATED, problem)
    }

    internal sealed interface Credential {
        /** Authenticated with the operator token itself. Not subject to CSRF. */
        data object OperatorToken : Credential

        data class Session(
            val session: SessionRegistry.Session,
        ) : Credential
    }

    companion object {
        const val SESSION_COOKIE: String = "mcorch_session"

        /** Short enough to be typed, long enough that guessing it is not a strategy. */
        const val MIN_TOKEN_LENGTH: Int = 32

        fun digest(value: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

        fun constantTimeEquals(
            a: String,
            b: String,
        ): Boolean = MessageDigest.isEqual(digest(a), digest(b))
    }
}

/**
 * Operator sessions, in memory.
 *
 * In memory rather than in the store, and that is a decision rather than a
 * shortcut. A session is worthless after a restart anyway — the operator simply
 * logs in again — and putting it in the store would mean a credential-shaped
 * thing living in the same database that gets copied around for backups and
 * debugging, which is exactly the property [mcorch.store.SecretStore] exists to
 * avoid. The cost is that a restart logs everyone out, which for a single-host
 * tool is the correct trade.
 *
 * Ids are held as digests. A heap dump, a log of the map, or a stray `toString`
 * then yields something that cannot be replayed as a cookie.
 */
internal class SessionRegistry(
    private val clock: Clock,
    private val ttl: Duration,
    /** A ceiling so a login loop cannot grow this without bound. Oldest sessions go first. */
    private val maxSessions: Int = DEFAULT_MAX_SESSIONS,
) {
    private val random = SecureRandom()
    private val sessions = ConcurrentHashMap<String, Session>()

    data class Session(
        /** SHA-256 of the cookie value, hex. Never the value itself. */
        val idDigest: String,
        val csrfToken: String,
        val createdAt: Instant,
        val expiresAt: Instant,
    )

    /** A new session. The returned id is the only time the raw value exists here. */
    fun create(): Issued {
        prune()
        if (sessions.size >= maxSessions) {
            sessions.entries
                .sortedBy { it.value.createdAt }
                .take(sessions.size - maxSessions + 1)
                .forEach { sessions.remove(it.key) }
        }
        val id = randomToken()
        val now = clock.instant()
        val session =
            Session(
                idDigest = hex(OperatorAuth.digest(id)),
                csrfToken = randomToken(),
                createdAt = now,
                expiresAt = now.plusMillis(ttl.inWholeMilliseconds),
            )
        sessions[session.idDigest] = session
        return Issued(id, session)
    }

    fun lookup(id: String): Session? {
        val key = hex(OperatorAuth.digest(id))
        val session = sessions[key] ?: return null
        if (!clock.instant().isBefore(session.expiresAt)) {
            sessions.remove(key)
            return null
        }
        return session
    }

    fun revoke(id: String): Boolean = sessions.remove(hex(OperatorAuth.digest(id))) != null

    fun size(): Int = sessions.size

    private fun prune() {
        val now = clock.instant()
        sessions.entries.removeIf { !now.isBefore(it.value.expiresAt) }
    }

    private fun randomToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    data class Issued(
        /** The cookie value. Held nowhere else. */
        val id: String,
        val session: Session,
    )

    companion object {
        const val DEFAULT_MAX_SESSIONS: Int = 64
        private const val TOKEN_BYTES = 32

        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }
}
