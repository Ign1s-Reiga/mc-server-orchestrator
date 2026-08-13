package mcorch.api

import mcorch.core.console.ConsoleUnavailable
import mcorch.core.console.ServerConsole
import mcorch.schema.PaperServerDefinition

/**
 * A console with nothing behind it.
 *
 * The API tests run against a real store and a real HTTP socket, but no
 * containerd — so a console command has no server to reach. This answers as the
 * real seam does when a workload is not running, which is the state those tests
 * are actually in.
 *
 * It means the API suite covers the gates, the audit and the error mapping, and
 * **not** that a command reaches a Minecraft server. That is an integration test's
 * job, and `spec/README.md` records it as owed.
 */
internal object RefusingConsole : ServerConsole {
    override suspend fun run(
        definition: PaperServerDefinition,
        command: String,
    ): String = throw ConsoleUnavailable("`${definition.metadata.name.value}` has no workload in this test")
}
