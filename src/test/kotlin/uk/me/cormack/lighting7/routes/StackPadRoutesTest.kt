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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.midi.DefaultSurfaceActions
import uk.me.cormack.lighting7.models.CueAttributeAssignmentDto
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.PaletteRowDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Stack pads (busk-stack plan session 3, D9–D11): a pad naming a busk stack, its press — the stack's
 * GO — and the solo rows of the design page's §5 table, the layout's guard, the delete sweep, and the
 * running-order guard on the stack's `PUT`.
 */
class StackPadRoutesTest : RouteIntegrationTest() {

    private fun pages() = "/api/rest/projects/$projectId/busk/pages"
    private fun stacks() = "/api/rest/projects/$projectId/cue-stacks"
    private val manager get() = state.show.cueStackManager

    private fun seedRig() {
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", 13)
        state.show.fixtures.patchListChanged()
    }

    private suspend fun HttpClient.createStack(name: String, busk: Boolean = true, loop: Boolean = false, releaseAtEnd: Boolean? = null): Int {
        val resp = post(stacks()) {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = name, loop = loop, inRunningOrder = !busk, releaseAtEnd = releaseAtEnd))
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body<CueStackDetails>().id
    }

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

    private suspend fun HttpClient.createPalette(name: String): Int {
        val resp = post("/api/rest/projects/$projectId/palettes") {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = name, rows = listOf(PaletteRowDto(DEFERRED_TARGET_TYPE, "", "rgbColour", "#ff8800"))))
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body<PaletteDto>().id
    }

    private suspend fun HttpClient.layout(pageId: Int, solo: Boolean, vararg pads: BuskLayoutPad): HttpResponse =
        put("${pages()}/$pageId/layout") {
            contentType(ContentType.Application.Json)
            val bank = BuskLayoutBank(name = "Songs", solo = solo, pads = pads.toList())
            setBody(BuskLayoutRequest(listOf(BuskLayoutRow(listOf(BuskLayoutColumn(width = 12, banks = listOf(bank)))))))
        }

    private suspend fun HttpClient.page(name: String): BuskPageDto =
        post(pages()) {
            contentType(ContentType.Application.Json)
            setBody(CreateBuskPageRequest(name))
        }.body()

    /** One page, one bank holding [pads]; answers the pads. */
    private suspend fun HttpClient.bank(solo: Boolean, vararg pads: BuskLayoutPad, name: String = "Act 1"): List<BuskPadDto> {
        val resp = layout(page(name).id, solo, *pads)
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        return resp.body<BuskPageDto>().rows.single().columns.single().banks.single().pads
    }

    private suspend fun HttpClient.allPads(): List<BuskPadDto> =
        get(pages()).body<List<BuskPageDto>>().flatMap { it.rows }.flatMap { it.columns }.flatMap { it.banks }.flatMap { it.pads }

    private suspend fun HttpClient.press(padId: Int, vararg fixtureKeys: String): BuskPressResponse {
        val resp = post("/api/rest/projects/$projectId/busk/pads/$padId/press") {
            contentType(ContentType.Application.Json)
            setBody(BuskPressRequest(targets = fixtureKeys.map { CueTargetDto("fixture", it) }))
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        return resp.body()
    }

    private fun stackPad(id: Int) = BuskLayoutPad(cueStackId = id)

    private fun codeOf(resp: String): String? = Json.parseToJsonElement(resp).jsonObject["code"]?.jsonPrimitive?.content

    // ─── The layout ─────────────────────────────────────────────────────

    @Test
    fun `a pad may name a busk stack, and the page answers it by name`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val song = client.createStack("Song 3")

        val pad = client.bank(solo = false, stackPad(song)).single()
        assertEquals("CUE_STACK", pad.kind)
        assertEquals(song, pad.cueStack?.id)
        assertEquals("Song 3", pad.cueStack?.name)
    }

    @Test
    fun `a stack in the running order is refused by name, through the layout and the append`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val act = client.createStack("Act 1", busk = false)
        val page = client.page("Act 1")

        val viaLayout = client.layout(page.id, solo = false, stackPad(act))
        assertEquals(HttpStatusCode.BadRequest, viaLayout.status)
        val body = viaLayout.bodyAsText()
        assertEquals(CODE_BUSK_LAYOUT_REF, codeOf(body))
        assertTrue("'Act 1'" in body && "running order" in body, body)

        val song = client.createStack("Song 3")
        val bankId = client.layout(page.id, solo = false, stackPad(song)).body<BuskPageDto>()
            .rows.single().columns.single().banks.single().id
        val viaAppend = client.post("/api/rest/projects/$projectId/busk/banks/$bankId/pads") {
            contentType(ContentType.Application.Json)
            setBody(AddBuskPadRequest(cueStackId = act))
        }
        assertEquals(HttpStatusCode.BadRequest, viaAppend.status)
        assertEquals(CODE_BUSK_LAYOUT_REF, codeOf(viaAppend.bodyAsText()))

        val appended = client.post("/api/rest/projects/$projectId/busk/banks/$bankId/pads") {
            contentType(ContentType.Application.Json)
            setBody(AddBuskPadRequest(cueStackId = song))
        }
        assertEquals(HttpStatusCode.Created, appended.status, appended.bodyAsText())
        assertEquals(2, client.allPads().count { it.cueStack?.id == song })
    }

    @Test
    fun `a separator is no stack a pad can name`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val separator = client.post(stacks()) {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = "Interval", type = "SEPARATOR"))
        }.body<CueStackDetails>().id

        val resp = client.layout(client.page("Act 1").id, solo = false, stackPad(separator))
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertEquals(CODE_BUSK_LAYOUT_REF, codeOf(resp.bodyAsText()))
    }

    @Test
    fun `a pad left on a stack now in the running order does not lock its page`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val song = client.createStack("Song 3")
        val page = client.page("Act 1")
        val pad = client.layout(page.id, solo = false, stackPad(song)).body<BuskPageDto>()
            .rows.single().columns.single().banks.single().pads.single()
        // What an import or an undo can leave: the stack back in the running order, its pad kept.
        transaction(state.database) { uk.me.cormack.lighting7.models.DaoCueStack[song].inRunningOrder = true }

        val kept = client.layout(page.id, solo = true, BuskLayoutPad(padId = pad.id, cueStackId = song))
        assertEquals(HttpStatusCode.OK, kept.status, kept.bodyAsText())
        val added = client.layout(page.id, solo = true, BuskLayoutPad(padId = pad.id, cueStackId = song), stackPad(song))
        assertEquals(HttpStatusCode.BadRequest, added.status, "a new pad on it is still refused")
        assertEquals(CODE_BUSK_LAYOUT_REF, codeOf(added.bodyAsText()))
    }

    // ─── The press ──────────────────────────────────────────────────────

    @Test
    fun `a tap starts the stack, advances it, and releases it past the last cue`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val song = client.createStack("Song 3")
        val chorus = client.createCue("Chorus", song)
        val bridge = client.createCue("Bridge", song)
        val pad = client.bank(solo = false, stackPad(song)).single()

        val started = client.press(pad.id)
        assertEquals("CUE_STACK", started.kind)
        assertEquals("started", started.action)
        assertEquals(chorus, manager.getActiveCueId(song))

        assertEquals("advanced", client.press(pad.id).action)
        assertEquals(bridge, manager.getActiveCueId(song))

        // A busk stack is Release unless it loops (D3).
        assertEquals("released", client.press(pad.id).action)
        assertFalse(manager.isStackActive(song))

        assertEquals("started", client.press(pad.id).action, "and the next tap starts it again")
        assertEquals(chorus, manager.getActiveCueId(song))
    }

    @Test
    fun `on the last cue a Stay stack stays and a Loop stack goes round`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val stay = client.createStack("Stay", releaseAtEnd = false)
        client.createCue("Only", stay)
        val loop = client.createStack("Loop", loop = true)
        val first = client.createCue("First", loop)
        client.createCue("Second", loop)
        val (stayPad, loopPad) = client.bank(solo = false, stackPad(stay), stackPad(loop))

        client.press(stayPad.id)
        assertEquals("stayed", client.press(stayPad.id).action)
        assertTrue(manager.isStackActive(stay))

        client.press(loopPad.id)
        client.press(loopPad.id)
        assertEquals("advanced", client.press(loopPad.id).action)
        assertEquals(first, manager.getActiveCueId(loop))
    }

    @Test
    fun `a stack with no cues answers empty and starts nothing`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val song = client.createStack("Song 3")
        val pad = client.bank(solo = false, stackPad(song)).single()

        assertEquals("empty", client.press(pad.id).action)
        assertFalse(manager.isStackActive(song))
    }

    @Test
    fun `a surface's PressPad is the same GO`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val song = client.createStack("Song 3")
        val chorus = client.createCue("Chorus", song)
        val pad = client.bank(solo = false, stackPad(song)).single()

        DefaultSurfaceActions(state).pressPad(pad.uuid)
        assertEquals(chorus, manager.getActiveCueId(song))
    }

    @Test
    fun `a surface press of an empty stack's pad is refused, not done`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val song = client.createStack("Song 3")
        val pad = client.bank(solo = false, stackPad(song)).single()

        val outcome = DefaultSurfaceActions(state).pressPad(pad.uuid)
        assertTrue(outcome is uk.me.cormack.lighting7.midi.ActionOutcome.Refused, "$outcome")
        assertEquals(uk.me.cormack.lighting7.midi.ActionOutcome.STACK_EMPTY, outcome.code)
    }

    // ─── Solo (the page's §5 table) ─────────────────────────────────────

    @Test
    fun `a start in a solo bank stops a running sibling stack, and an advance takes nothing off`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val a = client.createStack("Song A")
        client.createCue("A1", a)
        val b = client.createStack("Song B")
        client.createCue("B1", b)
        client.createCue("B2", b)
        val (padA, padB) = client.bank(solo = true, stackPad(a), stackPad(b))

        client.press(padA.id)
        val startB = client.press(padB.id)
        assertEquals("started", startB.action)
        assertEquals(1, startB.released, "Song A was stopped")
        assertFalse(manager.isStackActive(a))
        assertTrue(manager.isReleasing(a), "over its release time (D6), not snapped")

        client.press(padA.id)
        assertFalse(manager.isStackActive(b), "starting A again stopped B")
        client.press(padB.id)
        val advance = client.press(padB.id)
        assertEquals("advanced", advance.action)
        assertEquals(0, advance.released, "an advance takes nothing off")
        assertFalse(manager.isStackActive(a), "A stays stopped")
    }

    /** What the programmer holds from the palettes: its layers and the palettes its values reference. */
    private fun paletteHeld(): Boolean =
        state.show.programmerStore.layers.isNotEmpty() ||
            state.show.programmerStore.entries().any { entry ->
                entry.slots.firstOrNull()?.value is uk.me.cormack.lighting7.fx.ProgrammerValue.Reference
            }

    @Test
    fun `a start in a solo bank takes palette siblings off whole and lets a cue sibling's loose hold go`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createPalette("amber")
        val main = client.createStack("Main", busk = false)
        val opening = client.createCue("opening", main)
        val song = client.createStack("Song 3")
        client.createCue("Chorus", song)
        val (palettePad, cuePad, songPad) = client.bank(
            solo = true, BuskLayoutPad(paletteId = amber), BuskLayoutPad(cueId = opening), stackPad(song),
        )

        client.press(palettePad.id, "hex-1")
        assertTrue(paletteHeld())
        val first = client.press(songPad.id)
        assertEquals("started", first.action)
        assertEquals(1, first.released, "the palette came off")
        assertFalse(paletteHeld(), "whole, whatever heads it held")

        manager.stopStack(state, song)
        client.press(cuePad.id)
        assertTrue(manager.holds.looseHeld(opening))
        val second = client.press(songPad.id)
        assertEquals("started", second.action)
        assertEquals(1, second.released, "the cue's loose hold went")
        assertFalse(manager.holds.looseHeld(opening))
    }

    @Test
    fun `a palette or cue pad's on press stops a running sibling stack`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val amber = client.createPalette("amber")
        val main = client.createStack("Main", busk = false)
        val opening = client.createCue("opening", main)
        val song = client.createStack("Song 3")
        client.createCue("Chorus", song)
        val (palettePad, cuePad, songPad) = client.bank(
            solo = true, BuskLayoutPad(paletteId = amber), BuskLayoutPad(cueId = opening), stackPad(song),
        )

        client.press(songPad.id)
        val palette = client.press(palettePad.id, "hex-1")
        assertEquals("applied", palette.action)
        assertFalse(manager.isStackActive(song), "the palette's on press stopped the song")

        client.press(songPad.id)
        val cue = client.press(cuePad.id)
        assertEquals("applied", cue.action)
        assertFalse(manager.isStackActive(song), "and so did the cue's")
    }

    @Test
    fun `a Look pad's on press stops a running sibling stack too`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val look = client.post("/api/rest/projects/$projectId/looks") {
            contentType(ContentType.Application.Json)
            setBody(CreateLookRequest(name = "warm", rows = listOf(uk.me.cormack.lighting7.models.LookRowDto("fixture", "hex-1", "dimmer", "200"))))
        }.body<LookDetails>().id
        val song = client.createStack("Song 3")
        client.createCue("Chorus", song)
        val (lookPad, songPad) = client.bank(solo = true, BuskLayoutPad(lookId = look), stackPad(song))

        client.press(songPad.id)
        assertEquals("applied", client.press(lookPad.id).action)
        assertFalse(manager.isStackActive(song))
    }

    @Test
    fun `a stacking bank's stack pad stops nothing`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val a = client.createStack("Song A")
        client.createCue("A1", a)
        val b = client.createStack("Song B")
        client.createCue("B1", b)
        val (padA, padB) = client.bank(solo = false, stackPad(a), stackPad(b))

        client.press(padA.id)
        assertEquals(0, client.press(padB.id).released)
        assertTrue(manager.isStackActive(a) && manager.isStackActive(b))
    }

    // ─── The stack's own writes ─────────────────────────────────────────

    @Test
    fun `deleting a stack takes its pads with it`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val song = client.createStack("Song 3")
        val keep = client.createStack("Song 4")
        client.bank(solo = false, stackPad(song), stackPad(keep))

        assertEquals(HttpStatusCode.OK, client.delete("${stacks()}/$song").status)
        assertEquals(listOf(keep), client.allPads().map { it.cueStack?.id })
    }

    @Test
    fun `putting a stack with pads back into the running order asks first, and force takes the pads off`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val song = client.createStack("Song 3")
        client.bank(solo = false, stackPad(song), name = "Act 1")
        client.bank(solo = false, stackPad(song), name = "Act 2")

        val refused = client.put("${stacks()}/$song") {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = "Song 3", inRunningOrder = true))
        }
        assertEquals(HttpStatusCode.Conflict, refused.status)
        val body = refused.body<CueStackOnPadsResponse>()
        assertEquals("CUE_STACK_ON_PADS", body.code)
        assertEquals(listOf("Act 1", "Act 2"), body.pages)
        assertEquals(2, client.allPads().size, "nothing written")

        val forced = client.put("${stacks()}/$song?force=true") {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = "Song 3", inRunningOrder = true))
        }
        assertEquals(HttpStatusCode.OK, forced.status, forced.bodyAsText())
        assertTrue(forced.body<CueStackDetails>().inRunningOrder)
        assertTrue(client.allPads().isEmpty())
    }

    @Test
    fun `a renamed stack's pads read its new name`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val song = client.createStack("Song 3")
        client.bank(solo = false, stackPad(song))

        val resp = client.put("${stacks()}/$song") {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = "Ugly Sisters"))
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        assertEquals("Ugly Sisters", client.allPads().single().cueStack?.name)
    }
}
