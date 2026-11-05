package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.LookRowDto
import uk.me.cormack.lighting7.models.PaletteRowDto
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `POST /looks/{id}/press` (palette-references plan D23): a value palette tapped into a Look focused
 * in the programmer writes its reference rows into the Look's own rows, by the cue press's rule, and
 * a second tap takes them out.
 */
class LookReferencePressRouteTest : RouteIntegrationTest() {

    private val base get() = "/api/rest/projects/$projectId"

    private fun seedRig() {
        listOf("hex-1", "hex-2").forEachIndexed { i, key -> LocateTestSupport.seedHex(state, projectId, key, startChannel = 1 + i * 16) }
        LocateTestSupport.seedGroup(state, projectId, "wash", "hex-1", "hex-2")
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

    private suspend fun HttpClient.look(name: String, vararg rows: LookRowDto): LookDetails {
        val resp = post("$base/looks") {
            contentType(ContentType.Application.Json)
            setBody(CreateLookRequest(name = name, rows = rows.toList()))
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body()
    }

    private suspend fun HttpClient.press(lookId: Int, palette: PaletteDto, vararg targets: CueTargetDto, families: List<String>? = null): HttpResponse =
        post("$base/looks/$lookId/press") {
            contentType(ContentType.Application.Json)
            setBody(LookPressRequest(paletteId = palette.id, targets = targets.toList(), families = families))
        }

    private suspend fun HttpClient.pressed(lookId: Int, palette: PaletteDto, vararg targets: CueTargetDto): LookPressResponse {
        val resp = press(lookId, palette, *targets)
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        return resp.body()
    }

    private fun fixture(key: String) = CueTargetDto("fixture", key)

    private fun LookDetails.spelled() = rows.map { "${it.targetType}:${it.targetKey}.${it.attribute}=${it.value}" }.sorted()

    @Test
    fun `a tap writes the palette's references over the Look's rows on those heads, and a second tap takes them out`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedRig()
        val half = client.palette("Half", generic("dimmer", "pct:50"))
        val look = client.look(
            "Night",
            LookRowDto("fixture", "hex-1", "dimmer", "40"),
            LookRowDto("fixture", "hex-2", "dimmer", "60"),
        )

        val on = client.pressed(look.id, half, fixture("hex-1"))
        assertEquals("applied", on.action)
        assertEquals(listOf("fixture:hex-1.dimmer=pal:${half.uuid}", "fixture:hex-2.dimmer=60"), on.look.spelled())
        val written = on.look.rows.single { it.targetKey == "hex-1" }
        assertEquals("Half", written.reference?.name)
        assertEquals("128", written.resolvedByHead?.get("hex-1"))

        val off = client.pressed(look.id, half, fixture("hex-1"))
        assertEquals("removed", off.action)
        assertEquals(listOf("fixture:hex-2.dimmer=60"), off.look.spelled())
    }

    @Test
    fun `a group stays one row, and the family's other palette gives up the heads this one lands on`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedRig()
        val half = client.palette("Half", generic("dimmer", "pct:50"))
        val full = client.palette("Full", generic("dimmer", "pct:100"))
        val look = client.look("Night")

        client.pressed(look.id, half, CueTargetDto("group", "wash"))
        val replaced = client.pressed(look.id, full, CueTargetDto("group", "wash"))
        assertEquals(1, replaced.released)
        assertEquals(listOf("group:wash.dimmer=pal:${full.uuid}"), replaced.look.spelled())
    }

    @Test
    fun `a generic palette with no selection, a masked-out family and an unknown Look are refused`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedRig()
        val half = client.palette("Half", generic("dimmer", "pct:50"))
        val look = client.look("Night", LookRowDto("fixture", "hex-1", "dimmer", "40"))

        val none = client.press(look.id, half)
        assertEquals(HttpStatusCode.BadRequest, none.status)
        assertTrue(none.bodyAsText().contains(CODE_PALETTE_NEEDS_SELECTION))

        val masked = client.press(look.id, half, fixture("hex-1"), families = listOf("COLOUR"))
        assertEquals(HttpStatusCode.BadRequest, masked.status)
        assertTrue(masked.bodyAsText().contains(CODE_PALETTE_OUTSIDE_MASK))

        assertEquals(HttpStatusCode.NotFound, client.press(look.id + 99, half, fixture("hex-1")).status)
    }

    @Test
    fun `the library row counts references and keeps them out of its preview of literals`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedRig()
        val half = client.palette("Half", generic("dimmer", "pct:50"))
        val look = client.look("Night", LookRowDto("fixture", "hex-2", "dimmer", "60"))
        client.pressed(look.id, half, fixture("hex-1"))

        val resp = client.get("$base/looks")
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        val row = resp.body<List<LookDto>>().single { it.id == look.id }
        assertEquals(1, row.referenceCount)
        assertEquals(listOf("60"), row.preview)
    }

    @Test
    fun `a press on a cell writes over the cell's stored row, and stores its own under the parent`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedFixture(state, projectId, "shehds-led19-rgbw-24ch", "shehds-1", 1)
        state.show.fixtures.patchListChanged()
        val cells = (state.show.fixtures.untypedFixture("shehds-1") as MultiElementFixture<*>).elements.map { it.targetKey }
        val red = client.palette("Red", generic("rgbColour", "#ff0000;policy=rgbonly"))
        // As Record stores a cell's row: under its parent, the cell named by its full key.
        val look = client.look(
            "Night",
            LookRowDto("fixture", "shehds-1", "rgbColour", "#00ff00", elementKey = cells[0]),
            LookRowDto("fixture", "shehds-1", "rgbColour", "#0000ff", elementKey = cells[1]),
        )

        val on = client.pressed(look.id, red, fixture(cells[0]))
        assertEquals("applied", on.action)
        val first = on.look.rows.filter { it.elementKey == cells[0] }
        assertEquals(listOf("pal:${red.uuid}"), first.map { it.value }, "the cell's row taken over, not shadowed: ${on.look.rows}")
        assertEquals(listOf("shehds-1"), first.map { it.targetKey })
        assertEquals("#0000ff", on.look.rows.single { it.elementKey == cells[1] }.value)

        val off = client.pressed(look.id, red, fixture(cells[0]))
        assertEquals("removed", off.action)
        assertEquals(listOf(cells[1]), off.look.rows.map { it.elementKey })
    }

    @Test
    fun `rows the press does not touch keep their order`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        seedRig()
        val half = client.palette("Half", generic("dimmer", "pct:50"))
        val look = client.look(
            "Night",
            LookRowDto("fixture", "hex-1", "dimmer", "40", sortOrder = 0),
            LookRowDto("fixture", "hex-2", "dimmer", "60", sortOrder = 1),
        )
        val stored = look.rows.associate { it.targetKey to it.sortOrder }

        val off = client.pressed(look.id, half, fixture("hex-1")).let { client.pressed(look.id, half, fixture("hex-1")) }
        assertEquals("removed", off.action)
        assertEquals(stored.getValue("hex-2"), off.look.rows.single().sortOrder)
    }
}
