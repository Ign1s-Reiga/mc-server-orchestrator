package mcorch.api

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import mcorch.schema.fixtures.ExampleDefinitions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings

/**
 * The CRUD surface, over real HTTP against a real store.
 *
 * What is being checked here is mostly the seams: that a `WriteOutcome` becomes
 * the right status code, that a `resourceVersion` survives the round trip
 * through an `ETag` and back through an `If-Match`, and that a tombstoned
 * definition reads as a state rather than as an absence.
 */
class ServerCrudTest {
    private lateinit var api: TestApi

    @BeforeEach
    fun start() {
        api = TestApi.start()
    }

    @AfterEach
    fun stop() {
        api.close()
    }

    private val minimal: String get() = ExampleDefinitions.valid("minimal.yaml")

    @Test
    fun `a valid definition round-trips through create, read and replace`() {
        val created = api.call("POST", "/api/v1/servers", minimal)
        created.status shouldBe 201
        created.header("Location") shouldBe "/api/v1/servers/survival-01"
        val etag = created.header("ETag").shouldNotBeNull()

        val fetched = api.call("GET", "/api/v1/servers/survival-01")
        fetched.status shouldBe 200
        fetched.header("ETag") shouldBe etag

        val document = fetched.json()
        document["name"] shouldBe "survival-01"
        document["kind"] shouldBe "PaperServer"
        val metadata = document["metadata"] as Map<*, *>
        metadata["generation"] shouldBe 1
        metadata["terminating"] shouldBe false
        // Nothing has observed it yet, and that is a state rather than a gap.
        document["status"] shouldBe null
        (document["display"] as Map<*, *>)["state"] shouldBe "PENDING"

        // The `definition` sub-document is valid input, unchanged. Replaying it
        // must leave the generation alone: the store only moves a generation when
        // the *spec* differs, so an unchanged generation is proof that what came
        // out re-parsed to exactly what went in.
        val replayed = dump(document["definition"])
        val replaced = api.call("PUT", "/api/v1/servers/survival-01", replayed, headers = listOf("If-Match" to etag))
        replaced.status shouldBe 200
        ((replaced.json()["metadata"] as Map<*, *>)["generation"]) shouldBe 1
    }

    @Test
    fun `a JSON body is accepted and reports violations against its own line numbers`() {
        // YAML 1.2 is a strict superset of JSON, so this needs no separate parser
        // and gets the same field paths — which is the whole reason a browser can
        // POST `JSON.stringify(definition)` and render a form from the answer.
        val json =
            """
            {"apiVersion":"mcorch.dev/v1alpha1","kind":"PaperServer",
             "metadata":{"name":"survival-json"},
             "spec":{"eulaAccepted":true,"image":"docker.io/itzg/minecraft-server:2026.6.1",
                     "paper":{"minecraftVersion":"1.21.8"},"resources":{"memory":"4Gi"}}}
            """.trimIndent()
        api.call("POST", "/api/v1/servers", json, contentType = "application/json").status shouldBe 201

        val rejected =
            api.call(
                "POST",
                "/api/v1/servers",
                """
                {"apiVersion":"mcorch.dev/v1alpha1","kind":"PaperServer",
                "metadata":{"name":"bad-json"},
                "spec":{"eulaAccepted":true,"image":"docker.io/itzg/minecraft-server:latest",
                        "paper":{"minecraftVersion":"1.21.8"},"resources":{"memory":"4Gi"}}}
                """.trimIndent(),
                contentType = "application/json",
            )
        rejected.status shouldBe 422
        val violation = violations(rejected).single()
        violation["field"] shouldBe "spec.image"
        val location = violation["location"] as Map<*, *>
        location["source"] shouldBe "request-body"
        // A position into the JSON the client sent, not into some re-serialised form.
        (location["line"] as Int) shouldBe 3
    }

