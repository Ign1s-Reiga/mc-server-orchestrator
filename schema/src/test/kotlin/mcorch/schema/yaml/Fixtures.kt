package mcorch.schema.yaml

import mcorch.schema.fixtures.ExampleDefinitions

/**
 * The example definitions, loaded by name.
 *
 * The files live in this module's `testFixtures` source set rather than its
 * `test` one, because `:store` reads the same copy. [ExampleDefinitions] owns
 * where they are; this is only the local spelling of it.
 */
internal object Fixtures {
    fun load(name: String): String = ExampleDefinitions.read(name)

    fun names(directory: String): List<String> = ExampleDefinitions.names(directory)
}
