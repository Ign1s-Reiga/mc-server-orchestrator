package mcorch.api.http

import com.sun.net.httpserver.HttpExchange
import mcorch.api.json.Json
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal object MediaTypes {
    const val JSON: String = "application/json; charset=utf-8"
    const val EVENT_STREAM: String = "text/event-stream; charset=utf-8"
    const val TEXT: String = "text/plain; charset=utf-8"
}

internal object HeaderNames {
    const val AUTHORIZATION: String = "Authorization"
    const val CONTENT_TYPE: String = "Content-Type"
    const val COOKIE: String = "Cookie"
    const val ETAG: String = "ETag"
    const val IF_MATCH: String = "If-Match"
    const val LAST_EVENT_ID: String = "Last-Event-ID"
    const val ORIGIN: String = "Origin"
    const val CSRF: String = "X-CSRF-Token"
}

/**
 * One request, already read.
 *
 * The body is read eagerly and capped, because every body this API accepts is a
 * small document and a handler that streams an unbounded request body is a
 * handler that can be made to allocate without limit. The cap is enforced by the
 * reader, not by trusting `Content-Length`.
 */
internal class Request(
    val method: String,
    val path: String,
    val query: Map<String, List<String>>,
    val pathParams: Map<String, String>,
    private val headers: Map<String, List<String>>,
    val body: ByteArray,
) {
    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()

    fun headers(name: String): List<String> = headers[name.lowercase()].orEmpty()

    fun queryValue(name: String): String? = query[name]?.firstOrNull()

    fun queryValues(name: String): List<String> = query[name].orEmpty()

    fun bodyText(): String = String(body, StandardCharsets.UTF_8)

    /** The media type without parameters, lowercased. Empty when the header is absent. */
    fun contentType(): String =
        header(HeaderNames.CONTENT_TYPE)
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            .orEmpty()

    fun cookie(name: String): String? {
        for (raw in headers(HeaderNames.COOKIE)) {
            for (pair in raw.split(';')) {
                val separator = pair.indexOf('=')
                if (separator <= 0) continue
                if (pair.substring(0, separator).trim() == name) {
                    return pair.substring(separator + 1).trim().removeSurrounding("\"")
                }
            }
        }
        return null
    }

    /** Mutating methods are the ones that need CSRF protection and an operator credential. */
    val mutating: Boolean get() = method == "POST" || method == "PUT" || method == "PATCH" || method == "DELETE"

    companion object {
        /**
         * Reads an exchange, refusing a body larger than [maxBodyBytes].
         *
         * Reads one byte past the limit deliberately: a body of exactly the limit
         * is accepted, and one byte over is detected without trusting the declared
         * `Content-Length`.
         */
        fun read(
            exchange: HttpExchange,
            pathParams: Map<String, String>,
            maxBodyBytes: Int,
        ): Request? {
            val uri = exchange.requestURI
            val body =
                exchange.requestBody.use { stream ->
                    val buffer = stream.readNBytes(maxBodyBytes + 1)
                    if (buffer.size > maxBodyBytes) return null
                    buffer
                }
            val headers =
                exchange.requestHeaders.entries.associate { (name, values) ->
                    name.lowercase() to values.toList()
                }
            return Request(
                method = exchange.requestMethod.uppercase(),
                path = uri.rawPath.orEmpty(),
                query = parseQuery(uri.rawQuery),
                pathParams = pathParams,
                headers = headers,
                body = body,
            )
        }

        fun parseQuery(raw: String?): Map<String, List<String>> {
            if (raw.isNullOrEmpty()) return emptyMap()
            val result = LinkedHashMap<String, MutableList<String>>()
            for (pair in raw.split('&')) {
                if (pair.isEmpty()) continue
                val separator = pair.indexOf('=')
                val name = if (separator < 0) pair else pair.substring(0, separator)
                val value = if (separator < 0) "" else pair.substring(separator + 1)
                result.getOrPut(decode(name)) { mutableListOf() }.add(decode(value))
            }
            return result
        }

        private fun decode(raw: String): String =
            try {
                URLDecoder.decode(raw, StandardCharsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                // A malformed percent escape is not worth a 400 on its own: the
                // value goes on to be validated as whatever it is meant to be,
                // and will be rejected there with a field name attached.
                raw
            }
    }
}

/** A complete response. Streaming responses do not go through this — see [HandlerResult.Handled]. */
internal class Response(
    val status: Int,
    val body: ByteArray = ByteArray(0),
    val contentType: String? = null,
    val headers: List<Pair<String, String>> = emptyList(),
) {
    fun withHeader(
        name: String,
        value: String,
    ): Response = Response(status, body, contentType, headers + (name to value))

    companion object {
        fun json(
            status: Int,
            document: Json,
            headers: List<Pair<String, String>> = emptyList(),
        ): Response =
            Response(
                status = status,
                body = document.render().toByteArray(StandardCharsets.UTF_8),
                contentType = MediaTypes.JSON,
                headers = headers,
            )

        fun empty(
            status: Int,
            headers: List<Pair<String, String>> = emptyList(),
        ): Response = Response(status = status, headers = headers)
    }
}

/**
 * What a handler did.
 *
 * [Handled] exists for the event stream: it takes the exchange over, writes for
 * as long as the client is connected, and closes it itself. Modelling that as a
 * nullable [Response] would make "forgot to return" and "took over the socket"
 * the same value.
 */
internal sealed interface HandlerResult {
    data class Send(
        val response: Response,
    ) : HandlerResult

    data object Handled : HandlerResult
}

internal object Http {
    /** Writes a [Response] and closes the exchange. Safe to call once per exchange. */
    fun send(
        exchange: HttpExchange,
        response: Response,
    ) {
        val headers = exchange.responseHeaders
        response.contentType?.let { headers.set(HeaderNames.CONTENT_TYPE, it) }
        for ((name, value) in response.headers) {
            headers.add(name, value)
        }
        try {
            if (response.body.isEmpty()) {
                // -1 means "no body at all", which is what 204 and 304 require and
                // what every other empty response should say rather than declaring
                // a zero-length one.
                exchange.sendResponseHeaders(response.status, -1L)
            } else {
                exchange.sendResponseHeaders(response.status, response.body.size.toLong())
                exchange.responseBody.use { it.write(response.body) }
            }
        } catch (_: IOException) {
            // The client went away mid-response. Nothing to do and nothing to
            // report: it is not a server fault and not actionable.
        } finally {
            exchange.close()
        }
    }
}
