package mcorch.api

import mcorch.api.auth.OperatorAuth
import mcorch.schema.DurationFormat
import java.security.MessageDigest
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The operator credential, held as a digest.
 *
 * The raw token exists for as long as it takes to hash it and no longer. There
 * is no accessor for it, `toString` is a placeholder, and equality is on the
 * digest — so a configuration object can be logged, compared or dumped without
 * putting the credential anywhere.
 */
public class OperatorToken private constructor(
    internal val digest: ByteArray,
) {
    override fun toString(): String = REDACTED

    override fun equals(other: Any?): Boolean = other is OperatorToken && MessageDigest.isEqual(digest, other.digest)

    override fun hashCode(): Int = digest.contentHashCode()

    public companion object {
        public const val REDACTED: String = "OperatorToken(redacted)"

        /** At least [OperatorAuth.MIN_TOKEN_LENGTH] characters. Shorter is not a token, it is a password. */
        public fun of(raw: String): Result<OperatorToken> =
            if (raw.length < OperatorAuth.MIN_TOKEN_LENGTH) {
                Result.failure(
                    IllegalArgumentException(
                        "must be at least ${OperatorAuth.MIN_TOKEN_LENGTH} characters, found ${raw.length}. " +
                            "Generate one with `head -c 32 /dev/urandom | base64`",
                    ),
                )
            } else {
                Result.success(OperatorToken(OperatorAuth.digest(raw)))
            }
    }
}

/** `SameSite` on the session cookie. See [ApiConfig.cookieSameSite]. */
public enum class SameSite(
    public val wireValue: String,
) {
    STRICT("Strict"),
    LAX("Lax"),

    /** Cross-site dashboards only, and browsers require `Secure` alongside it. */
    NONE("None"),
    ;

    public companion object {
        public fun fromWire(raw: String): SameSite? =
            entries.firstOrNull { it.wireValue.equals(raw, ignoreCase = true) }
    }
}

/**
 * Everything the API server needs to know, and nothing about what it serves.
 *
 * Read from the environment for the same reason [mcorch.schema] rejects
 * guessing: every value has to be settled before a socket is bound, and a config
 * file format would be one more parser to get wrong.
 */
