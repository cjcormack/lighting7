package uk.me.cormack.lighting7.cloud.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.reflect.full.findAnnotation
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The golden fixtures under `src/test/resources/protocol/` are the contract the cloud tests against
 * too (docs/cloud-protocol.md §"Golden fixtures"). Each one decodes to exactly the value below and
 * re-encodes to the same JSON, so a renamed field, a changed type name or a lost default fails here.
 */
class GoldenFixturesTest {
    private val ts = "2026-11-02T22:18:03.000Z"
    private val authSignature = "pywOheIKHXnb67KQokXxaAntdoy26DnwVTqh9u9oKqBF6KS4fPXeCeeVEAr9yxXfcWaSvMk4iN3D4HOjQBn1CQ"

    private val messages: Map<String, Envelope> = mapOf(
        "challenge" to Envelope("c-0001", Message.Challenge("oN6s1kZlqH0tq3fWb2pQe8Yd4VxCjR7uTgMa9LzK5wI", "2026-11-02T22:18:02.950Z")),
        "auth" to Envelope("d-0001", Message.Auth("inst_01JBX3Q5Z8", ts, authSignature), replyTo = "c-0001"),
        "welcome" to Envelope("c-0002", Message.Welcome("Cormack Lighting", 25), replyTo = "d-0001"),
        "hello" to Envelope(
            "d-0002",
            Message.Hello(1, "1.4.0", "windows-x64", "6f1d2c3e-8a4b-4c5d-9e6f-7a8b9c0d1e2f", "Front-of-house desk", emptyList()),
        ),
        "health" to Envelope("d-0003", Message.Health(3600, "1.4.0")),
        "unadopted" to Envelope("c-0003", Message.Unadopted),
        "error" to Envelope("c-0004", Message.Error(ErrorCode.PROTOCOL_ERROR, "hello came before welcome"), replyTo = "d-0002"),
        "unknown" to Envelope(
            "c-0005",
            Message.Unknown(
                "rtc.offer",
                Json.parseToJsonElement("""{"sdp":"v=0","nested":{"candidates":[1,2,3],"trickle":true}}""").jsonObject,
            ),
        ),
    )

    private class Dto<T>(val serializer: KSerializer<T>, val value: T)

    private val adoption: Map<String, Dto<*>> = mapOf(
        "adoption-request" to Dto(
            AdoptionRequest.serializer(),
            AdoptionRequest("11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo", "6f1d2c3e-8a4b-4c5d-9e6f-7a8b9c0d1e2f", "Front-of-house desk", "windows-x64", "1.4.0"),
        ),
        "adoption-started" to Dto(
            AdoptionStarted.serializer(),
            AdoptionStarted("adp_7Kq2Vx9mT4", "WDJB-MJHT", "https://cloud.example/adopt", "https://cloud.example/adopt?code=WDJB-MJHT", 5, 900),
        ),
        "poll-pending" to Dto(AdoptionPollResult.serializer(), AdoptionPollResult.Pending),
        "poll-slow-down" to Dto(AdoptionPollResult.serializer(), AdoptionPollResult.SlowDown(10)),
        "poll-approved" to Dto(
            AdoptionPollResult.serializer(),
            AdoptionPollResult.Approved(
                "inst_01JBX3Q5Z8",
                CloudOrg("org_3Hc8Wn", "Cormack Lighting"),
                CloudAccount("acc_9Pz4Rt", "owner@example.com", "Desk Owner"),
            ),
        ),
        "poll-denied" to Dto(AdoptionPollResult.serializer(), AdoptionPollResult.Denied),
        "poll-expired" to Dto(AdoptionPollResult.serializer(), AdoptionPollResult.Expired),
        "api-error" to Dto(ApiError.serializer(), ApiError(ErrorCode.CLOCK_SKEW, "The timestamp is 7 minutes from the cloud's clock")),
    )

    @Test
    fun `every message fixture decodes to its value and re-encodes as written`() {
        for ((name, expected) in messages) {
            val text = fixture("messages/$name.json")
            assertEquals(expected, Envelope.decode(text), "messages/$name.json decodes")
            assertEquals(Json.parseToJsonElement(text), Json.parseToJsonElement(expected.encode()), "messages/$name.json re-encodes")
        }
    }

    @Test
    fun `every adoption fixture decodes to its value and re-encodes as written`() {
        for ((name, dto) in adoption) {
            assertRoundTrips("adoption/$name.json", dto)
        }
    }

    private fun <T> assertRoundTrips(path: String, dto: Dto<T>) {
        val text = fixture(path)
        assertEquals(dto.value, CloudJson.decodeFromString(dto.serializer, text), "$path decodes")
        assertEquals(Json.parseToJsonElement(text), CloudJson.encodeToJsonElement(dto.serializer, dto.value), "$path re-encodes")
    }

    @Test
    fun `every message type has a fixture and every fixture is checked`() {
        val sealedTypes = Message::class.sealedSubclasses
            .filter { it != Message.Unknown::class }
            .map { requireNotNull(it.findAnnotation<SerialName>()) { "${it.simpleName} has no @SerialName" }.value }
            .toSet()
        assertEquals(sealedTypes, KNOWN_MESSAGE_TYPES, "every Message subclass decodes")
        assertEquals(KNOWN_MESSAGE_TYPES + "unknown", messages.keys, "a fixture per message type, and one unknown")
        assertEquals(messages.keys, fixtureNames("messages"), "no message fixture goes unchecked")
        assertEquals(adoption.keys, fixtureNames("adoption"), "no adoption fixture goes unchecked")
    }

    @Test
    fun `the constants match their fixture`() {
        val written = Json.parseToJsonElement(fixture("close-codes.json")).jsonObject
        val expected = buildJsonObject {
            put("closeCodes", buildJsonObject { CloseCode.entries.forEach { put(it.name, it.code.toInt()) } })
            put("authFailures", Json.parseToJsonElement(AuthFailure.entries.joinToString(",", "[", "]") { "\"${it.name}\"" }))
            put("errorCodes", written.getValue("errorCodes"))
            put("maxFrameBytes", MAX_FRAME_BYTES)
            put("protocolVersion", PROTOCOL_VERSION)
            put("clockSkewToleranceS", CLOCK_SKEW_TOLERANCE.seconds)
        }
        assertEquals(expected, written)
        val errorCodes = ErrorCode::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.get(null) as String }
            .sorted()
        assertEquals(written.getValue("errorCodes").jsonArray.map { it.jsonPrimitive.content }, errorCodes)
        for ((name, code) in written.getValue("closeCodes").jsonObject) {
            assertEquals(CloseCode.valueOf(name), CloseCode.of(code.jsonPrimitive.int))
        }
    }

    private fun fixtureNames(dir: String): Set<String> =
        requireNotNull(File(resource("protocol/$dir")).listFiles()).map { it.nameWithoutExtension }.toSet()
}

internal fun fixture(path: String): String = File(resource("protocol/$path")).readText()

internal fun fixtureJson(path: String): JsonElement = Json.parseToJsonElement(fixture(path))

private fun resource(path: String) = requireNotNull(GoldenFixturesTest::class.java.classLoader.getResource(path)) { "no $path" }.toURI()
