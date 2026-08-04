package mcorch.velocity.control

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * How the plugin is configured, and the only channel that carries the token.
 *
 * `:core` writes these into the proxy container's environment at create time,
 * the same way an RCON password reaches a Paper server: resolved from the secret
 * store at the moment the container is made, never written into a spec, a stored
 * row, an API response or a log line.
 *
 * ## The bind address is the wildcard, and the sandbox is the boundary
 *
 * `ControlEndpointSpec` makes the endpoint safe by omission: with only `port`
 * set it exists inside the sandbox, and `tokenSecret` becomes *required* the
 * moment `hostPort` publishes it. That means the isolation is the container
 * network namespace, not the bind address — `:core` reaches the endpoint from
 * outside the container, through the Node abstraction, so binding to loopback
 * would make the unpublished case unreachable rather than safe.
 */
public data class ControlConfig(
    val port: Int,
    val bindAddress: String,
    /**
     * The bearer token, or null when the endpoint is unpublished.
     *
     * Null is a legitimate configuration and not a fallback: the schema refuses
     * to spell `hostPort` without `tokenSecret`, so an endpoint with no token is
     * one that only exists inside the sandbox. [ControlAuth] still refuses the
     * empty string, so a token variable that was *set but blank* — a template
     * that expanded to nothing — is a startup failure rather than an open door.
     */
    val token: String?,
) {
    /**
     * Redacted, and for the same reason [ControlAuth] digests the material: this is
     * a `data class` `:core` is invited to depend on, so its generated `toString`
     * would otherwise print the control token into whatever logged, diffed or
     * asserted on one.
     */
    override fun toString(): String =
        "ControlConfig(port=$port, bindAddress=$bindAddress, token=${if (token == null) "absent" else "REDACTED"})"

    public companion object {
        public const val ENV_PORT: String = "MCORCH_CONTROL_PORT"
        public const val ENV_BIND: String = "MCORCH_CONTROL_BIND"
        public const val ENV_TOKEN: String = "MCORCH_CONTROL_TOKEN"

        /** Matches `VelocityProxyDefaults.CONTROL_PORT`. Nowhere near a port a player speaks. */
        public const val DEFAULT_PORT: Int = 8375

        public const val DEFAULT_BIND: String = "0.0.0.0"

        /**
         * Reads the environment. Refuses rather than guesses.
         *
         * A malformed port is a failure, not a fall back to the default: a proxy
         * that quietly listens somewhere other than where `:core` will look is a
         * control endpoint that reads as unreachable forever, and the error message
         * for that is nowhere near the typo that caused it.
         */
        public fun fromEnvironment(environment: (String) -> String?): ControlConfig {
            val rawPort = environment(ENV_PORT)?.trim()
            val port =
                when {
                    rawPort.isNullOrEmpty() -> {
                        DEFAULT_PORT
                    }

                    else -> {
                        rawPort.toIntOrNull()?.takeIf { it in 1..65535 }
                            ?: throw IllegalArgumentException(
                                "$ENV_PORT must be a port number in 1..65535, found `$rawPort`",
                            )
                    }
                }
            val bind = environment(ENV_BIND)?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_BIND
            val token = environment(ENV_TOKEN)?.takeIf { it.isNotEmpty() }
            return ControlConfig(port = port, bindAddress = bind, token = token)
        }
    }
}

/**
 * Whether a caller may use this endpoint.
 *
 * The token is held as a SHA-256 digest and compared with
 * [MessageDigest.isEqual], which is constant time — the same treatment
 * `:api`'s operator token gets, and for the same reason: this endpoint can seal
 * every backend in a fleet and move every player on it.
 *
 * The digest also means the material is not sitting in a `String` field that a
 * heap dump or a stray `toString` would hand over. [toString] is overridden so
 * that this object cannot be logged into revealing anything at all.
 */
