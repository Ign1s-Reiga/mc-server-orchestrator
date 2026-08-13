package mcorch.api

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import mcorch.api.auth.OperatorAuth
import mcorch.api.auth.SessionRegistry
import mcorch.api.http.Access
import mcorch.api.http.ApiException
import mcorch.api.http.Cors
import mcorch.api.http.ErrorCode
import mcorch.api.http.HandlerResult
import mcorch.api.http.HeaderNames
import mcorch.api.http.Http
import mcorch.api.http.Request
import mcorch.api.http.Response
import mcorch.api.http.Route
import mcorch.api.http.RouteMatch
import mcorch.api.http.Router
import mcorch.api.http.StaticFiles
import mcorch.api.routes.AuthRoutes
import mcorch.api.routes.IdentityRoutes
import mcorch.api.routes.MetaRoutes
import mcorch.api.routes.SecretRoutes
import mcorch.api.routes.ServerRoutes
import mcorch.api.routes.StreamRoutes
import mcorch.api.stream.StreamRegistry
import mcorch.store.IdentityStore
import mcorch.store.SecretStore
import mcorch.store.Store
import mcorch.store.StoreConflictException
import mcorch.store.StoreException
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The dashboard backend.
 *
 * A thin edge over the declarative core, and thin is a design constraint rather
 * than a description: mutating handlers write desired state through [Store] and
 * return, and the reconcile loop converges. Nothing here starts, stops or
 * inspects a container, and this module has no `:core` or `:cri` dependency to
 * do it with.
 *
 * The contract this serves is written down in `api/API.md`. That document is the
 * deliverable a frontend is built against; this class is one implementation of
 * it.
 *
 * ## Threading
 *
 * One virtual thread per exchange. Handlers are `suspend` because [Store] is,
 * and each exchange bridges with `runBlocking` on its own thread — which is the
 * right shape here rather than a compromise: a virtual thread parked in a store
 * call or in a socket write to a slow event-stream client costs a few hundred
 * bytes, so the thread-per-exchange model that would be wrong for a platform
 * thread pool is exactly what a long-lived SSE connection wants.
 */
public class ApiServer private constructor(
    private val http: HttpServer,
    private val executor: ExecutorService,
    private val streams: StreamRegistry,
    /** The bound port. Resolved, so a configuration of port 0 is discoverable. */
    public val port: Int,
) : AutoCloseable {
    /** `http://host:port`, for logging and for tests. */
    public val baseUrl: String get() = "http://${http.address.hostString}:$port"

    /**
     * Stops accepting, closes every event stream, and waits briefly for
     * in-flight exchanges.
     *
     * Streams are closed *first* and deliberately: each one is a loop parked in a
     * socket write or a `delay`, and waiting out a grace period for every open
     * dashboard tab would turn a one-second shutdown into a thirty-second one.
     */
    override fun close() {
        streams.shutdown()
        http.stop(SHUTDOWN_GRACE_SECONDS)
        executor.shutdownNow()
        LOG.info("api server stopped port={}", port)
    }

    public companion object {
        private val LOG = LoggerFactory.getLogger(ApiServer::class.java)
        private const val SHUTDOWN_GRACE_SECONDS = 1
        private const val BACKLOG = 32

        /**
         * Binds and starts serving.
         *
         * @throws IOException if the address cannot be bound.
         */
        public fun start(
            config: ApiConfig,
            store: Store,
            secrets: SecretStore,
            identities: IdentityStore,
        ): ApiServer {
            val streams = StreamRegistry(config.maxStreams)
            val sessions = SessionRegistry(config.clock, config.sessionTtl)
            val auth = OperatorAuth(config.token.digest, sessions, config.authFailureDelay, identities)
            val dispatcher =
                Dispatcher(
                    Router(routeTable(config, store, secrets, identities, auth, sessions, streams)),
                    auth,
                    Cors(config.allowedOrigins),
                    config,
                    config.staticRoot?.let(::StaticFiles),
                )

            val http = HttpServer.create(InetSocketAddress(config.bindHost, config.bindPort), BACKLOG)
            val executor = Executors.newVirtualThreadPerTaskExecutor()
            http.executor = executor
            http.createContext("/") { exchange -> dispatcher.dispatch(exchange) }
            http.start()

            val port = http.address.port
            LOG.info(
                "api server started host={} port={} origins={} cookieSecure={} sameSite={} maxStreams={}",
                config.bindHost,
                port,
                if (config.allowedOrigins.isEmpty()) "same-origin only" else config.allowedOrigins.joinToString(","),
                config.cookieSecure,
                config.cookieSameSite.wireValue,
                config.maxStreams,
            )
            if (!ApiConfig.isLoopback(config.bindHost)) {
                LOG.warn(
                    "the api server is bound to {} and speaks plain HTTP. Put a TLS terminator in front of it: " +
                        "the operator token and the session cookie are both replayable by anyone on the path",
                    config.bindHost,
                )
            }
            return ApiServer(http, executor, streams, port)
        }

        /**
         * The whole route table, in one expression.
         *
         * Pulled out of [start] so a test can assert on it directly — in
         * particular that no mutating route is declared [Access.Public]. That
         * check cannot be made by exercising endpoints, because it has to hold
         * for endpoints nobody has thought to exercise yet.
         */
        internal fun routeTable(
            config: ApiConfig,
            store: Store,
            secrets: SecretStore,
            identities: IdentityStore,
            auth: OperatorAuth,
            sessions: SessionRegistry,
            streams: StreamRegistry,
        ): List<Route> =
            MetaRoutes(config).routes() +
                AuthRoutes(auth, sessions, config).routes() +
                ServerRoutes(store).routes() +
                SecretRoutes(secrets).routes() +
                IdentityRoutes(identities, sessions, config.clock).routes() +
                StreamRoutes(store, config, streams).routes()
    }
}

