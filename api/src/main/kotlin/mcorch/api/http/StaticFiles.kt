package mcorch.api.http

import java.nio.file.Files
import java.nio.file.Path

/**
 * The dashboard bundle, served from the same origin as the API.
 *
 * `spec/08-origin-and-client.md` §1.1. Same-origin is not tidiness: a cross-site
 * dashboard needs `SameSite=None`, which needs `Secure`, which needs TLS — so
 * serving the bundle here is what keeps the loopback plain-HTTP deployment
 * viable. It is also what removes the intermediaries that would otherwise hold
 * copies of every request, which matters more once the console ships.
 *
 * Off unless [MCORCH_API_STATIC_ROOT][mcorch.api.ApiConfig.staticRoot] is set, so
 * an API-only deployment is unchanged.
 *
 * ## What it will not serve
 *
 * **Anything outside the root.** A request path is resolved against the root and
 * then checked to still be under it after normalisation, which is the check that
 * matters: `..` segments, an absolute path, and a symlink pointing out all fail
 * it. The path is normalised *before* the comparison rather than pattern-matched
 * for `..`, because a blocklist of dangerous spellings is a list somebody finds a
 * new entry for.
 *
 * **Anything under `/api/` or `/healthz`.** Those belong to the router, and a
 * fallback that could answer them would let a file on disk shadow an endpoint —
 * silently, and only on deployments that happen to have the file.
 *
 * ## Percent-encoding is not decoded
 *
 * The dispatcher passes `requestURI.rawPath`, and this resolves it as-is. That is
 * deliberate: decoding is where traversal bugs live — `%2e%2e` becoming `..`
 * after the check rather than before it is the classic shape — and not decoding
 * removes the question entirely.
 *
 * The cost is real and small: a bundle cannot contain a filename needing an
 * escape, so no spaces and no non-ASCII names. Build tools emit hashed ASCII
 * filenames, so this has not been a constraint in practice; if it ever is, decode
 * **before** resolving and keep the real-path check as the thing that decides.
 *
 * ## The SPA fallback
 *
 * A path that names no file falls back to `index.html`, because client-side
 * routing means `/servers/survival-01` is a real dashboard URL with no file
 * behind it. That fallback is why the `/api/` exclusion above has to be explicit:
 * without it, a mistyped endpoint would return the dashboard's HTML with a `200`
 * instead of a `404`, and a client would parse a page as JSON.
 */
internal class StaticFiles(
    root: Path,
) {
    private val root: Path = root.toAbsolutePath().normalize()

    /**
     * The file to serve for [path], or null to let the router's 404 stand.
     *
     * Null rather than an exception for a miss: not every unmatched path is an
     * error here, and the caller already has a 404 to fall back on.
     */
    fun resolve(path: String): Served? {
        if (isReserved(path)) return null
        val requested = path.trimStart('/').ifEmpty { INDEX }
        val candidate = fileUnderRoot(requested)
        if (candidate != null) return Served(candidate, contentTypeOf(candidate))
        // Client-side routing: a dashboard URL with no file behind it is the SPA's
        // own, and it renders it from index.html.
        val index = fileUnderRoot(INDEX) ?: return null
        return Served(index, HTML)
    }

    /**
     * [relative] resolved under the root, or null if it escapes or is not a
     * regular file.
     */
    private fun fileUnderRoot(relative: String): Path? {
        val resolved =
            try {
                root.resolve(relative).normalize()
            } catch (invalid: java.nio.file.InvalidPathException) {
                // A path the filesystem cannot express at all — a NUL byte, say.
                // Nothing to serve, and nothing worth reporting to the caller.
                return null
            }
        if (!resolved.startsWith(root)) return null
        // `toRealPath` follows symlinks, so a link inside the root pointing out of
        // it is caught here rather than by the check above.
        val real =
            try {
                resolved.toRealPath()
            } catch (missing: java.io.IOException) {
                return null
            }
        if (!real.startsWith(root)) return null
        return real.takeIf { Files.isRegularFile(it) }
    }

    private fun isReserved(path: String): Boolean = path.startsWith("/api/") || path == "/api" || path == "/healthz"

    /** One file to serve, and what to call it. */
    data class Served(
        val file: Path,
        val contentType: String,
    )

    companion object {
        private const val INDEX = "index.html"
        private const val HTML = "text/html; charset=utf-8"

        /**
         * By extension, and deliberately short.
         *
         * Anything unrecognised is `application/octet-stream`, which a browser
         * downloads rather than executes — the safe direction for a file this
         * server does not understand.
         */
        private val TYPES: Map<String, String> =
            mapOf(
                "html" to HTML,
                "js" to "text/javascript; charset=utf-8",
                "mjs" to "text/javascript; charset=utf-8",
                "css" to "text/css; charset=utf-8",
                "json" to "application/json; charset=utf-8",
                "svg" to "image/svg+xml",
                "png" to "image/png",
                "jpg" to "image/jpeg",
                "jpeg" to "image/jpeg",
                "webp" to "image/webp",
                "ico" to "image/x-icon",
                "woff" to "font/woff",
                "woff2" to "font/woff2",
                "map" to "application/json; charset=utf-8",
                "txt" to "text/plain; charset=utf-8",
            )

        fun contentTypeOf(file: Path): String =
            TYPES[
                file.fileName
                    .toString()
                    .substringAfterLast('.', "")
                    .lowercase(),
            ]
                ?: "application/octet-stream"
    }
}
