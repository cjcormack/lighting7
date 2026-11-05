package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import uk.me.cormack.lighting7.ai.ProgrammerTools
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.PaletteReferences
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.fx.ProgrammerValue
import uk.me.cormack.lighting7.midi.DefaultSurfaceActions
import uk.me.cormack.lighting7.models.CueAttributeAssignmentDto
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.PaletteRowDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.plugins.ProgrammerHandler
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A value palette's press writes references, never a layer (palette-references plan session 2):
 * every door, the arms, the family replacement on attributes it does not name, a per-fixture palette
 * on two of its heads, the rings, a solo bank, the stored cue's press and the Timeline, *Use it on
 * these heads*, and Record, Include and Update carrying a reference as one.
 */
class PalettePressReferencesTest : RouteIntegrationTest() {

    private val base get() = "/api/rest/projects/$projectId"

    private fun seedRig(vararg keys: String = arrayOf("hex-1", "hex-2")) {
        keys.forEachIndexed { i, key -> LocateTestSupport.seedHex(state, projectId, key, startChannel = 1 + i * 16) }
        LocateTestSupport.seedGroup(state, projectId, "wash", *keys)
        state.show.fixtures.patchListChanged()
    }

    private fun generic(attribute: String, value: String) = PaletteRowDto(DEFERRED_TARGET_TYPE, "", attribute, value)

