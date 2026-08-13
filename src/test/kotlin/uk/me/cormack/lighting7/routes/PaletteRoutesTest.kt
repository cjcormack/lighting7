package uk.me.cormack.lighting7.routes

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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.fx.paletteRefValue
import uk.me.cormack.lighting7.models.CueStackType
import uk.me.cormack.lighting7.models.CueType
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCuePropertyAssignment
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.PaletteEntryDto
import uk.me.cormack.lighting7.models.PaletteType
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import uk.me.cormack.lighting7.testsupport.LocateTestSupport

class PaletteRoutesTest : RouteIntegrationTest() {

    private fun colourEntry(targetKey: String, value: String, sortOrder: Int = 0) = PaletteEntryDto(
        targetType = "fixture",
        targetKey = targetKey,
        propertyName = "colour",
        value = value,
        sortOrder = sortOrder,
    )

    @Test
    fun `palettes round-trip through POST GET PUT DELETE`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val createResp = client.post("/api/rest/project/$projectId/palettes") {
            contentType(ContentType.Application.Json)
            setBody(
                CreatePaletteRequest(
                    name = "Warm Amber",
                    type = PaletteType.COLOUR.name,
                    notes = "act one",
                    entries = listOf(
                        colourEntry("hex-1", "#ff8800", 0),
                        colourEntry("hex-2", "#ffaa44", 1),
                    ),
                )
            )
        }
        assertEquals(HttpStatusCode.Created, createResp.status, createResp.bodyAsText())
        val created = createResp.body<PaletteDetails>()
        assertEquals("Warm Amber", created.name)
        assertEquals(PaletteType.COLOUR.name, created.type)
        assertEquals(2, created.entries.size)
        assertEquals(0, created.referenceCount)

        val list = client.get("/api/rest/project/$projectId/palettes").body<List<PaletteDto>>()
        assertEquals(1, list.size)
        assertEquals(2, list.single().entryCount)
        assertEquals(2, list.single().targetCount)
        assertEquals(
            listOf("#ff8800", "#ffaa44"), list.single().preview.sorted(),
            "summary carries a resolved preview so a tile needs no detail fetch",
        )

        val fetched = client.get("/api/rest/project/$projectId/palettes/${created.id}")
            .body<PaletteDetails>()
        assertEquals(created.uuid, fetched.uuid)

