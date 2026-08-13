package mcorch.api.http

import mcorch.api.json.Json
import mcorch.api.json.jsonObject
import mcorch.schema.SchemaViolation
import mcorch.store.ConflictReason
import mcorch.store.StatePart
import mcorch.store.Unreadable
import mcorch.store.UnreadableServer
import mcorch.store.WriteOutcome

/**
 * Every way a request can fail, as a closed set.
 *
 * A closed set because the dashboard branches on it: a form wants to know that a
 * 422 carries per-field violations and a 409 carries a version to re-read,
 * without pattern-matching on prose. The HTTP status is a property of the code
 * rather than something a call site picks, so the same failure cannot come back
 * as a 400 from one handler and a 422 from another.
 */
internal enum class ErrorCode(
    val status: Int,
    /** Whether repeating the identical request could plausibly succeed. */
    val retryable: Boolean = false,
) {
    /** The request was not understood: a bad query parameter, a body that is not text. */
    BAD_REQUEST(400),

    /** No operator credential, or one that is not valid. */
    UNAUTHENTICATED(401),

    /** Authenticated by cookie, on a mutating request, with no `X-CSRF-Token`. */
    CSRF_REQUIRED(403),
    CSRF_INVALID(403),

    /** An `Origin` header naming an origin the server was not configured to accept. */
    ORIGIN_NOT_ALLOWED(403),

    /**
     * Authenticated, and not holding the tier the route requires.
     *
     * Distinct from [UNAUTHENTICATED] because the remedy is different and a client
     * that confuses them loops: there is nothing to log in again *as*, and the
     * credential is fine. Carries `requiredTier` so a dashboard can name what is
     * missing.
     */
    FORBIDDEN(403),

    NOT_FOUND(404),
    METHOD_NOT_ALLOWED(405),

    /**
     * Reading secret material back. A distinct code rather than a 404, because
     * "not found" would invite a client to retry with different coordinates: the
     * refusal is about the operation, not about whether the secret exists.
     */
    SECRET_NOT_READABLE(405),

    /** A write lost a race, or hit an integrity rule. The body carries the current version. */
    CONFLICT(409),

    PAYLOAD_TOO_LARGE(413),
    UNSUPPORTED_MEDIA_TYPE(415),

    /** The document parsed but is not a valid definition. The body carries every violation. */
    VALIDATION_FAILED(422),

    /** A replace with no `If-Match`. See the note on the servers PUT route. */
    PRECONDITION_REQUIRED(428),

    INTERNAL(500),

    /**
     * The store holds this server and cannot decode the part the request needed.
     *
     * A 500 because it is a server-side fault that no retry and no change to the
     * request will fix — the stored bytes will say the same thing next time — and
     * a distinct code because the remedy is specific: a human repairs the row.
     * Reporting it as `NOT_FOUND` would tell an operator the server is gone when
     * it may well be running with players on it.
     */
    SERVER_UNREADABLE(500),

    /** The store could not be reached. [mcorch.store.StoreException.retryable] was true. */
    STORE_UNAVAILABLE(503, retryable = true),

    /** Too many event streams are already open. Retry, or close one. */
    STREAM_LIMIT(503, retryable = true),
}

/**
 * A failure on its way to becoming a response.
 *
 * Handlers throw these so their happy path stays linear; the dispatcher is the
 * single place that renders one. Nothing here is ever logged with its message
 * interpolated into a format string that could carry request content.
 */