public class ControlAuth(
    token: String?,
) {
    private val expected: ByteArray? =
        token?.let {
            require(it.isNotBlank()) { "${ControlConfig.ENV_TOKEN} was set to a blank value" }
            digest(it)
        }

    /** Whether a token is required at all. False only for an unpublished endpoint. */
    public val required: Boolean get() = expected != null

    public fun authorise(authorizationHeader: String?) {
        val required = expected ?: return
        val raw =
            authorizationHeader?.trim()
                ?: throw ControlFailure(
                    ControlErrorCode.UNAUTHENTICATED,
                    "no ${ControlProtocol.HEADER_AUTHORIZATION} header",
                )
        if (!raw.regionMatches(
                0,
                ControlProtocol.BEARER_PREFIX,
                0,
                ControlProtocol.BEARER_PREFIX.length,
                ignoreCase = true,
            )
        ) {
            throw ControlFailure(ControlErrorCode.UNAUTHENTICATED, "the credential must be a Bearer token")
        }
        val supplied = raw.substring(ControlProtocol.BEARER_PREFIX.length).trim()
        if (supplied.isEmpty() || !MessageDigest.isEqual(required, digest(supplied))) {
            // Deliberately says nothing about the expected value, its length, or how
            // close the supplied one was.
            throw ControlFailure(ControlErrorCode.UNAUTHENTICATED, "the bearer token is not the control token")
        }
    }

    override fun toString(): String = "ControlAuth(required=$required)"

    private fun digest(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
}

/**
 * The control endpoint's transport: a JDK HTTP server, auth, and nothing else.
 *
 * `com.sun.net.httpserver` rather than a framework, for the reason `:api` gives
 * at length: this is a handful of routes with flat JSON bodies, and a Velocity
 * plugin is the worst place in the system to add eight artifacts that have to be
 * shaded into a JAR sharing a classloader with a proxy's own dependencies. The
 * JDK's server is already on the plugin's classpath and can collide with
 * nothing.
 *
 * ## What it does not log
 *
 * Nothing here logs a request body, a header, or a peer address. The bodies
 * cannot contain a player identity by construction, but the `Authorization`
 * header holds the control token and the peer address is an address — CLAUDE.md
 * bans logging those everywhere, and a proxy is the component that sees the most
 * of them.
 */
public class ControlEndpoint(
    private val service: ControlService,
    private val auth: ControlAuth,
    private val config: ControlConfig,
    private val log: (String) -> Unit,
) {
    @Volatile
    private var server: HttpServer? = null

    @Volatile
    private var pool: ExecutorService? = null

    /** The port actually bound, or -1 before [start]. Not the configured one when that was 0. */
    public val boundPort: Int get() = server?.address?.port ?: -1

    public fun start() {
        // Best effort, and set before the first HttpServer.create because the JDK's
        // ServerImpl reads it in a static initialiser. Without it a request whose
        // body never arrives holds its handler thread with no deadline.
        if (System.getProperty(MAX_REQUEST_TIME_PROPERTY) == null) {
            System.setProperty(MAX_REQUEST_TIME_PROPERTY, MAX_REQUEST_SECONDS.toString())
        }
        val http = HttpServer.create(InetSocketAddress(config.bindAddress, config.port), BACKLOG)
        val executor = Executors.newFixedThreadPool(THREADS, namedThreads())
        pool = executor
        http.executor = executor
        http.createContext("/") { exchange ->
            try {
                serve(exchange)
            } finally {
                exchange.close()
            }
        }
        http.start()
        server = http
        // The bound port rather than the configured one. They are the same in
        // production and the difference is the whole value of the line: what is
        // worth logging is where the endpoint actually is, not where it was asked
        // to be.
        log(
            "control endpoint listening bind=${config.bindAddress} port=$boundPort " +
                "protocol=${ControlProtocol.VERSION} authenticated=${auth.required}",
        )
    }

    public fun stop() {
        server?.stop(0)
        server = null
        // HttpServer.stop does not touch a user-supplied executor, so without this a
        // plugin reload leaks the pool every cycle.
        pool?.shutdownNow()
        pool = null
        log("control endpoint stopped")
    }

    private fun serve(exchange: HttpExchange) {
        val response =
            try {
                val path =
                    exchange.requestURI.path
                        .trimEnd('/')
                        .ifEmpty { "/" }
                // The handshake is the one route that answers without a credential.
                // ControlEndpointStatus has to be able to tell "did not answer" from
                // "answered, wrong version" from "answered, wrong token", and an
                // authenticated handshake collapses all three into the first.
                if (path != ControlProtocol.PATH_VERSION) {
                    auth.authorise(exchange.requestHeaders.getFirst(ControlProtocol.HEADER_AUTHORIZATION))
                }
                val method = exchange.requestMethod.uppercase()
                service.handle(method, path, bodyOf(exchange, method, path))
            } catch (failure: ControlFailure) {
                failureResponse(failure.code, failure.problem)
            } catch (thrown: Exception) {
                // Do not swallow: it is reported to the caller as an opaque INTERNAL
                // and logged here with its type. The message is not passed on, because
                // an arbitrary exception message is the one string in this process
                // that nothing has checked for a player's name.
                log("control request failed unexpectedly type=${thrown.javaClass.name}")
                failureResponse(ControlErrorCode.INTERNAL, "the control endpoint failed to handle the request")
            }
        write(exchange, response)
    }

    /**
     * The request body, for the requests that have one.
     *
     * The handshake and the bodiless methods are never read from. That is not a
     * micro-optimisation: the handshake is deliberately unauthenticated, so reading
     * its body would let anything that can reach the port announce a
     * `Content-Length` and then send nothing, holding a handler thread with no
     * credential. Two of those on a small pool is every drain call to this proxy
     * blocked.
     */
    private fun bodyOf(
        exchange: HttpExchange,
        method: String,
        path: String,
    ): String =
        when {
            path == ControlProtocol.PATH_VERSION -> ""
            method == "GET" || method == "DELETE" || method == "HEAD" -> ""
            else -> readBody(exchange)
        }

    private fun readBody(exchange: HttpExchange): String {
        val body = exchange.requestBody.readNBytes(ControlProtocol.MAX_BODY_BYTES + 1)
        if (body.size > ControlProtocol.MAX_BODY_BYTES) {
            throw ControlFailure(
                ControlErrorCode.MALFORMED_REQUEST,
                "the request body must be at most ${ControlProtocol.MAX_BODY_BYTES} bytes",
            )
        }
        return String(body, Charsets.UTF_8)
    }

    private fun write(
        exchange: HttpExchange,
        response: ControlResponse,
    ) {
        val bytes = response.body.toByteArray(Charsets.UTF_8)
        try {
            exchange.responseHeaders.add("Content-Type", ControlProtocol.CONTENT_TYPE)
            exchange.sendResponseHeaders(response.status, bytes.size.toLong())
            exchange.responseBody.write(bytes)
        } catch (broken: IOException) {
            // The caller went away mid-response. Nothing to report to, and nothing
            // about the proxy is wrong.
            log("control response not delivered status=${response.status} reason=${broken.javaClass.simpleName}")
        }
    }

    private fun failureResponse(
        code: ControlErrorCode,
        problem: String,
    ): ControlResponse =
        ControlResponse(
            code.httpStatus,
            JsonWriter()
                .obj {
                    objectField(ControlProtocol.FIELD_ERROR) {
                        field("code", code.name)
                        field("message", problem)
                    }
                }.toString(),
        )

    private fun namedThreads(): ThreadFactory {
        val counter = AtomicInteger()
        return ThreadFactory { runnable ->
            Thread(runnable, "mcorch-control-${counter.incrementAndGet()}").apply { isDaemon = true }
        }
    }

    private companion object {
        /**
         * `:core` makes one request at a time per proxy, so this is not about
         * throughput — it is headroom so that one slow request cannot be every
         * request. Still small: this pool lives inside a Minecraft proxy, where
         * threads compete with packet handling.
         */
        const val THREADS = 4
        const val BACKLOG = 8

        const val MAX_REQUEST_TIME_PROPERTY = "sun.net.httpserver.maxReqTime"
        const val MAX_REQUEST_SECONDS = 15
    }
}
