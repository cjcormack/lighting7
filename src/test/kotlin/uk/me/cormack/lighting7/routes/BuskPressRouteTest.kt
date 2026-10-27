package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.models.CueAttributeAssignmentDto
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.LookFxDto
import uk.me.cormack.lighting7.models.LookRowDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.fx.AttributeFamily
import uk.me.cormack.lighting7.midi.BindingTarget
import uk.me.cormack.lighting7.midi.DefaultSurfaceActions
import uk.me.cormack.lighting7.state.SelectionSource
import uk.me.cormack.lighting7.models.PaletteRowDto
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `POST /busk/pads/{id}/press` — each kind, the solo rules, and **the surface's own door onto the
 * same press**.
 *
 * The stack's own tests (`ProgrammerLayerStackTest`) prove `toggle`'s `releaseSiblings` narrows
 * and `release` drops; these prove the route *resolves* the siblings from the bank — per kind,
 * per pad, at press time — and that a cue pad presses the way a cue slot does.
 *
 * The last section drives `DefaultSurfaceActions` against the same fixtures. That is the whole
 * point of `BuskPressService` existing: a `PressPad` binding and a screen press are one press, and
 * two test classes could each be green while the two doors did different things.
 */
class BuskPressRouteTest : RouteIntegrationTest() {

    private fun pages() = "/api/rest/projects/$projectId/busk/pages"