        val putResp = client.put("/api/rest/project/$projectId/palettes/${created.id}") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("name", JsonPrimitive("Warm Amber 2")) })
        }
        assertEquals(HttpStatusCode.OK, putResp.status, putResp.bodyAsText())
        val updated = putResp.body<PaletteDetails>()
        assertEquals("Warm Amber 2", updated.name)
        assertEquals("act one", updated.notes, "untouched notes survive a rename")
        assertEquals(2, updated.entries.size, "a rename does not disturb entries")

        val del = client.delete("/api/rest/project/$projectId/palettes/${created.id}")
        assertEquals(HttpStatusCode.NoContent, del.status)
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/rest/project/$projectId/palettes/${created.id}").status,
        )
    }

    @Test
    fun `palette names are unique per type, not per project`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        suspend fun create(name: String, type: PaletteType) =
            client.post("/api/rest/project/$projectId/palettes") {
                contentType(ContentType.Application.Json)
                setBody(CreatePaletteRequest(name = name, type = type.name))
            }

        assertEquals(HttpStatusCode.Created, create("Warm", PaletteType.COLOUR).status)
        assertEquals(
            HttpStatusCode.Created, create("Warm", PaletteType.POSITION).status,
            "the same name in a different type bank is a different palette",
        )
        assertEquals(
            HttpStatusCode.Conflict, create("Warm", PaletteType.COLOUR).status,
            "a duplicate within one type is refused",
        )
    }

    @Test
    fun `palette entries may not hold references, so palettes cannot nest`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val resp = client.post("/api/rest/project/$projectId/palettes") {
            contentType(ContentType.Application.Json)
            setBody(
                CreatePaletteRequest(
                    name = "Nested",
                    type = PaletteType.COLOUR.name,
                    entries = listOf(colourEntry("hex-1", paletteRefValue(UUID.randomUUID()))),
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(
            resp.bodyAsText().contains("literal"),
            "the error should say entries must be literal: ${resp.bodyAsText()}",
        )
    }

    @Test
    fun `unknown palette type is rejected on create and on the list filter`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val create = client.post("/api/rest/project/$projectId/palettes") {
            contentType(ContentType.Application.Json)
            setBody(CreatePaletteRequest(name = "Bad", type = "GOBO"))
        }
        assertEquals(HttpStatusCode.BadRequest, create.status)

        assertEquals(
            HttpStatusCode.BadRequest,
            client.get("/api/rest/project/$projectId/palettes?type=GOBO").status,
        )
    }

    @Test
    fun `the list filters by type`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        listOf(PaletteType.COLOUR, PaletteType.POSITION, PaletteType.POSITION).forEachIndexed { i, type ->
            client.post("/api/rest/project/$projectId/palettes") {
                contentType(ContentType.Application.Json)
                setBody(CreatePaletteRequest(name = "p$i", type = type.name))
            }
        }

        val positions = client.get("/api/rest/project/$projectId/palettes?type=POSITION")
            .body<List<PaletteDto>>()
        assertEquals(2, positions.size)
        assertTrue(positions.all { it.type == PaletteType.POSITION.name })
    }

    @Test
    fun `deleting a referenced palette is refused unless forced`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val paletteDto = client.post("/api/rest/project/$projectId/palettes") {
            contentType(ContentType.Application.Json)
            setBody(
                CreatePaletteRequest(
                    name = "Warm Amber",
                    type = PaletteType.COLOUR.name,
                    entries = listOf(colourEntry("hex-1", "#ff8800")),
                )
            )
        }.body<PaletteDetails>()

        val cueId = transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            val stack = DaoCueStack.new {
                this.project = project
                name = "show"; this.palette = emptyList(); loop = false
                type = CueStackType.STACK.name; sortOrder = 0
            }
            val cue = DaoCue.new {
                this.project = project
                name = "open"; cueStack = stack; sortOrder = 0
                this.palette = emptyList(); cueType = CueType.STANDARD.name
            }
            DaoCuePropertyAssignment.new {
                this.cue = cue
                targetType = "fixture"; targetKey = "hex-1"
                propertyName = "colour"
                value = paletteRefValue(UUID.fromString(paletteDto.uuid))
                sortOrder = 0
            }
            cue.id.value
        }

        // The summary must now advertise the reference, which is what greys out Delete client-side.
        val listed = client.get("/api/rest/project/$projectId/palettes").body<List<PaletteDto>>().single()
        assertEquals(1, listed.referenceCount)
        val details = client.get("/api/rest/project/$projectId/palettes/${paletteDto.id}").body<PaletteDetails>()
        assertEquals(listOf(cueId), details.referencedByCueIds)

        val refused = client.delete("/api/rest/project/$projectId/palettes/${paletteDto.id}")
        assertEquals(HttpStatusCode.Conflict, refused.status, refused.bodyAsText())
        val conflict = refused.body<PaletteInUseResponse>()
        assertEquals(CODE_PALETTE_IN_USE, conflict.code)
        assertEquals(1, conflict.referenceCount)
        assertEquals(1, conflict.cueAssignmentCount)
        assertEquals(listOf(cueId), conflict.cueIds)

        // Forcing leaves the cue row dangling on purpose — it reports MissingPalette health and
        // is skipped at apply, which is louder than silently rewriting the operator's value.
        val forced = client.delete("/api/rest/project/$projectId/palettes/${paletteDto.id}?force=true")
        assertEquals(HttpStatusCode.NoContent, forced.status, forced.bodyAsText())
        assertTrue(
            client.get("/api/rest/project/$projectId/palettes").body<List<PaletteDto>>().isEmpty(),
        )
    }

    // ─── record-palette ────────────────────────────────────────────────────

    private suspend fun io.ktor.client.HttpClient.recordPalette(body: String) =
        post("/api/rest/programmer/record-palette") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun writeProgrammerColour(fixtureKey: String, hex: String) {
        val fixture = state.show.fixtures.untypedGroupableFixture(fixtureKey)
        state.show.fxEngine.writeProgrammerProperty(
            uk.me.cormack.lighting7.fx.ProgrammerOwner.WEB, fixture, "rgbColour",
            uk.me.cormack.lighting7.fx.Layer3Resolver.parseAssignmentValue(
                uk.me.cormack.lighting7.fixture.PropertyCategory.COLOUR, "rgbColour", hex,
            )!!,
        )
    }

    @Test
    fun `record-palette creates a palette from the programmer, scoped to the selection`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", 20)

        writeProgrammerColour("hex-1", "#ff8800")
        writeProgrammerColour("hex-2", "#00ff00")

        // Only hex-1 is selected: a palette recorded from the *whole* programmer would silently
        // capture hex-2 as well, which is the mistake the scope filter exists to prevent.
        val resp = client.recordPalette(
            """{"projectId":"$projectId","mode":"CREATE","type":"COLOUR","name":"Warm Amber",
               "targets":[{"type":"fixture","key":"hex-1"}]}"""
        )
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        val body = resp.body<ProgrammerRecordPaletteResponse>()
        assertTrue(body.created)
        assertEquals(1, body.entriesWritten)
        assertEquals(
            listOf("hex-1"), body.palette.entries.map { it.targetKey },
            "hex-2 was out of scope",
        )
        assertEquals("#ff8800", body.palette.entries.single().value)
        assertTrue(
            body.skipped.any { it.targetKey == "hex-2" },
            "the out-of-scope entry is reported rather than silently dropped",
        )
    }

    @Test
    fun `record-palette masks by the palette's own type`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)

        writeProgrammerColour("hex-1", "#ff8800")
        val fixture = state.show.fixtures.untypedGroupableFixture("hex-1")
        state.show.fxEngine.writeProgrammerProperty(
            uk.me.cormack.lighting7.fx.ProgrammerOwner.WEB, fixture, "dimmer",
            uk.me.cormack.lighting7.fx.Layer3Resolver.PropertyValue.Slider(200u),
        )

        val resp = client.recordPalette(
            """{"projectId":"$projectId","mode":"CREATE","type":"COLOUR","name":"Warm"}"""
        )
        val body = resp.body<ProgrammerRecordPaletteResponse>()
        assertEquals(
            listOf("rgbColour"), body.palette.entries.map { it.propertyName },
            "the dimmer is INTENSITY, so a COLOUR palette must not capture it",
        )
    }

    @Test
    fun `re-recording a referenced palette moves the cue that references it`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)

        writeProgrammerColour("hex-1", "#ff8800")
        val created = client.recordPalette(
            """{"projectId":"$projectId","mode":"CREATE","type":"COLOUR","name":"Warm Amber"}"""
        ).body<ProgrammerRecordPaletteResponse>()

        // A live cue referencing it.
        val cueId = transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            val stack = DaoCueStack.new {
                this.project = project
                name = "show"; this.palette = emptyList(); loop = false
                type = CueStackType.STACK.name; sortOrder = 0
            }
            val cue = DaoCue.new {
                this.project = project
                name = "open"; cueStack = stack; sortOrder = 0
                this.palette = emptyList(); cueType = CueType.STANDARD.name
            }
            DaoCuePropertyAssignment.new {
                this.cue = cue
                targetType = "fixture"; targetKey = "hex-1"
                propertyName = "colour"
                value = paletteRefValue(UUID.fromString(created.palette.uuid))
                sortOrder = 0
            }
            cue.id.value
        }
        val applyData = transaction(state.database) { buildCueApplyData(DaoCue.findById(cueId)!!) }
        applyCue(state, applyData, replaceAll = false)

        // Re-record the palette with a new colour busked in — the console editing gesture.
        writeProgrammerColour("hex-1", "#0000ff")
        val again = client.recordPalette(
            """{"projectId":"$projectId","mode":"UPDATE_EXISTING","paletteId":${created.palette.id}}"""
        ).body<ProgrammerRecordPaletteResponse>()

        assertEquals(listOf(cueId), again.cuesRepublished, "the referencing cue republished")
        val composed = state.show.fxEngine.layerResolver
            .currentLayer3State[uk.me.cormack.lighting7.fx.Layer3Resolver.Key.fixture("hex-1", "rgbColour")]
        assertEquals(
            "#0000ff",
            (composed as uk.me.cormack.lighting7.fx.Layer3Resolver.PropertyValue.Colour)
                .value.toSerializedString(),
            "re-recording a palette moves the looks that reference it, with no GO",
        )
    }

    @Test
    fun `record-palette rejects a type that disagrees with the existing palette`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val created = client.post("/api/rest/project/$projectId/palettes") {
            contentType(ContentType.Application.Json)
            setBody(CreatePaletteRequest(name = "Warm", type = PaletteType.COLOUR.name))
        }.body<PaletteDetails>()

        val resp = client.recordPalette(
            """{"projectId":"$projectId","mode":"MERGE","paletteId":${created.id},"type":"POSITION"}"""
        )
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertTrue(resp.bodyAsText().contains("not POSITION"), resp.bodyAsText())
    }
}
