package mcorch.api

import mcorch.core.termination.ForcedTermination
import mcorch.store.IdentityStore
import mcorch.store.SecretStore
import mcorch.store.Store
import mcorch.store.sqlite.EmbeddedStore
import mcorch.store.sqlite.EmbeddedStoreConfig
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.Duration as JavaDuration

/**
 * A real API server, on a real port, over a real [EmbeddedStore].
 *
 * No layer of this module is mocked, and that is the point rather than
 * thoroughness for its own sake. Most of what these tests are checking lives
 * *between* the layers — a status code chosen from a store outcome, a header
 * that has to survive the response writer, an event frame a browser has to be
 * able to parse — and a test that calls a handler function directly checks none
 * of it. The client is `java.net.http.HttpClient`, so the requests go over a
 * socket exactly as a browser's would.
 */
class TestApi private constructor(
    val server: ApiServer,
    val store: Store,
    val secrets: SecretStore,
    val identities: IdentityStore,
    /** The forced-stop seam this harness was built with, so a test can assert on it. */
    val forced: ForcedTermination,
    val directory: Path,
    val token: String,
    private val onClose: () -> Unit,
) : AutoCloseable {
    private val client: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(JavaDuration.ofSeconds(5))
            .build()

    val base: String get() = "http://127.0.0.1:${server.port}"

    /** Sends a request with no credential at all. */
    fun anonymous(
        method: String,
        path: String,
        body: String? = null,
        contentType: String? = null,
        headers: List<Pair<String, String>> = emptyList(),
    ): Reply = send(method, path, body, contentType, headers)

    /** Sends a request authenticated with the operator token. */
    fun call(
        method: String,
        path: String,
        body: String? = null,
        contentType: String? = "application/yaml",
        headers: List<Pair<String, String>> = emptyList(),
    ): Reply = send(method, path, body, contentType, headers + ("Authorization" to "Bearer $token"))

    private fun send(
        method: String,
        path: String,
        body: String?,
        contentType: String?,
        headers: List<Pair<String, String>>,
    ): Reply {
        val builder = HttpRequest.newBuilder(URI.create("$base$path")).timeout(JavaDuration.ofSeconds(20))
        val publisher =
            if (body == null) {
                HttpRequest.BodyPublishers.noBody()
            } else {
                HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
            }
        builder.method(method, publisher)
        if (body != null && contentType != null) builder.header("Content-Type", contentType)
        headers.forEach { (name, value) -> builder.header(name, value) }
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        return Reply(response.statusCode(), response.body(), response.headers().map())
    }

    /** Opens an event stream and reads until [read] says to stop or [limit] frames arrive. */
    fun stream(
        query: String = "",
        limit: Int = 4,
        headers: List<Pair<String, String>> = emptyList(),
        onEvent: (SseEvent) -> Boolean = { true },
    ): List<SseEvent> {
        val builder =
            HttpRequest
                .newBuilder(URI.create("$base${routesStream()}$query"))
                .timeout(JavaDuration.ofSeconds(30))
                .header("Authorization", "Bearer $token")
                .GET()
        headers.forEach { (name, value) -> builder.header(name, value) }
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        check(response.statusCode() == 200) { "stream returned ${response.statusCode()}" }
        val events = mutableListOf<SseEvent>()
        response.body().bufferedReader(StandardCharsets.UTF_8).use { reader ->
            var name: String? = null
            var id: String? = null
            val data = StringBuilder()
            while (events.size < limit) {
                val line = reader.readLine() ?: break
                when {
                    line.startsWith("event: ") -> {
                        name = line.removePrefix("event: ")
                    }

                    line.startsWith("id: ") -> {
                        id = line.removePrefix("id: ")
                    }

                    line.startsWith("data: ") -> {
                        data.append(line.removePrefix("data: "))
                    }

                    // A comment frame. The stream no longer sends any — the
                    // keep-alive is a `ping` event, because EventSource cannot see
                    // a comment — but the parser still tolerates one rather than
                    // treating it as data.
                    line.startsWith(":") -> {
                    }

                    line.startsWith("retry: ") -> {
                    }

                    line.isEmpty() -> {
                        val completed = name
                        if (completed != null) {
                            val event = SseEvent(completed, id, data.toString())
                            events += event
                            if (!onEvent(event)) return events
                        }
                        name = null
                        id = null
                        data.setLength(0)
                    }
                }
            }
        }
        return events
    }

    private fun routesStream(): String = "/api/v1/stream"

    /**
     * Opens a stream, reads its response head, and closes it.
     *
     * For asserting on the headers a stream carries. The stream never produces a
     * [Response] object — it takes the exchange over — so its headers are the one
     * set nothing else in the suite would look at.
     */
    fun streamHead(headers: List<Pair<String, String>> = emptyList()): Reply {
        val builder =
            HttpRequest
                .newBuilder(URI.create("$base${routesStream()}"))
                .timeout(JavaDuration.ofSeconds(20))
                .header("Authorization", "Bearer $token")
                .GET()
        headers.forEach { (name, value) -> builder.header(name, value) }
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        response.body().close()
        return Reply(response.statusCode(), "", response.headers().map())
    }

    /**
     * A second harness pointing at [other], with this one's token.
     *
     * For the tests that need a server built over a *different* store — a failing
     * one, say — while still having somewhere real to keep secrets. Closing it
     * closes only [other]; this harness still owns the store and the directory.
     */
    fun sharing(other: ApiServer): TestApi =
        TestApi(other, store, secrets, identities, forced, directory, token) { other.close() }

    override fun close(): Unit = onClose()

    /** One HTTP response, with the body parsed on demand. */
    data class Reply(
        val status: Int,
        val body: String,
        val headers: Map<String, List<String>>,
    ) {
        fun header(name: String): String? =
            headers.entries
                .firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.value
                ?.firstOrNull()

        fun headerValues(name: String): List<String> =
            headers.entries
                .firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.value
                .orEmpty()

        /** The body as a document. YAML 1.2 reads JSON, so the repo's parser is the JSON reader. */
        fun json(): Map<String, Any?> = Documents.parse(body)

        fun errorCode(): String? = (json()["error"] as? Map<*, *>)?.get("code") as? String
    }

    data class SseEvent(
        val name: String,
        val id: String?,
        val data: String,
    ) {
        fun json(): Map<String, Any?> = Documents.parse(data)
    }

    companion object {
        /** Fixed so tests never depend on wall-clock ordering. */
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-07-28T10:15:30Z"), ZoneOffset.UTC)

        fun start(
            changeLogRetention: Int = 10_000,
            /** Defaults to the seam with nothing behind it, which is this harness's real state. */
            forced: ForcedTermination = RefusingForce(),
            configure: (ApiConfig) -> ApiConfig = { it },
        ): TestApi {
            val directory = Files.createTempDirectory("mcorch-api-test")
            val embedded =
                EmbeddedStore.open(
                    EmbeddedStoreConfig(
                        directory = directory,
                        clock = CLOCK,
                        changeLogRetention = changeLogRetention,
                    ),
                )
            // Generated per run, never a literal: a literal credential in a test
            // file is how a real one eventually ends up in one.
            val token =
                "test-" +
                    java.util.UUID
                        .randomUUID()
                        .toString()
                        .repeat(2)
            val config =
                configure(
                    ApiConfig(
                        bindHost = "127.0.0.1",
                        bindPort = 0,
                        token = OperatorToken.of(token).getOrThrow(),
                        clock = CLOCK,
                        // Fast enough that a test does not wait on it, slow enough
                        // that it is still two distinct cadences.
                        changePollInterval = kotlin.time.Duration.parse("50ms"),
                        statusPollInterval = kotlin.time.Duration.parse("100ms"),
                        // Failures are asserted on constantly here; the production
                        // delay would add seconds to the suite for no coverage.
                        authFailureDelay = kotlin.time.Duration.ZERO,
                    ),
                )
            return try {
                val server =
                    ApiServer.start(
                        config,
                        embedded.state,
                        embedded.secrets,
                        embedded.identities,
                        RefusingConsole,
                        forced,
                    )
                TestApi(
                    server = server,
                    store = embedded.state,
                    identities = embedded.identities,
                    forced = forced,
                    secrets = embedded.secrets,
                    directory = directory,
                    token = token,
                    onClose = {
                        server.close()
                        embedded.close()
                        directory.toFile().deleteRecursively()
                    },
                )
            } catch (failure: Throwable) {
                embedded.close()
                directory.toFile().deleteRecursively()
                throw failure
            }
        }
    }
}

/** Reads a JSON response as a document, using the YAML 1.2 parser this repo already has. */
object Documents {
    private val load = Load(LoadSettings.builder().setLabel("response").build())

    @Suppress("UNCHECKED_CAST")
    fun parse(text: String): Map<String, Any?> = load.loadFromString(text) as Map<String, Any?>

    /** Every scalar in a document, flattened. For "does this response contain X anywhere". */
    fun scalars(value: Any?): List<String> =
        when (value) {
            null -> emptyList()
            is Map<*, *> -> value.entries.flatMap { scalars(it.key) + scalars(it.value) }
            is List<*> -> value.flatMap(::scalars)
            else -> listOf(value.toString())
        }
}
