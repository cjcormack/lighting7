package uk.me.cormack.lighting7.routes

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Test
import uk.me.cormack.lighting7.fixture.dmx.MartinMac250Fixture.Colour
import uk.me.cormack.lighting7.fx.FxSampleClock
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The preview of a chase on a colour wheel (wheel-colour-chase plan D8): the wheel's row read as
 * slots and splits, sample by sample, and the notes on what the chase does there.
 */
class WheelPreviewRouteTest : RouteIntegrationTest() {

    private val clock = FxSampleClock(ticks = { 0L }, msPerTicks = { 20.0 }, rateScales = { 1.0 })

    private fun chase(colours: List<Colour>, between: String): JsonObject = buildJsonObject {
        put("fxType", "ColourCycle")
        put("attribute", "rgbColour")
        putJsonObject("parameters") {
            put("colours", colours.joinToString(",") { it.colourPreview!! })
            put("between", between)
            put("fade", "0.5")
        }
    }

    private fun preview(draft: JsonObject, keys: List<String> = listOf("mac-1")): FxPreviewResponse {
        LocateTestSupport.seedFixture(state, projectId, "martin-mac-250-mode-4", "mac-1", 1)
        val request = FxPreviewRequest(
            targets = keys.map { CueTargetDto(TargetRef.Fixture.TYPE, it) },
            fx = listOf(FxPreviewEntry(draft = draft)),
            include = "ONLY",
        )
        return assertIs<FxPreviewOutcome.Done>(previewFx(state, request, clock)).response
    }

    private fun wheelRow(answer: FxPreviewResponse): FxPreviewAttributeRow =
        answer.lanes.flatMap { it.attributes }.single { it.attribute == "colourWheel" }.rows.single()

    @Test
    fun `a Split chase's wheel row reads as its slots and their split`() {
        val row = wheelRow(preview(chase(listOf(Colour.RED, Colour.BLUE_108), "SPLIT")))
        val wheel = assertNotNull(row.wheel)
        assertEquals(wheel.samples.size, 96, "two cycles of 48 samples")
        val shown = wheel.samples.map { wheel.states[it] }.toSet()
        assertTrue(FxPreviewWheelState("RED", preview = "#FF0000") in shown, "$shown")
        assertTrue(FxPreviewWheelState("BLUE_108", preview = "#3370E0") in shown, "$shown")
        val split = shown.single { it.splitWith != null }
        assertEquals(setOf("RED", "BLUE_108"), setOf(split.slot, split.splitWith), "$split")
        assertNotNull(split.preview, "a split is drawn as the two slots mixed")
        // Blue 108 back to red is a split too: they are neighbours either way round.
        assertTrue(row.fx.single().notes.isEmpty(), "${row.fx.single().notes}")
    }

    @Test
    fun `slots that are not neighbours are noted, and the wheel shows no split`() {
        val row = wheelRow(preview(chase(listOf(Colour.RED, Colour.GREEN), "SPLIT")))
        val wheel = assertNotNull(row.wheel)
        assertTrue(wheel.states.all { it.splitWith == null }, "${wheel.states}")
        assertEquals(listOf(FxPreviewNote("NO_SPLIT", from = "RED", to = "GREEN", reason = "NOT_NEIGHBOURS")), row.fx.single().notes)
    }

    @Test
    fun `an RGB head's row carries no wheel`() {
        LocateTestSupport.seedHex(state, projectId, "hex-1", 100)
        val answer = preview(chase(listOf(Colour.RED, Colour.BLUE_108), "SPLIT"), listOf("mac-1", "hex-1"))
        val rgb = answer.lanes.flatMap { it.attributes }.single { it.attribute == "rgbColour" }.rows.single()
        assertEquals("hex-1", answer.rows[rgb.row].key)
        assertNull(rgb.wheel)
        assertTrue(rgb.fx.single().notes.isEmpty())
        assertNotNull(wheelRow(answer).wheel)
    }
}
