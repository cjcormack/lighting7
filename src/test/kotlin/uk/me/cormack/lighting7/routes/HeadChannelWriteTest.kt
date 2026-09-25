package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.CueApplyData
import uk.me.cormack.lighting7.fx.ExtendedColour
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.fx.PropertyChannelWriter
import uk.me.cormack.lighting7.fx.ProvenanceSource
import uk.me.cormack.lighting7.fx.buildCueAssignmentsForCue
import uk.me.cormack.lighting7.models.AssignmentHealth
import uk.me.cormack.lighting7.models.CuePropertyAssignmentDto
import uk.me.cormack.lighting7.plugins.UpdateChannelInMessage
import uk.me.cormack.lighting7.plugins.buildChannelMappingMessage
import uk.me.cormack.lighting7.plugins.handleUpdateChannel
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import java.awt.Color
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A raw channel write to a **head** of a multi-head fixture behaves like one to a whole fixture.
 *
 * The desk's covering lookup (`CascadePublisher.resolveChannelCoveringKey`) used to walk only the
 * owning fixture's own properties, so on a bar that keeps its channels on its heads every head
 * channel resolved to nothing: `updateChannel` held a raw byte in the sideband, Record dropped it
 * as `NO_BACKING_PROPERTY`, Clear and Blind sent it to DMX 0 instead of the layer beneath, and
 * provenance named no key. Each test below pins one of those readers.
 *
 * The bar is `led-lightbar-12-pixel-48ch` at channel 1 — twelve RGBW heads, four channels each, the
 * parent declaring none. Head 3 is `bar.pixel-2`: R/G/B 9/10/11, W 12.
 */
class HeadChannelWriteTest : RouteIntegrationTest() {

    private val head = "bar.pixel-2"

    private fun busk(channel: Int, level: UByte) =
        handleUpdateChannel(state, UpdateChannelInMessage(0, channel, level, fadeTime = 0))

    private fun seedBar() =
        LocateTestSupport.seedFixture(state, projectId, "led-lightbar-12-pixel-48ch", "bar", 1)

    private fun controller() = state.show.fixtures.controllerOrNull(Universe(0, 0)) as MockDmxController

    /**
     * A cue under two heads — what Clear and Blind must fall back to. Head 3's colour (red 50) and
     * head 4's white (70) as separate keys, so each check below exercises one key alone: a colour
     * key and a white key on the *same* head share the W channel, and which publish lands last
     * there is a question about bundled emitters, not about heads.
     */
    private fun cueUnderHeads() {
        state.show.fxEngine.cueLayer.setAssignments(
            993,
            listOf(
                CueAssignmentResolver.Assignment(
                    cueId = 993, priority = 10, fadeWeight = 1.0,
                    targetKey = head, targetIsGroup = false, propertyName = "rgbColour",
                    category = PropertyCategory.COLOUR,
                    value = CueAssignmentResolver.PropertyValue.Colour(ExtendedColour(Color(50, 0, 0))),
                ),
                CueAssignmentResolver.Assignment(
                    cueId = 993, priority = 10, fadeWeight = 1.0,
                    targetKey = "bar.pixel-3", targetIsGroup = false, propertyName = "white",
                    category = PropertyCategory.WHITE,
                    value = CueAssignmentResolver.PropertyValue.Slider(70u),
                ),
            ),
        )
    }

    @Test
    fun `the covering lookup names the head, and follows a re-patch`() = testApplication {
        mountTestApp(state)
        seedBar()
        val cascade = state.show.fxEngine.cascade

        assertEquals(CueAssignmentResolver.Key.fixture(head, "rgbColour"), cascade.resolveChannelCoveringKey(0, 9))
        assertEquals(CueAssignmentResolver.Key.fixture(head, "white"), cascade.resolveChannelCoveringKey(0, 12))
        assertNull(cascade.resolveChannelCoveringKey(0, 60), "nothing is patched at 60 yet")

        // One walk per register version, shared by the covering lookup and the mapping frame.
        val fixtures = state.show.fixtures
        val index = PropertyChannelWriter.channelKeyIndex(fixtures)
        buildChannelMappingMessage(fixtures)
        assertSame(index, PropertyChannelWriter.channelKeyIndex(fixtures), "the mapping frame reused the walk")

        // A patch edit must not leave it stale.
        LocateTestSupport.seedHex(state, projectId, "hex-1", 60)
        assertEquals(CueAssignmentResolver.Key.fixture("hex-1", "dimmer"), cascade.resolveChannelCoveringKey(0, 60))
        assertNotSame(index, PropertyChannelWriter.channelKeyIndex(fixtures))
    }

    @Test
    fun `a head's red write lifts into the head's colour, keeping its white`() = testApplication {
        mountTestApp(state)
        seedBar()

        busk(12, 200u)
        assertEquals(
            CueAssignmentResolver.PropertyValue.Slider(200u),
            state.show.programmerStore.get(head, "white")?.value?.resolved,
            "the head's white is its own slider",
        )

        busk(9, 180u)
        val colour = state.show.programmerStore.get(head, "rgbColour")?.value?.resolved
            as? CueAssignmentResolver.PropertyValue.Colour
        assertNotNull(colour, "red lifts to the head's rgbColour, not a raw sideband byte")
        assertEquals(180, colour.value.color.red)
        assertEquals(200u.toUByte(), colour.value.white, "the head's white is read, not taken as 0")
        assertNull(state.show.programmerStore.getChannel(0, 9), "nothing is left in the sideband")

        assertEquals(180u.toUByte(), controller().getEffectiveValue(9))
        assertEquals(200u.toUByte(), controller().getEffectiveValue(12), "the red write did not dim the white")
    }

