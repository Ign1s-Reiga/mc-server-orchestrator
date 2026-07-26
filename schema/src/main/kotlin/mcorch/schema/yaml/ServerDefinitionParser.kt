package mcorch.schema.yaml

import mcorch.schema.LabelSyntax
import mcorch.schema.ObjectMetadata
import mcorch.schema.ParseResult
import mcorch.schema.ResourceName
import mcorch.schema.SchemaVersion
import mcorch.schema.SchemaViolation
import mcorch.schema.ServerDefinition
import mcorch.schema.ServerKind
import mcorch.schema.SourceLocation
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.lowlevel.Compose
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException
import org.snakeyaml.engine.v2.exceptions.YamlEngineException
import org.snakeyaml.engine.v2.nodes.Node

/**
 * The one way a definition enters this system.
 *
 * Parsing never throws and never half-succeeds: it returns either a fully
 * validated, fully defaulted [ServerDefinition], or every problem it found.
 * There is no "valid enough to reconcile" state — the loop is not a place to
 * discover that a port is out of range.
 *
 * Version dispatch happens here. A document declares `apiVersion`, this picks
 * the reader for that version, and (once there is more than one version) a
 * conversion runs on the way out. Nothing downstream ever sees an old shape.
 */
public object ServerDefinitionParser {
    /** Parses a single-document input. Multiple documents are an error here; use [parseAll]. */
    public fun parse(
        yaml: String,
        source: String = "<yaml>",
    ): ParseResult<ServerDefinition> {
        val sink = ViolationSink(source)
        val nodes =
            composeAll(yaml, source, sink)
                ?: return ParseResult.Invalid(sink.violations)
        if (nodes.isEmpty()) {
            return ParseResult.Invalid(
                listOf(SchemaViolation("<document>", "the input contains no YAML document")),
            )
        }
        if (nodes.size > 1) {
            return ParseResult.Invalid(
                listOf(
                    SchemaViolation(
                        "<document>",
                        "the input contains ${nodes.size} YAML documents; use parseAll for multi-document files",
                        sink.locationOf(nodes[1]),
                    ),
                ),
            )
        }
        val definition = readDocument(nodes[0], sink)
        return if (definition == null || sink.size > 0) {
            ParseResult.Invalid(sink.violations)
        } else {
            ParseResult.Valid(definition)
        }
    }

    /** Parses a `---`-separated file. Names must be unique within the input. */
    public fun parseAll(
        yaml: String,
        source: String = "<yaml>",
    ): ParseResult<List<ServerDefinition>> {
        val sink = ViolationSink(source)
        val nodes = composeAll(yaml, source, sink) ?: return ParseResult.Invalid(sink.violations)
        val definitions = mutableListOf<ServerDefinition>()
        val seen = mutableMapOf<ResourceName, Int>()
        nodes.forEachIndexed { index, node ->
            val definition = readDocument(node, sink)
            if (definition != null) {
                val previous = seen.put(definition.metadata.name, index)
                if (previous != null) {
                    sink.add(
                        "metadata.name",
                        "`${definition.metadata.name}` is declared by more than one document in this input",
                        node,
                    )
                }
                definitions += definition
            }
        }
        return if (sink.size > 0) ParseResult.Invalid(sink.violations) else ParseResult.Valid(definitions)
    }

    private fun composeAll(
        yaml: String,
        source: String,
        sink: ViolationSink,
    ): List<Node>? {
        val settings =
            LoadSettings
                .builder()
                .setLabel(source)
                .setUseMarks(true)
                // Duplicate keys are detected while indexing a mapping instead,
                // so they arrive as an ordinary violation with a location and
                // aggregate with everything else rather than aborting the parse.
                .setAllowDuplicateKeys(true)
                .setAllowRecursiveKeys(false)
                .build()
        return try {
            Compose(settings).composeAllFromString(yaml).toList()
        } catch (failure: MarkedYamlEngineException) {
            val mark = failure.problemMark.orElse(null)
            val location = mark?.let { SourceLocation(source, it.line + 1, it.column + 1) }
            sink.addAt("<document>", "is not valid YAML: ${failure.problem.trim()}", location)
            null
        } catch (failure: YamlEngineException) {
            sink.addAt("<document>", "is not valid YAML: ${failure.message.orEmpty().trim()}", null)
            null
        }
    }

    private fun readDocument(
        node: Node,
        sink: ViolationSink,
    ): ServerDefinition? {
        val root = MappingReader.of("", node, sink) ?: return null
        val apiVersion =
            root.enum(
                "apiVersion",
                required = true,
                supported = SchemaVersion.supported(),
                lookup = SchemaVersion::fromWire,
            )
        val kind =
            root.enum(
                "kind",
                required = true,
                supported = ServerKind.supported(),
                lookup = ServerKind::fromWire,
            )
        val metadata = root.mapping("metadata", required = true)?.let(::readMetadata)
        val specNode = root.node("spec")
        if (specNode == null && !root.isPresent("spec")) {
            sink.add("spec", "is required", node)
        }
        root.done()

        if (apiVersion == null || kind == null || specNode == null) return null
        // A bad name must not hide everything else in the file: keep validating
        // the spec against a placeholder. The parse already carries the name
        // violation, so this can never be handed out as a valid definition.
        val resolvedMetadata = metadata ?: ObjectMetadata(PLACEHOLDER_NAME)
        return when (apiVersion) {
            // One reader per (version, kind). A future version reads with its own
            // reader and converts here, so old documents keep loading.
            SchemaVersion.V1ALPHA1 -> {
                when (kind) {
                    ServerKind.PAPER_SERVER -> PaperServerReader(sink).read(apiVersion, resolvedMetadata, specNode)
                }
            }
        }
    }

    private fun readMetadata(reader: MappingReader): ObjectMetadata? {
        val name = reader.value("name", required = true, parse = ResourceName::of)
        val labels =
            reader.stringMap(
                "labels",
                keyProblem = LabelSyntax::keyProblem,
                valueProblem = LabelSyntax::valueProblem,
            )
        reader.done()
        return ObjectMetadata(name ?: return null, labels)
    }

    /** Stands in for a rejected `metadata.name` so the rest of the document is still checked. */
    private val PLACEHOLDER_NAME: ResourceName =
        ResourceName.of("unnamed").getOrElse { error("placeholder name is invalid: ${it.message}") }
}
