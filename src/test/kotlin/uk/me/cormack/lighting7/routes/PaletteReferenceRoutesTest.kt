package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.PaletteReferenceInvalidException
import uk.me.cormack.lighting7.fx.PaletteReferences
import uk.me.cormack.lighting7.fx.ProgrammerValue
import uk.me.cormack.lighting7.fx.takeLooseCue
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueAttributeAssignment
import uk.me.cormack.lighting7.models.DaoCueLayer
import uk.me.cormack.lighting7.models.DaoCues
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoPalette
import uk.me.cormack.lighting7.models.DaoPalettes
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.LookRowDto
import uk.me.cormack.lighting7.models.PaletteFxDto
import uk.me.cormack.lighting7.models.PaletteRowDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.plugins.ProgrammerHandler
import uk.me.cormack.lighting7.show.FixturesChangeListener
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Palette references at the desk's doors (palette-references plan session 1): the write boundary,
 * the read, a palette edit moving every reference in one pass, the delete guard and its hardening,
 * *Copy the values*, and a copy between projects.
 */
class PaletteReferenceRoutesTest : RouteIntegrationTest() {

    private fun palettes() = "/api/rest/projects/$projectId/palettes"
    private fun looks() = "/api/rest/projects/$projectId/looks"

    private fun seedHexes(vararg keys: String) =
        keys.forEachIndexed { i, key -> LocateTestSupport.seedHex(state, projectId, key, startChannel = 1 + i * 16) }

    private fun dimmer(value: String, target: String? = null) = PaletteRowDto(
        targetType = if (target == null) DEFERRED_TARGET_TYPE else "fixture",
        targetKey = target.orEmpty(),
        attribute = "dimmer", value = value,
    )

