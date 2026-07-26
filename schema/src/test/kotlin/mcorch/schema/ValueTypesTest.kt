package mcorch.schema

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class MemoryQuantityTest {
    @Test
    fun `binary and decimal suffixes are both understood`() {
        MemoryQuantity.parse("4Gi").getOrThrow().bytes shouldBe 4L * 1024 * 1024 * 1024
        MemoryQuantity.parse("512Mi").getOrThrow().bytes shouldBe 512L * 1024 * 1024
        MemoryQuantity.parse("2G").getOrThrow().bytes shouldBe 2_000_000_000L
        MemoryQuantity.parse("1.5Gi").getOrThrow().bytes shouldBe 1_610_612_736L
        MemoryQuantity.parse("1048576").getOrThrow().bytes shouldBe 1_048_576L
    }

    @Test
    fun `rendering round-trips`() {
        MemoryQuantity.parse("4Gi").getOrThrow().render() shouldBe "4Gi"
        MemoryQuantity.parse("3276Mi").getOrThrow().render() shouldBe "3276Mi"
    }

    @Test
    fun `nonsense is rejected with a message naming the expected shape`() {
        val failure = MemoryQuantity.parse("lots").exceptionOrNull()
        failure?.message?.contains("expected a memory quantity") shouldBe true
        MemoryQuantity.parse("-1Gi").isFailure shouldBe true
        MemoryQuantity.parse("4 GB").isFailure shouldBe true
    }
}

class DurationFormatTest {
    @Test
    fun `compound durations parse`() {
        DurationFormat.parse("30s").getOrThrow() shouldBe 30.seconds
        DurationFormat.parse("5m").getOrThrow() shouldBe 5.minutes
        DurationFormat.parse("1m30s").getOrThrow() shouldBe 90.seconds
    }

    @Test
    fun `rendering round-trips`() {
        DurationFormat.render(90.seconds) shouldBe "1m30s"
        DurationFormat.render(240.seconds) shouldBe "4m"
    }

    @Test
    fun `prose and ISO-8601 are rejected`() {
        DurationFormat.parse("5 minutes").isFailure shouldBe true
        DurationFormat.parse("PT5M").isFailure shouldBe true
    }
}

class ImageRefTest {
    @Test
    fun `a tagged reference splits into registry, repository and tag`() {
        val image = ImageRef.parse("docker.io/itzg/minecraft-server:2026.6.1").getOrThrow()

        val tagged = image.shouldBeInstanceOf<ImageRef.Tagged>()
        tagged.registry shouldBe "docker.io"
        tagged.repository shouldBe "itzg/minecraft-server"
        tagged.tag shouldBe "2026.6.1"
        tagged.canonical shouldBe "docker.io/itzg/minecraft-server:2026.6.1"
    }

    @Test
    fun `an unqualified reference leaves the registry unset rather than guessing`() {
        val image = ImageRef.parse("paper:1.21.8").getOrThrow()

        image.shouldBeInstanceOf<ImageRef.Tagged>().registry shouldBe null
        image.repository shouldBe "paper"
    }

    @Test
    fun `a digest reference parses`() {
        val digest = "sha256:" + "a".repeat(64)
        val image = ImageRef.parse("registry.example.com:5000/mc/paper@$digest").getOrThrow()

        val digested = image.shouldBeInstanceOf<ImageRef.Digested>()
        digested.registry shouldBe "registry.example.com:5000"
        digested.repository shouldBe "mc/paper"
        digested.digest shouldBe digest
    }

    @Test
    fun `unpinned, latest, uppercase and both-at-once references are rejected`() {
        ImageRef
            .parse("paper")
            .exceptionOrNull()
            ?.message
            ?.contains("must be pinned") shouldBe true
        ImageRef
            .parse("paper:latest")
            .exceptionOrNull()
            ?.message
            ?.contains("`latest`") shouldBe true
        ImageRef
            .parse("Paper:1.21.8")
            .exceptionOrNull()
            ?.message
            ?.contains("lowercase") shouldBe true
        ImageRef.parse("paper:1.21.8@sha256:${"a".repeat(64)}").isFailure shouldBe true
        ImageRef.parse("").isFailure shouldBe true
    }
}

class ResourceNameTest {
    @Test
    fun `RFC 1123 names are accepted`() {
        ResourceName.of("survival-01").getOrThrow().value shouldBe "survival-01"
    }

    @Test
    fun `names that could not become a container name are rejected`() {
        ResourceName
            .of("Survival")
            .exceptionOrNull()
            ?.message
            ?.contains("lowercase") shouldBe true
        ResourceName.of("survival_01").isFailure shouldBe true
        ResourceName.of("-survival").isFailure shouldBe true
        ResourceName.of("").isFailure shouldBe true
        ResourceName.of("a".repeat(64)).isFailure shouldBe true
    }
}