internal class ApiException(
    val code: ErrorCode,
    override val message: String,
    val violations: List<SchemaViolation> = emptyList(),
    val conflict: WriteOutcome.Conflict? = null,
    /**
     * Set on [ErrorCode.SERVER_UNREADABLE]: which row, which half would not
     * decode, and why. The name is nullable because the store's is — a record
     * with no name at all is one of the ways a row becomes unreadable.
     */
    val unreadable: Pair<String?, Unreadable>? = null,
    /** Set on [ErrorCode.FORBIDDEN]: the tier the route needed. Never the caller's own. */
    val requiredTier: String? = null,
    /** Response headers this failure needs — `Allow` on a 405, `ETag` on a conflict. */
    val headers: List<Pair<String, String>> = emptyList(),
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    fun toResponse(): Response = Response.json(code.status, render(), headers)

    private fun render(): Json =
        jsonObject {
            put(
                "error",
                jsonObject {
                    put("code", code.name)
                    put("message", message)
                    put("retryable", code.retryable)
                    put(
                        "violations",
                        if (violations.isEmpty()) {
                            Json.Null
                        } else {
                            Json.Arr(violations.map(::renderViolation))
                        },
                    )
                    putObject("conflict", conflict) { detail ->
                        put("name", detail.name.value)
                        put("reason", detail.reason)
                        put("currentResourceVersion", detail.currentResourceVersion?.token)
                        put("explanation", explain(detail.reason))
                    }
                    put("requiredTier", requiredTier?.let(Json::Str) ?: Json.Null)
                    putObject("unreadable", unreadable) { (name, detail) ->
                        put("name", name)
                        put("part", detail.part)
                        put("reason", detail.reason)
                        put("retryable", detail.retryable)
                    }
                },
            )
        }

    private fun renderViolation(violation: SchemaViolation): Json =
        jsonObject {
            put("field", violation.field)
            put("problem", violation.problem)
            putObject("location", violation.location) { location ->
                put("source", location.source)
                put("line", location.line)
                put("column", location.column)
            }
        }

    companion object {
        fun notFound(what: String): ApiException = ApiException(ErrorCode.NOT_FOUND, what)

        fun unreadable(row: UnreadableServer): ApiException = unreadable(row.name, row.unreadable)

        /**
         * The store has the row and cannot decode the half this request needed.
         *
         * The message says what is *unknown* rather than what is wrong, because
         * the container is very probably running exactly as it was and what is
         * broken is the record of it. An operator who reads this as "the server is
         * broken" reaches for a restart, which is the one thing that would turn a
         * bookkeeping problem into a player-facing one.
         */
        fun unreadable(
            name: String?,
            detail: Unreadable,
        ): ApiException =
            ApiException(
                code = ErrorCode.SERVER_UNREADABLE,
                message =
                    when {
                        // Not reachable by naming a server — a nameless row cannot
                        // match a path segment — but the type admits it, and a
                        // message that pretended otherwise would be the placeholder
                        // identity `:store` refuses to invent.
                        name == null -> {
                            "a stored row has no name at all and its definition could not be read. Nothing can " +
                                "refer to it: it cannot be fetched, repaired or deleted through this API, " +
                                "because every one of those names a server. It has to be repaired in the store"
                        }

                        detail.part == StatePart.DESIRED -> {
                            "`$name` is stored and its definition could not be read, so there is nothing to " +
                                "serve for it. The server may still be running: this is a fault in the record, " +
                                "not evidence about the container. Repair it by PUTting a valid definition with " +
                                "`If-Match: *`, or fix the row in the store"
                        }

                        else -> {
                            "`$name` is stored and its last observation could not be read, so what the server " +
                                "is actually doing is not known. The definition is intact and the container is " +
                                "very probably running as it was; the reconcile loop will record a fresh " +
                                "observation over the broken one"
                        }
                    },
                unreadable = name to detail,
            )

        fun badRequest(problem: String): ApiException = ApiException(ErrorCode.BAD_REQUEST, problem)

        /**
         * A [WriteOutcome.Conflict] as a 409, with the current version in the body
         * *and* in an `ETag` so a client can retry from either.
         *
         * A version mismatch arising from `If-Match` would also be a defensible
         * 412. It is a 409 on purpose: the dashboard then has exactly one branch
         * for "somebody else got there first", whatever the underlying reason, and
         * `error.conflict.reason` says which reason it was.
         */
        fun conflict(outcome: WriteOutcome.Conflict): ApiException =
            ApiException(
                code = ErrorCode.CONFLICT,
                message = "`${outcome.name}`: ${explain(outcome.reason)}",
                conflict = outcome,
                headers =
                    outcome.currentResourceVersion
                        ?.let { listOf(HeaderNames.ETAG to "\"${it.token}\"") }
                        .orEmpty(),
            )

        private fun explain(reason: ConflictReason): String =
            when (reason) {
                ConflictReason.ALREADY_EXISTS -> {
                    "a server with this name already exists"
                }

                ConflictReason.VERSION_MISMATCH -> {
                    "the stored definition has changed since the version you sent in If-Match. " +
                        "Re-read it, re-apply your edit and try again"
                }

                ConflictReason.NOT_FOUND -> {
                    "no server is stored under this name"
                }

                ConflictReason.TERMINATING -> {
                    "a delete is in progress for this name and the drain has not finished. The name " +
                        "cannot be reused until it does: creating a replacement while the old container " +
                        "may still have players on it is what the drain protocol exists to prevent"
                }

                ConflictReason.NOT_DELETED -> {
                    "this name has not been deleted, so there is nothing to complete"
                }

                ConflictReason.KIND_MISMATCH -> {
                    "this name is held by a server of a different kind. Changing kind in place is a " +
                        "recreate: delete the old one and wait for it to be removed first"
                }

                ConflictReason.DEFINITION_CHANGED -> {
                    "the definition moved on while an observation was being recorded"
                }
            }
    }
}
