package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.fx.parseExtendedColour
import uk.me.cormack.lighting7.models.CueLayerDto
import uk.me.cormack.lighting7.models.CuePropertyAssignmentDto
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.plugins.UpdateChannelInMessage
import uk.me.cormack.lighting7.plugins.handleUpdateChannel
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals

/**
 * A bundled emitter's channel — a Hex's white, a `Mode48Ch` head's white — is driven by two keys:
 * the emitter's own slider and the W component of the fixture's `rgbColour`. When both are
 * asserted the **emitter's own value wins**, deterministically, on every path that puts either on
 * the channel. Each test pins one of those paths, on a whole Hex and on a head of the 48-channel
 * bar alike.
 *
 * The race this closes: Layer 4 kept the two keys as separate contributors and the publish wrote
 * both, so the channel took whichever key was published last — map iteration order.
 */
class BundledEmitterPrecedenceTest : RouteIntegrationTest() {

    /** One fixture shape under test: where its colour's red and its white land on the wire. */
    private data class Rig(
        val fixtureType: String,
        val patchKey: String,
        val target: String,
        val red: Int,
        val white: Int,
        /** A slider key outside the bundle under test, for an edit that gives Update something to do. */
        val other: Pair<String, String>,
    )

    /** Hex at 1: dimmer 1, R/G/B 2–4, amber 5, white 6. */
    private val hex = Rig("hex", "hex-1", "hex-1", red = 2, white = 6, other = "hex-1" to "dimmer")

    /** The 48-channel bar at 1; Head 3 is `bar.pixel-2`, R/G/B 9–11, W 12. */
    private val head = Rig(
        "led-lightbar-12-pixel-48ch", "bar", "bar.pixel-2", red = 9, white = 12, other = "bar.pixel-3" to "white",
    )

    private fun controller() = state.show.fixtures.controllerOrNull(Universe(0, 0)) as MockDmxController

    private fun level(channel: Int) = controller().getEffectiveValue(channel).toInt()

    private fun busk(channel: Int, level: Int) =
        handleUpdateChannel(state, UpdateChannelInMessage(0, channel, level.toUByte(), fadeTime = 0))

    private fun program(rig: Rig, property: String, value: CueAssignmentResolver.PropertyValue) {
        state.show.fxEngine.programmer.writeProperty(
            ProgrammerOwner.WEB, state.show.fixtures.untypedGroupableFixture(rig.target), property, value,
        )
    }

    private fun colour(serialized: String) = CueAssignmentResolver.PropertyValue.Colour(parseExtendedColour(serialized))
    private fun slider(level: Int) = CueAssignmentResolver.PropertyValue.Slider(level.toUByte())

    private fun row(rig: Rig, property: String, value: String) =
        CuePropertyAssignmentDto("fixture", rig.target, property, value)

    /** A cue holding a colour whose W disagrees with the emitter's own row — in [order]. */
    private suspend fun disagreeingCue(client: HttpClient, rig: Rig, colourFirst: Boolean): Int {
        val colourRow = row(rig, "rgbColour", "#ff0000;w50")
        val whiteRow = row(rig, "white", "200")
        return ProgrammerRouteTestSupport.createCue(
            client, projectId, "both-${rig.patchKey}-$colourFirst",
            rows = if (colourFirst) listOf(colourRow, whiteRow) else listOf(whiteRow, colourRow),
        )
    }

    private suspend fun HttpClient.applyCue(cueId: Int) {
        val resp = post("/api/rest/projects/$projectId/cues/$cueId/apply")
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
    }