public data class ApiConfig(
    /** Loopback by default. This server speaks plain HTTP; exposing it needs a TLS terminator in front. */
    val bindHost: String = DEFAULT_HOST,
    /** 0 binds an ephemeral port, which is what the tests use. */
    val bindPort: Int = DEFAULT_PORT,
    val token: OperatorToken,
    /** Exact origins (`https://ops.example.com`), never a wildcard. Empty means same-origin only. */
    val allowedOrigins: Set<String> = emptySet(),
    val sessionTtl: Duration = 12.hours,
    /** Concurrent event streams. Each one holds a connection and polls the store. */
    val maxStreams: Int = 16,
    val maxBodyBytes: Int = 1 shl 20,
    /**
     * `Secure` on the session cookie.
     *
     * Defaults to false only when bound to loopback, where plain HTTP is the
     * normal case and a `Secure` cookie would simply never be stored. Anywhere
     * else it defaults to true, because a session cookie sent in the clear is a
     * session cookie anyone on the path can replay.
     */
    val cookieSecure: Boolean = !isLoopback(bindHost),
    val cookieSameSite: SameSite = SameSite.STRICT,
    /** Fixed cost of a rejected credential. Not a lockout: see [OperatorAuth]. */
    val authFailureDelay: Duration = 250.milliseconds,
    /** How often an open stream pulls the desired-state change feed. */
    val changePollInterval: Duration = 500.milliseconds,
    /** How often an open stream re-reads observed state. Observed state is not in the change feed. */
    val statusPollInterval: Duration = 2.seconds,
    /**
     * How often an idle stream sends a `ping`.
     *
     * Two jobs. It keeps a proxy from reaping the connection, and it is the only
     * liveness signal a client has on an idle fleet — so a client should treat
     * roughly two and a half of these without a `ping` as a dead connection. See
     * [mcorch.api.routes.StreamRoutes] for why it is an event rather than the
     * conventional SSE comment frame.
     */
    val streamKeepAlive: Duration = 15.seconds,
    /**
     * Sent as the SSE `retry:` field, which `EventSource` honours silently, and
     * echoed in the `hello` event so a client that owns its own backoff can see
     * what it is overriding.
     */
    val streamReconnectDelay: Duration = 3.seconds,
    /**
     * A stream is closed after this long and the browser reconnects with
     * `Last-Event-ID`. Bounds how long one connection can hold a slot and forces
     * the resume path to be exercised in normal operation rather than only after
     * a failure.
     */
    val maxStreamLifetime: Duration = 30.minutes,
    /**
     * The dashboard bundle to serve, or null for an API-only deployment.
     *
     * `MCORCH_API_STATIC_ROOT`. Same-origin is what keeps the loopback plain-HTTP
     * deployment viable — a cross-site dashboard needs `SameSite=None`, which
     * needs `Secure`, which needs TLS. See
     * [mcorch.api.http.StaticFiles].
     */
    val staticRoot: java.nio.file.Path? = null,
    val clock: Clock = Clock.systemUTC(),
) {
    init {
        require(bindPort in 0..65535) { "bindPort must be in 0..65535, found $bindPort" }
        require(maxStreams > 0) { "maxStreams must be positive, found $maxStreams" }
        require(maxBodyBytes > 0) { "maxBodyBytes must be positive, found $maxBodyBytes" }
        require(sessionTtl.isPositive()) { "sessionTtl must be positive, found $sessionTtl" }
        require(cookieSameSite != SameSite.NONE || cookieSecure) {
            "cookieSameSite=None requires cookieSecure=true; browsers reject the combination otherwise"
        }
    }

    public companion object {
        public const val LISTEN_VARIABLE: String = "MCORCH_API_LISTEN"
        public const val TOKEN_VARIABLE: String = "MCORCH_API_TOKEN"
        public const val ORIGINS_VARIABLE: String = "MCORCH_API_ALLOWED_ORIGINS"
        public const val SESSION_TTL_VARIABLE: String = "MCORCH_API_SESSION_TTL"
        public const val MAX_STREAMS_VARIABLE: String = "MCORCH_API_MAX_STREAMS"
        public const val MAX_BODY_VARIABLE: String = "MCORCH_API_MAX_BODY_BYTES"
        public const val COOKIE_SECURE_VARIABLE: String = "MCORCH_API_COOKIE_SECURE"
        public const val COOKIE_SAMESITE_VARIABLE: String = "MCORCH_API_COOKIE_SAMESITE"
        public const val STATIC_ROOT_VARIABLE: String = "MCORCH_API_STATIC_ROOT"

        public const val DEFAULT_HOST: String = "127.0.0.1"
        public const val DEFAULT_PORT: Int = 8080

        /** The one value of [LISTEN_VARIABLE] that turns the API off. Anything else is an address. */
        public const val DISABLED: String = "off"

        /**
         * Builds a configuration from [environment].
         *
         * Total: it returns a usable configuration, [ApiConfiguration.Disabled],
         * or throws naming the variable at fault. In particular there is **no
         * default token** — a deployment either configures a credential or turns
         * the API off in so many words. Starting an unauthenticated API server
         * because a variable was unset is not a thing this can do.
         *
         * @throws IllegalArgumentException if a variable is missing or unusable.
         */
        public fun fromEnvironment(
            environment: Map<String, String>,
            clock: Clock = Clock.systemUTC(),
        ): ApiConfiguration {
            val listen =
                environment[LISTEN_VARIABLE]?.takeIf { it.isNotBlank() }?.trim() ?: "$DEFAULT_HOST:$DEFAULT_PORT"
            if (listen.equals(DISABLED, ignoreCase = true)) return ApiConfiguration.Disabled

            val (host, port) = parseListen(listen)
            val rawToken =
                environment[TOKEN_VARIABLE]?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException(
                        "$TOKEN_VARIABLE is not set. It is the operator credential for the dashboard API, and " +
                            "there is no default: every mutating endpoint can stop and replace a Minecraft " +
                            "server, so an API that starts without one is a data-loss bug waiting to be " +
                            "found. Generate one with `head -c 32 /dev/urandom | base64`, or set " +
                            "$LISTEN_VARIABLE=$DISABLED to run without the API at all",
                    )
            val token =
                OperatorToken.of(rawToken).getOrElse {
                    // The message describes the token's shape, never its value.
                    throw IllegalArgumentException("$TOKEN_VARIABLE ${it.message}")
                }
            val secureDefault = !isLoopback(host)
            return ApiConfiguration.Listening(
                ApiConfig(
                    bindHost = host,
                    bindPort = port,
                    token = token,
                    allowedOrigins =
                        environment[ORIGINS_VARIABLE]
                            .orEmpty()
                            .split(',')
                            .map { it.trim().trimEnd('/') }
                            .filter { it.isNotEmpty() }
                            .toSet(),
                    sessionTtl = duration(environment, SESSION_TTL_VARIABLE, 12.hours),
                    maxStreams = positiveInt(environment, MAX_STREAMS_VARIABLE, 16),
                    maxBodyBytes = positiveInt(environment, MAX_BODY_VARIABLE, 1 shl 20),
                    cookieSecure = boolean(environment, COOKIE_SECURE_VARIABLE, secureDefault),
                    cookieSameSite = sameSite(environment, secureDefault),
                    staticRoot = staticRoot(environment),
                    clock = clock,
                ),
            )
        }

        private fun parseListen(raw: String): Pair<String, Int> {
            val separator = raw.lastIndexOf(':')
            if (separator <= 0 || separator == raw.length - 1) {
                throw IllegalArgumentException(
                    "$LISTEN_VARIABLE must be `host:port` (such as `$DEFAULT_HOST:$DEFAULT_PORT`) or " +
                        "`$DISABLED`, found `$raw`",
                )
            }
            val host = raw.substring(0, separator).removeSurrounding("[", "]")
            val port =
                raw.substring(separator + 1).toIntOrNull()
                    ?: throw IllegalArgumentException("$LISTEN_VARIABLE has a port that is not a number: `$raw`")
            if (port !in 0..65535) {
                throw IllegalArgumentException("$LISTEN_VARIABLE has a port outside 0..65535: `$raw`")
            }
            return host to port
        }

        private fun duration(
            environment: Map<String, String>,
            name: String,
            fallback: Duration,
        ): Duration {
            val raw = environment[name]?.takeIf { it.isNotBlank() } ?: return fallback
            return DurationFormat.parse(raw).getOrElse { throw IllegalArgumentException("$name ${it.message}") }
        }

        private fun positiveInt(
            environment: Map<String, String>,
            name: String,
            fallback: Int,
        ): Int {
            val raw = environment[name]?.takeIf { it.isNotBlank() } ?: return fallback
            val value =
                raw.toIntOrNull()
                    ?: throw IllegalArgumentException("$name must be a number, found `$raw`")
            if (value <= 0) throw IllegalArgumentException("$name must be positive, found $value")
            return value
        }

        private fun boolean(
            environment: Map<String, String>,
            name: String,
            fallback: Boolean,
        ): Boolean =
            when (val raw = environment[name]?.takeIf { it.isNotBlank() }?.lowercase()) {
                null -> fallback
                "true", "1", "yes" -> true
                "false", "0", "no" -> false
                else -> throw IllegalArgumentException("$name must be true or false, found `$raw`")
            }

        private fun sameSite(
            environment: Map<String, String>,
            secureDefault: Boolean,
        ): SameSite {
            val raw = environment[COOKIE_SAMESITE_VARIABLE]?.takeIf { it.isNotBlank() } ?: return SameSite.STRICT
            val parsed =
                SameSite.fromWire(raw)
                    ?: throw IllegalArgumentException(
                        "$COOKIE_SAMESITE_VARIABLE must be one of " +
                            "${SameSite.entries.joinToString(", ") { it.wireValue }}, found `$raw`",
                    )
            if (parsed == SameSite.NONE && !secureDefault) {
                throw IllegalArgumentException(
                    "$COOKIE_SAMESITE_VARIABLE=None needs a secure cookie, and $COOKIE_SECURE_VARIABLE defaults " +
                        "to false on a loopback bind. A cross-site dashboard has to be reached over TLS",
                )
            }
            return parsed
        }

        internal fun isLoopback(host: String): Boolean =
            host == "127.0.0.1" || host == "::1" || host.equals("localhost", ignoreCase = true) ||
                host.startsWith("127.")

        /**
         * The dashboard bundle to serve, or null.
         *
         * A directory that is not there is a **startup failure**, not a silently
         * API-only server: somebody who set this variable meant to serve a
         * dashboard, and a 404 on every page is a worse way to learn the path was
         * wrong than a message at boot.
         */
        private fun staticRoot(environment: Map<String, String>): java.nio.file.Path? {
            val raw = environment[STATIC_ROOT_VARIABLE]?.takeIf { it.isNotBlank() }?.trim() ?: return null
            val path =
                java.nio.file.Path
                    .of(raw)
                    .toAbsolutePath()
                    .normalize()
            require(
                java.nio.file.Files
                    .isDirectory(path),
            ) {
                "$STATIC_ROOT_VARIABLE is `$raw`, which is not a directory. It is the dashboard bundle to serve; " +
                    "unset it for an API-only server"
            }
            return path
        }
    }
}

/** Whether this deployment runs an API server at all. */
public sealed interface ApiConfiguration {
    /** [ApiConfig.LISTEN_VARIABLE] was set to [ApiConfig.DISABLED]. Nothing is bound. */
    public data object Disabled : ApiConfiguration

    public data class Listening(
        val config: ApiConfig,
    ) : ApiConfiguration
}
