package mcorch.store.sqlite

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Temporary directories and the stores in them, cleaned up after each test.
 *
 * A helper rather than JUnit's `@TempDir` because a test needs to close a store,
 * reopen the *same* directory, and assert what survived — which is the whole
 * point of a migration test.
 */
internal class TempStores {
    private val directories = mutableListOf<Path>()
    private val opened = mutableListOf<EmbeddedStore>()

    /** A fixed clock, so `createdAt` and `deletedAt` are comparable rather than merely present. */
    val clock: Clock = Clock.fixed(Instant.parse("2026-07-26T10:15:30.123456789Z"), ZoneOffset.UTC)

    fun directory(): Path = Files.createTempDirectory("mcorch-store").also { directories.add(it) }

    fun config(
        directory: Path,
        changeLogRetention: Int = 10_000,
    ): EmbeddedStoreConfig =
        EmbeddedStoreConfig(
            directory = directory,
            clock = clock,
            changeLogRetention = changeLogRetention,
        )

    fun open(
        directory: Path,
        changeLogRetention: Int = 10_000,
    ): EmbeddedStore = EmbeddedStore.open(config(directory, changeLogRetention)).also { opened += it }

    fun cleanUp() {
        opened.forEach { runCatching { it.close() } }
        opened.clear()
        directories.forEach { it.toFile().deleteRecursively() }
        directories.clear()
    }
}