    @Test
    fun `provenance names the head for a head write and for a sideband slot`() = testApplication {
        mountTestApp(state)
        seedBar()

        busk(9, 180u)
        // A raw sideband slot on another head's channel — an unpark hand-down, say. Provenance
        // attributes it only through the covering lookup.
        state.show.fxEngine.programmer.writeChannel(
            ProgrammerOwner.WEB, 0, 16, 90u, coveringKey = null,
        )

        val entries = state.show.fxEngine.provenance.compute()
        fun sourceOf(target: String, property: String) =
            entries.singleOrNull { it.targetKey == target && it.propertyName == property }?.source
        assertEquals(ProvenanceSource.PROGRAMMER, sourceOf(head, "rgbColour"))
        assertEquals(ProvenanceSource.PROGRAMMER, sourceOf("bar.pixel-3", "white"), "the sideband slot's head")
    }

    @Test
    fun `Record writes a head as a cell-target row that applies to the head`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedBar()
        val stackId = ProgrammerRouteTestSupport.createStack(client, projectId, "stack-a")

        // White first, then red: the red write lifts into a colour that carries the head's
        // current white, so the two recorded rows agree about the W channel they share.
        busk(12, 200u)
        busk(9, 180u)
        // And a raw sideband slot on head 4's red: Record lifts it through the covering lookup.
        state.show.fxEngine.programmer.writeChannel(ProgrammerOwner.WEB, 0, 13, 90u, coveringKey = null)

        val response: ProgrammerRecordResponse = client.record(
            ProgrammerRecordRequest(projectId = projectId.toString(), mode = "CREATE", cueStackId = stackId),
        ).body()

        assertTrue(response.skipped.isEmpty(), "nothing skipped: ${response.skipped}")
        val rows = response.cue.propertyAssignments.associateBy { it.targetKey to it.propertyName }
        assertEquals(setOf(head to "rgbColour", head to "white", "bar.pixel-3" to "rgbColour"), rows.keys)
        assertTrue(rows.values.all { it.targetType == "fixture" }, "a cell is a fixture-typed target")
        assertTrue(rows.values.all { it.health == AssignmentHealth.Ok }, "a head row is healthy: ${rows.values}")

        // Played back from a clean programmer, the cue drives the heads.
        clearProgrammerCompletely(state)
        client.post("/api/rest/projects/$projectId/cues/${response.cue.id}/apply")
        assertEquals(180u.toUByte(), controller().getEffectiveValue(9))
        assertEquals(200u.toUByte(), controller().getEffectiveValue(12))
        assertEquals(90u.toUByte(), controller().getEffectiveValue(13))
    }

    @Test
    fun `a stage snapshot keeps a head the programmer holds`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedBar()
        val stackId = ProgrammerRouteTestSupport.createStack(client, projectId, "stack-a")

        busk(12, 200u)
        val response: ProgrammerRecordResponse = client.record(
            ProgrammerRecordRequest(
                projectId = projectId.toString(), mode = "CREATE", cueStackId = stackId, source = "STAGE_SNAPSHOT",
            ),
        ).body()

        val row = response.cue.propertyAssignments.singleOrNull { it.targetKey == head && it.propertyName == "white" }
        assertEquals("200", row?.value, "rows: ${response.cue.propertyAssignments}")
    }

    @Test
    fun `a cue's cell-target row builds a Layer 4 assignment on the head`() = testApplication {
        mountTestApp(state)
        seedBar()

        val built = buildCueAssignmentsForCue(
            state.show.fixtures,
            CueApplyData(
                cueId = 7, cueName = "cells", adHocEffects = emptyList(),
                propertyAssignments = listOf(CuePropertyAssignmentDto("fixture", head, "white", "120")),
            ),
        )
        val row = built.single()
        assertEquals(head, row.targetKey)
        assertEquals(CueAssignmentResolver.PropertyValue.Slider(120u), row.value)
    }

    @Test
    fun `Clear returns a head to the cue underneath rather than to 0`() = testApplication {
        mountTestApp(state)
        seedBar()
        cueUnderHeads()
        assertEquals(50u.toUByte(), controller().getEffectiveValue(9))
        assertEquals(70u.toUByte(), controller().getEffectiveValue(16))

        busk(9, 180u)
        // The shape every head write used to take: a raw sideband slot, here on head 4's white.
        state.show.fxEngine.programmer.writeChannel(ProgrammerOwner.WEB, 0, 16, 220u, coveringKey = null)
        assertEquals(180u.toUByte(), controller().getEffectiveValue(9))
        assertEquals(220u.toUByte(), controller().getEffectiveValue(16))

        state.show.fxEngine.programmer.clearAll()
        assertEquals(50u.toUByte(), controller().getEffectiveValue(9), "head 3 red back to the cue")
        assertEquals(70u.toUByte(), controller().getEffectiveValue(16), "head 4 white back to the cue, not DMX 0")
    }

    @Test
    fun `Blind shows the cue underneath a head, and unblind restores it`() = testApplication {
        mountTestApp(state)
        seedBar()
        cueUnderHeads()

        busk(9, 180u)
        state.show.fxEngine.programmer.writeChannel(ProgrammerOwner.WEB, 0, 16, 220u, coveringKey = null)

        state.show.fxEngine.programmer.setBlind(true)
        assertEquals(50u.toUByte(), controller().getEffectiveValue(9), "blind lets the cue show")
        assertEquals(70u.toUByte(), controller().getEffectiveValue(16), "blind does not write 0 over the cue")

        state.show.fxEngine.programmer.setBlind(false)
        assertEquals(180u.toUByte(), controller().getEffectiveValue(9))
        assertEquals(220u.toUByte(), controller().getEffectiveValue(16))
    }

    private suspend fun HttpClient.record(request: ProgrammerRecordRequest) =
        post("/api/rest/programmer/record") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
}