    private suspend fun HttpClient.createPalette(name: String, value: String = "#ff8800", attribute: String = "rgbColour"): Int {
        val resp = post("/api/rest/projects/$projectId/palettes") {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = name, rows = listOf(PaletteRowDto(DEFERRED_TARGET_TYPE, "", attribute, value))))
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body<PaletteDto>().id
    }

    private suspend fun HttpClient.createLook(
        name: String,
        vararg fixtureKeys: String,
        /** Also a Pulse on the first head. */
        effect: Boolean = false,
        /** Also a colour row per head, so the Look spans INTENSITY + COLOUR. */
        withColour: Boolean = false,
    ): Int {
        val resp = post("/api/rest/projects/$projectId/looks") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateLookRequest(
                    name = name,
                    rows = fixtureKeys.flatMap { key ->
                        listOfNotNull(
                            LookRowDto("fixture", key, "dimmer", "200"),
                            if (withColour) LookRowDto("fixture", key, "rgbColour", "#ff0000") else null,
                        )
                    },
                    fx = if (effect) {
                        listOf(
                            LookFxDto(
                                targetType = "fixture", targetKey = fixtureKeys.first(),
                                fxType = "Wave", family = "intensity", attribute = "dimmer",
                                cycle = 0.5, blendMode = "OVERRIDE", order = "NORMAL",
                            ),
                        )
                    } else {
                        emptyList()
                    },
                ),
            )
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body<LookDetails>().id
    }

    private suspend fun HttpClient.createStack(name: String): Int =
        post("/api/rest/projects/$projectId/cue-stacks") {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = name))
        }.body<CueStackDetails>().id

    private suspend fun HttpClient.createCue(name: String, stackId: Int): Int {
        val resp = post("/api/rest/projects/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(
                NewCue(
                    name = name, cueStackId = stackId,
                    attributeAssignments = listOf(CueAttributeAssignmentDto(TargetRef.Fixture.TYPE, "hex-1", "dimmer", "128")),
                ),
            )
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body<CueDetails>().id
    }

    /** One page, one column, one bank holding [pads]; returns the pad ids in order. */
    private suspend fun HttpClient.bank(name: String, solo: Boolean, vararg pads: BuskLayoutPad): List<Int> {
        val page = post(pages()) {
            contentType(ContentType.Application.Json)
            setBody(CreateBuskPageRequest(name))
        }.body<BuskPageDto>()
        val resp = put("${pages()}/${page.id}/layout") {
            contentType(ContentType.Application.Json)
            val bank = BuskLayoutBank(name = name, solo = solo, pads = pads.toList())
            setBody(BuskLayoutRequest(listOf(BuskLayoutRow(listOf(BuskLayoutColumn(width = 12, banks = listOf(bank)))))))
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        return resp.body<BuskPageDto>().rows.single().columns.single().banks.single().pads.map { it.id }
    }

    private fun tpl(id: Int) = BuskLayoutPad(paletteId = id)
    private fun look(id: Int) = BuskLayoutPad(lookId = id)
    private fun cue(id: Int) = BuskLayoutPad(cueId = id)

    private suspend fun HttpClient.pressRaw(
        padId: Int,
        vararg fixtureKeys: String,
        families: List<String>? = null,
    ): HttpResponse =
        post("/api/rest/projects/$projectId/busk/pads/$padId/press") {
            contentType(ContentType.Application.Json)
            setBody(BuskPressRequest(targets = fixtureKeys.map { CueTargetDto("fixture", it) }, families = families))
        }

    private suspend fun HttpClient.press(
        padId: Int,
        vararg fixtureKeys: String,
        families: List<String>? = null,
    ): BuskPressResponse {
        val resp = pressRaw(padId, *fixtureKeys, families = families)
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        return resp.body()
    }

    private suspend fun HttpClient.activeCueId(stackId: Int): Int? =
        get("/api/rest/projects/$projectId/cue-stacks/$stackId").body<CueStackDetails>().activeCueId

    /** Whether a press holds [cueId] loose — what a cue pad toggles (cue model plan D6, D11). */
    private fun looseHeld(cueId: Int): Boolean = state.show.cueStackManager.holds.looseHeld(cueId)

    /**
     * `name@key+key` per live programmer layer — the idiom the template-group tests left behind. A
     * Look's layer names no targets (absolute-looks plan D1), so it reads `name@`.
     */
    private fun live(): Set<String> = state.show.programmerStore.layers
        .map { layer -> "${layer.source.name}@${layer.targets.map { it.key }.sorted().joinToString("+")}" }
        .toSet()

    /** The heads whose dimmer the programmer's layers hold. */
    private fun dimmedByLayers(): Set<String> = listOf("hex-1", "hex-2").filter { key ->
        state.show.programmerStore.get(key, "dimmer")?.owner == uk.me.cormack.lighting7.fx.ProgrammerOwner.LAYERS
    }.toSet()

    private fun seedRig() {
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", 13)
        state.show.fixtures.patchListChanged()
    }

    // ─── A fixture whose colour lives on its cells (venue-visit plan S1) ─

    private fun seedShehds() {
        LocateTestSupport.seedFixture(state, projectId, "shehds-led19-rgbw-24ch", "shehds-1", 1)
        LocateTestSupport.seedFixture(state, projectId, "shehds-led19-rgbw-24ch", "shehds-2", 25)
        LocateTestSupport.seedGroup(state, projectId, "washes", "shehds-1", "shehds-2")
        state.show.fixtures.patchListChanged()
    }

    private suspend fun HttpClient.pressTargets(padId: Int, vararg targets: CueTargetDto): BuskPressResponse {
        val resp = post("/api/rest/projects/$projectId/busk/pads/$padId/press") {
            contentType(ContentType.Application.Json)
            setBody(BuskPressRequest(targets = targets.toList()))
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        return resp.body()
    }

    /** The cells of [keys] the programmer's layers hold a colour on. */
    private fun layerColoured(vararg keys: String): Set<String> = keys.filter { key ->
        state.show.programmerStore.get(key, "rgbColour")?.owner == uk.me.cormack.lighting7.fx.ProgrammerOwner.LAYERS
    }.toSet()

    private val shehdsZones = listOf("shehds-1", "shehds-2").flatMap { k -> (1..3).map { "$k.zone-$it" } }

    @Test
    fun `a colour pad pressed on a Shehds lights its three zones`() = testApplication {
        mountTestApp(state)
        seedShehds()
        val client = jsonClient()
        val (pad) = client.bank("keys", solo = false, tpl(client.createPalette("amber")))

        assertEquals("applied", client.pressTargets(pad, CueTargetDto("fixture", "shehds-1")).action)

        assertEquals(setOf("shehds-1.zone-1", "shehds-1.zone-2", "shehds-1.zone-3"), layerColoured(*shehdsZones.toTypedArray()))
        assertNull(state.show.programmerStore.get("shehds-1", "rgbColour"), "the parent has no colour to hold")
    }

    @Test
    fun `a colour pad pressed on one Shehds zone lights that zone alone`() = testApplication {
        mountTestApp(state)
        seedShehds()
        val client = jsonClient()
        val (pad) = client.bank("keys", solo = false, tpl(client.createPalette("amber")))

        client.pressTargets(pad, CueTargetDto("fixture", "shehds-2.zone-2"))

        assertEquals(setOf("shehds-2.zone-2"), layerColoured(*shehdsZones.toTypedArray()))
    }

    @Test
    fun `a colour pad pressed through a group of Shehds lights every zone, and a second press clears them`() =
        testApplication {
            mountTestApp(state)
            seedShehds()
            val client = jsonClient()
            val (pad) = client.bank("keys", solo = false, tpl(client.createPalette("amber")))

            client.pressTargets(pad, CueTargetDto("group", "washes"))
            assertEquals(shehdsZones.toSet(), layerColoured(*shehdsZones.toTypedArray()))

            assertEquals("removed", client.pressTargets(pad, CueTargetDto("group", "washes")).action)
            assertTrue(layerColoured(*shehdsZones.toTypedArray()).isEmpty())
        }

    // ─── Each kind ──────────────────────────────────────────────────────

    @Test
    fun `a template pad applies a family-masked layer and a second press removes it`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createPalette("amber")
        val (pad) = client.bank("keys", solo = false, tpl(amber))

        val on = client.press(pad, "hex-1")
        assertEquals("PALETTE", on.kind)
        assertEquals("applied", on.action)
        assertEquals(0, on.released)
        assertEquals(setOf("amber@hex-1"), live())
        assertEquals("COLOUR", state.show.programmerStore.layers.single().attributeMask)

        val off = client.press(pad, "hex-1")
        assertEquals("removed", off.action)
        assertTrue(state.show.programmerStore.layers.isEmpty())
    }

    @Test
    fun `a generic template pad refuses an empty selection, a per-fixture one lands on its own heads`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createPalette("amber")
        val focus = client.post("/api/rest/projects/$projectId/palettes") {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = "focus", rows = listOf(PaletteRowDto("fixture", "hex-1", "position", "deg:12,-8"))))
        }.body<PaletteDto>().id
        val (amberPad, focusPad) = client.bank("keys", solo = false, tpl(amber), tpl(focus))

        val refused = client.pressRaw(amberPad)
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        assertEquals(CODE_PALETTE_NEEDS_SELECTION, refused.body<ErrorResponse>().code)
        assertTrue(state.show.programmerStore.layers.isEmpty(), "no dead layer lighting the pad")

        assertEquals("applied", client.press(focusPad).action)
        assertEquals(setOf("focus@hex-1"), live(), "a per-fixture template pressed with none names its own rows' heads")
    }

    @Test
    fun `deleting a Look releases the layer a pad put on the stack`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val warm = client.createLook("warm", "hex-1")
        val (pad) = client.bank("looks", solo = false, look(warm))
        client.press(pad, "hex-1")
        assertEquals(setOf("warm@"), live())

        // No cue layer names it, so the delete needs no `force` — and must not leave the layer.
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/rest/projects/$projectId/looks/$warm").status)
        assertTrue(state.show.programmerStore.layers.isEmpty(), "the layer went with its record")
    }

    @Test
    fun `a Look pad lands whole whatever is selected, and a press with any selection takes it off`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val warm = client.createLook("warm", "hex-1", "hex-2")
        val (pad) = client.bank("looks", solo = false, look(warm))

        val on = client.press(pad, "hex-2")
        assertEquals("LOOK", on.kind)
        assertEquals("applied", on.action)
        assertEquals(setOf("warm@"), live(), "one layer, naming no targets")
        assertNull(state.show.programmerStore.layers.single().attributeMask, "a Look layer is unmasked")
        assertEquals(setOf("hex-1", "hex-2"), dimmedByLayers(), "both its rows land, not only the selected head")

        val off = client.press(pad)
        assertEquals("removed", off.action, "nothing selected still takes it off")
        assertTrue(state.show.programmerStore.layers.isEmpty())

        assertEquals("applied", client.press(pad).action, "and nothing selected puts it on")
        assertEquals("removed", client.press(pad, "hex-1").action, "a selection that is part of it takes it off")
        assertTrue(state.show.programmerStore.layers.isEmpty())
    }

    @Test
    fun `a Look pad's effect runs on its own target, whatever is selected`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val pulse = client.createLook("pulse", "hex-1", effect = true)
        val (pad) = client.bank("looks", solo = false, look(pulse))

        assertEquals("applied", client.press(pad, "hex-2").action)
        assertEquals(setOf("pulse@"), live())
        assertEquals(listOf("hex-1"), state.show.fxEngine.programmerLayerFx().values.map { it.target.targetKey })
    }

    @Test
    fun `a cue pad applies its cue and a second press stops it`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val stackId = client.createStack("Main")
        val opening = client.createCue("opening", stackId)
        val (pad) = client.bank("cues", solo = false, cue(opening))

        val on = client.press(pad)
        assertEquals("CUE", on.kind)
        assertEquals("applied", on.action)
        assertTrue(looseHeld(opening), "a pad holds its cue loose")
        assertNull(client.activeCueId(stackId), "and names no stack: the cue's stack stays dark")
        assertTrue(opening in state.show.fxEngine.cueLayer.activeCueIds(), "the rows-only cue is live on Layer 4")

        val off = client.press(pad)
        assertEquals("removed", off.action)
        assertFalse(looseHeld(opening), "the second press lets the hold go")
        assertFalse(opening in state.show.fxEngine.cueLayer.activeCueIds())
    }

    // ─── Solo ───────────────────────────────────────────────────────────

    @Test
    fun `a solo press narrows a layer sibling on the pressed heads and leaves the rest`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createPalette("amber")
        val blue = client.createPalette("blue", "#0000ff")
        val (amberPad, bluePad) = client.bank("keys", solo = true, tpl(amber), tpl(blue))

        client.press(amberPad, "hex-1", "hex-2")
        val on = client.press(bluePad, "hex-1")
        assertEquals("applied", on.action)
        assertEquals(1, on.released, "amber was narrowed — one release")
        assertEquals(setOf("amber@hex-2", "blue@hex-1"), live())
    }

    @Test
    fun `a solo press stops a live cue sibling`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createPalette("amber")
        val stackId = client.createStack("Main")
        val opening = client.createCue("opening", stackId)
        val (amberPad, cuePad) = client.bank("keys", solo = true, tpl(amber), cue(opening))

        client.press(cuePad)
        assertTrue(looseHeld(opening))

        val on = client.press(amberPad, "hex-1")
        assertEquals("applied", on.action)
        assertEquals(1, on.released, "the live cue sibling was stopped")
        assertFalse(looseHeld(opening))
        assertEquals(setOf("amber@hex-1"), live())
    }

    @Test
    fun `a cue press in a solo bank turns its layer siblings off wholesale`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createPalette("amber")
        val blue = client.createPalette("blue", "#0000ff")
        val stackId = client.createStack("Main")
        val opening = client.createCue("opening", stackId)
        val (amberPad, bluePad, cuePad) = client.bank("keys", solo = true, tpl(amber), tpl(blue), cue(opening))

        client.press(amberPad, "hex-1", "hex-2")
        assertEquals(1, client.press(bluePad, "hex-1").released)
        assertEquals(setOf("amber@hex-2", "blue@hex-1"), live())

        val on = client.press(cuePad)
        assertEquals("applied", on.action)
        assertEquals(2, on.released, "both layer siblings came off, whatever heads they held")
        assertTrue(state.show.programmerStore.layers.isEmpty())
        assertTrue(looseHeld(opening))

        // Off: nothing else moves.
        assertEquals(0, client.press(cuePad).released)
    }

    @Test
    fun `a Look press in a solo bank takes every sibling off whole, as a cue press does`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createPalette("amber")
        val warm = client.createLook("warm", "hex-1")
        val stackId = client.createStack("Main")
        val opening = client.createCue("opening", stackId)
        val (_, warmPad, cuePad) = client.bank("keys", solo = true, tpl(amber), look(warm), cue(opening))

        client.press(cuePad)
        // Both on at once, as a door with no siblings leaves them: amber's own pad would stop the cue.
        state.show.programmerLayerStack.toggle(
            uk.me.cormack.lighting7.models.LayerSource.palette(amber, java.util.UUID.fromString(client.paletteUuid(amber)), "amber"),
            listOf(CueTargetDto("fixture", "hex-1"), CueTargetDto("fixture", "hex-2")),
        )
        assertTrue(looseHeld(opening))
        assertEquals(setOf("amber@hex-1+hex-2"), live())

        // Amber holds hex-2 as well, which the Look never touches: it still comes off whole (P3).
        val on = client.press(warmPad, "hex-1")
        assertEquals("applied", on.action)
        assertEquals(2, on.released, "the palette sibling and the cue sibling")
        assertEquals(setOf("warm@"), live())
        assertFalse(looseHeld(opening))
    }

    @Test
    fun `a palette press in a solo bank takes a Look sibling off whole and narrows a palette`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createPalette("amber")
        val blue = client.createPalette("blue", "#0000ff")
        val warm = client.createLook("warm", "hex-1", "hex-2")
        val (amberPad, bluePad, warmPad) = client.bank("keys", solo = true, tpl(amber), tpl(blue), look(warm))

        client.press(warmPad)
        client.press(amberPad, "hex-1", "hex-2")
        assertEquals(setOf("amber@hex-1+hex-2"), live(), "amber took the Look off whole on its way on")
        client.press(warmPad)
        assertEquals(setOf("warm@"), live(), "and the Look took amber off whole")

        // Both on at once, as a door with no siblings leaves them, for the narrowing half.
        state.show.programmerLayerStack.toggle(
            uk.me.cormack.lighting7.models.LayerSource.palette(amber, java.util.UUID.fromString(client.paletteUuid(amber)), "amber"),
            listOf(CueTargetDto("fixture", "hex-1"), CueTargetDto("fixture", "hex-2")),
            attributeMask = "COLOUR",
        )
        assertEquals(setOf("warm@", "amber@hex-1+hex-2"), live())

        val on = client.press(bluePad, "hex-1")
        assertEquals("applied", on.action)
        assertEquals(2, on.released, "the Look off whole, amber narrowed")
        assertEquals(setOf("amber@hex-2", "blue@hex-1"), live())
    }

    @Test
    fun `a stacking bank releases nothing`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createPalette("amber")
        // A level, so the colour palette is not replaced by the press of its own family (apply-model D2).
        val half = client.createPalette("half", "pct:50", attribute = "dimmer")
        val stackId = client.createStack("Main")
        val opening = client.createCue("opening", stackId)
        val (amberPad, halfPad, cuePad) = client.bank("keys", solo = false, tpl(amber), tpl(half), cue(opening))

        client.press(amberPad, "hex-1")
        assertEquals(0, client.press(halfPad, "hex-1").released)
        assertEquals(0, client.press(cuePad).released)
        assertEquals(setOf("amber@hex-1", "half@hex-1"), live())
        assertTrue(looseHeld(opening))
    }

    @Test
    fun `an off press releases nothing, and a disjoint sibling is untouched`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createPalette("amber")
        val blue = client.createPalette("blue", "#0000ff")
        val (amberPad, bluePad) = client.bank("keys", solo = true, tpl(amber), tpl(blue))

        client.press(amberPad, "hex-1")
        assertEquals(0, client.press(bluePad, "hex-2").released, "two pads on two rigs are not a conflict")
        assertEquals(setOf("amber@hex-1", "blue@hex-2"), live())

        val off = client.press(bluePad, "hex-2")
        assertEquals("removed", off.action)
        assertEquals(0, off.released)
        assertEquals(setOf("amber@hex-1"), live(), "an off press touches no sibling")
    }

    @Test
    fun `a record on two pads lights on both and either pad turns it off`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createPalette("amber")
        val (one) = client.bank("page one", solo = true, tpl(amber))
        val (two) = client.bank("page two", solo = false, tpl(amber))

        assertEquals("applied", client.press(one, "hex-1").action)
        val applied = state.show.programmerLayerStack.appliedState()
        assertEquals(1, applied.size, "one record applied, however many pads show it")

        assertEquals("removed", client.press(two, "hex-1").action, "the other pad reads the same record as on")
        assertTrue(state.show.programmerStore.layers.isEmpty())
    }

    @Test
    fun `a record on two pads of one solo bank is not its own sibling`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createPalette("amber")
        val (first, second) = client.bank("keys", solo = true, tpl(amber), tpl(amber))

        client.press(first, "hex-1")
        val on = client.press(second, "hex-2")
        assertEquals("applied", on.action, "hex-2 was not covered, so the press extends")
        assertEquals(0, on.released)
        assertEquals(setOf("amber@hex-1+hex-2"), live(), "its own top layer widens (apply-model P3)")
    }

    // ─── The selection's attribute mask (multi-screen plan D5) ──────────

    @Test
    fun `a template pad outside the selection's mask is refused by name, inside it lands as before`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createPalette("amber")
        val (pad) = client.bank("keys", solo = false, tpl(amber))

        val refused = client.pressRaw(pad, "hex-1", families = listOf("INTENSITY"))
        assertEquals(HttpStatusCode.BadRequest, refused.status, refused.bodyAsText())
        val error = refused.body<ErrorResponse>()
        assertEquals(CODE_PALETTE_OUTSIDE_MASK, error.code)
        assertTrue("Colour" in error.error && "Intensity" in error.error, "names both families: ${error.error}")
        assertTrue(state.show.programmerStore.layers.isEmpty(), "nothing lands, so nothing lights the pad")

        // Inside the mask the template's own family is the mask: the intersection is itself.
        val on = client.press(pad, "hex-1", families = listOf("COLOUR", "INTENSITY"))
        assertEquals("applied", on.action)
        assertEquals("COLOUR", state.show.programmerStore.layers.single().attributeMask)
    }

    @Test
    fun `a Look pad ignores the selection's mask, and a mask set on its layer afterwards is kept`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val warm = client.createLook("warm", "hex-1", withColour = true)
        val half = client.createPalette("half", "pct:50", attribute = "dimmer")
        val (pad, halfPad) = client.bank("looks", solo = false, look(warm), tpl(half))

        val on = client.press(pad, "hex-1", families = listOf("COLOUR", "POSITION"))
        assertEquals("applied", on.action)
        assertNull(state.show.programmerStore.layers.single().attributeMask)
        assertEquals(setOf("hex-1"), dimmedByLayers(), "its Intensity row lands under a Colour mask")

        // The mask is the layer's own setting (D3), set after the press and kept by the next one.
        val layer = state.show.programmerStore.layers.single()
        state.show.programmerLayerStack.patch(layer.layerId, attributeMask = "COLOUR")
        assertEquals("applied", client.press(halfPad, "hex-2").action)
        assertEquals("COLOUR", state.show.programmerStore.layers.single { it.source.name == "warm" }.attributeMask)
        assertEquals(setOf("hex-2"), dimmedByLayers(), "the cook masks the Look's rows; hex-2's is the palette's")
    }

    @Test
    fun `a Look pad with nothing inside the mask still lands, as a cue pad does`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val warm = client.createLook("warm", "hex-1")
        val stackId = client.createStack("Main")
        val cueId = client.createCue("Q1", stackId)
        val (lookPad, cuePad) = client.bank("mixed", solo = false, look(warm), cue(cueId))

        val look = client.press(lookPad, "hex-1", families = listOf("COLOUR"))
        assertEquals("applied", look.action, "a Look press takes no mask")
        assertEquals(setOf("warm@"), live())

        val cue = client.press(cuePad, "hex-1", families = listOf("COLOUR"))
        assertEquals("CUE", cue.kind)
        assertEquals("applied", cue.action, "a cue has no targets to be masked on")
        assertTrue(looseHeld(cueId))

        assertEquals("removed", client.press(lookPad, families = listOf("Gobo")).action, "nor is an unknown family a Look's to refuse")
    }

    @Test
    fun `an off press comes off under any mask — the mask is about what a press puts on`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createPalette("amber")
        val warm = client.createLook("warm", "hex-1")
        val (amberPad, warmPad) = client.bank("keys", solo = false, tpl(amber), look(warm))

        // Both lit unmasked, then a marquee elsewhere masks the selection to Position: the operator
        // presses each lit pad to turn it off, and neither may read dead.
        client.press(amberPad, "hex-1")
        client.press(warmPad, "hex-1")
        assertEquals(setOf("amber@hex-1", "warm@"), live())

        val amberOff = client.press(amberPad, "hex-1", families = listOf("POSITION"))
        assertEquals("removed", amberOff.action, "a lit Colour pad releases under a Position mask")
        val warmOff = client.press(warmPad, "hex-1", families = listOf("POSITION"))
        assertEquals("removed", warmOff.action, "a lit Intensity Look releases under a Position mask")
        assertTrue(state.show.programmerStore.layers.isEmpty())

        // And now that nothing is lit, the same presses are on presses: the mask refuses the palette,
        // and the Look, which takes no mask, goes on.
        assertEquals(CODE_PALETTE_OUTSIDE_MASK, client.pressRaw(amberPad, "hex-1", families = listOf("POSITION")).body<ErrorResponse>().code)
        assertEquals("applied", client.press(warmPad, "hex-1", families = listOf("POSITION")).action)
    }

    @Test
    fun `a per-fixture template pressed with no targets releases under an excluding mask`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val focus = client.post("/api/rest/projects/$projectId/palettes") {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = "focus", rows = listOf(PaletteRowDto("fixture", "hex-1", "position", "deg:12,-8"))))
        }.body<PaletteDto>().id
        val (pad) = client.bank("keys", solo = false, tpl(focus))

        assertEquals("applied", client.press(pad).action)
        assertEquals(setOf("focus@hex-1"), live(), "it names its own rows' heads")
        // `toggle` reads the empty press as its rows' heads, and so must the arm decided before the
        // mask test (`pressTargets`), or the off press would be refused.
        assertEquals("removed", client.press(pad, families = listOf("COLOUR")).action)
        assertTrue(state.show.programmerStore.layers.isEmpty())
        assertEquals(CODE_PALETTE_OUTSIDE_MASK, client.pressRaw(pad, families = listOf("COLOUR")).body<ErrorResponse>().code)
    }

    @Test
    fun `pressWouldRelease agrees with toggle on both arms`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createPalette("amber")
        val uuid = java.util.UUID.fromString(client.paletteUuid(amber))
        val source = uk.me.cormack.lighting7.models.LayerSource.palette(amber, uuid, "amber")
        val stack = state.show.programmerLayerStack
        val hex1 = listOf(CueTargetDto("fixture", "hex-1"))
        val both = listOf(CueTargetDto("fixture", "hex-1"), CueTargetDto("fixture", "hex-2"))

        // The guard: for every press, the helper's answer before the press is the arm `toggle`
        // then takes. A change to toggle's comparison fails here rather than drifting in pressArm.kt.
        fun check(targets: List<CueTargetDto>) {
            val predicted = pressWouldRelease(state, uuid, targets)
            val outcome = stack.toggle(source = source, targets = targets, attributeMask = "COLOUR")
            assertEquals(predicted, outcome.action == "removed", "targets=$targets")
        }
        check(hex1)          // on
        check(both)          // covered only partly → on (widens)
        check(both)          // fully covered → off, both heads
        check(hex1)          // on again
        check(emptyList())   // no twin with empty targets → on
        check(emptyList())   // twin → off
        check(hex1)          // hex-1 still covered by the first layer → off
        assertTrue(state.show.programmerStore.layers.isEmpty())
    }

    @Test
    fun `a family name outside the vocabulary is refused rather than widening the mask`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createPalette("amber")
        val (pad) = client.bank("keys", solo = false, tpl(amber))

        val resp = client.pressRaw(pad, "hex-1", families = listOf("GOBO"))
        assertEquals(HttpStatusCode.BadRequest, resp.status, resp.bodyAsText())
        assertTrue(state.show.programmerStore.layers.isEmpty())
    }

    @Test
    fun `an unknown pad is 404`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        assertEquals(HttpStatusCode.NotFound, client.pressRaw(999_999, "hex-1").status)
    }

    // ─── The same press, from a button (midi-surface plan D6) ───────────

    private fun actions() = DefaultSurfaceActions(state)

    private suspend fun HttpClient.lookUuid(id: Int): String =
        get("/api/rest/projects/$projectId/looks/$id").body<LookDetails>().uuid

    private suspend fun HttpClient.paletteUuid(id: Int): String =
        get("/api/rest/projects/$projectId/palettes/$id").body<PaletteDto>().uuid

    private suspend fun HttpClient.padUuid(padId: Int): String =
        get(pages()).body<List<BuskPageDto>>()
            .flatMap { it.rows }.flatMap { it.columns }.flatMap { it.banks }.flatMap { it.pads }
            .single { it.id == padId }.uuid

    @Test
    fun `applyLook presses the Look whole, not onto the selection`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val warm = client.createLook("warm", "hex-1")
        // A selection naming a *different* head, to prove the button ignores it: an `ApplyLook`
        // does the same thing every press, which is what makes it safe on a button.
        state.deskSelection.set(listOf(CueTargetDto("fixture", "hex-2")))

        actions().applyLook(client.lookUuid(warm))
        assertEquals(setOf("warm@"), live())
        assertEquals(setOf("hex-1"), dimmedByLayers())

        actions().applyLook(client.lookUuid(warm))
        assertTrue(state.show.programmerStore.layers.isEmpty(), "a second press takes it off")
    }

    @Test
    fun `applyLook runs a Look's effect on its own fixture, not the selection`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val pulse = client.createLook("pulse", "hex-1", effect = true)
        state.deskSelection.set(listOf(CueTargetDto("fixture", "hex-2")))

        actions().applyLook(client.lookUuid(pulse))
        assertEquals(setOf("pulse@"), live())
        assertEquals(listOf("hex-1"), state.show.fxEngine.programmerLayerFx().values.map { it.target.targetKey })
    }

    @Test
    fun `pressPalette lands on the selection and is dropped when a generic one has none`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createPalette("amber")
        val uuid = client.paletteUuid(amber)

        actions().pressPalette(uuid)
        assertTrue(state.show.programmerStore.layers.isEmpty(), "generic, nothing selected")

        state.deskSelection.set(listOf(CueTargetDto("fixture", "hex-2")))
        actions().pressPalette(uuid)
        assertEquals(setOf("amber@hex-2"), live())
        assertEquals("COLOUR", state.show.programmerStore.layers.single().attributeMask, "mask derived, not sent")
    }

    @Test
    fun `pressPalette honours the desk's mask, and a Sel attribute fader ignores it`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createPalette("amber")
        val uuid = client.paletteUuid(amber)

        state.deskSelection.set(listOf(CueTargetDto("fixture", "hex-2")), setOf(AttributeFamily.INTENSITY))
        actions().pressPalette(uuid)
        assertTrue(state.show.programmerStore.layers.isEmpty(), "a Colour template under an Intensity mask is dropped")

        // The fader names its own attribute, so the mask does not touch it (§3.3).
        actions().writeSelectionAttribute("rgbColour", 127u, null)
        assertTrue(state.show.programmerStore.size > 0, "the selection write landed under a mask it is outside")

        state.deskSelection.set(listOf(CueTargetDto("fixture", "hex-2")), setOf(AttributeFamily.COLOUR))
        actions().pressPalette(uuid)
        assertEquals(setOf("amber@hex-2"), live())
        assertEquals("COLOUR", state.show.programmerStore.layers.single().attributeMask)
    }

    @Test
    fun `a surface press releases a lit record under an excluding mask`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createPalette("amber")
        val warm = client.createLook("warm", "hex-1")
        val (warmPad) = client.bank("keys", solo = false, look(warm))
        val uuid = client.paletteUuid(amber)
        state.deskSelection.set(listOf(CueTargetDto("fixture", "hex-1")))

        actions().pressPalette(uuid)
        actions().pressPad(client.padUuid(warmPad))
        assertEquals(setOf("amber@hex-1", "warm@"), live())

        // A marquee on a screen masks the desk to Position; the buttons must still turn them off.
        state.deskSelection.set(listOf(CueTargetDto("fixture", "hex-1")), setOf(AttributeFamily.POSITION))
        actions().pressPalette(uuid)
        actions().pressPad(client.padUuid(warmPad))
        assertTrue(state.show.programmerStore.layers.isEmpty(), "both released under a mask that excludes them")

        // Off, the palette's press is an on press and is dropped by name; the Look's lands whole.
        actions().pressPalette(uuid)
        actions().pressPad(client.padUuid(warmPad))
        assertEquals(setOf("warm@"), live())
    }

    @Test
    fun `pressPad passes the desk's mask through the same press, which a Look ignores`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val warm = client.createLook("warm", "hex-1", withColour = true)
        val amber = client.createPalette("amber")
        val (lookPad, amberPad) = client.bank("keys", solo = false, look(warm), tpl(amber))
        state.deskSelection.set(listOf(CueTargetDto("fixture", "hex-1")), setOf(AttributeFamily.INTENSITY))

        actions().pressPad(client.padUuid(amberPad))
        assertTrue(state.show.programmerStore.layers.isEmpty(), "refused by name, logged — a button has no 400")

        actions().pressPad(client.padUuid(lookPad))
        assertEquals(setOf("warm@"), live())
        assertNull(state.show.programmerStore.layers.single().attributeMask, "a Look lands whole, under no mask")
    }

    @Test
    fun `a surface select toggle keeps the desk's mask and a replace clears it`() = testApplication {
        mountTestApp(state)
        seedRig()
        val colour = setOf(AttributeFamily.COLOUR)
        state.deskSelection.set(listOf(CueTargetDto("fixture", "hex-1")), colour)

        actions().selectTarget(CueTargetDto("fixture", "hex-2"), BindingTarget.SelectMode.TOGGLE)
        assertEquals(colour, state.deskSelection.state.value.families, "a select button adds a head under the standing mask")
        assertEquals(SelectionSource.SURFACE, state.deskSelection.state.value.source)

        actions().selectTarget(CueTargetDto("fixture", "hex-2"), BindingTarget.SelectMode.REPLACE)
        assertEquals(listOf(CueTargetDto("fixture", "hex-2")), state.deskSelection.state.value.targets)
        assertNull(state.deskSelection.state.value.families, "a replace has no column axis to speak with")

        actions().clearSelection()
        assertNull(state.deskSelection.state.value.source)
    }

    @Test
    fun `pressPad runs the pad's own plan, solo siblings included`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createPalette("amber")
        val blue = client.createPalette("blue", "#0044ff")
        val (amberPad, bluePad) = client.bank("keys", solo = true, tpl(amber), tpl(blue))
        state.deskSelection.set(listOf(CueTargetDto("fixture", "hex-1")))

        actions().pressPad(client.padUuid(amberPad))
        assertEquals(setOf("amber@hex-1"), live())

        actions().pressPad(client.padUuid(bluePad))
        assertEquals(setOf("blue@hex-1"), live(), "the solo bank released its sibling from a hardware press too")
    }

    @Test
    fun `the busk page targets move the desk's showing page`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val first = client.post(pages()) {
            contentType(ContentType.Application.Json)
            setBody(CreateBuskPageRequest("act one"))
        }.body<BuskPageDto>()
        val second = client.post(pages()) {
            contentType(ContentType.Application.Json)
            setBody(CreateBuskPageRequest("act two"))
        }.body<BuskPageDto>()

        actions().buskPageSet(second.uuid)
        assertEquals(second.id, state.buskPageState.pageId.value)

        actions().buskPageStep(1)
        assertEquals(first.id, state.buskPageState.pageId.value, "next wraps")

        // A uuid that resolves to nothing leaves the page where it is rather than clearing it.
        actions().buskPageSet("00000000-0000-4000-8000-000000000000")
        assertEquals(first.id, state.buskPageState.pageId.value)
    }

    // ─── Cells (busk-further plan D11) ─────────────────────────────────

    private fun cell(i: Int) = "bar-1.pixel-$i"

    private fun seedBar() {
        LocateTestSupport.seedFixture(state, projectId, "led-lightbar-12-pixel-48ch", "bar-1", 1)
    }

    @Test
    fun `a cell press under a whole-bar palette layer reads lit and releases nothing`() = testApplication {
        mountTestApp(state)
        seedRig()
        seedBar()
        val client = jsonClient()
        val red = client.createPalette("red", "#ff0000")
        val half = client.createPalette("half", "pct:50", attribute = "dimmer")
        val (pad, otherPad) = client.bank("solo", solo = true, tpl(red), tpl(half))
        assertEquals("applied", client.press(otherPad, "hex-1").action)

        assertEquals("applied", client.press(pad, "bar-1").action)
        assertEquals(setOf("red@bar-1", "half@hex-1"), live())

        val off = client.press(pad, cell(0), cell(1), cell(2), cell(3))
        assertEquals("removed", off.action, "the whole bar covers four of its cells")
        assertEquals(0, off.released, "an off press releases nothing")
        assertEquals(
            setOf("red@" + (4 until 12).map { cell(it) }.sorted().joinToString("+"), "half@hex-1"),
            live(),
            "the layer is narrowed to the cells the press did not name; the sibling is untouched",
        )
    }

    @Test
    fun `a whole-bar press over a cell layer adds`() = testApplication {
        mountTestApp(state)
        seedRig()
        seedBar()
        val client = jsonClient()
        val red = client.createPalette("red", "#ff0000")
        val (pad) = client.bank("keys", solo = false, tpl(red))

        assertEquals("applied", client.press(pad, cell(0), cell(1), cell(2), cell(3)).action)
        assertEquals(setOf("red@" + (0 until 4).map { cell(it) }.joinToString("+")), live())

        val on = client.press(pad, "bar-1")
        assertEquals("applied", on.action, "four cells do not cover the whole bar")
        assertEquals(0, on.released)
        assertEquals(setOf("red@bar-1"), live(), "the bar subsumes its cells: one layer per head")
    }
}
