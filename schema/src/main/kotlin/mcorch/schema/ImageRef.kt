package mcorch.schema

/**
 * A container image reference, already split into its parts so nothing
 * downstream has to re-parse a string.
 *
 * A reference must be pinned: either an explicit tag or a digest. `:latest` is
 * rejected — reconcile compares desired against observed, and a moving tag makes
 * "the image changed" unobservable, which turns a routine pass into a surprise
 * restart of a server with players on it.
 *
 * [registry] is null when the reference did not name one. It is deliberately not
 * defaulted to `docker.io` here: which registry an unqualified name resolves to
 * is the runtime's business, not the schema's.
 */
public sealed interface ImageRef {
    public val registry: String?
    public val repository: String

    /** The reference as the runtime should see it. */
    public val canonical: String

    public data class Tagged(
        override val registry: String?,
        override val repository: String,
        val tag: String,
    ) : ImageRef {
        override val canonical: String get() = "${registry?.plus("/").orEmpty()}$repository:$tag"

        override fun toString(): String = canonical
    }

    public data class Digested(
        override val registry: String?,
        override val repository: String,
        val digest: String,
    ) : ImageRef {
        override val canonical: String get() = "${registry?.plus("/").orEmpty()}$repository@$digest"

        override fun toString(): String = canonical
    }

    public companion object {
        private val COMPONENT = Regex("^[a-z0-9]+(?:(?:[._]|__|-+)[a-z0-9]+)*$")
        private val REGISTRY = Regex("^[A-Za-z0-9._-]+(?::[0-9]+)?$")
        private val TAG = Regex("^[A-Za-z0-9_][A-Za-z0-9._-]{0,127}$")
        private val DIGEST = Regex("^[a-z0-9]+(?:[.+_-][a-z0-9]+)*:[a-fA-F0-9]{32,}$")

        public fun parse(raw: String): Result<ImageRef> {
            val text = raw.trim()
            if (text.isEmpty()) return invalidValue("must not be empty")

            val atIndex = text.indexOf('@')
            val digest = if (atIndex >= 0) text.substring(atIndex + 1) else null
            val withoutDigest = if (atIndex >= 0) text.substring(0, atIndex) else text

            val lastColon = withoutDigest.lastIndexOf(':')
            val lastSlash = withoutDigest.lastIndexOf('/')
            val tag = if (lastColon > lastSlash) withoutDigest.substring(lastColon + 1) else null
            val name = if (lastColon > lastSlash) withoutDigest.substring(0, lastColon) else withoutDigest

            if (tag != null && digest != null) {
                return invalidValue(
                    "must be pinned by either a tag or a digest, not both, found `$raw`",
                )
            }
            if (tag == null && digest == null) {
                return invalidValue(
                    "must be pinned to a tag or a digest, for example " +
                        "`docker.io/itzg/minecraft-server:2026.6.1`, found `$raw`",
                )
            }

            val parts = name.split('/')
            val hasRegistry =
                parts.size > 1 &&
                    (parts[0].contains('.') || parts[0].contains(':') || parts[0] == "localhost")
            val registry = if (hasRegistry) parts[0] else null
            val repositoryParts = if (hasRegistry) parts.drop(1) else parts

            if (registry != null && !REGISTRY.matches(registry)) {
                return invalidValue("registry `$registry` is not a valid host[:port], found `$raw`")
            }
            if (repositoryParts.isEmpty() || repositoryParts.any { it.isEmpty() }) {
                return invalidValue("repository must not contain empty path segments, found `$raw`")
            }
            val repository = repositoryParts.joinToString("/")
            if (repository != repository.lowercase()) {
                return invalidValue("repository must be lowercase, found `$repository`")
            }
            repositoryParts.firstOrNull { !COMPONENT.matches(it) }?.let {
                return invalidValue("repository segment `$it` is not a valid image path component, found `$raw`")
            }

            if (digest != null) {
                if (!DIGEST.matches(digest)) {
                    return invalidValue(
                        "digest must look like `sha256:<hex>`, found `$digest`",
                    )
                }
                return Result.success(Digested(registry, repository, digest))
            }

            val resolvedTag = tag ?: return invalidValue("must be pinned to a tag or a digest, found `$raw`")
            if (resolvedTag == "latest") {
                return invalidValue(
                    "must not use the `latest` tag: a moving tag makes an image change invisible to reconcile " +
                        "and can restart a server with players on it. Pin a version or a digest",
                )
            }
            if (!TAG.matches(resolvedTag)) {
                return invalidValue("tag `$resolvedTag` is not a valid image tag")
            }
            return Result.success(Tagged(registry, repository, resolvedTag))
        }
    }
}
