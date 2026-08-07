package mcorch.velocity.control

import io.kotest.matchers.shouldBe

/**
 * Assertions read responses as parsed documents rather than as substrings.
 *
 * A test that matches `"admitsNewPlayers\":false"` in a body passes when the
 * field moves, is renamed, or ends up nested under something else — which is
 * exactly the change such a test exists to catch. `:api`'s tests make the same
 * choice for the same reason.
 */
internal fun ControlResponse.json(): JsonObject = Json.parseObject(body)

internal fun JsonObject.obj(name: String): JsonObject =
    requireNotNull(fields[name] as? JsonObject) { "`$name` is not an object in $fields" }

internal fun JsonObject.array(name: String): List<JsonValue> =
    requireNotNull(fields[name] as? JsonArray) { "`$name` is not an array in $fields" }.items

internal fun JsonObject.int(name: String): Int =
    requireNotNull(fields[name] as? JsonNumber) { "`$name` is not a number in $fields" }.value.toInt()

/**
 * Epoch milliseconds, which do not fit in an [Int].
 *
 * Read through a `Double` on the way in, which is exact for any epoch-millis
 * value this side of the year 287396 — `:core` reading a timestamp back as
 * something other than what the plugin wrote is what this asserts against.
 */
internal fun JsonObject.long(name: String): Long =
    requireNotNull(fields[name] as? JsonNumber) { "`$name` is not a number in $fields" }.value.toLong()

internal fun JsonObject.isNull(name: String): Boolean = fields[name] === JsonNull

/** The error code from a refusal, so tests branch on the symbol rather than on prose. */
internal fun ControlResponse.errorCode(): String = json().obj(ControlProtocol.FIELD_ERROR).string("code")

internal fun ControlResponse.shouldFailWith(code: ControlErrorCode) {
    status shouldBe code.httpStatus
    errorCode() shouldBe code.name
}

/** One backend out of a `GET /v1/state` body. */
internal fun ControlResponse.backend(name: String): JsonObject =
    array("backends")
        .filterIsInstance<JsonObject>()
        .firstOrNull { it.string("name").equals(name, ignoreCase = true) }
        ?: error("no backend `$name` in $body")

private fun ControlResponse.array(name: String): List<JsonValue> = json().array(name)

/** A ready service over [proxy], which is what every test but the readiness one wants. */
internal fun readyService(
    proxy: FakeProxy,
    admission: AdmissionRegistry = AdmissionRegistry(),
    clock: () -> Long = { 1_770_000_000_000L },
): ControlService = ControlService(proxy, admission, clock).also { it.markReady() }

internal fun ControlService.assertBackend(
    name: String,
    address: String,
    admits: Boolean,
): ControlResponse =
    handle("PUT", ControlProtocol.PATH_BACKEND + name, """{"address":"$address","admitsNewPlayers":$admits}""")

internal fun ControlService.deregisterBackend(name: String): ControlResponse =
    handle("DELETE", ControlProtocol.PATH_BACKEND + name, "")

internal fun ControlService.transferBackend(
    name: String,
    destination: String,
    message: String? = null,
): ControlResponse {
    val body =
        if (message == null) {
            """{"destination":"$destination"}"""
        } else {
            """{"destination":"$destination","message":"$message"}"""
        }
    return handle("POST", ControlProtocol.PATH_BACKEND + name + ControlProtocol.TRANSFER_SUFFIX, body)
}

internal fun ControlService.readState(): ControlResponse = handle("GET", ControlProtocol.PATH_STATE, "")
