package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.fx.BeatDivision
import uk.me.cormack.lighting7.fx.FxEngine
import uk.me.cormack.lighting7.fx.FxInstance
import uk.me.cormack.lighting7.fx.FxTargetRef
import uk.me.cormack.lighting7.fx.FxTiming
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.fx.effects.SineWave
import uk.me.cormack.lighting7.fx.SliderTarget
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.plugins.ProgrammerHandler
import uk.me.cormack.lighting7.plugins.UpdateChannelInMessage
import uk.me.cormack.lighting7.plugins.handleUpdateChannel
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `POST /api/rest/programmer/record`.
 *
 * The headline case is `records a value that only exists in the programmer` — that is the §1
 * "Record is lossy" bug the whole redesign exists to fix, and the one regression that must
 * never come back.
 */
class ProgrammerRecordRouteTest : RouteIntegrationTest() {

    // Hex at channel 1: dimmer=1, R/G/B=2/3/4, amber=5, white=6, uv=7, strobe=8.

    @Test
    fun `records a value that only exists in the programmer`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        val stackId = createStack(client, "stack-a")

        setProgrammer("hex-1", "dimmer", "200")

        val response: ProgrammerRecordResponse = client.record(
            ProgrammerRecordRequest(
                projectId = projectId.toString(),
                mode = "CREATE",
                cueStackId = stackId,
                name = "busked",
            )
        ).body()

