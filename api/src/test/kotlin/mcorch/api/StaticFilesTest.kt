package mcorch.api

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import mcorch.api.http.StaticFiles
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Serving the dashboard bundle.
 *
 * Most of this file is about what must **not** be served. A static handler is the
 * one part of an API that turns a request path into a filesystem path, which is
 * the oldest way to read a file nobody meant to publish.
 */
class StaticFilesTest {
    @TempDir
    lateinit var root: Path

    private fun bundle(): StaticFiles {
        root.resolve("index.html").writeText("<!doctype html><title>dashboard</title>")
        root.resolve("assets").createDirectories()
        root.resolve("assets/app.js").writeText("console.log(1)")
        return StaticFiles(root)
    }

    @Test
    fun `a file under the root is served with a content type`() {
        val files = bundle()

        files.resolve("/index.html").shouldNotBeNull().contentType shouldBe "text/html; charset=utf-8"
        files.resolve("/assets/app.js").shouldNotBeNull().contentType shouldBe "text/javascript; charset=utf-8"

        // `/` is the dashboard's own entry point.
        files.resolve("/").shouldNotBeNull().file shouldBe root.resolve("index.html").toRealPath()
    }

    @Test
    fun `a path that names no file falls back to index, because client routing owns it`() {
        val files = bundle()

        // `/servers/survival-01` is a real dashboard URL with no file behind it.
        val served = files.resolve("/servers/survival-01").shouldNotBeNull()
        served.file shouldBe root.resolve("index.html").toRealPath()
        served.contentType shouldBe "text/html; charset=utf-8"
    }

    @Test
    fun `nothing outside the root is reachable`() {
        val secret = root.parent.resolve("secret.txt")
        secret.writeText("not for publication")
        val files = bundle()

        listOf(
            "/../secret.txt",
            "/assets/../../secret.txt",
            "/%2e%2e/secret.txt",
            "/....//secret.txt",
            "//etc/passwd",
            "/./../../etc/passwd",
        ).forEach { attempt ->
            // Either refused outright or answered with the SPA's index — never the
            // file outside the root.
            files.resolve(attempt)?.file?.let { it shouldBe root.resolve("index.html").toRealPath() }
        }
    }

    @Test
    fun `a symlink pointing out of the root is not followed out of it`() {
        val outside = root.parent.resolve("outside.txt")
        outside.writeText("not for publication")
        val files = bundle()
        val link = root.resolve("escape.txt")
        try {
            Files.createSymbolicLink(link, outside)
        } catch (unsupported: UnsupportedOperationException) {
            return // No symlinks on this filesystem; the check below is untestable here.
        }

        // Resolves inside the root by string, and outside it in reality. The real
        // path is what decides, which is why the check is not textual.
        files.resolve("/escape.txt")?.file shouldBe root.resolve("index.html").toRealPath()
    }

    @Test
    fun `the API and the liveness probe are never shadowed by a file`() {
        // A bundle that happens to contain these paths must not answer them: a
        // mistyped endpoint returning the dashboard's HTML with a 200 would have a
        // client parsing a page as JSON.
        root.resolve("api/v1").createDirectories()
        root.resolve("api/v1/servers").writeText("not the API")
        root.resolve("healthz").writeText("not the probe")
        val files = StaticFiles(root)

        files.resolve("/api/v1/servers").shouldBeNull()
        files.resolve("/api/v1/nonexistent").shouldBeNull()
        files.resolve("/api").shouldBeNull()
        files.resolve("/healthz").shouldBeNull()
    }

    @Test
    fun `an unknown extension is not guessed at`() {
        root.resolve("index.html").writeText("<!doctype html>")
        root.resolve("payload.wasm").writeText("binary-ish")
        val files = StaticFiles(root)

        // Downloaded rather than executed, which is the safe direction for a file
        // this server does not understand.
        files.resolve("/payload.wasm").shouldNotBeNull().contentType shouldBe "application/octet-stream"
    }

    @Test
    fun `a root with no index serves nothing rather than half a dashboard`() {
        root.resolve("assets").createDirectories()
        root.resolve("assets/app.js").writeText("console.log(1)")
        val files = StaticFiles(root)

        // The asset is still there; the fallback is not, so an unknown path is a
        // 404 rather than an empty 200.
        files.resolve("/assets/app.js").shouldNotBeNull()
        files.resolve("/servers/survival-01").shouldBeNull()
        files.resolve("/").shouldBeNull()
    }
}