    @Test
    fun `an invalid definition returns every violation with its field path`() {
        val rejected = api.call("POST", "/api/v1/servers", ExampleDefinitions.invalid("many-problems.yaml"))
        rejected.status shouldBe 422
        rejected.errorCode() shouldBe "VALIDATION_FAILED"

        val reported = violations(rejected)
        // All of them in one answer. An API that reported the first would make a
        // seven-field mistake a seven-request conversation, and the schema went to
        // real trouble to make that unnecessary.
        reported shouldHaveAtLeastSize 7
        reported.map { it["field"] } shouldContainAll
            listOf(
                "metadata.name",
                "spec.image",
                "spec.paper.minecraftVersion",
                "spec.resources.memory",
                "spec.network.port",
                "spec.maxPlayers",
                "spec.strage",
            )
        for (violation in reported) {
            (violation["problem"] as String).isNotEmpty() shouldBe true
            val location = violation["location"] as Map<*, *>
            location["source"] shouldBe "request-body"
            (location["line"] as Int) shouldBe location["line"]
        }
        // Nothing was written.
        api.call("GET", "/api/v1/servers/survival-01").status shouldBe 404
    }

    @Test
    fun `a name that disagrees with the path is a field-level violation`() {
        api.call("POST", "/api/v1/servers", minimal).status shouldBe 201
        val etag = api.call("GET", "/api/v1/servers/survival-01").header("ETag").shouldNotBeNull()

        val rejected =
            api.call("PUT", "/api/v1/servers/survival-02", minimal, headers = listOf("If-Match" to etag))
        rejected.status shouldBe 422
        violations(rejected).single()["field"] shouldBe "metadata.name"
    }

    @Test
    fun `a second create conflicts with 409 and the current version`() {
        val first = api.call("POST", "/api/v1/servers", minimal)
        first.status shouldBe 201

        val second = api.call("POST", "/api/v1/servers", minimal)
        second.status shouldBe 409
        second.errorCode() shouldBe "CONFLICT"
        val conflict = (second.json()["error"] as Map<*, *>)["conflict"] as Map<*, *>
        conflict["reason"] shouldBe "ALREADY_EXISTS"
        // The current version, so a client can act without a second read — in the
        // body and in the header, because a browser reads one and a script the other.
        conflict["currentResourceVersion"].shouldNotBeNull()
        second.header("ETag") shouldBe first.header("ETag")
    }

    @Test
    fun `a stale If-Match conflicts with 409 carrying the version that won`() {
        api.call("POST", "/api/v1/servers", minimal)
        val stale = api.call("GET", "/api/v1/servers/survival-01").header("ETag").shouldNotBeNull()

        val edited = minimal.replace("memory: 4Gi", "memory: 6Gi")
        val winner = api.call("PUT", "/api/v1/servers/survival-01", edited, headers = listOf("If-Match" to stale))
        winner.status shouldBe 200
        val current = winner.header("ETag").shouldNotBeNull()
        current shouldNotBeSameAs stale

        val loser =
            api.call(
                "PUT",
                "/api/v1/servers/survival-01",
                minimal.replace("memory: 4Gi", "memory: 8Gi"),
                headers = listOf("If-Match" to stale),
            )
        loser.status shouldBe 409
        val conflict = (loser.json()["error"] as Map<*, *>)["conflict"] as Map<*, *>
        conflict["reason"] shouldBe "VERSION_MISMATCH"
        "\"${conflict["currentResourceVersion"]}\"" shouldBe current
    }

    @Test
    fun `a replace with no If-Match is refused rather than silently winning`() {
        api.call("POST", "/api/v1/servers", minimal)
        val refused = api.call("PUT", "/api/v1/servers/survival-01", minimal)
        refused.status shouldBe 428
        refused.errorCode() shouldBe "PRECONDITION_REQUIRED"
        (refused.json()["error"] as Map<*, *>)["message"].toString() shouldContain "If-Match"

        // The deliberate override still works.
        api.call("PUT", "/api/v1/servers/survival-01", minimal, headers = listOf("If-Match" to "*")).status shouldBe 200
    }

