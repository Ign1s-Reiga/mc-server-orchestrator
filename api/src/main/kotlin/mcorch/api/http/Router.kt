package mcorch.api.http

import com.sun.net.httpserver.HttpExchange

/** Whether a route may be reached without an operator credential. */
internal enum class Access {
    /**
     * Reachable by anyone who can reach the port. Only two things are: the
     * liveness probe and the CORS preflight, and neither reads any state.
     */
    PUBLIC,

    /** Requires an operator credential. Everything else. */
    OPERATOR,
}

internal sealed interface Segment {
    data class Literal(
        val text: String,
    ) : Segment

    data class Param(
        val name: String,
    ) : Segment
}

internal class Route(
    val method: String,
    /** `/api/v1/servers/{name}`. Braces mark a single path segment. */
    val pattern: String,
    val access: Access,
    val handler: suspend (Request, HttpExchange) -> HandlerResult,
) {
    val segments: List<Segment> =
        pattern
            .trim('/')
            .split('/')
            .filter { it.isNotEmpty() }
            .map { part ->
                if (part.startsWith("{") && part.endsWith("}")) {
                    Segment.Param(part.substring(1, part.length - 1))
                } else {
                    Segment.Literal(part)
                }
            }
}

internal sealed interface RouteMatch {
    data class Found(
        val route: Route,
        val params: Map<String, String>,
    ) : RouteMatch

    /** The path exists, the method does not. Carries what to put in `Allow`. */
    data class WrongMethod(
        val allowed: Set<String>,
    ) : RouteMatch

    data object NoRoute : RouteMatch
}

/**
 * Path-and-method dispatch.
 *
 * Deliberately dumb: literal segments and single-segment `{params}`, no
 * wildcards, no regex, no precedence rules to get wrong. The route table is a
 * dozen entries and is meant to be readable as the contract it implements.
 */
internal class Router(
    private val routes: List<Route>,
) {
    fun match(
        method: String,
        rawPath: String,
    ): RouteMatch {
        val parts =
            rawPath
                .trim('/')
                .split('/')
                .filter { it.isNotEmpty() }
                .map(::decodeSegment)
        val pathMatches = routes.filter { matches(it, parts) != null }
        if (pathMatches.isEmpty()) return RouteMatch.NoRoute
        val route = pathMatches.firstOrNull { it.method == method }
        if (route != null) {
            return RouteMatch.Found(route, matches(route, parts).orEmpty())
        }
        return RouteMatch.WrongMethod(pathMatches.mapTo(sortedSetOf()) { it.method })
    }

    private fun matches(
        route: Route,
        parts: List<String>,
    ): Map<String, String>? {
        if (route.segments.size != parts.size) return null
        val params = LinkedHashMap<String, String>()
        route.segments.forEachIndexed { index, segment ->
            when (segment) {
                is Segment.Literal -> if (segment.text != parts[index]) return null
                is Segment.Param -> params[segment.name] = parts[index]
            }
        }
        return params
    }

    /**
     * Percent-decodes one path segment. `+` is left alone, unlike in a query
     * string, because in a path it is a literal `+` and not a space.
     */
    private fun decodeSegment(raw: String): String {
        if (!raw.contains('%')) return raw
        return try {
            java.net.URLDecoder.decode(raw.replace("+", "%2B"), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            raw
        }
    }
}
