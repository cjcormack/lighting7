package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.TemplateRowDto
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `POST /projects/{id}/programmer/spread` (busk-further plan D9, §3.5): a group spreads in member
 * order and rig order, a head lacking the property is skipped by name, `tmpl:` endpoints, the mask
 * answering `skippedFamilies`, `over = CELLS`, and every write landing as a programmer entry.
 */
class ProgrammerSpreadRouteTest : RouteIntegrationTest() {

    private fun spread() = "/api/rest/projects/$projectId/programmer/spread"

    private fun seed() {
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", 13)
        LocateTestSupport.seedHex(state, projectId, "hex-3", 25)
        LocateTestSupport.seedFixture(state, projectId, "led-lightbar-12-pixel-48ch", "bar-1", 100)
        LocateTestSupport.seedGroup(state, projectId, "front-wash", "hex-1", "hex-2")
        state.show.fixtures.patchListChanged()
    }

    private fun fixture(key: String) = CueTargetDto("fixture", key)
    private fun group(name: String) = CueTargetDto("group", name)

    private suspend fun HttpClient.post(request: SpreadRequest): HttpResponse =
        post(spread()) {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    private suspend fun HttpClient.run(request: SpreadRequest): SpreadResponse {
        val resp = post(request)
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        return resp.body()
    }

    private suspend fun HttpClient.createTemplate(name: String, hex: String): String {
        val resp = post("/api/rest/projects/$projectId/templates") {
            contentType(ContentType.Application.Json)
            setBody(TemplateInput(name = name, rows = listOf(TemplateRowDto(DEFERRED_TARGET_TYPE, "", "rgbColour", "$hex;policy=extract"))))
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body<TemplateDto>().uuid
    }

    private fun entries() = state.show.programmerStore.entries().map { it.fixtureKey to it.propertyName }.toSet()

    @Test
    fun `a group spreads in member order and every head lands as a programmer entry`() = testApplication {
        seed()
        mountTestApp(state)
        val client = jsonClient()
        val out = client.run(
            SpreadRequest(targets = listOf(group("front-wash"), fixture("hex-3")), property = "dimmer", from = "pct:0", to = "pct:100"),
        )
        assertEquals(listOf("hex-1", "hex-2", "hex-3"), out.written.map { it.target.key })
        assertEquals(listOf("pct:0", "pct:50", "pct:100"), out.written.map { it.value })
        assertEquals(listOf("dimmer", "dimmer", "dimmer"), out.written.map { it.propertyName })
        assertTrue(out.skipped.isEmpty())
        assertEquals(setOf("hex-1" to "dimmer", "hex-2" to "dimmer", "hex-3" to "dimmer"), entries())
    }

    @Test
    fun `LINEAR is rig order, so a rig that reverses the heads reverses the fan`() = testApplication {
        seed()
        mountTestApp(state)
        val client = jsonClient()
        val ids = transaction(state.database) {
            listOf("hex-3", "hex-2", "hex-1").map { key -> DaoFixturePatch.find { DaoFixturePatches.key eq key }.first().id.value }
        }
        val rig = client.put("/api/rest/projects/$projectId/busk/rig") {
            contentType(ContentType.Application.Json)
            setBody(BuskRigRequest(listOf(BuskRigRowInput(name = "Back to front", tiles = ids.map { BuskRigTileInput(patchId = it) }))))
        }
        assertEquals(HttpStatusCode.OK, rig.status, rig.bodyAsText())

        val out = client.run(
            SpreadRequest(targets = listOf(fixture("hex-1"), fixture("hex-2"), fixture("hex-3")), property = "dimmer", from = "pct:0", to = "pct:100"),
        )
        assertEquals(listOf("hex-3", "hex-2", "hex-1"), out.written.map { it.target.key })
        assertEquals(listOf("pct:0", "pct:50", "pct:100"), out.written.map { it.value })

        val reversed = client.run(
            SpreadRequest(targets = listOf(fixture("hex-1"), fixture("hex-2"), fixture("hex-3")), property = "dimmer", from = "pct:0", to = "pct:100", order = "REVERSE"),
        )
        assertEquals(listOf("pct:100", "pct:50", "pct:0"), reversed.written.map { it.value })
    }

    @Test
    fun `a head lacking the property is skipped by name and the rest land`() = testApplication {
        seed()
        mountTestApp(state)
        val client = jsonClient()
        val out = client.run(
            SpreadRequest(targets = listOf(fixture("hex-1"), fixture("hex-2")), property = "position", from = "deg:0,0", to = "deg:90,45"),
        )
        assertTrue(out.written.isEmpty())
        assertEquals(listOf("hex-1", "hex-2"), out.skipped.map { it.target.key })
        assertTrue(out.skipped.all { it.reason.isNotBlank() })
        val gone = client.run(SpreadRequest(targets = listOf(fixture("nope")), property = "dimmer", from = "pct:0", to = "pct:100"))
        assertEquals(listOf("not patched"), gone.skipped.map { it.reason })
    }

    @Test
    fun `colour interpolates in Lab and tmpl endpoints resolve the template's generic colour`() = testApplication {
        seed()
        mountTestApp(state)
        val client = jsonClient()
        val red = client.createTemplate("red", "#ff0000")
        val blue = client.createTemplate("blue", "#0000ff")
        val out = client.run(
            SpreadRequest(targets = listOf(fixture("hex-1"), fixture("hex-2"), fixture("hex-3")), property = "rgbColour", from = "tmpl:$red", to = "tmpl:$blue"),
        )
        assertEquals(3, out.written.size)
        assertEquals("#FF0000;policy=extract", out.written.first().value)
        assertEquals("#0000FF;policy=extract", out.written.last().value)
        val middle = out.written[1].value
        assertNotEquals(out.written.first().value, middle)
        assertNotEquals("#800080;policy=extract", middle, "the midpoint is a Lab mix, not a channel-space average")
        assertEquals(setOf("hex-1" to "rgbColour", "hex-2" to "rgbColour", "hex-3" to "rgbColour"), entries())

        val literal = client.run(SpreadRequest(targets = listOf(fixture("hex-1")), property = "rgbColour", from = "#ff8800", to = "#ff8800"))
        assertEquals("#FF8800;policy=rgbonly", literal.written.single().value, "a bare hex reads as RGB_ONLY, as every other reader reads it")
    }

    @Test
    fun `the selection's mask keeps a property outside it from landing`() = testApplication {
        seed()
        mountTestApp(state)
        val client = jsonClient()
        val out = client.run(
            SpreadRequest(targets = listOf(fixture("hex-1")), families = listOf("POSITION"), property = "dimmer", from = "pct:0", to = "pct:100"),
        )
        assertTrue(out.written.isEmpty())
        assertEquals(listOf("INTENSITY"), out.skippedFamilies)
        assertTrue(entries().isEmpty())
        val inside = client.run(
            SpreadRequest(targets = listOf(fixture("hex-1")), families = listOf("INTENSITY", "COLOUR"), property = "dimmer", from = "pct:0", to = "pct:100"),
        )
        assertEquals(1, inside.written.size)
        assertTrue(inside.skippedFamilies.isEmpty())
    }

    @Test
    fun `over CELLS fans across every cell of a bar, over HEADS treats it as one`() = testApplication {
        seed()
        mountTestApp(state)
        val client = jsonClient()
        val cells = client.run(
            SpreadRequest(targets = listOf(fixture("bar-1")), property = "rgbColour", from = "#ff0000", to = "#0000ff", over = "CELLS"),
        )
        assertEquals((0 until 12).map { "bar-1.pixel-$it" }, cells.written.map { it.target.key })
        assertEquals(12, entries().count { it.first.startsWith("bar-1.pixel-") })

        val heads = client.run(
            SpreadRequest(targets = listOf(fixture("bar-1"), fixture("hex-1")), property = "rgbColour", from = "#ff0000", to = "#0000ff", over = "HEADS"),
        )
        assertEquals(listOf("hex-1"), heads.written.map { it.target.key }, "rig order: every fixture in patch order")
        // The bar is **one** head over HEADS, and as one head it has no colour of its own in pixel
        // mode: one skip naming the bar, not twelve naming its cells.
        assertEquals(listOf("bar-1"), heads.skipped.map { it.target.key })
    }

    @Test
    fun `malformed requests are refused by code`() = testApplication {
        seed()
        mountTestApp(state)
        val client = jsonClient()
        val cases = listOf(
            SpreadRequest(targets = listOf(fixture("hex-1")), property = "gobo", from = "pct:0", to = "pct:1"),
            SpreadRequest(targets = listOf(fixture("hex-1")), property = "dimmer", from = "#ff0000", to = "pct:1"),
            SpreadRequest(targets = listOf(fixture("hex-1")), property = "dimmer", from = "pct:0", to = "pct:1", curve = "ZIGZAG"),
            SpreadRequest(targets = listOf(fixture("hex-1")), property = "dimmer", from = "pct:0", to = "pct:1", order = "SIDEWAYS"),
            SpreadRequest(targets = listOf(fixture("hex-1")), property = "dimmer", from = "pct:0", to = "pct:1", over = "PIXELS"),
            SpreadRequest(targets = listOf(fixture("hex-1")), property = "dimmer", from = "pct:0", to = "pct:1", parts = 0),
            SpreadRequest(targets = listOf(fixture("hex-1")), property = "rgbColour", from = "tmpl:00000000-0000-0000-0000-000000000000", to = "#000000"),
        )
        for (bad in cases) {
            val resp = client.post(bad)
            assertEquals(HttpStatusCode.BadRequest, resp.status, resp.bodyAsText())
            assertEquals(CODE_SPREAD_INVALID, resp.body<ErrorResponse>().code, bad.toString())
        }
        val empty = client.post(SpreadRequest(property = "dimmer", from = "pct:0", to = "pct:1"))
        assertEquals(HttpStatusCode.BadRequest, empty.status)
        assertEquals(CODE_SPREAD_NEEDS_SELECTION, empty.body<ErrorResponse>().code)
        val mask = client.post(SpreadRequest(targets = listOf(fixture("hex-1")), families = listOf("WIBBLE"), property = "dimmer", from = "pct:0", to = "pct:1"))
        assertEquals(HttpStatusCode.BadRequest, mask.status)
    }
}
