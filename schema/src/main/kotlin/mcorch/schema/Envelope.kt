package mcorch.schema

/**
 * The schema version a document is written in.
 *
 * Every definition carries one, from the very first kind, so that a later
 * breaking change has something to dispatch on: the parser picks a reader per
 * (version, kind) pair, and a converted document is re-emitted at the current
 * version with a log line. Versions are never reinterpreted in place.
 *
 * `v1alpha1` means exactly what it says: fields may still be removed or
 * retyped, and when they are, the old version keeps parsing through a
 * conversion for at least one release.
 */
public enum class SchemaVersion(
    public val wireValue: String,
) {
    V1ALPHA1("mcorch.dev/v1alpha1"),
    ;

    override fun toString(): String = wireValue

    public companion object {
        /** The version new documents should be written in. */
        public val CURRENT: SchemaVersion = V1ALPHA1

        public fun fromWire(raw: String): SchemaVersion? = entries.firstOrNull { it.wireValue == raw }

        public fun supported(): List<String> = entries.map { it.wireValue }
    }
}

/** The declarable kinds. One per server type; the parser dispatches on it. */
public enum class ServerKind(
    public val wireValue: String,
) {
    PAPER_SERVER("PaperServer"),
    ;

    override fun toString(): String = wireValue

    public companion object {
        public fun fromWire(raw: String): ServerKind? = entries.firstOrNull { it.wireValue == raw }

        public fun supported(): List<String> = entries.map { it.wireValue }
    }
}

/**
 * Identity and selector metadata. Common to every kind.
 *
 * `generation` is deliberately absent: it is assigned by the store when a
 * definition is written, never declared by an operator, and
 * [ServerStatus.observedGeneration] is what the loop compares against.
 */
public data class ObjectMetadata(
    val name: ResourceName,
    val labels: Map<String, String> = emptyMap(),
)

/**
 * Where the operator wants this server to run.
 *
 * [node] is a *pin*, and it is optional: with it unset the scheduler chooses.
 * Nothing else in a definition may refer to a node — single host is today's
 * deployment, not an assumption the schema is allowed to make.
 */
public data class PlacementSpec(
    val node: NodeName? = null,
)

/** Marker for the per-kind spec bodies, so [ServerDefinition] can stay generic. */
public sealed interface ServerSpec

/**
 * A parsed, fully-defaulted, fully-validated definition. Every optional field
 * has already been resolved to a concrete value here: the reconciler reads a
 * spec, it never re-derives one.
 */
public sealed interface ServerDefinition {
    public val apiVersion: SchemaVersion
    public val kind: ServerKind
    public val metadata: ObjectMetadata
    public val spec: ServerSpec
}
