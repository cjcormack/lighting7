package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import uk.me.cormack.lighting7.fx.CueRunState
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
 * not exhaustive per-leaf testing. Domain-specific field-level tests (e.g. programmer
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
    fun `fx domain — FxStateInMessage object decodes`() {
        val decoded = json.decodeFromString<InMessage>("""{"type":"fxState"}""")
        assertIs<FxInMessage>(decoded)
        assertIs<FxStateInMessage>(decoded)
    }

    // ─── Speed-master domain ────────────────────────────────────────────────
    // The `speedMasters.*` family is the whole tempo surface. It used to be the keyed
    // superset of an unkeyed master-1-only one (`setFxBpm`/`tapTempo`/`beatSync`, once
    // pinned here as a compatibility promise); that surface is retired, and `fxState`
    // carries no tempo at all any more.

    @Test
    fun `speedMasters domain — setBpm routes via SpeedMasterInMessage with an optional uuid`() {
        val keyed = json.decodeFromString<InMessage>(
            """{"type":"speedMasters.setBpm","bpm":90.0,"masterUuid":"7d444840-9dc0-11d1-b245-5ffdce74fad2"}"""
        )
        assertIs<SpeedMasterInMessage>(keyed)
        val leaf = assertIs<SpeedMastersSetBpmInMessage>(keyed)
        assertEquals(90.0, leaf.bpm)
        assertEquals("7d444840-9dc0-11d1-b245-5ffdce74fad2", leaf.masterUuid)

        // Omitted uuid → master 1, so the strip can address the global master uniformly.
        val unkeyed = assertIs<SpeedMastersSetBpmInMessage>(
            json.decodeFromString<InMessage>("""{"type":"speedMasters.setBpm","bpm":90.0}""")
        )
        assertEquals(null, unkeyed.masterUuid)
    }

    @Test
    fun `speedMasters domain — tap decodes with and without a uuid`() {
        val keyed = json.decodeFromString<InMessage>(
            """{"type":"speedMasters.tap","masterUuid":"7d444840-9dc0-11d1-b245-5ffdce74fad2"}"""
        )
        assertIs<SpeedMasterInMessage>(keyed)
        assertEquals("7d444840-9dc0-11d1-b245-5ffdce74fad2", assertIs<SpeedMastersTapInMessage>(keyed).masterUuid)
        assertEquals(null, assertIs<SpeedMastersTapInMessage>(
            json.decodeFromString<InMessage>("""{"type":"speedMasters.tap"}""")
        ).masterUuid)
    }

    @Test
    fun `speedMasters domain — state request object decodes`() {
        val decoded = json.decodeFromString<InMessage>("""{"type":"speedMasters.state"}""")
        assertIs<SpeedMasterInMessage>(decoded)
        assertIs<SpeedMastersStateInMessage>(decoded)
    }

    @Test
    fun `speedMasters domain — state out message round-trips with discriminator`() {
        val out = SpeedMastersStateOutMessage(
            masters = listOf(
                SpeedMasterStateJson(
                    uuid = "7d444840-9dc0-11d1-b245-5ffdce74fad2",
                    index = 1, name = "Master 1", bpm = 128.0, isRunning = true, source = "TAP",
                ),
            ),
        )
        val encoded = json.encodeToString<OutMessage>(out)
        assertTrue(encoded.contains(""""type":"speedMasters.state""""))
        assertEquals(out, assertIs<SpeedMastersStateOutMessage>(json.decodeFromString<OutMessage>(encoded)))
    }

    @Test
    fun `speedMasters domain — changed out message round-trips with discriminator`() {
        val out = SpeedMasterChangedOutMessage(
            masterUuid = "7d444840-9dc0-11d1-b245-5ffdce74fad2",
            index = 2, bpm = 64.0, source = "MANUAL", timestampMs = 1_000_000L,
        )
        val encoded = json.encodeToString<OutMessage>(out)
        assertTrue(encoded.contains(""""type":"speedMasters.changed""""))
        assertEquals(out, assertIs<SpeedMasterChangedOutMessage>(json.decodeFromString<OutMessage>(encoded)))
    }

    @Test
    fun `speedMasters domain — beat out message round-trips with discriminator`() {
        val out = SpeedMasterBeatOutMessage(
            masterUuid = "7d444840-9dc0-11d1-b245-5ffdce74fad2",
            index = 2, beatNumber = 32L, bpm = 64.0, timestampMs = 1_000_000L,
        )
        val encoded = json.encodeToString<OutMessage>(out)
        assertTrue(encoded.contains(""""type":"speedMasters.beat""""))
        assertEquals(out, assertIs<SpeedMasterBeatOutMessage>(json.decodeFromString<OutMessage>(encoded)))

        // Master 1 rides the same stream. A null uuid is the pre-load master 1 — once the
        // bank has loaded, master 1's frames carry its real uuid like every other master's.
        val m1 = SpeedMasterBeatOutMessage(
            masterUuid = null, index = 1, beatNumber = 0L, bpm = 120.0, timestampMs = 1L,
        )
        assertEquals(m1, assertIs<SpeedMasterBeatOutMessage>(
            json.decodeFromString<OutMessage>(json.encodeToString<OutMessage>(m1))
        ))
    }

    @Test
    fun `speedMasters domain — requestBeat decodes with and without a uuid`() {
        val keyed = json.decodeFromString<InMessage>(
            """{"type":"speedMasters.requestBeat","masterUuid":"7d444840-9dc0-11d1-b245-5ffdce74fad2"}"""
        )
        assertIs<SpeedMasterInMessage>(keyed)
        assertEquals(
            "7d444840-9dc0-11d1-b245-5ffdce74fad2",
            assertIs<SpeedMastersRequestBeatInMessage>(keyed).masterUuid,
        )
        assertEquals(null, assertIs<SpeedMastersRequestBeatInMessage>(
            json.decodeFromString<InMessage>("""{"type":"speedMasters.requestBeat"}""")
        ).masterUuid)
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

    // ─── Programmer domain ──────────────────────────────────────────────────

    @Test
    fun `programmer domain — set routes via ProgrammerInMessage`() {
        val raw = """{"type":"programmer.set","targetType":"fixture","targetKey":"hex-1","propertyName":"dimmer","value":"200"}"""
        val decoded = json.decodeFromString<InMessage>(raw)
        assertIs<ProgrammerInMessage>(decoded)
        val leaf = assertIs<ProgrammerSetInMessage>(decoded)
        assertEquals("hex-1", leaf.targetKey)
        assertEquals("200", leaf.value)
        assertEquals(null, leaf.fadeMs, "fadeMs is optional and defaults to null")
    }

    @Test
    fun `programmer domain — clearAll and setBlind parse with optional fadeMs`() {
        val clearAll = json.decodeFromString<InMessage>("""{"type":"programmer.clearAll","fadeMs":500}""")
        assertEquals(500L, assertIs<ProgrammerClearAllInMessage>(clearAll).fadeMs)

        val blind = json.decodeFromString<InMessage>("""{"type":"programmer.setBlind","blind":true}""")
        assertTrue(assertIs<ProgrammerSetBlindInMessage>(blind).blind)
    }

    @Test
    fun `programmer domain — provenanceState round-trips with discriminator`() {
        val out = ProvenanceStateOutMessage(
            entries = listOf(
                ProvenanceEntryDto("hex-1", "dimmer", source = "PROGRAMMER"),
                ProvenanceEntryDto("hex-2", "rgbColour", source = "CUE", cueId = 42),
            ),
        )
        val encoded = json.encodeToString<OutMessage>(out)
        assertTrue(encoded.contains(""""type":"provenanceState""""))
        assertEquals(out, assertIs<ProvenanceStateOutMessage>(json.decodeFromString<OutMessage>(encoded)))
    }

    @Test
    fun `programmer domain — provenanceState carries programmerRevision on the wire`() {
        // The refetch-suppression signal for crossfade weight ticks: the client refetches
        // programmer.state only when the revision moved. It must survive encoding — a client
        // that never sees it refetches ~10×/s for the whole fade, which is the regression
        // this field exists to prevent. (A frame omitting it — an older server, or the
        // pre-first-trigger default of 0 — makes the client refetch every frame: fail-safe.)
        val out = ProvenanceStateOutMessage(entries = emptyList(), programmerRevision = 7)
        val encoded = json.encodeToString<OutMessage>(out)
        assertTrue(encoded.contains(""""programmerRevision":7"""))
        assertEquals(out, assertIs<ProvenanceStateOutMessage>(json.decodeFromString<OutMessage>(encoded)))
    }

    @Test
    fun `programmer domain — set accepts an optional sourceGroup hint`() {
        // For clients that fan a group gesture out to members (group virtual dimmer, Highlight
        // release) rather than sending targetType=group. Optional, so existing senders are
        // unaffected.
        val withHint = json.decodeFromString<InMessage>(
            """{"type":"programmer.set","targetType":"fixture","targetKey":"hex-1","propertyName":"dimmer","value":"200","sourceGroup":"front-wash"}"""
        )
        assertEquals("front-wash", assertIs<ProgrammerSetInMessage>(withHint).sourceGroup)

        val without = json.decodeFromString<InMessage>(
            """{"type":"programmer.set","targetType":"fixture","targetKey":"hex-1","propertyName":"dimmer","value":"200"}"""
        )
        assertEquals(null, assertIs<ProgrammerSetInMessage>(without).sourceGroup)
    }

    @Test
    fun `programmer domain — includeTarget round-trips, including the cleared form`() {
        val set = ProgrammerIncludeTargetOutMessage(
            IncludedTargetDto(kind = "CUE", cueId = 42, cueStackId = 7, cueName = "Look 1", cueNumber = "1"),
        )
        val encoded = json.encodeToString<OutMessage>(set)
        assertTrue(encoded.contains(""""type":"programmer.includeTarget""""))
        assertEquals(set, assertIs<ProgrammerIncludeTargetOutMessage>(json.decodeFromString<OutMessage>(encoded)))

        // Clear sends a null target; the client must be able to tell "no target" from "no message".
        val cleared = ProgrammerIncludeTargetOutMessage(null)
        assertEquals(
            cleared,
            assertIs<ProgrammerIncludeTargetOutMessage>(
                json.decodeFromString<OutMessage>(json.encodeToString<OutMessage>(cleared)),
            ),
        )
    }

    @Test
    fun `programmer domain — state out message round-trips with discriminator`() {
        val out = ProgrammerStateOutMessage(
            blind = false,
            entries = listOf(
                ProgrammerEntryDto(
                    targetKey = "hex-1", propertyName = "dimmer", value = "200",
                    owner = "web", touched = true, sourceGroup = null, owners = listOf("web"),
                ),
            ),
            channels = listOf(ProgrammerChannelDto(0, 7, 55u, "unpark", touched = false)),
        )
        val encoded = json.encodeToString<OutMessage>(out)
        assertTrue(encoded.contains(""""type":"programmer.state""""))
        assertEquals(out, assertIs<ProgrammerStateOutMessage>(json.decodeFromString<OutMessage>(encoded)))
    }

    // ─── Broadcast domain (out-only) ────────────────────────────────────────

    @Test
    fun `broadcast domain — ShowChangedOutMessage round-trips with discriminator`() {
        val out = ShowChangedOutMessage(
            projectId = 3,
            activeStackId = 2,
            activeStackName = "Act 1",
        )
        val encoded = json.encodeToString<OutMessage>(out)
        assertTrue(encoded.contains(""""type":"showChanged""""))
        assertEquals(out, assertIs<ShowChangedOutMessage>(json.decodeFromString<OutMessage>(encoded)))
    }

    @Test
    fun `broadcast domain — CueRunStateChangedOutMessage round-trips with discriminator`() {
        val out = CueRunStateChangedOutMessage.of(
            CueRunState(
                projectId = 3,
                stackId = 4,
                activeCueId = 11,
                nextCueId = 12,
                nextIsArmed = true,
                transition = true,
                fadeDurationMs = 2000,
                fadeElapsedMs = 0,
                autoAdvance = false,
                autoAdvanceDelayMs = null,
            )
        )
        val encoded = json.encodeToString<OutMessage>(out)
        assertTrue(encoded.contains(""""type":"cueRunStateChanged""""))
        assertEquals(out, assertIs<CueRunStateChangedOutMessage>(json.decodeFromString<OutMessage>(encoded)))
    }

    @Test
    fun `broadcast domain — list-changed objects emit the bare discriminator`() {
        val encoded = json.encodeToString<OutMessage>(LookListChangedOutMessage)
        assertEquals("""{"type":"lookListChanged"}""", encoded)
    }

    // ─── Machine domain (out-only) ──────────────────────────────────────────

    /**
     * All three are payload-free on purpose: these sockets are open to operators while
     * `/api/rest/users` is admin-only, so anything in the body would leak what that gate
     * withholds. An assertion on the *exact* encoding is therefore the point here, not just the
     * discriminator — a field added later fails this rather than shipping quietly.
     */
    @Test
    fun `machine domain — change objects emit the bare discriminator and nothing else`() {
        assertEquals(
            """{"type":"userListChanged"}""",
            json.encodeToString<OutMessage>(UserListChangedOutMessage),
        )
        assertEquals(
            """{"type":"ownAccountChanged"}""",
            json.encodeToString<OutMessage>(OwnAccountChangedOutMessage),
        )
        assertEquals(
            """{"type":"installChanged"}""",
            json.encodeToString<OutMessage>(InstallChangedOutMessage),
        )
    }

    @Test
    fun `machine domain — leaves decode back under MachineOutMessage`() {
        // Catches the mistake this file exists for: a leaf parented directly on `OutMessage`
        // instead of the domain's sealed subclass still serialises, but breaks the collector's
        // ability to treat the family as one thing.
        val decoded = json.decodeFromString<OutMessage>("""{"type":"userListChanged"}""")
        assertIs<MachineOutMessage>(decoded)
        assertIs<UserListChangedOutMessage>(decoded)
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