    private suspend fun createPalette(client: HttpClient, name: String, vararg rows: PaletteRowDto): PaletteDto {
        val resp = client.post(palettes()) {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = name, rows = rows.toList()))
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body()
    }

    private suspend fun createLook(client: HttpClient, name: String, vararg rows: LookRowDto) = client.post(looks()) {
        contentType(ContentType.Application.Json)
        setBody(CreateLookRequest(name = name, rows = rows.toList()))
    }

    private fun ref(palette: PaletteDto) = "pal:${palette.uuid}"

    /** A live (loosely held) cue holding [rows], and, when [lookUuid] is given, a layer of that Look. */
    private fun liveCue(name: String, rows: List<Triple<String, String, String>>, lookUuid: String? = null): Int {
        val cueId = transaction(state.database) {
            val cue = DaoCue.new { project = DaoProject.findById(projectId)!!; this.name = name }
            rows.forEachIndexed { i, (type, key, value) ->
                DaoCueAttributeAssignment.new {
                    this.cue = cue; targetType = type; targetKey = key; attribute = "dimmer"; this.value = value; sortOrder = i
                }
            }
            if (lookUuid != null) {
                DaoCueLayer.new {
                    this.cue = cue; look = DaoLook.all().single { it.uuid.toString() == lookUuid }; sortOrder = 0; targets = emptyList()
                }
            }
            cue.id.value
        }
        state.show.cueStackManager.holds.takeLooseCue(state, cueId)
        return cueId
    }

    private fun cueDimmer(key: String) =
        state.show.fxEngine.layerResolver.currentCueLayerState[CueAssignmentResolver.Key.fixture(key, "dimmer")]

    private fun programmerDimmer(key: String) = state.show.programmerStore.get(key, "dimmer")?.value

    @Test
    fun `a Look row may reference this project's value palette, and reads what it resolves to`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHexes("hex-1")
        val full = createPalette(client, "Full", dimmer("pct:100"))

        val resp = createLook(client, "Follows", LookRowDto("fixture", "hex-1", "dimmer", ref(full)))
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        val look = resp.body<LookDetails>()
        val row = look.rows.single()
        assertEquals(ref(full), row.value, "stored as the reference")
        assertEquals("Full", row.reference?.name)
        assertEquals(mapOf("hex-1" to "255"), row.resolvedByHead)
        assertNotNull(row.uuid)
    }

    @Test
    fun `a reference to a missing palette, an FX palette or another family is refused by name`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHexes("hex-1")
        val fx = client.post(palettes()) {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = "Pulse", fx = PaletteFxDto(fxType = "Wave", family = "intensity", attribute = "dimmer", cycle = 1.0, blendMode = "OVERRIDE", order = "NORMAL")))
        }.body<PaletteDto>()
        val full = createPalette(client, "Full", dimmer("pct:100"))

        for (row in listOf(
            LookRowDto("fixture", "hex-1", "dimmer", "pal:${UUID.randomUUID()}"),
            LookRowDto("fixture", "hex-1", "dimmer", ref(fx)),
            LookRowDto("fixture", "hex-1", "rgbColour", ref(full)),
        )) {
            val resp = createLook(client, "Bad ${row.value.takeLast(4)}${row.attribute}", row)
            assertEquals(HttpStatusCode.BadRequest, resp.status, resp.bodyAsText())
            assertTrue(PaletteReferenceInvalidException.CODE in resp.bodyAsText(), resp.bodyAsText())
        }
    }

    @Test
    fun `a palette edit moves the programmer's reference, a live cue's row and a layered Look in one pass`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHexes("hex-1", "hex-2", "hex-3")
        val level = createPalette(client, "Level", dimmer("pct:100"))

        ProgrammerHandler.set(state, TargetRef.Fixture("hex-1"), "dimmer", ref(level), fadeMs = 0)
        assertIs<ProgrammerValue.Reference>(programmerDimmer("hex-1"))
        val rowCue = liveCue("rows", listOf(Triple("fixture", "hex-2", ref(level))))
        val look = createLook(client, "Layered", LookRowDto("fixture", "hex-3", "dimmer", ref(level))).body<LookDetails>()
        val layerCue = liveCue("layer", emptyList(), lookUuid = look.uuid)
        assertEquals(CueAssignmentResolver.AttributeValue.Slider(255u), cueDimmer("hex-2"))
        assertEquals(CueAssignmentResolver.AttributeValue.Slider(255u), cueDimmer("hex-3"))

        val announced = mutableListOf<Int>()
        state.show.fixtures.registerListener(object : FixturesChangeListener {
            override fun cuesRecomposed(cueIds: List<Int>) { announced += cueIds }
        })
        val put = client.put("${palettes()}/${level.id}") {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = "Level", rows = listOf(dimmer("pct:20"))))
        }
        assertEquals(HttpStatusCode.OK, put.status, put.bodyAsText())

        val moved = programmerDimmer("hex-1")?.resolved
        assertTrue(moved != CueAssignmentResolver.AttributeValue.Slider(255u), "the programmer's reference moved")
        assertEquals(moved, cueDimmer("hex-2"), "the live cue's own row moved with no GO")
        assertEquals(moved, cueDimmer("hex-3"), "the Look the live cue layers moved with no GO")
        assertEquals(setOf(rowCue, layerCue), announced.toSet())
    }

    @Test
    fun `a referenced palette refuses delete, and force hardens every reference to its last value`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHexes("hex-1", "hex-2", "hex-3")
        val level = createPalette(client, "Level", dimmer("pct:100"))
        ProgrammerHandler.set(state, TargetRef.Fixture("hex-1"), "dimmer", ref(level), fadeMs = 0)
        val cueId = liveCue("rows", listOf(Triple("fixture", "hex-2", ref(level))))
        val look = createLook(client, "Follows", LookRowDto("fixture", "hex-3", "dimmer", ref(level))).body<LookDetails>()

        val refused = client.delete("${palettes()}/${level.id}")
        assertEquals(HttpStatusCode.Conflict, refused.status)
        val body = refused.body<PaletteInUseResponse>()
        assertEquals(listOf(1, 1, 1), listOf(body.cueReferenceCount, body.lookReferenceCount, body.programmerReferenceCount))

        assertEquals(HttpStatusCode.NoContent, client.delete("${palettes()}/${level.id}?force=true").status)
        transaction(state.database) {
            assertEquals("255", DaoCue.findById(cueId)!!.attributeAssignments.single().value)
            assertEquals("255", DaoLook.all().single { it.uuid.toString() == look.uuid }.rows.single().value)
        }
        assertEquals(ProgrammerValue.Hard(CueAssignmentResolver.AttributeValue.Slider(255u)), programmerDimmer("hex-1"))
        assertEquals(CueAssignmentResolver.AttributeValue.Slider(255u), cueDimmer("hex-2"), "nothing on stage moved")
    }

    @Test
    fun `Copy the values splits a group row where its heads resolve apart`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHexes("hex-1", "hex-2")
        LocateTestSupport.seedGroup(state, projectId, "front", "hex-1", "hex-2")
        val focus = createPalette(client, "Focus", dimmer("pct:100", "hex-1"), dimmer("pct:20", "hex-2"))
        val look = createLook(client, "Front", LookRowDto("group", "front", "dimmer", ref(focus))).body<LookDetails>()
        assertEquals(2, look.rows.single().resolvedByHead?.size)

        val hardened = client.post("${looks()}/${look.id}/harden") {
            contentType(ContentType.Application.Json)
            setBody(HardenRequest())
        }
        assertEquals(HttpStatusCode.OK, hardened.status, hardened.bodyAsText())
        assertEquals(1, hardened.body<HardenResponse>().hardened)
        val rows = client.get("${looks()}/${look.id}").body<LookDetails>().rows
        assertEquals(setOf("hex-1", "hex-2"), rows.map { it.targetKey }.toSet())
        assertTrue(rows.all { it.targetType == "fixture" && !PaletteReferences.isReference(it.value) })
        assertEquals(2, rows.map { it.value }.toSet().size)
    }

    @Test
    fun `Copy the values on a cue hardens the rows it names`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHexes("hex-1", "hex-2")
        val level = createPalette(client, "Level", dimmer("pct:100"))
        val cueId = liveCue("rows", listOf(Triple("fixture", "hex-1", ref(level)), Triple("fixture", "hex-2", ref(level))))
        val named = transaction(state.database) {
            DaoCue.findById(cueId)!!.attributeAssignments.single { it.targetKey == "hex-1" }.uuid.toString()
        }
        val resp = client.post("/api/rest/projects/$projectId/cues/$cueId/harden") {
            contentType(ContentType.Application.Json)
            setBody(HardenRequest(rowUuids = listOf(named)))
        }
        assertEquals(1, resp.body<HardenResponse>().hardened)
        transaction(state.database) {
            val byKey = DaoCue.findById(cueId)!!.attributeAssignments.associate { it.targetKey to it.value }
            assertEquals("255", byKey["hex-1"])
            assertEquals(ref(level), byKey["hex-2"], "the row not named still follows the palette")
        }
    }

    @Test
    fun `a programmer colour reference lands the palette's hex and white together`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHexes("hex-1")
        val cool = createPalette(
            client, "Cool",
            PaletteRowDto(DEFERRED_TARGET_TYPE, "", "rgbColour", "#4080ff"),
            PaletteRowDto(DEFERRED_TARGET_TYPE, "", "white", "dmx:120", sortOrder = 1),
        )
        val reply = assertIs<uk.me.cormack.lighting7.plugins.ProgrammerStateOutMessage>(
            ProgrammerHandler.set(state, TargetRef.Fixture("hex-1"), "rgbColour", ref(cool), fadeMs = 0),
        )
        val answered = reply.entries.associateBy { it.attribute }
        assertEquals("120", answered.getValue("white").value, "answered resolved, under the key it landed on")
        assertEquals("Cool", answered.getValue("rgbColour").reference?.name)
        assertIs<ProgrammerValue.Reference>(state.show.programmerStore.get("hex-1", "rgbColour")?.value)
        val white = assertIs<ProgrammerValue.Reference>(state.show.programmerStore.get("hex-1", "white")?.value)
        assertEquals(CueAssignmentResolver.AttributeValue.Slider(120u), white.resolved)
    }

    @Test
    fun `Copy the values leaves a reference it has nothing to keep for`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHexes("hex-1", "hex-2")
        val focus = createPalette(client, "Focus", dimmer("pct:100", "hex-1"))
        val look = createLook(
            client, "Two",
            LookRowDto("fixture", "hex-1", "dimmer", ref(focus)),
            LookRowDto("fixture", "hex-2", "dimmer", ref(focus), sortOrder = 1),
        ).body<LookDetails>()
        val resp = client.post("${looks()}/${look.id}/harden") {
            contentType(ContentType.Application.Json)
            setBody(HardenRequest())
        }.body<HardenResponse>()
        assertEquals(HardenResponse(hardened = 1, kept = 1), resp)
        val byKey = client.get("${looks()}/${look.id}").body<LookDetails>().rows.associate { it.targetKey to it.value }
        assertEquals("255", byKey["hex-1"])
        assertEquals(ref(focus), byKey["hex-2"], "the palette has no row for hex-2, so its reference stays")
    }

    @Test
    fun `a cue copied to another project references that project's palette of the name, else a copy`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedHexes("hex-1")
        val level = createPalette(client, "Level", dimmer("pct:100"))
        val cueId = liveCue("rows", listOf(Triple("fixture", "hex-1", ref(level))))
        transaction(state.database) {
            val source = DaoProject.findById(projectId)!!
            val target = DaoProject.new { name = "Other"; description = "copy target"; isCurrent = false }
            val copier = CueCopier(source, target)
            val copy = copier.copyCue(DaoCue.findById(cueId)!!, "copied")
            val copied = DaoPalette.find { (DaoPalettes.project eq target.id) }.single()
            assertEquals("Level", copied.name)
            assertEquals("pal:${copied.uuid}", copy.attributeAssignments.single().value)
            assertEquals(copied.uuid, copy.attributeAssignments.single().paletteUuid)
            assertTrue(DaoCue.find { DaoCues.project eq target.id }.count() == 1L)
        }
    }
}