    private suspend fun HttpClient.palette(name: String, vararg rows: PaletteRowDto): PaletteDto {
        val resp = post("$base/palettes") {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = name, rows = rows.toList()))
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body()
    }

    private suspend fun HttpClient.toggle(palette: PaletteDto, vararg keys: String): TogglePaletteResponse {
        val resp = post("$base/palettes/${palette.id}/toggle") {
            contentType(ContentType.Application.Json)
            setBody(TogglePaletteRequest(targets = keys.map { PaletteTargetDto("fixture", it) }))
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        return resp.body()
    }

    /** Each key's winning slot as `head.attribute=Palette` where it is a reference, sorted. */
    private fun refs(): List<String> = state.show.programmerStore.entries().mapNotNull { entry ->
        val reference = entry.slots.first().value as? ProgrammerValue.Reference ?: return@mapNotNull null
        val name = state.show.paletteRegistry.snapshot(reference.paletteUuid)?.name
        "${entry.fixtureKey}.${entry.attribute}=$name"
    }.sorted()

    private fun paletteLayers() = state.show.programmerStore.layers.filter { it.source.isPalette }

    private fun dimmer(key: String) =
        (state.show.programmerStore.get(key, "dimmer")?.value?.resolved as? CueAssignmentResolver.AttributeValue.Slider)?.value?.toInt()

    @Test
    fun `a value palette's tap writes references as your values, and a second tap takes them off`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedRig()
        val half = client.palette("Half", generic("dimmer", "pct:50"))

        val on = client.toggle(half, "hex-1", "hex-2")
        assertEquals("applied", on.action)
        assertEquals(listOf("hex-1.dimmer=Half", "hex-2.dimmer=Half"), refs())
        assertTrue(paletteLayers().isEmpty(), "no layer: a value palette is a value")
        val slot = state.show.programmerStore.get("hex-1", "dimmer")!!
        assertEquals(ProgrammerOwner.WEB, slot.owner)
        assertTrue(slot.touched)

        val off = client.toggle(half, "hex-1", "hex-2")
        assertEquals("removed", off.action)
        assertTrue(refs().isEmpty())
        assertNull(state.show.programmerStore.get("hex-1", "dimmer"))
    }

    @Test
    fun `a press over a literal replaces it, and its release takes the value away`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedRig()
        val full = client.palette("Full", generic("dimmer", "pct:100"))
        ProgrammerHandler.set(state, TargetRef.Fixture("hex-1"), "dimmer", "40", 0)

        client.toggle(full, "hex-1")
        assertEquals(listOf("hex-1.dimmer=Full"), refs())
        assertEquals(255, dimmer("hex-1"))

        client.toggle(full, "hex-1")
        assertNull(state.show.programmerStore.get("hex-1", "dimmer"), "the literal it replaced is gone (call D)")

        // A literal set over a reference replaces it in turn.
        client.toggle(full, "hex-1")
        ProgrammerHandler.set(state, TargetRef.Fixture("hex-1"), "dimmer", "40", 0)
        assertTrue(refs().isEmpty())
        assertEquals(40, dimmer("hex-1"))
    }

    @Test
    fun `a palette of the family takes the other's references off, on attributes it does not name too`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedRig()
        val warm = client.palette("Warm", generic("rgbColour", "#ff8800;policy=rgbonly"), generic("white", "dmx:200"))
        val blue = client.palette("Blue", generic("rgbColour", "#0000ff;policy=rgbonly"))
        val half = client.palette("Half", generic("dimmer", "pct:50"))

        client.toggle(half, "hex-1")
        client.toggle(warm, "hex-1")
        assertEquals(listOf("hex-1.dimmer=Half", "hex-1.rgbColour=Warm", "hex-1.white=Warm"), refs())

        val blueOn = client.toggle(blue, "hex-1")
        assertEquals(1, blueOn.released)
        assertEquals(listOf("hex-1.dimmer=Half", "hex-1.rgbColour=Blue"), refs(), "Warm's white goes with it; Half is another family")
    }

    @Test
    fun `a per-fixture palette pressed on two of its four heads lands on those two and says what it left out`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedRig("hex-1", "hex-2", "hex-3", "hex-4", "hex-5")
        val focus = client.palette(
            "DSR Focus",
            *(1..4).map { PaletteRowDto("fixture", "hex-$it", "dimmer", "pct:${it * 10}") }.toTypedArray(),
        )

        val on = client.toggle(focus, "hex-1", "hex-2", "hex-5")
        assertEquals(listOf("hex-1.dimmer=DSR Focus", "hex-2.dimmer=DSR Focus"), refs())
        assertEquals(listOf("hex-3", "hex-4"), on.omitted)
        assertEquals(listOf("hex-5"), on.notCovered)
        assertTrue(dimmer("hex-2")!! > dimmer("hex-1")!!, "each head takes its own row")
    }

    @Test
    fun `the rings read the references, a group partly covered reading some`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedRig()
        val half = client.palette("Half", generic("dimmer", "pct:50"))

        client.toggle(half, "hex-1")
        val applied = state.show.programmerLayerStack.appliedState().single { it.source.uuid.toString() == half.uuid }
        val byTarget = applied.targets.associate { "${it.target.type}:${it.target.key}" to it.extent.name }
        assertEquals("ALL", byTarget["fixture:hex-1"])
        assertNull(byTarget["fixture:hex-2"])
        assertEquals("SOME", byTarget["group:wash"])

        client.toggle(half, "hex-2")
        val whole = state.show.programmerLayerStack.appliedState().single { it.source.uuid.toString() == half.uuid }
        assertEquals("ALL", whole.targets.single { it.target.key == "wash" }.extent.name)
    }

    @Test
    fun `every door that presses a value palette writes references and puts no palette layer on`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedRig()
        val half = client.palette("Half", generic("dimmer", "pct:50"))

        // The toggle route.
        client.toggle(half, "hex-1")
        assertEquals(listOf("hex-1.dimmer=Half"), refs())
        state.show.fxEngine.programmer.clearAll()

        // MCP's apply_palette.
        val mcp = ProgrammerTools(state).applyPalette(buildJsonObject {
            put("paletteId", half.id)
            put("targets", JsonArray(listOf(buildJsonObject { put("type", "fixture"); put("key", "hex-2") })))
        })
        assertTrue(mcp.success, mcp.result)
        assertEquals(listOf("hex-2.dimmer=Half"), refs())
        state.show.fxEngine.programmer.clearAll()

        // A surface's PressPalette, on the desk selection.
        state.deskSelection.set(listOf(CueTargetDto("fixture", "hex-1")))
        DefaultSurfaceActions(state).pressPalette(half.uuid)
        assertEquals(listOf("hex-1.dimmer=Half"), refs())
        state.show.fxEngine.programmer.clearAll()

        // A busk pad.
        val padId = client.bank("Levels", solo = false, BuskLayoutPad(paletteId = half.id)).single()
        client.pressPad(padId, "hex-2")
        assertEquals(listOf("hex-2.dimmer=Half"), refs())

        assertTrue(paletteLayers().isEmpty(), "no door put a value palette layer on")
    }

    @Test
    fun `a solo bank's palette sibling comes off the pressed heads, whatever its family`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedRig()
        val half = client.palette("Half", generic("dimmer", "pct:50"))
        val red = client.palette("Red", generic("rgbColour", "#ff0000;policy=rgbonly"))
        val (halfPad, redPad) = client.bank("Solo", solo = true, BuskLayoutPad(paletteId = half.id), BuskLayoutPad(paletteId = red.id))

        client.pressPad(halfPad, "hex-1", "hex-2")
        val answer = client.pressPad(redPad, "hex-1")
        assertEquals(1, answer.released)
        assertEquals(listOf("hex-1.rgbColour=Red", "hex-2.dimmer=Half"), refs())
    }

    // ─── A stored cue ───────────────────────────────────────────────────

    private suspend fun HttpClient.stack(name: String): Int =
        post("$base/cue-stacks") {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = name))
        }.body<CueStackDetails>().id

    private suspend fun HttpClient.cue(stackId: Int?, name: String, rows: List<CueAttributeAssignmentDto> = emptyList()): Int =
        post("$base/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = name, cueStackId = stackId, attributeAssignments = rows))
        }.also { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }.body<CueDetails>().id

    private suspend fun HttpClient.details(cueId: Int): CueDetails = get("$base/cues/$cueId").body()

    private suspend fun HttpClient.cuePress(cueId: Int, palette: PaletteDto, vararg targets: CueTargetDto): CueLayerPressResponse {
        val resp = post("$base/cues/$cueId/layers/press") {
            contentType(ContentType.Application.Json)
            setBody(CueLayerPressRequest(paletteId = palette.id, targets = targets.toList()))
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        return resp.body()
    }

    /** The cue's rows as `type:key.attribute=value`, a reference by its palette's name. */
    private fun CueDetails.rowWords(vararg palettes: PaletteDto): List<String> {
        val names = palettes.associate { PaletteReferences.valueOf(UUID.fromString(it.uuid)) to it.name }
        return attributeAssignments.map { "${it.targetType}:${it.targetKey}.${it.attribute}=${names[it.value] ?: it.value}" }.sorted()
    }

    @Test
    fun `a press into a stored cue writes reference rows, Blue replaces Red, and a second press takes them out`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedRig()
        val red = client.palette("Red", generic("rgbColour", "#ff0000;policy=rgbonly"))
        val blue = client.palette("Blue", generic("rgbColour", "#0000ff;policy=rgbonly"))
        val cueId = client.cue(null, "Q1", listOf(CueAttributeAssignmentDto("fixture", "hex-1", "dimmer", "200")))

        val on = client.cuePress(cueId, red, CueTargetDto("group", "wash"))
        assertEquals("applied", on.action)
        assertEquals(listOf("fixture:hex-1.dimmer=200", "group:wash.rgbColour=Red"), on.cue.rowWords(red))
        assertTrue(on.cue.layers.isEmpty(), "no layer")

        val swap = client.cuePress(cueId, blue, CueTargetDto("group", "wash"))
        assertEquals(1, swap.released)
        assertEquals(listOf("fixture:hex-1.dimmer=200", "group:wash.rgbColour=Blue"), swap.cue.rowWords(blue))

        val off = client.cuePress(cueId, blue, CueTargetDto("group", "wash"))
        assertEquals("removed", off.action)
        assertEquals(listOf("fixture:hex-1.dimmer=200"), off.cue.rowWords())
    }

    @Test
    fun `the Timeline turns a value palette's cell on and off as rows, reads it as one row, and Undo puts the rows back`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedRig()
        val red = client.palette("Red", generic("rgbColour", "#ff0000;policy=rgbonly"))
        val stackId = client.stack("Act 1")
        val cueId = client.cue(stackId, "Q1")

        val source = buildJsonObject { put("kind", "PALETTE"); put("id", red.id) }
        val target = buildJsonObject { put("type", "fixture"); put("key", "hex-1") }
        val on = client.timelineEdit(stackId, buildJsonObject {
            put("op", "layerOn"); put("cueId", cueId); put("source", source); put("target", target)
        })
        assertEquals(listOf(cueId), on.changed)
        assertEquals(listOf("fixture:hex-1.rgbColour=Red"), client.details(cueId).rowWords(red))

        val timeline = client.get("$base/cue-stacks/$stackId/timeline").body<CueStackTimelineDto>()
        val row = timeline.entries.single { it.cueId == cueId }.layers.single()
        assertTrue(row.reference)
        assertEquals(red.id, row.source.id)
        assertEquals(listOf(CueTargetDto("fixture", "hex-1")), row.targets)

        val rowUuid = client.details(cueId).attributeAssignments.single().uuid
        val off = client.timelineEdit(stackId, buildJsonObject {
            put("op", "layerOff"); put("cueId", cueId); put("source", source); put("target", target)
        })
        assertTrue(client.details(cueId).attributeAssignments.isEmpty())

        val undo = Json.encodeToJsonElement(TimelineCueSnapshotDto.serializer(), off.before.single()) as JsonObject
        client.timelineEdit(stackId, JsonObject(undo + ("op" to JsonPrimitive("restore"))))
        val restored = client.details(cueId).attributeAssignments.single()
        assertEquals(PaletteReferences.valueOf(UUID.fromString(red.uuid)), restored.value)
        assertEquals(rowUuid, restored.uuid, "Undo puts the row back as it was")
    }

    @Test
    fun `a cue press writes the palette's own fade on its rows, and lands a colour on a Shehds's zones`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedFixture(state, projectId, "shehds-led19-rgbw-24ch", "shehds-1", 1)
        state.show.fixtures.patchListChanged()
        val resp = client.post("$base/palettes") {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = "Red", rows = listOf(generic("rgbColour", "#ff0000;policy=rgbonly")), fadeDurationMs = 2000))
        }
        val red = resp.body<PaletteDto>()
        val cueId = client.cue(null, "Q1")

        val on = client.cuePress(cueId, red, CueTargetDto("fixture", "shehds-1"))
        assertEquals(emptyList(), on.notCovered, "the parent's zones take the colour")
        val row = on.cue.attributeAssignments.single()
        assertEquals("shehds-1", row.targetKey)
        assertEquals(2000L, row.fadeDurationMs, "the palette's own fade, as its layer gave its rows")
        assertTrue(row.resolvedByHead.orEmpty().keys.any { it.startsWith("shehds-1.") }, "resolved on the zones: ${row.resolvedByHead}")
    }

    @Test
    fun `a value palette's kept layer still comes off with its pad, in the programmer and in a cue`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedRig()
        val half = client.palette("Half", generic("dimmer", "pct:50"))
        val source = uk.me.cormack.lighting7.models.LayerSource.palette(half.id, UUID.fromString(half.uuid), half.name)
        state.show.programmerLayerStack.add(source, listOf(CueTargetDto("fixture", "hex-1")))
        assertEquals(1, paletteLayers().size)

        val off = client.toggle(half, "hex-1")
        assertEquals("removed", off.action, "the kept layer lights the pad, so the press is off")
        assertTrue(paletteLayers().isEmpty())
        assertTrue(refs().isEmpty())

        val cueId = client.cue(null, "Q1")
        client.patch("$base/cues/$cueId") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("layers", JsonArray(listOf(buildJsonObject {
                    put("paletteId", half.id)
                    put("targets", JsonArray(listOf(buildJsonObject { put("type", "fixture"); put("key", "hex-1") })))
                })))
            })
        }.also { assertTrue(it.status.value in 200..299, it.bodyAsText()) }
        val cueOff = client.cuePress(cueId, half, CueTargetDto("fixture", "hex-1"))
        assertEquals("removed", cueOff.action)
        assertTrue(cueOff.cue.layers.isEmpty() && cueOff.cue.attributeAssignments.isEmpty())
    }

    @Test
    fun `an FX palette's press in a solo bank takes a value sibling's references off its heads`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedRig()
        val half = client.palette("Half", generic("dimmer", "pct:50"))
        val waveResp = client.post("$base/palettes") {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = "Pulse", fx = uk.me.cormack.lighting7.models.PaletteFxDto(
                fxType = "Wave", family = "intensity", attribute = "dimmer", cycle = 1.0, blendMode = "OVERRIDE", order = "NORMAL",
            )))
        }
        assertEquals(HttpStatusCode.Created, waveResp.status, waveResp.bodyAsText())
        val pulse = waveResp.body<PaletteDto>()
        val (halfPad, pulsePad) = client.bank("Solo", solo = true, BuskLayoutPad(paletteId = half.id), BuskLayoutPad(paletteId = pulse.id))

        client.pressPad(halfPad, "hex-1", "hex-2")
        client.pressPad(pulsePad, "hex-1")
        assertEquals(listOf("hex-2.dimmer=Half"), refs())
    }

    // ─── Use it on these heads, Record, Include and Update ──────────────

    @Test
    fun `Use it on these heads makes the recorded values references to the new palette, nothing moving`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedRig()
        ProgrammerHandler.set(state, TargetRef.Fixture("hex-1"), "dimmer", "51", 0)
        ProgrammerHandler.set(state, TargetRef.Fixture("hex-2"), "dimmer", "204", 0)

        val resp = client.post("$base/palettes/from-programmer") {
            contentType(ContentType.Application.Json)
            setBody(PaletteFromProgrammerRequest(
                name = "Spots", mask = listOf("INTENSITY"),
                targets = listOf(CueTargetDto("fixture", "hex-1"), CueTargetDto("fixture", "hex-2")),
                asReference = true,
            ))
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        val answer = resp.body<PaletteFromProgrammerResponse>()
        assertEquals(2, answer.referenced)
        assertEquals(listOf("hex-1.dimmer=Spots", "hex-2.dimmer=Spots"), refs())
        assertEquals(51, dimmer("hex-1"))
        assertEquals(204, dimmer("hex-2"))
    }

    @Test
    fun `Record writes a reference as one, Include brings it back as one, and Update writes a newer press back`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedRig()
        val half = client.palette("Half", generic("dimmer", "pct:50"))
        val full = client.palette("Full", generic("dimmer", "pct:100"))
        val stackId = client.stack("Act 1")

        val pressed = client.post("$base/palettes/${half.id}/toggle") {
            contentType(ContentType.Application.Json)
            setBody(TogglePaletteRequest(targets = listOf(PaletteTargetDto("group", "wash"))))
        }
        assertEquals(HttpStatusCode.OK, pressed.status, pressed.bodyAsText())

        val recorded = client.post("/api/rest/programmer/record") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerRecordRequest(mode = "CREATE", cueStackId = stackId))
        }
        assertTrue(recorded.status.value in 200..201, recorded.bodyAsText())
        val cue = recorded.body<ProgrammerRecordResponse>().cue
        assertEquals(listOf("group:wash.dimmer=Half"), cue.rowWords(half), "two members referencing one palette are one group row")

        state.show.fxEngine.programmer.clearAll()
        val included = client.post("/api/rest/programmer/include") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerIncludeRequest(cueId = cue.id))
        }
        assertEquals(HttpStatusCode.OK, included.status, included.bodyAsText())
        assertEquals(listOf("hex-1.dimmer=Half", "hex-2.dimmer=Half"), refs(), "Include loads the reference back as one")

        client.toggle(full, "hex-1")
        val updated = client.post("/api/rest/programmer/update") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerUpdateRequest())
        }
        assertEquals(HttpStatusCode.OK, updated.status, updated.bodyAsText())
        val rows = client.details(cue.id).rowWords(half, full)
        assertTrue("fixture:hex-1.dimmer=Full" in rows, "Update writes the newer press back as a reference: $rows")
    }

    // ─── Helpers over the busk and Timeline routes ──────────────────────

    private suspend fun HttpClient.bank(name: String, solo: Boolean, vararg pads: BuskLayoutPad): List<Int> {
        val page = post("$base/busk/pages") {
            contentType(ContentType.Application.Json)
            setBody(CreateBuskPageRequest(name))
        }.body<BuskPageDto>()
        val resp = put("$base/busk/pages/${page.id}/layout") {
            contentType(ContentType.Application.Json)
            val bank = BuskLayoutBank(name = name, solo = solo, pads = pads.toList())
            setBody(BuskLayoutRequest(listOf(BuskLayoutRow(listOf(BuskLayoutColumn(width = 12, banks = listOf(bank)))))))
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        return resp.body<BuskPageDto>().rows.single().columns.single().banks.single().pads.map { it.id }
    }

    private suspend fun HttpClient.pressPad(padId: Int, vararg keys: String): BuskPressResponse {
        val resp = post("$base/busk/pads/$padId/press") {
            contentType(ContentType.Application.Json)
            setBody(BuskPressRequest(targets = keys.map { CueTargetDto("fixture", it) }))
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        return resp.body()
    }

    private suspend fun HttpClient.timelineEdit(stackId: Int, vararg ops: JsonObject): TimelineEditResponse {
        val resp = post("$base/cue-stacks/$stackId/timeline/edit") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("ops", JsonArray(ops.toList())) })
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        return resp.body()
    }
}
