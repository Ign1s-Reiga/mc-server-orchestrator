package mcorch.velocity.control

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The real endpoint on a real socket, so the transport tests exercise the
 * server a proxy would run rather than a stand-in for it.
 *
 * Bound on port 0 and read back from [ControlEndpoint.boundPort]: a fixed port
 * in a test is a test that fails on whichever machine already has it.
 */
internal class TestEndpoint(
    proxy: FakeProxy,
    token: String?,
    val admission: AdmissionRegistry = AdmissionRegistry(),
) : AutoCloseable {
    /** Everything the endpoint logged. Asserted over by `PlayerIdentityLeakageTest`. */
    val logs: MutableList<String> = CopyOnWriteArrayList()

    val service: ControlService = readyService(proxy, admission)

    private val endpoint =
        ControlEndpoint(
            service,
            ControlAuth(token),
            ControlConfig(port = 0, bindAddress = "127.0.0.1", token = token),
        ) { message -> logs += message }

    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    init {
        endpoint.start()
    }

    fun call(
        method: String,
        path: String,
        body: String = "",
        bearer: String? = null,
    ): HttpResponse<String> {
        val builder =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:${endpoint.boundPort}$path"))
                .timeout(Duration.ofSeconds(10))
                .method(method, HttpRequest.BodyPublishers.ofString(body))
        if (bearer != null) builder.header(ControlProtocol.HEADER_AUTHORIZATION, "Bearer $bearer")
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    /** Every route the protocol offers, driven once, so a sweep-style assertion covers all of them. */
    fun exerciseEverything(
        bearer: String?,
        backend: String,
        destination: String,
    ): List<HttpResponse<String>> =
        listOf(
            call("GET", ControlProtocol.PATH_VERSION),
            call("GET", ControlProtocol.PATH_STATE, bearer = bearer),
            call("PUT", ControlProtocol.PATH_PROXY, """{"admitsNewPlayers":false}""", bearer),
            call(
                "PUT",
                ControlProtocol.PATH_BACKEND + backend,
                """{"address":"10.0.0.4:25565","admitsNewPlayers":false}""",
                bearer,
            ),
            call(
                "POST",
                ControlProtocol.PATH_BACKEND + backend + ControlProtocol.TRANSFER_SUFFIX,
                """{"destination":"$destination"}""",
                bearer,
            ),
            call("DELETE", ControlProtocol.PATH_BACKEND + backend, bearer = bearer),
            call("GET", ControlProtocol.PATH_STATE, bearer = bearer),
        )

    override fun close() {
        endpoint.stop()
    }
}