/**
 * The one place a request becomes a response.
 *
 * Order matters and is the same for every route: cross-origin decision, then
 * routing, then body, then credential, then handler. Authentication before the
 * handler rather than inside it is what makes "no unauthenticated mutating
 * endpoint" a property of the dispatcher rather than a thing each handler has to
 * remember.
 */
internal class Dispatcher(
    private val router: Router,
    private val auth: OperatorAuth,
    private val cors: Cors,
    private val config: ApiConfig,
    private val staticFiles: StaticFiles?,
) {
    fun dispatch(exchange: HttpExchange) {
        val method = exchange.requestMethod.uppercase()
        val path = exchange.requestURI.rawPath.orEmpty()
        val decision =
            cors.decide(
                origin = exchange.requestHeaders.getFirst(HeaderNames.ORIGIN),
                host = exchange.requestHeaders.getFirst("Host"),
            )

        if (decision == Cors.Decision.Refused) {
            // Refused before anything else looks at the request: a cross-site page
            // must not be able to reach a handler even with a valid cookie attached.
            Http.send(
                exchange,
                ApiException(
                    ErrorCode.ORIGIN_NOT_ALLOWED,
                    "this origin is not configured. Set ${ApiConfig.ORIGINS_VARIABLE} to allow it",
                ).toResponse(),
            )
            return
        }

        if (method == "OPTIONS") {
            Http.send(exchange, Response.empty(204, cors.preflightHeaders(decision)))
            return
        }

        // Written onto the exchange rather than folded into the returned
        // [Response], because the event stream never produces one: it takes the
        // exchange over and calls `sendResponseHeaders` itself. A cross-origin
        // `EventSource` with credentials is refused by the browser unless the
        // stream's *own* response carries these, so folding them in at the end
        // would leave exactly one endpoint broken cross-origin — and it is the
        // endpoint a dashboard leaves open all day.
        for ((name, value) in cors.headersFor(decision)) {
            exchange.responseHeaders.add(name, value)
        }

        val response =
            try {
                handle(exchange, method, path)
            } catch (failure: ApiException) {
                logFailure(method, path, failure)
                failure.toResponse()
            } catch (failure: StoreConflictException) {
                ApiException.conflict(failure.conflict).toResponse()
            } catch (failure: StoreException) {
                storeFailure(method, path, failure).toResponse()
            } catch (failure: IOException) {
                // Reading the request or writing the response failed. The socket is
                // the problem, so there is nothing useful to send down it.
                LOG.debug("connection failed method={} path={}", method, path, failure)
                exchange.close()
                return
            } catch (failure: RuntimeException) {
                // Not swallowed: logged with its stack and surfaced as a 500. The
                // message is this module's, never the exception's — an exception
                // message can carry request content, and request content can carry
                // things this API does not put in responses.
                LOG.error("unhandled failure method={} path={}", method, path, failure)
                ApiException(ErrorCode.INTERNAL, "the request could not be completed", cause = failure).toResponse()
            } ?: return

        Http.send(exchange, response)
    }

    /** Null means a handler took the exchange over — see [HandlerResult.Handled]. */
    private fun handle(
        exchange: HttpExchange,
        method: String,
        path: String,
    ): Response? {
        val match = router.match(method, path)
        val found =
            when (match) {
                RouteMatch.NoRoute -> {
                    // The dashboard bundle, if this deployment serves one. Only
                    // GET and HEAD: a POST to a path with no route is a client
                    // error, and answering it with a page would hide that.
                    val served = if (method == "GET" || method == "HEAD") staticFiles?.resolve(path) else null
                    if (served != null) {
                        return Response(
                            status = 200,
                            body = if (method == "HEAD") ByteArray(0) else Files.readAllBytes(served.file),
                            contentType = served.contentType,
                        )
                    }
                    throw ApiException.notFound("no such endpoint: $method $path")
                }

                is RouteMatch.WrongMethod -> {
                    throw ApiException(
                        ErrorCode.METHOD_NOT_ALLOWED,
                        "$method is not allowed here; try ${match.allowed.joinToString(", ")}",
                        headers = listOf("Allow" to match.allowed.joinToString(", ")),
                    )
                }

                is RouteMatch.Found -> {
                    match
                }
            }

        val request =
            Request.read(exchange, found.params, config.maxBodyBytes)
                ?: throw ApiException(
                    ErrorCode.PAYLOAD_TOO_LARGE,
                    "the request body is larger than ${config.maxBodyBytes} bytes",
                )

        // Authentication joins the handler's bridge rather than opening a second
        // one: resolving an identity reads the store, which suspends.
        return runBlocking {
            when (val access = found.route.access) {
                Access.Public -> {
                    Unit
                }

                Access.AnyIdentity -> {
                    auth.authenticate(request)
                }

                is Access.AtLeast -> {
                    val credential = auth.authenticate(request)
                    val held = credential.principal.tier
                    if (!held.atLeast(access.tier)) {
                        // Distinct from UNAUTHENTICATED on purpose: the caller does
                        // not need to log in again, and a dashboard that retries the
                        // login on this loops. `requiredTier` lets it say "this needs
                        // superuser" rather than "forbidden".
                        throw ApiException(
                            ErrorCode.FORBIDDEN,
                            "this endpoint requires the ${access.tier.wireValue} tier",
                            requiredTier = access.tier.wireValue,
                        )
                    }
                }
            }
            when (val result = found.route.handler(request, exchange)) {
                is HandlerResult.Send -> result.response
                HandlerResult.Handled -> null
            }
        }
    }

    /**
     * A store failure becomes a 503 or a 500 on the store's own classification,
     * never on a guess made here. `retryable` is the store saying the call could
     * plausibly succeed if repeated, which is precisely what tells a client to
     * retry rather than to raise a ticket.
     */
    private fun storeFailure(
        method: String,
        path: String,
        failure: StoreException,
    ): ApiException {
        if (failure.retryable) {
            LOG.warn("store unavailable method={} path={}", method, path, failure)
            return ApiException(
                ErrorCode.STORE_UNAVAILABLE,
                "the state store could not be reached; retry",
                headers = listOf("Retry-After" to "1"),
                cause = failure,
            )
        }
        LOG.error("store failed permanently method={} path={}", method, path, failure)
        return ApiException(
            ErrorCode.INTERNAL,
            "the state store rejected the operation and retrying will not help; this needs an operator",
            cause = failure,
        )
    }

    private fun logFailure(
        method: String,
        path: String,
        failure: ApiException,
    ) {
        when {
            failure.code.status >= 500 -> {
                LOG.error("request failed method={} path={} code={}", method, path, failure.code)
            }

            failure.code == ErrorCode.UNAUTHENTICATED ||
                failure.code == ErrorCode.CSRF_INVALID ||
                failure.code == ErrorCode.CSRF_REQUIRED ||
                failure.code == ErrorCode.ORIGIN_NOT_ALLOWED -> {
                // Worth a line each: a burst of these is the signal that somebody is
                // trying the port. The credential is never part of the line.
                LOG.warn("request rejected method={} path={} code={}", method, path, failure.code)
            }

            else -> {
                LOG.debug("request rejected method={} path={} code={}", method, path, failure.code)
            }
        }
    }

    private companion object {
        private val LOG = LoggerFactory.getLogger(Dispatcher::class.java)
    }
}
