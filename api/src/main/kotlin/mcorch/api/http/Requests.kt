package mcorch.api.http

import mcorch.schema.ParseResult
import mcorch.schema.ResourceName
import mcorch.schema.SchemaViolation
import mcorch.schema.SecretRef
import mcorch.schema.ServerDefinition
import mcorch.schema.yaml.ServerDefinitionParser
import mcorch.store.Precondition
import mcorch.store.ResourceVersion

/** Turning request material into the types the rest of the system speaks. */
internal object Requests {
    /**
     * The label the parser attaches to every [mcorch.schema.SourceLocation] it
     * produces for a request body, so a violation reads `request-body:12:9` and a
     * form knows the line and column refer to what it sent rather than to a file.
     */
    const val BODY_SOURCE: String = "request-body"

    /**
     * Media types accepted for a definition.
     *
     * JSON is on the list and needs no separate code path: YAML 1.2 is a strict
     * superset of JSON, and `:schema` parses YAML 1.2 through the composer. A
     * browser can therefore `JSON.stringify` a definition and get back the same
     * field paths, the same aggregate-every-problem behaviour, and line/column
     * positions into its own JSON text.
     *
     * The corollary, and it is worth stating rather than discovering: a body sent
     * as `application/json` that is YAML but not JSON is accepted. This does not
     * police the syntax it was told to expect, it validates the document.
     */
    val DEFINITION_MEDIA_TYPES: Set<String> =
        setOf(
            "application/json",
            "application/yaml",
            "application/x-yaml",
            "text/yaml",
            "text/x-yaml",
            "text/plain",
            // A body sent by `fetch` with no explicit Content-Type.
            "",
        )

    fun name(
        request: Request,
        parameter: String = "name",
    ): ResourceName {
        val raw =
            request.pathParams[parameter]
                ?: throw ApiException.badRequest("the path has no `$parameter` segment")
        return ResourceName.of(raw).getOrElse {
            throw ApiException(
                ErrorCode.BAD_REQUEST,
                "`$raw` is not a usable server name: ${it.message}",
            )
        }
    }

    /**
     * The two path segments of `/api/v1/secrets/{name}/{key}`, as coordinates.
     *
     * The rule is `:schema`'s and the wording is this route's. `SecretRef.of`
     * explains a rejection in terms of a definition file — it tells the reader
     * that a coordinate is where material lands "when someone abbreviates the
     * reference away" — and that mistake is not reachable over a URL, where
     * there is no reference to abbreviate. Relaying it would answer a client
     * with advice about a file it never wrote.
     *
     * Neither segment is quoted back, matching every other message about a
     * secret coordinate in this system.
     */
    fun secretRef(request: Request): SecretRef {
        val name = request.pathParams["name"].orEmpty()
        val key = request.pathParams["key"].orEmpty()
        if (ResourceName.of(name).isFailure) {
            throw ApiException(
                ErrorCode.BAD_REQUEST,
                "the `name` segment of the path is not a usable secret name: it must be ${ResourceName.SYNTAX}",
            )
        }
        // Only the key can be left: the name was just accepted by the same rule.
        return SecretRef.of(name, key).getOrElse {
            throw ApiException(
                ErrorCode.BAD_REQUEST,
                "the `key` segment of the path is not a usable secret key: it must be ${SecretRef.KEY_SYNTAX}",
            )
        }
    }

    /**
     * Parses a definition out of a request body, or throws a 422 carrying *every*
     * violation the schema found.
     *
     * All of them, not the first: the schema goes to real trouble to aggregate,
     * and an API that reports one problem per round trip makes a five-field
     * mistake a five-request conversation.
     */
    fun definition(request: Request): ServerDefinition {
        val mediaType = request.contentType()
        if (mediaType !in DEFINITION_MEDIA_TYPES) {
            throw ApiException(
                ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                "a definition must be sent as one of " +
                    "${DEFINITION_MEDIA_TYPES.filter { it.isNotEmpty() }.sorted().joinToString(", ")}, " +
                    "found `$mediaType`",
            )
        }
        if (request.body.isEmpty()) {
            throw ApiException(
                ErrorCode.VALIDATION_FAILED,
                "the request body is empty",
                violations = listOf(SchemaViolation("<document>", "the request body is empty")),
            )
        }
        return when (val result = ServerDefinitionParser.parse(request.bodyText(), BODY_SOURCE)) {
            is ParseResult.Valid -> result.value
            is ParseResult.Invalid -> throw validationFailed(result.violations)
        }
    }

    fun validationFailed(violations: List<SchemaViolation>): ApiException =
        ApiException(
            code = ErrorCode.VALIDATION_FAILED,
            message =
                if (violations.size == 1) {
                    "the definition has 1 problem"
                } else {
                    "the definition has ${violations.size} problems"
                },
            violations = violations,
        )

    /**
     * `If-Match` as a store [Precondition].
     *
     * Three cases. A quoted version becomes [Precondition.AtVersion] — the whole
     * point of the header. `*` becomes [Precondition.None] and is checked against
     * existence by the caller, which is what RFC 9110 says `*` means. Absent is
     * [Precondition.None], and whether that is acceptable is the caller's call:
     * `PUT` refuses it with a 428, `DELETE` allows it.
     */
    fun precondition(request: Request): IfMatch {
        val raw = request.header(HeaderNames.IF_MATCH)?.trim() ?: return IfMatch.Absent
        if (raw == "*") return IfMatch.Any
        val tokens =
            raw
                .split(',')
                .map {
                    it
                        .trim()
                        .removePrefix("W/")
                        .trim()
                        .removeSurrounding("\"")
                }.filter { it.isNotEmpty() }
        val token =
            tokens.singleOrNull()
                ?: throw ApiException.badRequest(
                    "${HeaderNames.IF_MATCH} must carry exactly one entity tag or `*`, found `$raw`",
                )
        return IfMatch.Version(ResourceVersion(token))
    }

    internal sealed interface IfMatch {
        data object Absent : IfMatch

        /** `*`: the caller requires the name to exist but does not care which version. */
        data object Any : IfMatch

        data class Version(
            val resourceVersion: ResourceVersion,
        ) : IfMatch

        fun toPrecondition(): Precondition =
            when (this) {
                Absent, Any -> Precondition.None
                is Version -> Precondition.AtVersion(resourceVersion)
            }
    }
}
