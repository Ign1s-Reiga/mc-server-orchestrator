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

    fun secretRef(request: Request): SecretRef {
        val name = request.pathParams["name"].orEmpty()
        val key = request.pathParams["key"].orEmpty()
        return SecretRef.of(name, key).getOrElse {
            throw ApiException(ErrorCode.BAD_REQUEST, "the secret reference is not usable: ${it.message}")
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
