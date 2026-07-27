package mcorch.schema.fixtures

import java.net.JarURLConnection
import java.net.URL
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * The example server definitions, as a published surface of `:schema`.
 *
 * These files are `:schema`'s test data and also the only copy `:store`'s
 * round-trip tests read — the store's job is to hold what the parser produces,
 * and a second copy written by hand would test the drift instead. Sharing them
 * needs one module to own the layout, and this is it: callers name an example,
 * never a path. Moving `examples/` or renaming a directory under it is then a
 * change to this file, not a silent break in somebody else's module.
 *
 * Nothing here parses. [read] returns the raw document so `:schema` can test its
 * own parser against it and `:store` can hand it to that parser; a fixture
 * pre-parsed here would hide which module actually did the parsing.
 */
public object ExampleDefinitions {
    private const val ROOT = "/examples"

    /**
     * An example that parses, by file name — `full.yaml`, `minimal.yaml`.
     *
     * Fails loudly and by name if it is absent. A fixture that has moved must
     * never read as "no examples to check".
     */
    public fun valid(name: String): String = read("valid/$name")

    /** An example that must be rejected, by file name. */
    public fun invalid(name: String): String = read("invalid/$name")

    /** Any example, by a path relative to the examples root. */
    public fun read(path: String): String = resource(path).readText()

    /**
     * The `.yaml` file names in the `valid` or `invalid` directory, sorted.
     *
     * Enumerating is what lets `:schema` assert that every invalid example has a
     * case covering it, so an example added without a test fails rather than
     * sitting unexercised.
     */
    public fun names(directory: String): List<String> {
        val url = resource(directory)
        val files =
            when (url.protocol) {
                // What an IDE runner gives, putting source set output
                // directories on the classpath instead of building a jar.
                "file" -> Path.of(url.toURI()).listDirectoryEntries().map(Path::name)

                // What Gradle gives — both here and in a consuming module, since
                // `:schema:test` resolves its own fixtures through the same
                // testFixturesJar that `:store:test` does. This is the branch
                // `./gradlew test` exercises.
                "jar" -> jarEntryNames(url)

                else -> error("unsupported example source: $url")
            }
        return files.filter { it.endsWith(".yaml") }.sorted()
    }

    private fun resource(path: String): URL =
        ExampleDefinitions::class.java.getResource("$ROOT/$path")
            ?: error("missing example: $ROOT/$path")

    private fun jarEntryNames(url: URL): List<String> {
        val connection = url.openConnection() as JarURLConnection
        // Deliberately not closed: URLConnection caches the JarFile and hands
        // the same instance to the next caller, so closing it here breaks every
        // later read of the same jar.
        val prefix = connection.entryName.removeSuffix("/") + "/"
        return connection.jarFile
            .entries()
            .asSequence()
            .map { it.name }
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .filter { it.isNotEmpty() && !it.contains('/') }
            .toList()
    }
}
