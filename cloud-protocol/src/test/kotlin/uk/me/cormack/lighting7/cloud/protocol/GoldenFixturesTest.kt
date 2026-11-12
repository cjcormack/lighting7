package uk.me.cormack.lighting7.cloud.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
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
    private val remoteOrigin = "https://inst-01jbx3q5z8.desk.cloud.example"
    private val sid = "rs_5Tg2Lm8Qa1"
    private val testPublicKey = fixtureJson("signing/test-key.json").jsonObject.getValue("publicKey").jsonPrimitive.content
    private val mcpToken = fixtureJson("tokens/vectors.json").jsonObject.getValue("valid").jsonArray
        .map { it.jsonObject }
        .single { it.getValue("name").jsonPrimitive.content == "mcp" }
        .getValue("token").jsonPrimitive.content

    private fun sdp(sessionId: String) =
        "v=0\r\no=- $sessionId 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 0\r\n" +
            "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 0.0.0.0\r\na=mid:0\r\na=sctp-port:5000\r\n"

    private val messages: Map<String, Envelope> = mapOf(
        "challenge" to Envelope("c-0001", Message.Challenge("oN6s1kZlqH0tq3fWb2pQe8Yd4VxCjR7uTgMa9LzK5wI", "2026-11-02T22:18:02.950Z")),
        "auth" to Envelope("d-0001", Message.Auth("inst_01JBX3Q5Z8", ts, authSignature), replyTo = "c-0001"),
        "welcome" to Envelope("c-0002", Message.Welcome("Cormack Lighting", 25, remoteOrigin), replyTo = "d-0001"),
        "hello" to Envelope(
            "d-0002",
            Message.Hello(
                1,
                "1.4.0",
                "windows-x64",
                "6f1d2c3e-8a4b-4c5d-9e6f-7a8b9c0d1e2f",
                "Front-of-house desk",
                listOf(Capability.REMOTE_V1, Capability.REMOTE_NO_SIDECAR),
            ),
        ),
        "health" to Envelope("d-0003", Message.Health(3600, "1.4.0")),
        "unadopted" to Envelope("c-0003", Message.Unadopted),
        "error" to Envelope("c-0004", Message.Error(ErrorCode.PROTOCOL_ERROR, "hello came before welcome"), replyTo = "d-0002"),
        "capabilities" to Envelope("d-0004", Message.Capabilities(listOf(Capability.REMOTE_V1, Capability.MCP_V1, Capability.MCP_OFF))),
        "jwks" to Envelope("c-0006", Message.Jwks(listOf(Jwk(kid = "test-1", x = testPublicKey)))),
        "revoke" to Envelope("c-0007", Message.Revoke("2026-11-05T12:00:00.000Z", sid = sid)),
        "rtc.offer" to Envelope(
            "c-0008",
            Message.RtcOffer(
                sid,
                "acc_9Pz4Rt",
                "owner@example.com",
                "Desk Owner",
                sdp("4611731400430051336"),
                listOf(
                    IceServer(listOf("stun:turn.cloud.example:3478")),
                    IceServer(
                        listOf("turn:turn.cloud.example:3478?transport=udp", "turns:turn.cloud.example:443?transport=tcp"),
                        username = "1793923200:$sid",
                        credential = "dGVzdC1jcmVkZW50aWFs",
                    ),
                ),
            ),
        ),
        "rtc.answer" to Envelope("d-0005", Message.RtcAnswer(sid, sdp("8273418265320916235")), replyTo = "c-0008"),
        "rtc.ice" to Envelope(
            "d-0006",
            Message.RtcIce(sid, IceCandidate("candidate:1966762134 1 udp 2122260223 192.168.1.20 51472 typ host", "0", 0, "f7Kq")),
        ),
        "rtc.close" to Envelope("d-0007", Message.RtcClose("rs_5Tg2Lm8Qa2", ErrorCode.REMOTE_ACCESS_OFF), replyTo = "c-0009"),
        "link.invite" to Envelope(
            "d-0008",
            Message.LinkInvite("inv_4Rm7Kc", "operator@example.com", "sam", "chris", "2026-11-12T12:00:00.000Z"),
        ),
        "link.cancelled" to Envelope("d-0009", Message.LinkCancelled("inv_4Rm7Kc")),
        "link.refused" to Envelope("c-0010", Message.LinkRefused("inv_4Rm7Kc")),
        "link.accepted" to Envelope("c-0011", Message.LinkAccepted("inv_4Rm7Kc", "acc_2Hs6Tv", "operator@example.com")),
        "link.ack" to Envelope(
            "d-0010",
            Message.LinkAck("inv_4Rm7Kc", LinkOutcome.REFUSED, ErrorCode.LINK_EMAIL_MISMATCH),
            replyTo = "c-0011",
        ),
        "link.created" to Envelope("d-0011", Message.LinkCreated("acc_2Hs6Tv", "sam")),
        "link.removed" to Envelope("d-0012", Message.LinkRemoved("acc_2Hs6Tv")),
        "mcp.call" to Envelope(
            "c-0012",
            Message.McpCall(
                mcpToken,
                McpGrant("gr_8Hw3Zp", "Claude"),
                Json.parseToJsonElement("""{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"describe_rig","arguments":{}}}""").jsonObject,
            ),
        ),
        "mcp.result" to Envelope(
            "d-0013",
            Message.McpResult(
                Json.parseToJsonElement(
                    """{"jsonrpc":"2.0","id":7,"result":{"content":[{"type":"text","text":"Front wash: 6 x Hex"}],"isError":false}}""",
                ).jsonObject,
            ),
            replyTo = "c-0012",
        ),
        "sync.changed" to Envelope(
            "c-0013",
            Message.SyncChanged(
                "repo_01JC2M7Q4R",
                "https://cloud.example/git/org_3Hc8Wn/repo_01JC2M7Q4R.git",
                "4b825dc642cb6eb9a060e54bf8d69288fbee4904",
            ),
        ),
        "unknown" to Envelope(
            "c-0005",
            Message.Unknown(
                "future.thing",
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

    private val showRepo = RepoDto(
        repoId = "repo_01JC2M7Q4R",
        url = "https://cloud.example/git/org_3Hc8Wn/repo_01JC2M7Q4R.git",
        projectUuid = "3f2b8c1d-4e5a-4b6c-8d7e-9f0a1b2c3d4e",
        kind = RepoKind.SHOW,
        name = "Winter Gala",
        headSha = "4b825dc642cb6eb9a060e54bf8d69288fbee4904",
        sizeBytes = 1_843_200,
        formatVersion = 56,
        lastPushAt = "2026-11-12T19:04:11.250Z",
    )
    private val newVenueRepo = RepoDto(
        repoId = "repo_01JC2M9V7T",
        url = "https://cloud.example/git/org_3Hc8Wn/repo_01JC2M9V7T.git",
        projectUuid = "8a1e0c2b-5d6f-4a7b-9c8d-0e1f2a3b4c5d",
        kind = RepoKind.VENUE,
        name = "Corn Exchange",
        sizeBytes = 0,
    )

    private val repos: Map<String, Dto<*>> = mapOf(
        "create-repo-request" to Dto(CreateRepoRequest.serializer(), CreateRepoRequest("3f2b8c1d-4e5a-4b6c-8d7e-9f0a1b2c3d4e", RepoKind.SHOW, "Winter Gala")),
        "repo" to Dto(RepoDto.serializer(), showRepo),
        "repo-new" to Dto(RepoDto.serializer(), newVenueRepo),
        "repo-list" to Dto(RepoList.serializer(), RepoList(listOf(showRepo, newVenueRepo))),
        "repo-exists" to Dto(RepoExistsError.serializer(), RepoExistsError(ErrorCode.REPO_EXISTS, "Winter Gala already has a repo in this org", showRepo)),
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

    @Test
    fun `every repo fixture decodes to its value and re-encodes as written`() {
        for ((name, dto) in repos) {
            assertRoundTrips("repos/$name.json", dto)
        }
    }

    @Test
    fun `a repo-exists answer reads as an ApiError`() {
        val error = CloudJson.decodeFromString(ApiError.serializer(), fixture("repos/repo-exists.json"))
        assertEquals(ApiError(ErrorCode.REPO_EXISTS, "Winter Gala already has a repo in this org"), error)
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
        assertEquals(repos.keys + "push-refusals", fixtureNames("repos"), "no repo fixture goes unchecked")
        assertEquals(setOf("vectors", "git"), fixtureNames("tokens"), "DeskTokensTest and InstallGitTokensTest check tokens/")
        assertEquals(setOf("framing", "ctl"), fixtureNames("link"), "LinkContractsTest checks link/")
        assertEquals(setOf("messages"), fixtureNames("remote-frame"), "LinkContractsTest checks remote-frame/")
        assertEquals(
            setOf("messages", "adoption", "repos", "signing", "tokens", "link", "remote-frame", "close-codes"),
            requireNotNull(File(resource("protocol")).listFiles()).map { it.nameWithoutExtension }.toSet(),
            "every fixture directory is one a test reads",
        )
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
            put("tokenRefusals", buildJsonObject { TokenRefusal.entries.forEach { put(it.name, it.code) } })
            put("capabilities", Json.parseToJsonElement(Capability.ALL.joinToString(",", "[", "]") { "\"$it\"" }))
            put("maxRelayedRequestBytes", MAX_RELAYED_REQUEST_BYTES)
            put("rtcOfferReplyTimeoutS", RTC_OFFER_REPLY_TIMEOUT.seconds)
            put("deskTokenMaxLifetimeS", DeskTokens.MAX_LIFETIME.seconds)
            put("deskTokenClockSkewS", DeskTokens.CLOCK_SKEW.seconds)
            put("gitTokenMaxLifetimeS", InstallGitTokens.MAX_LIFETIME.seconds)
            put("gitTokenClockSkewS", InstallGitTokens.CLOCK_SKEW.seconds)
            put("pushRefusalCodes", JsonArray(PushRefusals.CODES.map { JsonPrimitive(it) }))
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

    @Test
    fun `a repo of a kind the reader does not know still decodes, and the list with it`() {
        val list = CloudJson.encodeToJsonElement(RepoList.serializer(), RepoList(listOf(showRepo, newVenueRepo.copy(kind = "LIBRARY"))))
        assertEquals(listOf(RepoKind.SHOW, "LIBRARY"), CloudJson.decodeFromJsonElement(RepoList.serializer(), list).repos.map { it.kind })
    }
}

internal fun fixture(path: String): String = File(resource("protocol/$path")).readText()

internal fun fixtureJson(path: String): JsonElement = Json.parseToJsonElement(fixture(path))

private fun resource(path: String) = requireNotNull(GoldenFixturesTest::class.java.classLoader.getResource(path)) { "no $path" }.toURI()
