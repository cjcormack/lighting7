package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import uk.me.cormack.lighting7.midi.BindingTarget
import uk.me.cormack.lighting7.midi.SoftTakeoverStateMachine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Wire-format smoke tests for the nested sealed message hierarchy. Each domain owns a
 * sealed `XxxInMessage : InMessage()` (and / or `XxxOutMessage : OutMessage()`) under
 * which its leaves live. These tests verify two things per domain:
 *
 *  1. `decodeFromString<InMessage>` walks the nested tree and lands on the right leaf via
 *     the `@SerialName` discriminator.
 *  2. The decoded leaf is `is XxxInMessage` — catches the failure mode where a future
 *     message is added directly under `InMessage` instead of under its domain parent.
 *
 * Outbound messages get an encode-then-decode round-trip via `OutMessage` to confirm the
 * discriminator is emitted at the top level (kotlinx.serialization handles nested sealed
 * polymorphism transparently, but it's worth proving on the wire).
 *
 * One inbound + one outbound representative per domain; the goal is structural coverage,
 * not exhaustive per-leaf testing. Domain-specific field-level tests (e.g. CueEdit
 * setProperty value parsing) live in the per-domain test file.
 */
class SocketMessageWireFormatTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ─── Channel domain ─────────────────────────────────────────────────────

    @Test
    fun `channel domain — UpdateChannelInMessage routes via ChannelInMessage`() {
        val raw = """{"type":"updateChannel","universe":2,"id":17,"level":200,"fadeTime":150}"""
        val decoded = json.decodeFromString<InMessage>(raw)
        assertIs<ChannelInMessage>(decoded)
        val leaf = assertIs<UpdateChannelInMessage>(decoded)
        assertEquals(2, leaf.universe)
        assertEquals(17, leaf.id)
        assertEquals(200u.toUByte(), leaf.level)
        assertEquals(150L, leaf.fadeTime)
    }

    @Test
    fun `channel domain — ChannelStateOutMessage round-trips with discriminator`() {
        val out = ChannelStateOutMessage(listOf(ChannelState(0, 1, 128u)))
        val encoded = json.encodeToString<OutMessage>(out)
        assertTrue(encoded.contains(""""type":"channelState""""))
        assertEquals(out, assertIs<ChannelStateOutMessage>(json.decodeFromString<OutMessage>(encoded)))
    }

    // ─── Park domain ────────────────────────────────────────────────────────

    @Test
    fun `park domain — ParkChannelInMessage routes via ParkInMessage`() {
        val raw = """{"type":"parkChannel","universe":1,"channel":42,"value":255}"""
        val decoded = json.decodeFromString<InMessage>(raw)
        assertIs<ParkInMessage>(decoded)
        val leaf = assertIs<ParkChannelInMessage>(decoded)
        assertEquals(255u.toUByte(), leaf.value)
    }

    @Test
    fun `park domain — ParkStateOutMessage round-trips with discriminator`() {
        val out = ParkStateOutMessage(listOf(ParkedChannelState(0, 5, 100u)))
        val encoded = json.encodeToString<OutMessage>(out)
        assertTrue(encoded.contains(""""type":"parkState""""))
        assertEquals(out, assertIs<ParkStateOutMessage>(json.decodeFromString<OutMessage>(encoded)))
    }

    // ─── FX domain ──────────────────────────────────────────────────────────

    @Test
    fun `fx domain — SetFxBpmInMessage routes via FxInMessage`() {
        val raw = """{"type":"setFxBpm","bpm":128.5}"""
        val decoded = json.decodeFromString<InMessage>(raw)
        assertIs<FxInMessage>(decoded)
        assertEquals(128.5, assertIs<SetFxBpmInMessage>(decoded).bpm)
    }

    @Test
    fun `fx domain — TapTempoInMessage object decodes`() {
        val decoded = json.decodeFromString<InMessage>("""{"type":"tapTempo"}""")
        assertIs<FxInMessage>(decoded)
        assertIs<TapTempoInMessage>(decoded)
    }

    @Test
    fun `fx domain — BeatSyncOutMessage round-trips with discriminator`() {
        val out = BeatSyncOutMessage(beatNumber = 42L, bpm = 120.0, timestampMs = 1_000_000L)
        val encoded = json.encodeToString<OutMessage>(out)
        assertTrue(encoded.contains(""""type":"beatSync""""))
        assertEquals(out, assertIs<BeatSyncOutMessage>(json.decodeFromString<OutMessage>(encoded)))
    }

    // ─── FxChangeType enum (formerly stringly-typed) ────────────────────────

    @Test
    fun `FxChangedOutMessage encodes enum as kebab string`() {
        val encoded = json.encodeToString<OutMessage>(FxChangedOutMessage(FxChangeType.REMOVED, effectId = 7L))
        assertTrue(encoded.contains(""""changeType":"removed""""), "got: $encoded")
        assertTrue(encoded.contains(""""effectId":7"""))
    }

    @Test
    fun `FxChangedOutMessage round-trips every FxChangeType case`() {
        for (case in FxChangeType.entries) {
            val out = FxChangedOutMessage(case, effectId = if (case == FxChangeType.CLEARED) null else 1L)
            val encoded = json.encodeToString<OutMessage>(out)
            val decoded = assertIs<FxChangedOutMessage>(json.decodeFromString<OutMessage>(encoded))
            assertEquals(case, decoded.changeType)
        }
    }

    @Test
    fun `FxChangeType wire labels match historical strings`() {
        // Frontend code may still match on these strings; keep the wire format stable.
        val pairs = listOf(
            FxChangeType.ADDED to "added",
            FxChangeType.REMOVED to "removed",
            FxChangeType.UPDATED to "updated",
            FxChangeType.CLEARED to "cleared",
        )
        for ((case, expected) in pairs) {
            val encoded = json.encodeToString<OutMessage>(FxChangedOutMessage(case))
            assertTrue(
                encoded.contains(""""changeType":"$expected""""),
                "expected $expected in wire output, got: $encoded",
            )
        }
    }

    // ─── Palette domain ─────────────────────────────────────────────────────

    @Test
    fun `palette domain — SetPaletteInMessage routes via PaletteInMessage`() {
        val raw = """{"type":"setPalette","colours":["#ff0000","#00ff00"]}"""
        val decoded = json.decodeFromString<InMessage>(raw)
        assertIs<PaletteInMessage>(decoded)
        assertEquals(listOf("#ff0000", "#00ff00"), assertIs<SetPaletteInMessage>(decoded).colours)
    }

    @Test
    fun `palette domain — PaletteChangedOutMessage round-trips with discriminator`() {
        val out = PaletteChangedOutMessage(listOf("#ff0000"))
        val encoded = json.encodeToString<OutMessage>(out)
        assertTrue(encoded.contains(""""type":"paletteChanged""""))
        assertEquals(out, assertIs<PaletteChangedOutMessage>(json.decodeFromString<OutMessage>(encoded)))
    }

    // ─── Group domain ───────────────────────────────────────────────────────

    @Test
    fun `group domain — ClearGroupFxInMessage routes via GroupInMessage`() {
        val raw = """{"type":"clearGroupFx","groupName":"front-wash"}"""
        val decoded = json.decodeFromString<InMessage>(raw)
        assertIs<GroupInMessage>(decoded)
        assertEquals("front-wash", assertIs<ClearGroupFxInMessage>(decoded).groupName)
    }

    @Test
    fun `group domain — GroupFxClearedOutMessage round-trips with discriminator`() {
        val out = GroupFxClearedOutMessage(groupName = "front-wash", removedCount = 4)
        val encoded = json.encodeToString<OutMessage>(out)
        assertTrue(encoded.contains(""""type":"groupFxCleared""""))
        assertEquals(out, assertIs<GroupFxClearedOutMessage>(json.decodeFromString<OutMessage>(encoded)))
    }

    // ─── Surface domain ─────────────────────────────────────────────────────

    @Test
    fun `surface domain — SurfaceBankSetInMessage routes via SurfaceInMessage`() {
        val raw = """{"type":"surfaceBank.set","deviceTypeKey":"akai-mini","bank":"A"}"""
        val decoded = json.decodeFromString<InMessage>(raw)
        assertIs<SurfaceInMessage>(decoded)
        val leaf = assertIs<SurfaceBankSetInMessage>(decoded)
        assertEquals("akai-mini", leaf.deviceTypeKey)
        assertEquals("A", leaf.bank)
    }

    @Test
    fun `surface domain — SurfaceLearnCommitInMessage decodes nested BindingTarget`() {
        val raw = """{
            "type":"surfaceLearn.commit",
            "sessionId":"s-1",
            "bank":"A",
            "target":{"type":"fixtureProperty","fixtureKey":"hex-1","propertyName":"dimmer"}
        }"""
        val decoded = json.decodeFromString<InMessage>(raw)
        assertIs<SurfaceInMessage>(decoded)
        val leaf = assertIs<SurfaceLearnCommitInMessage>(decoded)
        val target = assertIs<BindingTarget.FixtureProperty>(leaf.target)
        assertEquals("hex-1", target.fixtureKey)
    }

    @Test
    fun `surface domain — SurfacePickupChangedOutMessage round-trips with discriminator`() {
        val out = SurfacePickupChangedOutMessage(
            displayKey = "akai:0",
            controlId = "fader1",
            state = SoftTakeoverStateMachine.State.AWAITING_PICKUP,
            target = 64,
        )
        val encoded = json.encodeToString<OutMessage>(out)
        assertTrue(encoded.contains(""""type":"surfacePickup.changed""""))
        assertEquals(out, assertIs<SurfacePickupChangedOutMessage>(json.decodeFromString<OutMessage>(encoded)))
    }

    // ─── Project domain ─────────────────────────────────────────────────────

    @Test
    fun `project domain — ProjectStateInMessage routes via ProjectInMessage`() {
        val decoded = json.decodeFromString<InMessage>("""{"type":"projectState"}""")
        assertIs<ProjectInMessage>(decoded)
        assertIs<ProjectStateInMessage>(decoded)
    }

    @Test
    fun `project domain — ProjectChangedOutMessage round-trips with discriminator`() {
        val out = ProjectChangedOutMessage(previousProjectId = 1, newProjectId = 2, newProjectName = "Stage")
        val encoded = json.encodeToString<OutMessage>(out)
        assertTrue(encoded.contains(""""type":"projectChanged""""))
        assertEquals(out, assertIs<ProjectChangedOutMessage>(json.decodeFromString<OutMessage>(encoded)))
    }

    // ─── CueEdit domain (verifies reparenting) ──────────────────────────────

    @Test
    fun `cueEdit domain — beginEdit routes via CueEditInMessage`() {
        val raw = """{"type":"cueEdit.beginEdit","cueId":7,"mode":"live"}"""
        val decoded = json.decodeFromString<InMessage>(raw)
        assertIs<CueEditInMessage>(decoded)
        assertIs<CueEditBeginEditInMessage>(decoded)
    }

    // ─── Broadcast domain (out-only) ────────────────────────────────────────

    @Test
    fun `broadcast domain — ShowChangedOutMessage round-trips with discriminator`() {
        val out = ShowChangedOutMessage(
            projectId = 3,
            activeEntryId = 9,
            activatedStackId = 2,
            activatedStackName = "Act 1",
        )
        val encoded = json.encodeToString<OutMessage>(out)
        assertTrue(encoded.contains(""""type":"showChanged""""))
        assertEquals(out, assertIs<ShowChangedOutMessage>(json.decodeFromString<OutMessage>(encoded)))
    }

    @Test
    fun `broadcast domain — list-changed objects emit the bare discriminator`() {
        val encoded = json.encodeToString<OutMessage>(PresetListChangedOutMessage)
        assertEquals("""{"type":"presetListChanged"}""", encoded)
    }

    // ─── CloudSync domain (out-only) ────────────────────────────────────────

    @Test
    fun `cloudSync domain — CloudSyncDoneOutMessage round-trips with discriminator`() {
        val out = CloudSyncDoneOutMessage(
            projectId = 1,
            outcome = "ok",
            headSha = "abc123",
            pushed = 3,
            pulled = 0,
            replaced = 0,
            message = "synced",
        )
        val encoded = json.encodeToString<OutMessage>(out)
        assertTrue(encoded.contains(""""type":"cloudSyncDone""""))
        assertEquals(out, assertIs<CloudSyncDoneOutMessage>(json.decodeFromString<OutMessage>(encoded)))
    }

    @Test
    fun `cloudSync domain — OAuthIdentityChangedOutMessage round-trips with optional fields`() {
        val out = OAuthIdentityChangedOutMessage(
            provider = "github",
            connected = true,
            login = "octocat",
            accessExpiresAtMs = 1_000L,
            refreshExpiresAtMs = null,
        )
        val encoded = json.encodeToString<OutMessage>(out)
        assertTrue(encoded.contains(""""type":"oauthIdentityChanged""""))
        assertEquals(out, assertIs<OAuthIdentityChangedOutMessage>(json.decodeFromString<OutMessage>(encoded)))
    }
}
