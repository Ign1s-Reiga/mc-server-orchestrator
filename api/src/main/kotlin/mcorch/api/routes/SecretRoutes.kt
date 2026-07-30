package mcorch.api.routes

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
import mcorch.store.SecretStore
import mcorch.store.SecretValue
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Setting secret material, and listing coordinates. Never reading material back.
 *
 * A definition names a secret; it never contains one (`RconSpec` has no inline
 * password field, and the Velocity forwarding secret will arrive the same way).
 * Something has to put the material where the reference points, and an operator
 * with a dashboard is that something — so this exists, and it is deliberately
 * one-way.
 *
 * `GET /api/v1/secrets/{name}/{key}` is routed on purpose, to a refusal. Leaving
 * it unrouted would answer with a generic 404, which reads as "wrong
 * coordinates" and invites a client to try others; answering
 * [ErrorCode.SECRET_NOT_READABLE] says the operation does not exist, whatever
 * the coordinates. There is no debug view, no export, and no "reveal" flag.
 *
 * ## Material handling
 *
 * The body is read as bytes and decoded straight into a `CharArray`, never into
 * a `String`. A `String` is immutable and interned-adjacent: once material is in
 * one it stays on the heap until a garbage collector happens to move it, and it
 * is one careless interpolation away from a log line. The intermediate buffers
 * are wiped here; [SecretValue] owns the copy from then on.
 *
 * `Content-Type` is required to be `text/plain` or `application/octet-stream` —
 * not JSON, so that no material ever passes through a JSON escape and no
 * material is ever bound into a parser's intermediate `String`.
 */
internal class SecretRoutes(
    private val secrets: SecretStore,
) {
    fun routes(): List<Route> =
        listOf(
            Route("GET", SECRETS, Access.OPERATOR) { _, _ -> HandlerResult.Send(list()) },
            Route("GET", SECRET, Access.OPERATOR) { request, _ -> HandlerResult.Send(keys(request)) },
            Route("DELETE", SECRET, Access.OPERATOR) { request, _ -> HandlerResult.Send(removeSecret(request)) },
            Route("PUT", SECRET_KEY, Access.OPERATOR) { request, _ -> HandlerResult.Send(put(request)) },
            Route("DELETE", SECRET_KEY, Access.OPERATOR) { request, _ -> HandlerResult.Send(removeKey(request)) },
            Route("GET", SECRET_KEY, Access.OPERATOR) { _, _ -> refuseRead() },
        )

    private suspend fun list(): Response {
        // Read first, render second: the JSON builder's block is not inline, so a
        // suspending call cannot happen inside it.
        val coordinates = secrets.listNames().sortedBy { it.value }.map { it to secrets.listKeys(it).sorted() }
        return Response.json(
            200,
            jsonObject {
                putArray("items", coordinates) { (name, keys) ->
                    jsonObject {
                        put("name", name.value)
                        put("keys", Json.strings(keys))
                    }
                }
            },
        )
    }

    private suspend fun keys(request: Request): Response {
        val name = Requests.name(request)
        val keys = secrets.listKeys(name).sorted()
        if (keys.isEmpty()) throw ApiException.notFound("no secret named `$name`")
        return Response.json(
            200,
            jsonObject {
                put("name", name.value)
                put("keys", Json.strings(keys))
            },
        )
    }

    private suspend fun put(request: Request): Response {
        val reference = Requests.secretRef(request)
        val mediaType = request.contentType()
        if (mediaType !in MATERIAL_MEDIA_TYPES) {
            throw ApiException(
                ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                "secret material must be sent as the raw request body with Content-Type text/plain or " +
                    "application/octet-stream, found `$mediaType`. It is deliberately not accepted as JSON: " +
                    "that would bind the material into a parser's intermediate strings on the way in",
            )
        }
        if (request.body.isEmpty()) {
            throw ApiException(ErrorCode.BAD_REQUEST, "the request body is empty; there is no material to store")
        }
        val existed = secrets.contains(reference)
        val value = materialFrom(request)
        try {
            secrets.put(reference, value)
        } finally {
            value.destroy()
        }
        LOG.info("secret written name={} key={} replaced={}", reference.name, reference.key, existed)
        return Response.json(
            if (existed) 200 else 201,
            jsonObject {
                put("name", reference.name.value)
                put("key", reference.key)
                put("replaced", existed)
                put("length", request.body.size)
            },
        )
    }

    private suspend fun removeKey(request: Request): Response {
        val reference = Requests.secretRef(request)
        if (!secrets.removeKey(reference)) {
            throw ApiException.notFound("no secret key `${reference.name}/${reference.key}`")
        }
        LOG.info("secret key removed name={} key={}", reference.name, reference.key)
        return Response.empty(204)
    }

    private suspend fun removeSecret(request: Request): Response {
        val name: ResourceName = Requests.name(request)
        val removed = secrets.removeSecret(name)
        if (removed == 0) throw ApiException.notFound("no secret named `$name`")
        LOG.info("secret removed name={} keys={}", name, removed)
        return Response.json(
            200,
            jsonObject {
                put("name", name.value)
                put("removedKeys", removed)
            },
        )
    }

    private fun refuseRead(): HandlerResult =
        throw ApiException(
            ErrorCode.SECRET_NOT_READABLE,
            "secret material is never returned by this API. A definition refers to a secret by name and key, " +
                "and only the component that needs the value resolves it, at the moment it needs it. " +
                "GET /api/v1/secrets and GET /api/v1/secrets/{name} list coordinates",
            headers = listOf("Allow" to "PUT, DELETE"),
        )

    /**
     * Decodes the body into a [SecretValue], wiping every intermediate.
     *
     * A malformed byte sequence is rejected rather than replaced with U+FFFD:
     * silently substituting a character would store material that is not what was
     * sent, and the failure would surface later as an RCON login that does not
     * work for no visible reason.
     */
    private fun materialFrom(request: Request): SecretValue {
        val decoder =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        val decoded =
            try {
                decoder.decode(ByteBuffer.wrap(request.body))
            } catch (failure: java.nio.charset.CharacterCodingException) {
                throw ApiException(
                    ErrorCode.BAD_REQUEST,
                    "the request body is not valid UTF-8",
                    cause = failure,
                )
            }
        val material = CharArray(decoded.remaining())
        decoded.get(material)
        return try {
            SecretValue.of(material)
        } finally {
            // NUL rather than a space: a wiped buffer that still reads as
            // printable text is one a heap dump cannot be told apart from live
            // material of the same length.
            material.fill('\u0000')
            if (decoded.hasArray()) decoded.array().fill('\u0000')
            request.body.fill(0)
        }
    }

    companion object {
        const val SECRETS: String = "/api/v1/secrets"
        const val SECRET: String = "/api/v1/secrets/{name}"
        const val SECRET_KEY: String = "/api/v1/secrets/{name}/{key}"

        private val MATERIAL_MEDIA_TYPES = setOf("text/plain", "application/octet-stream", "")

        private val LOG = LoggerFactory.getLogger(SecretRoutes::class.java)
    }
}