        assertTrue(response.created)
        val row = response.cue.propertyAssignments.single { it.propertyName == "dimmer" }
        assertEquals("fixture", row.targetType)
        assertEquals("hex-1", row.targetKey)
        assertEquals("200", row.value)
    }

    @Test
    fun `does not record a value that only a running cue asserts`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        val stackId = createStack(client, "stack-a")

        // A cue on stage, nothing in the programmer. TOUCHED records the operator's edits,
        // not the show — otherwise every Record would smear the running look into the new cue.
        state.show.fxEngine.setCueAssignments(
            991,
            listOf(
                CueAssignmentResolver.Assignment(
                    cueId = 991, priority = 10, fadeWeight = 1.0,
                    targetKey = "hex-1", targetIsGroup = false, propertyName = "dimmer",
                    category = uk.me.cormack.lighting7.fixture.PropertyCategory.DIMMER,
                    value = CueAssignmentResolver.PropertyValue.Slider(90u),
                )
            ),
            cueStackId = stackId,
        )

        val response: ProgrammerRecordResponse = client.record(
            ProgrammerRecordRequest(
                projectId = projectId.toString(), mode = "CREATE", cueStackId = stackId,
            )
        ).body()

        assertTrue(
            response.cue.propertyAssignments.isEmpty(),
            "TOUCHED records the programmer, not composed stage state; got ${response.cue.propertyAssignments}",
        )
    }

    @Test
    fun `STAGE_SNAPSHOT captures both the cue layer and the programmer`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        seedHex("hex-2", 13)
        val stackId = createStack(client, "stack-a")

        state.show.fxEngine.setCueAssignments(
            992,
            listOf(
                CueAssignmentResolver.Assignment(
                    cueId = 992, priority = 10, fadeWeight = 1.0,
                    targetKey = "hex-2", targetIsGroup = false, propertyName = "dimmer",
                    category = uk.me.cormack.lighting7.fixture.PropertyCategory.DIMMER,
                    value = CueAssignmentResolver.PropertyValue.Slider(90u),
                )
            ),
            cueStackId = stackId,
        )
        setProgrammer("hex-1", "dimmer", "200")

        val response: ProgrammerRecordResponse = client.record(
            ProgrammerRecordRequest(
                projectId = projectId.toString(), mode = "CREATE",
                cueStackId = stackId, source = "STAGE_SNAPSHOT",
            )
        ).body()

        val rows = response.cue.propertyAssignments.associate { it.targetKey to it.value }
        assertEquals("90", rows["hex-2"], "the cue layer is in the snapshot")
        // The old snapshot-from-live read Layer 4 only, so this row was silently dropped.
        assertEquals("200", rows["hex-1"], "the programmer overlays the snapshot")
    }

    @Test
    fun `a group write records as a group row, and a member override breaks it up`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        seedHex("hex-2", 13)
        LocateTestSupport.seedGroup(state, projectId, "front-wash", "hex-1", "hex-2")
        val stackId = createStack(client, "stack-a")

        ProgrammerHandler.set(state, TargetRef.Group("front-wash"), "dimmer", "150", 0)

        val grouped: ProgrammerRecordResponse = client.record(
            ProgrammerRecordRequest(
                projectId = projectId.toString(), mode = "CREATE", cueStackId = stackId,
            )
        ).body()
        val row = grouped.cue.propertyAssignments.single { it.propertyName == "dimmer" }
        assertEquals("group", row.targetType)
        assertEquals("front-wash", row.targetKey)
        assertEquals(1, grouped.groupRowsEmitted)

        // Break the uniformity: the group shape is no longer a true statement about the rig.
        setProgrammer("hex-2", "dimmer", "40")
        val split: ProgrammerRecordResponse = client.record(
            ProgrammerRecordRequest(
                projectId = projectId.toString(), mode = "CREATE", cueStackId = stackId,
            )
        ).body()
        val dimmerRows = split.cue.propertyAssignments.filter { it.propertyName == "dimmer" }
        assertEquals(0, split.groupRowsEmitted)
        assertEquals(setOf("hex-1", "hex-2"), dimmerRows.map { it.targetKey }.toSet())
        assertTrue(dimmerRows.all { it.targetType == "fixture" })
    }

    @Test
    fun `mask scopes what is recorded`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        val stackId = createStack(client, "stack-a")

        setProgrammer("hex-1", "dimmer", "200")
        setProgrammer("hex-1", "rgbColour", "#ff0000")

        val response: ProgrammerRecordResponse = client.record(
            ProgrammerRecordRequest(
                projectId = projectId.toString(), mode = "CREATE",
                cueStackId = stackId, mask = listOf("COLOUR"),
            )
        ).body()

        assertEquals(
            listOf("rgbColour"),
            response.cue.propertyAssignments.map { it.propertyName },
        )
        assertTrue(response.skipped.any { it.propertyName == "dimmer" && it.reason == "MASKED_OUT" })
    }

    @Test
    fun `UPDATE_EXISTING with a mask replaces only the masked rows`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        val stackId = createStack(client, "stack-a")

        setProgrammer("hex-1", "dimmer", "200")
        setProgrammer("hex-1", "rgbColour", "#ff0000")
        val created: ProgrammerRecordResponse = client.record(
            ProgrammerRecordRequest(
                projectId = projectId.toString(), mode = "CREATE", cueStackId = stackId,
            )
        ).body()
        val cueId = created.cue.id

        // Re-record just the colour: the dimmer row must survive untouched.
        clearProgrammer()
        setProgrammer("hex-1", "rgbColour", "#00ff00")
        val updated: ProgrammerRecordResponse = client.record(
            ProgrammerRecordRequest(
                projectId = projectId.toString(), mode = "UPDATE_EXISTING",
                cueId = cueId, mask = listOf("COLOUR"),
            )
        ).body()

        val rows = updated.cue.propertyAssignments.associate { it.propertyName to it.value }
        assertEquals("200", rows["dimmer"], "an out-of-mask row must not be deleted")
        assertTrue(rows.getValue("rgbColour").startsWith("#00ff00"))
    }

    @Test
    fun `MERGE keeps rows the recording does not name`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        seedHex("hex-2", 13)
        val stackId = createStack(client, "stack-a")

        setProgrammer("hex-1", "dimmer", "200")
        val created: ProgrammerRecordResponse = client.record(
            ProgrammerRecordRequest(
                projectId = projectId.toString(), mode = "CREATE", cueStackId = stackId,
            )
        ).body()

        clearProgrammer()
        setProgrammer("hex-2", "dimmer", "50")
        val merged: ProgrammerRecordResponse = client.record(
            ProgrammerRecordRequest(
                projectId = projectId.toString(), mode = "MERGE", cueId = created.cue.id,
            )
        ).body()

        val rows = merged.cue.propertyAssignments.associate { it.targetKey to it.value }
        assertEquals("200", rows["hex-1"])
        assertEquals("50", rows["hex-2"])
    }

    @Test
    fun `REMOVE deletes only exact key matches and warns about a covering group row`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        seedHex("hex-2", 13)
        LocateTestSupport.seedGroup(state, projectId, "front-wash", "hex-1", "hex-2")
        val stackId = createStack(client, "stack-a")

        ProgrammerHandler.set(state, TargetRef.Group("front-wash"), "dimmer", "150", 0)
        val created: ProgrammerRecordResponse = client.record(
            ProgrammerRecordRequest(
                projectId = projectId.toString(), mode = "CREATE", cueStackId = stackId,
            )
        ).body()
        assertEquals("group", created.cue.propertyAssignments.single().targetType)

        // Now try to remove just one member. The group row covers both, so deleting it would
        // silently drop hex-1 too — say so rather than guessing.
        clearProgrammer()
        setProgrammer("hex-2", "dimmer", "0")
        val removed: ProgrammerRecordResponse = client.record(
            ProgrammerRecordRequest(
                projectId = projectId.toString(), mode = "REMOVE", cueId = created.cue.id,
            )
        ).body()

        assertEquals(1, removed.cue.propertyAssignments.size, "the group row survives")
        assertTrue(
            removed.warnings.any { it.contains("front-wash") && it.contains("hex-2") },
            "expected a covering-group warning; got ${removed.warnings}",
        )
    }

    @Test
    fun `a newer sideband write beats an older property entry on the same property`() =
        testApplication {
            mountTestApp(state)
            val client = jsonClient()
            seedHex("hex-1", 1)
            val stackId = createStack(client, "stack-a")

            // The render path arbitrates a property entry against a covering sideband slot by
            // recency (FxTarget compares Slot.seq), so Record has to as well — otherwise the
            // stage shows the newer raw write and the cue records the older property value.
            setProgrammer("hex-1", "dimmer", "200")
            state.show.programmerStore.putChannel(ProgrammerOwner.WEB, 0, 1, 40u)

            val response: ProgrammerRecordResponse = client.record(
                ProgrammerRecordRequest(
                    projectId = projectId.toString(), mode = "CREATE", cueStackId = stackId,
                )
            ).body()

            assertEquals(
                "40",
                response.cue.propertyAssignments.single { it.propertyName == "dimmer" }.value,
                "the newer write is what's on stage, so it is what records",
            )
        }

    @Test
    fun `an older sideband write does not displace a newer property entry`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        val stackId = createStack(client, "stack-a")

        state.show.programmerStore.putChannel(ProgrammerOwner.WEB, 0, 1, 40u)
        setProgrammer("hex-1", "dimmer", "200")

        val response: ProgrammerRecordResponse = client.record(
            ProgrammerRecordRequest(
                projectId = projectId.toString(), mode = "CREATE", cueStackId = stackId,
            )
        ).body()

        assertEquals(
            "200",
            response.cue.propertyAssignments.single { it.propertyName == "dimmer" }.value,
        )
    }

    @Test
    fun `STAGE_SNAPSHOT captures a raw sideband write that a property covers`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        val stackId = createStack(client, "stack-a")

        // No property-level entries at all — only a raw channel write. A stage snapshot that
        // read the store's property map alone would miss this entirely and record the cue
        // layer's value instead of what is actually lit.
        state.show.programmerStore.putChannel(ProgrammerOwner.WEB, 0, 1, 175u)

        val response: ProgrammerRecordResponse = client.record(
            ProgrammerRecordRequest(
                projectId = projectId.toString(), mode = "CREATE",
                cueStackId = stackId, source = "STAGE_SNAPSHOT",
            )
        ).body()

        assertEquals(
            "175",
            response.cue.propertyAssignments.single { it.propertyName == "dimmer" }.value,
        )
    }

    @Test
    fun `an unpark hand-down is excluded by TOUCHED and included by ALL`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        val stackId = createStack(client, "stack-a")

        // The unpark sink is channel-shaped and writes touched=false — the only producer of
        // untouched slots anywhere in the store.
        state.show.programmerStore.putChannel(ProgrammerOwner.UNPARK, 0, 1, 77u, touched = false)

        val touched: ProgrammerRecordResponse = client.record(
            ProgrammerRecordRequest(
                projectId = projectId.toString(), mode = "CREATE", cueStackId = stackId,
            )
        ).body()
        assertTrue(
            touched.cue.propertyAssignments.none { it.propertyName == "dimmer" },
            "an unpark hand-down is not an operator edit",
        )

        val all: ProgrammerRecordResponse = client.record(
            ProgrammerRecordRequest(
                projectId = projectId.toString(), mode = "CREATE",
                cueStackId = stackId, source = "ALL",
            )
        ).body()
        assertEquals(
            "77",
            all.cue.propertyAssignments.single { it.propertyName == "dimmer" }.value,
            "ALL records the rig as the programmer holds it, hand-downs included",
        )
    }

    @Test
    fun `an unbacked sideband channel is reported as a skip, not recorded`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        val stackId = createStack(client, "stack-a")

        // Channel 100 is beyond the hex's patch — no property backs it, so there is no
        // (target, property) a cue assignment could name.
        handleUpdateChannel(state, UpdateChannelInMessage(0, 100, 90u, fadeTime = 0))

        val response: ProgrammerRecordResponse = client.record(
            ProgrammerRecordRequest(
                projectId = projectId.toString(), mode = "CREATE", cueStackId = stackId,
            )
        ).body()

        assertTrue(response.cue.propertyAssignments.isEmpty())
        val skip = response.skipped.single()
        assertEquals("NO_BACKING_PROPERTY", skip.reason)
        assertEquals(100, skip.channel)
    }

    @Test
    fun `a raw pan-channel drag records as a pan row`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedMovingHead("mh-1", 1)
        val stackId = createStack(client, "stack-a")

        val pan = state.show.fixtures.untypedFixture("mh-1")
            .let { (it as uk.me.cormack.lighting7.fixture.trait.WithPosition).pan }
            as uk.me.cormack.lighting7.fixture.dmx.DmxSlider
        handleUpdateChannel(state, UpdateChannelInMessage(0, pan.channelNo, 128u, fadeTime = 0))

        val response: ProgrammerRecordResponse = client.record(
            ProgrammerRecordRequest(
                projectId = projectId.toString(), mode = "CREATE", cueStackId = stackId,
            )
        ).body()

        // A head with an annotated `pan` property resolves the channel to that property, so
        // the drag lifts straight to a `pan` entry. (The synthetic `position` pairing is the
        // fallback for heads whose axes aren't annotated separately — that path goes through
        // the sideband, exercised by the unpark case above.)
        val row = response.cue.propertyAssignments.singleOrNull { it.propertyName == "pan" }
        assertNotNull(row, "raw axis drags used to vanish from a capture; got ${response.cue.propertyAssignments}")
        assertEquals("128", row.value)
    }

    @Test
    fun `recording into a cue with an open cue-edit session needs force`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        val stackId = createStack(client, "stack-a")

        setProgrammer("hex-1", "dimmer", "200")
        val created: ProgrammerRecordResponse = client.record(
            ProgrammerRecordRequest(
                projectId = projectId.toString(), mode = "CREATE", cueStackId = stackId,
            )
        ).body()

        val sessionRef = java.util.concurrent.atomic.AtomicReference<
            uk.me.cormack.lighting7.plugins.CueEditSessionState?
            >(null)
        uk.me.cormack.lighting7.plugins.CueEditSessionHandler.beginEdit(
            state, sessionRef, created.cue.id, "BLIND",
        )

        val blocked = client.record(
            ProgrammerRecordRequest(
                projectId = projectId.toString(), mode = "MERGE", cueId = created.cue.id,
            )
        )
        assertEquals(HttpStatusCode.Conflict, blocked.status)
        assertEquals("CUE_EDIT_SESSION_OPEN", blocked.body<ProgrammerConflictResponse>().code)

        val forced = client.record(
            ProgrammerRecordRequest(
                projectId = projectId.toString(), mode = "MERGE",
                cueId = created.cue.id, force = true,
            )
        )
        assertEquals(HttpStatusCode.OK, forced.status)
    }

    @Test
    fun `targets scope what is recorded`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        seedHex("hex-2", 13)
        val stackId = createStack(client, "stack-a")

        setProgrammer("hex-1", "dimmer", "200")
        setProgrammer("hex-2", "dimmer", "50")

        // "Record just these heads into this cue" — hex-2 is in the programmer but not selected.
        val response: ProgrammerRecordResponse = client.record(
            ProgrammerRecordRequest(
                projectId = projectId.toString(), mode = "CREATE", cueStackId = stackId,
                targets = listOf(CueTargetDto(type = "fixture", key = "hex-1")),
            )
        ).body()

        assertEquals(
            listOf("hex-1"),
            response.cue.propertyAssignments.map { it.targetKey },
        )
        assertTrue(
            response.skipped.any { it.targetKey == "hex-2" && it.reason == "OUT_OF_SCOPE" },
            "the out-of-scope entry is reported rather than silently dropped",
        )
    }

    @Test
    fun `a group target expands to its members`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        seedHex("hex-2", 13)
        seedHex("hex-3", 25)
        LocateTestSupport.seedGroup(state, projectId, "front-wash", "hex-1", "hex-2")
        val stackId = createStack(client, "stack-a")

        setProgrammer("hex-1", "dimmer", "200")
        setProgrammer("hex-2", "dimmer", "200")
        setProgrammer("hex-3", "dimmer", "10")

        val response: ProgrammerRecordResponse = client.record(
            ProgrammerRecordRequest(
                projectId = projectId.toString(), mode = "CREATE", cueStackId = stackId,
                targets = listOf(CueTargetDto(type = "group", key = "front-wash")),
            )
        ).body()

        // Both members are in scope, so the group collapse still applies — scoping narrows what
        // is captured, it doesn't force the rows to be per-fixture.
        val targets = response.cue.propertyAssignments.map { it.targetKey }.toSet()
        assertTrue("hex-3" !in targets, "hex-3 is outside the group; got $targets")
        assertTrue(response.skipped.any { it.targetKey == "hex-3" && it.reason == "OUT_OF_SCOPE" })
    }

    @Test
    fun `UPDATE_EXISTING preserves rows outside the scope`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        seedHex("hex-2", 13)
        val stackId = createStack(client, "stack-a")

        setProgrammer("hex-1", "dimmer", "200")
        setProgrammer("hex-2", "dimmer", "50")
        val created: ProgrammerRecordResponse = client.record(
            ProgrammerRecordRequest(
                projectId = projectId.toString(), mode = "CREATE", cueStackId = stackId,
            )
        ).body()
        val cueId = created.cue.id

        // Re-record only hex-1. Without the scope guard UPDATE_EXISTING deletes every row it
        // wasn't handed, which would silently drop hex-2 out of the cue.
        clearProgrammer()
        setProgrammer("hex-1", "dimmer", "255")
        val updated: ProgrammerRecordResponse = client.record(
            ProgrammerRecordRequest(
                projectId = projectId.toString(), mode = "UPDATE_EXISTING", cueId = cueId,
                targets = listOf(CueTargetDto(type = "fixture", key = "hex-1")),
            )
        ).body()

        val rows = updated.cue.propertyAssignments.associate { it.targetKey to it.value }
        assertEquals("255", rows["hex-1"])
        assertEquals("50", rows["hex-2"], "an out-of-scope row must not be deleted")
        assertEquals(1, updated.preserved.outOfScopeAssignments)
    }

    @Test
    fun `a group row survives a re-record that only covers some of its members`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        seedHex("hex-2", 13)
        LocateTestSupport.seedGroup(state, projectId, "front-wash", "hex-1", "hex-2")
        val stackId = createStack(client, "stack-a")

        ProgrammerHandler.set(state, TargetRef.Group("front-wash"), "dimmer", "150", 0)
        val created: ProgrammerRecordResponse = client.record(
            ProgrammerRecordRequest(
                projectId = projectId.toString(), mode = "CREATE", cueStackId = stackId,
            )
        ).body()
        assertEquals("group", created.cue.propertyAssignments.single().targetType)

        // Only hex-1 is selected. Replacing the group row would rewrite hex-2's value too, so
        // the row is preserved and the new fixture row lands beside it.
        clearProgrammer()
        setProgrammer("hex-1", "dimmer", "255")
        val updated: ProgrammerRecordResponse = client.record(
            ProgrammerRecordRequest(
                projectId = projectId.toString(), mode = "UPDATE_EXISTING", cueId = created.cue.id,
                targets = listOf(CueTargetDto(type = "fixture", key = "hex-1")),
            )
        ).body()

        assertEquals(1, updated.preserved.outOfScopeAssignments)
        val byTarget = updated.cue.propertyAssignments.associate { it.targetKey to it.value }
        assertEquals("150", byTarget["front-wash"], "the partly-covered group row survives")
        assertEquals("255", byTarget["hex-1"])
    }

    @Test
    fun `scope applies to a STAGE_SNAPSHOT record`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        seedHex("hex-2", 13)
        val stackId = createStack(client, "stack-a")

        // This source bypasses collectProgrammerEntries entirely, so it needs its own scope
        // filter — the easiest place for a scoped record to silently capture the whole rig.
        state.show.fxEngine.setCueAssignments(
            993,
            listOf(
                CueAssignmentResolver.Assignment(
                    cueId = 993, priority = 10, fadeWeight = 1.0,
                    targetKey = "hex-2", targetIsGroup = false, propertyName = "dimmer",
                    category = uk.me.cormack.lighting7.fixture.PropertyCategory.DIMMER,
                    value = CueAssignmentResolver.PropertyValue.Slider(90u),
                )
            ),
            cueStackId = stackId,
        )
        setProgrammer("hex-1", "dimmer", "200")

        val response: ProgrammerRecordResponse = client.record(
            ProgrammerRecordRequest(
                projectId = projectId.toString(), mode = "CREATE", cueStackId = stackId,
                source = "STAGE_SNAPSHOT",
                targets = listOf(CueTargetDto(type = "fixture", key = "hex-1")),
            )
        ).body()

        assertEquals(
            listOf("hex-1"),
            response.cue.propertyAssignments.map { it.targetKey }.distinct(),
        )
        assertTrue(response.skipped.any { it.targetKey == "hex-2" && it.reason == "OUT_OF_SCOPE" })
    }

    @Test
    fun `a scoped re-record does not stack an effect a surviving group row already covers`() =
        testApplication {
            mountTestApp(state)
            val client = jsonClient()
            seedHex("hex-1", 1)
            seedHex("hex-2", 13)
            LocateTestSupport.seedGroup(state, projectId, "front-wash", "hex-1", "hex-2")
            val stackId = createStack(client, "stack-a")

            // A cue whose effect is asserted on the whole group.
            ProgrammerHandler.set(state, TargetRef.Group("front-wash"), "dimmer", "150", 0)
            state.show.fxEngine.addEffect(programmerBandSine(FxTargetRef.group("front-wash")))
            val created: ProgrammerRecordResponse = client.record(
                ProgrammerRecordRequest(
                    projectId = projectId.toString(), mode = "CREATE", cueStackId = stackId,
                )
            ).body()
            val cueId = created.cue.id

            // Re-record only hex-1, with the same effect now running on hex-1 alone.
            clearProgrammer()
            state.show.fxEngine.getActiveEffects().forEach { state.show.fxEngine.removeEffect(it.id) }
            setProgrammer("hex-1", "dimmer", "255")
            state.show.fxEngine.addEffect(programmerBandSine(FxTargetRef.fixture("hex-1")))
            val updated: ProgrammerRecordResponse = client.record(
                ProgrammerRecordRequest(
                    projectId = projectId.toString(), mode = "UPDATE_EXISTING", cueId = cueId,
                    targets = listOf(CueTargetDto(type = "fixture", key = "hex-1")),
                )
            ).body()

            // The group row survives (hex-2 is outside the selection), so a second row naming
            // hex-1 would run the same effect twice on it — cue activation instantiates per row
            // per target with no de-duplication.
            val dimmerEffects = updated.cue.adHocEffects.filter { it.propertyName == "dimmer" }
            val coverage = dimmerEffects.flatMap { effect ->
                if (effect.targetType == TargetRef.Group.TYPE) listOf("hex-1", "hex-2")
                else listOf(effect.targetKey)
            }
            assertEquals(
                coverage.distinct().size, coverage.size,
                "an effect must not be asserted twice for the same fixture; got $dimmerEffects",
            )
        }

    @Test
    fun `targets that resolve to nothing are rejected`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        val stackId = createStack(client, "stack-a")
        setProgrammer("hex-1", "dimmer", "200")

        // A group is the case that can expand to nothing — `expandTargetsToFixtureKeys` looks up
        // group membership but passes fixture keys through unchecked, leaving a bad one to be
        // reported per-entry as MISSING_FIXTURE rather than rejected up front.
        //
        // Recording the whole programmer because the selection turned out to be empty would be
        // the wrong recovery: the operator asked for a narrower record, not a wider one.
        val response = client.record(
            ProgrammerRecordRequest(
                projectId = projectId.toString(), mode = "CREATE", cueStackId = stackId,
                targets = listOf(CueTargetDto(type = "group", key = "no-such-group")),
            )
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `record points the include target at the cue it wrote`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHex("hex-1", 1)
        val stackId = createStack(client, "stack-a")

        assertNull(state.show.programmerStore.lastIncludedTarget)
        setProgrammer("hex-1", "dimmer", "200")
        val created: ProgrammerRecordResponse = client.record(
            ProgrammerRecordRequest(
                projectId = projectId.toString(), mode = "CREATE", cueStackId = stackId,
            )
        ).body()

        // Record → tweak → Update is the obvious next gesture.
        val target = state.show.programmerStore.lastIncludedTarget
        assertNotNull(target)
        assertEquals(created.cue.id, target.cueId)
        assertEquals(stackId, target.cueStackId)
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private suspend fun HttpClient.record(request: ProgrammerRecordRequest) =
        post("/api/rest/programmer/record") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    private suspend fun createStack(client: HttpClient, name: String): Int =
        ProgrammerRouteTestSupport.createStack(client, projectId, name)

    private fun setProgrammer(fixtureKey: String, property: String, value: String) {
        ProgrammerHandler.set(state, TargetRef.Fixture(fixtureKey), property, value, 0)
    }

    private fun clearProgrammer() = clearProgrammerCompletely(state)

    /** A programmer-band sine on [target]'s dimmer — what busking an effect leaves running. */
    private fun programmerBandSine(target: FxTargetRef) = FxInstance(
        effect = SineWave(),
        target = SliderTarget(target, "dimmer"),
        timing = FxTiming(beatDivision = BeatDivision.QUARTER),
    ).apply { priority = FxEngine.PROGRAMMER_FX_PRIORITY_BASE }

    private fun seedHex(key: String, startChannel: Int) =
        LocateTestSupport.seedHex(state, projectId, key, startChannel)

    private fun seedMovingHead(key: String, startChannel: Int) =
        LocateTestSupport.seedFixture(state, projectId, "martin-mac-250-mode-4", key, startChannel)
}