    @Test
    fun `a deleted server reads as terminating rather than absent`() {
        api.call("POST", "/api/v1/servers", minimal).status shouldBe 201

        val deleted = api.call("DELETE", "/api/v1/servers/survival-01")
        // 202, not 204: the drain has been *requested*. Nothing has stopped, and
        // nothing may stop until players are off and a save is confirmed.
        deleted.status shouldBe 202
        val body = deleted.json()
        body["accepted"] shouldBe true
        body["message"].toString() shouldContain "drains"

        val fetched = api.call("GET", "/api/v1/servers/survival-01")
        fetched.status shouldBe 200
        val document = fetched.json()
        (document["metadata"] as Map<*, *>)["terminating"] shouldBe true
        (document["metadata"] as Map<*, *>)["deletedAt"].shouldNotBeNull()
        (document["display"] as Map<*, *>)["state"] shouldBe "TERMINATING"

        // It is still in the list, and filterable as its own state.
        val listed = api.call("GET", "/api/v1/servers?terminating=true").json()
        listed["count"] shouldBe 1
        api.call("GET", "/api/v1/servers?terminating=false").json()["count"] shouldBe 0

        // And the name cannot be reused until :core has finished with it.
        val reused = api.call("POST", "/api/v1/servers", minimal)
        reused.status shouldBe 409
        ((reused.json()["error"] as Map<*, *>)["conflict"] as Map<*, *>)["reason"] shouldBe "TERMINATING"
    }

    @Test
    fun `the list carries the cursor to stream from and filters by label`() {
        api.call("POST", "/api/v1/servers", minimal).status shouldBe 201
        api.call("POST", "/api/v1/servers", ExampleDefinitions.valid("full.yaml")).status shouldBe 201

        val listed = api.call("GET", "/api/v1/servers").json()
        listed["count"] shouldBe 2
        (listed["cursor"] as String).isNotEmpty() shouldBe true
        @Suppress("UNCHECKED_CAST")
        val items = listed["items"] as List<Map<String, Any?>>
        items.map { it["name"] } shouldBe listOf("survival-01", "survival-02")

        val filtered = api.call("GET", "/api/v1/servers?labelSelector=tier%3Dsurvival").json()
        filtered["count"] shouldBe 1
        api.call("GET", "/api/v1/servers?labelSelector=tier%3Dnope").json()["count"] shouldBe 0
        api.call("GET", "/api/v1/servers?labelSelector=broken").status shouldBe 400
    }

    @Test
    fun `validate reports problems without writing anything`() {
        val bad = api.call("POST", "/api/v1/validate", ExampleDefinitions.invalid("heap-exceeds-memory.yaml"))
        bad.status shouldBe 422
        violations(bad).map { it["field"] } shouldContainAll listOf("spec.resources.heap.max")

        val good = api.call("POST", "/api/v1/validate", minimal)
        good.status shouldBe 200
        good.json()["valid"] shouldBe true
        // The *effective* definition: showing an operator what their omissions
        // became is most of what this endpoint is for.
        val spec = (good.json()["definition"] as Map<*, *>)["spec"] as Map<*, *>
        (spec["storage"] as Map<*, *>)["mode"] shouldBe "persistent"
        (spec["lifecycle"] as Map<*, *>)["stopGracePeriod"] shouldBe "4m"

        // Nothing was stored.
        api.call("GET", "/api/v1/servers").json()["count"] shouldBe 0
    }

    @Test
    fun `unknown names, endpoints and methods answer distinctly`() {
        api.call("GET", "/api/v1/servers/absent").status shouldBe 404
        api.call("GET", "/api/v1/nope").status shouldBe 404

        val wrongMethod = api.call("PATCH", "/api/v1/servers/survival-01", minimal)
        wrongMethod.status shouldBe 405
        wrongMethod.header("Allow").shouldNotBeNull() shouldContain "PUT"

        val badName = api.call("GET", "/api/v1/servers/NOT-A-NAME")
        badName.status shouldBe 400
        badName.errorCode() shouldBe "BAD_REQUEST"

        val wrongType = api.call("POST", "/api/v1/servers", minimal, contentType = "image/png")
        wrongType.status shouldBe 415
    }

    @Test
    fun `a body over the cap is refused before it is parsed`() {
        val small = TestApi.start { it.copy(maxBodyBytes = 256) }
        try {
            val reply = small.call("POST", "/api/v1/servers", "#".repeat(4096))
            reply.status shouldBe 413
            reply.errorCode() shouldBe "PAYLOAD_TOO_LARGE"
        } finally {
            small.close()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun violations(reply: TestApi.Reply): List<Map<String, Any?>> =
        (reply.json()["error"] as Map<*, *>)["violations"] as List<Map<String, Any?>>

    private fun dump(document: Any?): String = Dump(DumpSettings.builder().build()).dumpToString(document)

    private infix fun String.shouldNotBeSameAs(other: String) {
        if (this == other) throw AssertionError("expected `$this` to differ from `$other`")
    }
}
