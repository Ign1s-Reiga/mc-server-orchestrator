package mcorch.schema.yaml

import java.io.File

/** The example definitions in `src/test/resources/examples`, loaded by name. */
internal object Fixtures {
    fun load(name: String): String =
        Fixtures::class.java
            .getResource("/examples/$name")
            ?.readText()
            ?: error("missing fixture: examples/$name")

    fun names(directory: String): List<String> {
        val url =
            Fixtures::class.java.getResource("/examples/$directory")
                ?: error("missing fixture directory: examples/$directory")
        val files = File(url.toURI()).listFiles().orEmpty()
        return files.map { it.name }.filter { it.endsWith(".yaml") }.sorted()
    }
}