    private suspend fun HttpClient.record(stackId: Int): ProgrammerRecordResponse =
        post("/api/rest/programmer/record") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerRecordRequest(projectId = projectId.toString(), mode = "CREATE", cueStackId = stackId))
        }.body()

    private fun publish(rig: Rig, vararg properties: String) {
        for (property in properties) {
            state.show.fxEngine.cascade.publishCascadeForKeys(
                setOf(CueAssignmentResolver.Key.fixture(rig.target, property)),
            )
        }
    }

    /** Seed [rig] into a fresh app and run [block] — one rig per test, so each starts clean. */
    private fun onRig(rig: Rig, block: suspend (HttpClient) -> Unit) = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedFixture(state, projectId, rig.fixtureType, rig.patchKey, 1)
        block(jsonClient())
    }

    // ── A cue holding both, in either row order ─────────────────────────────────────────────

    @Test fun `Hex - a cue holding both lands the emitter's value, whatever the row order`() = cueHoldingBoth(hex)
    @Test fun `head - a cue holding both lands the emitter's value, whatever the row order`() = cueHoldingBoth(head)

    private fun cueHoldingBoth(rig: Rig) = onRig(rig) { client ->
        for (colourFirst in listOf(true, false)) {
            client.applyCue(disagreeingCue(client, rig, colourFirst))
            assertEquals(200, level(rig.white), "colour row first = $colourFirst")
            assertEquals(255, level(rig.red), "the colour's RGB is untouched")
        }
    }

    // ── Publish order ───────────────────────────────────────────────────────────────────────

    @Test fun `Hex - either publish order lands the emitter's value`() = eitherPublishOrder(hex)
    @Test fun `head - either publish order lands the emitter's value`() = eitherPublishOrder(head)

    private fun eitherPublishOrder(rig: Rig) = onRig(rig) { client ->
        client.applyCue(disagreeingCue(client, rig, colourFirst = true))
        publish(rig, "white", "rgbColour")
        assertEquals(200, level(rig.white), "colour published last")
        publish(rig, "rgbColour", "white")
        assertEquals(200, level(rig.white), "white published last")
    }

    // ── A Look layer ────────────────────────────────────────────────────────────────────────

    @Test fun `Hex - a Look layer carrying both rows lands the emitter's value`() = lookLayer(hex)
    @Test fun `head - a Look layer carrying both rows lands the emitter's value`() = lookLayer(head)

    private fun lookLayer(rig: Rig) = onRig(rig) { client ->
        val look = ProgrammerRouteTestSupport.createLookBoundTo(
            client, projectId, "both",
            rows = linkedMapOf("rgbColour" to "#ff0000;w50", "white" to "200"),
            targetKey = rig.target,
        )
        val cueId = ProgrammerRouteTestSupport.createCue(
            client, projectId, "look", layers = listOf(CueLayerDto(lookId = look.id)),
        )
        client.applyCue(cueId)
        assertEquals(200, level(rig.white), "through a cue's Look layer")
        publish(rig, "white", "rgbColour")
        assertEquals(200, level(rig.white), "through a cue's Look layer, colour published last")
    }

    @Test fun `Hex - a Look pressed into the programmer lands the emitter's value`() = lookPressed(hex)
    @Test fun `head - a Look pressed into the programmer lands the emitter's value`() = lookPressed(head)

    /** A programmer layer stamps its slots with one seq, so the two readers must break the tie alike. */
    private fun lookPressed(rig: Rig) = onRig(rig) { client ->
        val look = ProgrammerRouteTestSupport.createLookBoundTo(
            client, projectId, "both",
            rows = linkedMapOf("rgbColour" to "#ff0000;w50", "white" to "200"),
            targetKey = rig.target,
        )
        val press = client.post("/api/rest/projects/$projectId/looks/${look.id}/toggle") {
            contentType(ContentType.Application.Json)
            setBody(ToggleLookRequest(targets = listOf(CueTargetDto("fixture", rig.target))))
        }
        assertEquals(HttpStatusCode.OK, press.status, press.bodyAsText())
        publish(rig, "white", "rgbColour")
        assertEquals(200, level(rig.white), "colour published last")
        publish(rig, "rgbColour", "white")
        assertEquals(200, level(rig.white), "white published last")
    }

    // ── Record ──────────────────────────────────────────────────────────────────────────────

    @Test fun `Hex - Record red then white plays back the white it saw`() = recordRedThenWhite(hex)
    @Test fun `head - Record red then white plays back the white it saw`() = recordRedThenWhite(head)

    /** The repro: busk red (lifts to a colour carrying the white of the moment, 0), then white. */
    private fun recordRedThenWhite(rig: Rig) = onRig(rig) { client ->
        val stackId = ProgrammerRouteTestSupport.createStack(client, projectId, "stack")
        busk(rig.red, 180)
        busk(rig.white, 200)
        assertEquals(200, level(rig.white), "precondition: the stage shows the newer white")

        val recorded = client.record(stackId)
        val rows = recorded.cue.propertyAssignments.filter { it.targetKey == rig.target }.associateBy { it.propertyName }
        assertEquals("200", rows["white"]?.value)
        assertEquals(
            200u.toUByte(), parseExtendedColour(rows["rgbColour"]!!.value).white,
            "the colour row carries the white on stage, so the cue reads honestly",
        )

        clearProgrammerCompletely(state)
        client.applyCue(recorded.cue.id)
        assertEquals(180, level(rig.red))
        assertEquals(200, level(rig.white), "played back, W is the recorded 200")
    }

    @Test fun `Hex - Record white then a newer colour plays back the colour's white`() = recordWhiteThenColour(hex)
    @Test fun `head - Record white then a newer colour plays back the colour's white`() = recordWhiteThenColour(head)

    private fun recordWhiteThenColour(rig: Rig) = onRig(rig) { client ->
        val stackId = ProgrammerRouteTestSupport.createStack(client, projectId, "stack")
        program(rig, "white", slider(200))
        program(rig, "rgbColour", colour("#00ff00;w30"))
        assertEquals(30, level(rig.white), "precondition: the newer colour's W is on stage")

        val recorded = client.record(stackId)
        val rows = recorded.cue.propertyAssignments.filter { it.targetKey == rig.target }.associateBy { it.propertyName }
        assertEquals("30", rows["white"]?.value, "the emitter row takes the on-stage W")
        assertEquals(30u.toUByte(), parseExtendedColour(rows["rgbColour"]!!.value).white)

        clearProgrammerCompletely(state)
        client.applyCue(recorded.cue.id)
        assertEquals(30, level(rig.white), "played back, W is what was on stage")
    }

    // ── Clear and Blind ─────────────────────────────────────────────────────────────────────

    @Test fun `Hex - Clear and Blind over a cue holding both land on the emitter's value`() = clearAndBlind(hex)
    @Test fun `head - Clear and Blind over a cue holding both land on the emitter's value`() = clearAndBlind(head)

    private fun clearAndBlind(rig: Rig) = onRig(rig) { client ->
        client.applyCue(disagreeingCue(client, rig, colourFirst = true))
        assertEquals(200, level(rig.white))

        program(rig, "rgbColour", colour("#0000ff;w10"))
        assertEquals(10, level(rig.white), "the programmer's colour is above the cue")
        state.show.fxEngine.programmer.clearAll()
        assertEquals(200, level(rig.white), "Clear returns W to the cue's emitter value")

        program(rig, "rgbColour", colour("#0000ff;w10"))
        state.show.fxEngine.programmer.setBlind(true)
        assertEquals(200, level(rig.white), "Blind shows the cue's emitter value")
        state.show.fxEngine.programmer.setBlind(false)
        assertEquals(10, level(rig.white))
    }

    // ── The programmer ──────────────────────────────────────────────────────────────────────

    @Test fun `Hex - the programmer still arbitrates the shared channel by recency`() = programmerRecency(hex)
    @Test fun `head - the programmer still arbitrates the shared channel by recency`() = programmerRecency(head)

    private fun programmerRecency(rig: Rig) = onRig(rig) {
        program(rig, "rgbColour", colour("#ff0000;w10"))
        program(rig, "white", slider(200))
        assertEquals(200, level(rig.white), "newer white")
        program(rig, "rgbColour", colour("#ff0000;w30"))
        assertEquals(30, level(rig.white), "newer colour — in the programmer the emitter does not win outright")
        publish(rig, "rgbColour", "white")
        assertEquals(30, level(rig.white), "and publish order does not move it")
        publish(rig, "white", "rgbColour")
        assertEquals(30, level(rig.white))
        program(rig, "white", slider(40))
        assertEquals(40, level(rig.white), "newer white again")
    }

    @Test fun `Hex - Include of a cue holding both shows the emitter's value, whatever the row order`() = include(hex)
    @Test fun `head - Include of a cue holding both shows the emitter's value, whatever the row order`() = include(head)

    // ── Provenance ──────────────────────────────────────────────────────────────────────────

    @Test fun `Hex - provenance credits the emitter's cue with the component it supplied`() = provenance(hex)
    @Test fun `head - provenance credits the emitter's cue with the component it supplied`() = provenance(head)

    private fun provenance(rig: Rig) = onRig(rig) { client ->
        val colourCue = ProgrammerRouteTestSupport.createCue(
            client, projectId, "colour", rows = listOf(row(rig, "rgbColour", "#ff0000;w50")),
        )
        val whiteCue = ProgrammerRouteTestSupport.createCue(
            client, projectId, "white", rows = listOf(row(rig, "white", "200")),
        )
        client.applyCue(colourCue)
        client.applyCue(whiteCue)
        assertEquals(200, level(rig.white), "precondition: the emitter's row is on the channel")

        val entries = state.show.fxEngine.provenance.compute()
        val colour = entries.single { it.targetKey == rig.target && it.propertyName == "rgbColour" }
        assertEquals(colourCue, colour.cueId, "RGB is still the colour cue's")
        assertEquals(listOf("white" to whiteCue), colour.bundled.map { it.propertyName to it.cueId })
        val white = entries.single { it.targetKey == rig.target && it.propertyName == "white" }
        assertEquals(whiteCue, white.cueId)
        assertEquals(emptyList(), white.bundled, "only the colour carries bundled attribution")
    }

    // ── Update ──────────────────────────────────────────────────────────────────────────────

    private suspend fun HttpClient.include(cueId: Int) {
        val resp = post("/api/rest/programmer/include") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerIncludeRequest(projectId = projectId.toString(), cueId = cueId))
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
    }

    private suspend fun HttpClient.update() {
        val resp = post("/api/rest/programmer/update") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerUpdateRequest(projectId = projectId.toString()))
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
    }

    private suspend fun HttpClient.cueRows(cueId: Int, rig: Rig): Map<String, String> =
        get("/api/rest/projects/$projectId/cues/$cueId").body<CueDetails>().propertyAssignments
            .filter { it.targetKey == rig.target }
            .associate { it.propertyName to it.value }

    @Test fun `Hex - Update leaves an untouched disagreeing pair verbatim`() = updateUntouched(hex)
    @Test fun `head - Update leaves an untouched disagreeing pair verbatim`() = updateUntouched(head)

    /** The programmer reconciles the pair to the on-stage value; that must not read as an edit. */
    private fun updateUntouched(rig: Rig) = onRig(rig) { client ->
        val (otherKey, otherProperty) = rig.other
        val cueId = ProgrammerRouteTestSupport.createCue(
            client, projectId, "pair",
            rows = listOf(
                row(rig, "rgbColour", "#ff0000;w50"),
                row(rig, "white", "200"),
                CuePropertyAssignmentDto("fixture", otherKey, otherProperty, "70"),
            ),
        )
        fun bundleRows(rows: Map<String, String>) = rows.filterKeys { it == "rgbColour" || it == "white" }
        val before = bundleRows(client.cueRows(cueId, rig))
        client.include(cueId)
        // Something else is edited, so Update has a row to write.
        state.show.fxEngine.programmer.writeProperty(
            ProgrammerOwner.WEB, state.show.fixtures.untypedGroupableFixture(otherKey), otherProperty, slider(90),
        )
        client.update()
        assertEquals(before, bundleRows(client.cueRows(cueId, rig)), "rows the operator never changed are not rewritten")
        val other = client.get("/api/rest/projects/$projectId/cues/$cueId").body<CueDetails>().propertyAssignments
            .single { it.targetKey == otherKey && it.propertyName == otherProperty }
        assertEquals("90", other.value, "precondition: Update did write the edited row")
    }

    @Test fun `Hex - Update after a newer colour writes its W into the emitter row too`() = updateNewerColour(hex)
    @Test fun `head - Update after a newer colour writes its W into the emitter row too`() = updateNewerColour(head)

    private fun updateNewerColour(rig: Rig) = onRig(rig) { client ->
        val cueId = disagreeingCue(client, rig, colourFirst = true)
        client.include(cueId)
        program(rig, "rgbColour", colour("#00ff00;w30"))
        assertEquals(30, level(rig.white), "precondition: the newer colour's W is on stage")
        client.update()

        val rows = client.cueRows(cueId, rig)
        assertEquals(30u.toUByte(), parseExtendedColour(rows.getValue("rgbColour")).white)
        assertEquals("30", rows["white"], "the emitter row the colour overrode is written back")

        clearProgrammerCompletely(state)
        client.applyCue(cueId)
        assertEquals(30, level(rig.white), "played back, W is what was on stage")
    }

    /** One gesture is one contributor: Include stores the emitter last, so it is the newer write. */
    private fun include(rig: Rig) = onRig(rig) { client ->
        for (colourFirst in listOf(true, false)) {
            clearProgrammerCompletely(state)
            client.include(disagreeingCue(client, rig, colourFirst))
            assertEquals(200, level(rig.white), "colour row first = $colourFirst")
        }
    }
}
