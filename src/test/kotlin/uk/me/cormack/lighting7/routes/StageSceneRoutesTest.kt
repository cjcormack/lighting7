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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoStageElement
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `stage-elements` and `stage-viewpoints` (stage-view plan session 2, §3.3): CRUD, the per-kind
 * params check at the write boundary, and the seat views that guard their seating.
 */
class StageSceneRoutesTest : RouteIntegrationTest() {

    private fun body(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private suspend fun HttpClient.send(method: String, path: String, json: String? = null): HttpResponse {
        val url = "/api/rest/projects/$projectId/$path"
        return when (method) {
            "POST" -> post(url) { contentType(ContentType.Application.Json); setBody(body(json!!)) }
            "PUT" -> put(url) { contentType(ContentType.Application.Json); setBody(body(json!!)) }
            "DELETE" -> delete(url)
            else -> get(url)
        }
    }

    private val stalls = """{"name":"Stalls","kind":"seating","positionY":-2.4,"positionZ":-0.95,
        "finishColour":"#6A2733","params":{"rows":12,"seatsPerRow":12,"rowPitchM":0.95,"seatPitchM":0.52}}"""

    @Test
    fun `an element round-trips through POST GET PUT DELETE with its params canonical`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val created = client.send(
            "POST", "stage-elements",
            """{"name":"Proscenium","kind":"proscenium","positionY":0.35,"positionZ":-0.95,
               "widthM":8.6,"depthM":0.3,"heightM":5.55,"finishPattern":"panels",
               "params":{"surroundM":0.18,"openingWidthM":5.1,"openingHeightM":2.9,"openingSillM":0.95}}""",
        )
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val dto = created.body<StageElementDto>()
        assertEquals("PROSCENIUM" to "VENUE", dto.kind to dto.layer)
        assertEquals("PANELS", dto.finishPattern)
        assertEquals(listOf("openingHeightM", "openingSillM", "openingWidthM", "surroundM"), dto.params.keys.toList())

        val list = client.send("GET", "stage-elements").body<List<StageElementDto>>()
        assertEquals(listOf("Proscenium"), list.map { it.name })

        val put = client.send("PUT", "stage-elements/${dto.id}", """{"layer":"set","yawDeg":5}""")
        assertEquals(HttpStatusCode.OK, put.status, put.bodyAsText())
        val updated = put.body<StageElementDto>()
        assertEquals("SET", updated.layer)
        assertEquals(5.0, updated.yawDeg)
        assertEquals(8.6, updated.widthM, "an untouched field survives")
        assertEquals(dto.params, updated.params)

        // Shrinking the wall below its opening is refused: the merged element is checked whole.
        val shrunk = client.send("PUT", "stage-elements/${dto.id}", """{"widthM":4}""")
        assertEquals(HttpStatusCode.BadRequest, shrunk.status)
        assertTrue("wider than the proscenium" in shrunk.bodyAsText(), shrunk.bodyAsText())

        assertEquals(HttpStatusCode.NoContent, client.send("DELETE", "stage-elements/${dto.id}").status)
        assertEquals(HttpStatusCode.NotFound, client.send("GET", "stage-elements/${dto.id}").status)
    }

    @Test
    fun `a write lists every problem, refuses a duplicate name, and needs a kind`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val bad = client.send(
            "POST", "stage-elements",
            """{"name":"Sofa","kind":"object","positionX":"DSC","widthM":1.9,"finishColour":"brown"}""",
        )
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        val text = bad.bodyAsText()
        assertTrue("positionX must be a number" in text && "finishColour must be a colour" in text, text)

        val noSize = client.send("POST", "stage-elements", """{"name":"Sofa","kind":"object","widthM":1.9}""")
        assertEquals(HttpStatusCode.BadRequest, noSize.status)
        assertTrue("depthM must be greater than 0" in noSize.bodyAsText(), noSize.bodyAsText())

        assertEquals(HttpStatusCode.BadRequest, client.send("POST", "stage-elements", """{"name":"Sofa"}""").status)

        val sofa = """{"name":"Sofa","kind":"object","widthM":1.9,"depthM":0.85,"heightM":0.85}"""
        assertEquals(HttpStatusCode.Created, client.send("POST", "stage-elements", sofa).status)
        assertEquals(HttpStatusCode.Conflict, client.send("POST", "stage-elements", sofa).status)
    }

    @Test
    fun `viewpoints round-trip, and a seat view guards its seating`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val seating = client.send("POST", "stage-elements", stalls).body<StageElementDto>()
        assertEquals("#6a2733", seating.finishColour)

        val eye = client.send(
            "POST", "stage-viewpoints",
            """{"name":"Balcony · desk","kind":"eye","eyeX":1.25,"eyeY":-17,"eyeZ":2.6,"targetX":0,"targetY":2.6,"targetZ":0.8,"fovDeg":50}""",
        )
        assertEquals(HttpStatusCode.Created, eye.status, eye.bodyAsText())

        val noSeat = client.send(
            "POST", "stage-viewpoints",
            """{"name":"Row Q","kind":"seat","seatElementUuid":"${seating.uuid}","seatId":"Q1"}""",
        )
        assertEquals(HttpStatusCode.BadRequest, noSeat.status)
        assertTrue("has no seat 'Q1'" in noSeat.bodyAsText(), noSeat.bodyAsText())

        val seatResp = client.send(
            "POST", "stage-viewpoints",
            """{"name":"Row F centre","kind":"seat","seatElementUuid":"${seating.uuid}","seatId":"f6"}""",
        )
        assertEquals(HttpStatusCode.Created, seatResp.status, seatResp.bodyAsText())
        val seat = seatResp.body<StageViewpointDto>()
        assertEquals("F6", seat.seatId, "a seat id is stored upper-case")
        assertNull(seat.eyeX)

        val partial = client.send("PUT", "stage-viewpoints/${seat.id}", """{"targetX":1}""")
        assertEquals(HttpStatusCode.BadRequest, partial.status, "a point's three coordinates go together")

        // Five rows would leave Row F without its seat: refused, then allowed with ?force=true.
        val shrink = """{"params":{"rows":5,"seatsPerRow":12,"rowPitchM":0.95,"seatPitchM":0.52}}"""
        val refused = client.send("PUT", "stage-elements/${seating.id}", shrink)
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals(CODE_STAGE_ELEMENT_IN_USE, refused.body<ErrorResponse>().code)
        assertTrue("'Row F centre'" in refused.bodyAsText())

        val deleteRefused = client.send("DELETE", "stage-elements/${seating.id}")
        assertEquals(HttpStatusCode.Conflict, deleteRefused.status)
        assertEquals(CODE_STAGE_ELEMENT_IN_USE, deleteRefused.body<ErrorResponse>().code)

        val forced = client.send("DELETE", "stage-elements/${seating.id}?force=true")
        assertEquals(HttpStatusCode.NoContent, forced.status)
        val dangling = client.send("GET", "stage-viewpoints/${seat.id}").body<StageViewpointDto>()
        assertEquals(seating.uuid, dangling.seatElementUuid, "a forced delete leaves the reference dangling")

        assertEquals(
            listOf("Balcony · desk", "Row F centre"),
            client.send("GET", "stage-viewpoints").body<List<StageViewpointDto>>().map { it.name },
        )
        assertEquals(HttpStatusCode.NoContent, client.send("DELETE", "stage-viewpoints/${seat.id}").status)
    }

    @Test
    fun `a seat view left dangling by a forced write does not refuse later edits of its seating`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val seating = client.send("POST", "stage-elements", stalls).body<StageElementDto>()
        val rowF = client.send(
            "POST", "stage-viewpoints",
            """{"name":"Row F centre","kind":"seat","seatElementUuid":"${seating.uuid}","seatId":"F6"}""",
        ).body<StageViewpointDto>()
        val shrink = """{"params":{"rows":5,"seatsPerRow":12,"rowPitchM":0.95,"seatPitchM":0.52}}"""
        assertEquals(HttpStatusCode.OK, client.send("PUT", "stage-elements/${seating.id}?force=true", shrink).status)
        // Row F now dangles, but a rename or a move does not break it further, so it is not refused.
        val rename = client.send("PUT", "stage-elements/${seating.id}", """{"name":"Stalls left","positionX":0.5}""")
        assertEquals(HttpStatusCode.OK, rename.status, rename.bodyAsText())
        // Growing it back re-seats the view; shrinking it again is a fresh break, and refused.
        val grow = """{"params":{"rows":12,"seatsPerRow":12,"rowPitchM":0.95,"seatPitchM":0.52}}"""
        assertEquals(HttpStatusCode.OK, client.send("PUT", "stage-elements/${seating.id}", grow).status)
        assertEquals(HttpStatusCode.Conflict, client.send("PUT", "stage-elements/${seating.id}", shrink).status)
        assertEquals("F6", client.send("GET", "stage-viewpoints/${rowF.id}").body<StageViewpointDto>().seatId)
    }

    @Test
    fun `a kind change forgets what only the old kind carried`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val seating = client.send("POST", "stage-elements", stalls).body<StageElementDto>()
        val view = client.send(
            "POST", "stage-viewpoints",
            """{"name":"Row F centre","kind":"seat","seatElementUuid":"${seating.uuid}","seatId":"F6","fovDeg":55}""",
        ).body<StageViewpointDto>()
        val eye = client.send(
            "PUT", "stage-viewpoints/${view.id}",
            """{"kind":"eye","eyeX":0,"eyeY":-7,"eyeZ":0.2,"targetX":0,"targetY":2.4,"targetZ":0.9}""",
        )
        assertEquals(HttpStatusCode.OK, eye.status, eye.bodyAsText())
        val asEye = eye.body<StageViewpointDto>()
        assertNull(asEye.seatElementUuid)
        assertNull(asEye.seatId)
        assertEquals(55.0, asEye.fovDeg, "the lens is an eye view's too")
        val orbit = client.send("PUT", "stage-viewpoints/${view.id}", """{"kind":"orbit"}""")
        assertEquals(HttpStatusCode.OK, orbit.status, orbit.bodyAsText())
        assertNull(orbit.body<StageViewpointDto>().fovDeg, "an orbit view has no lens")
        val seat = client.send(
            "PUT", "stage-viewpoints/${view.id}",
            """{"kind":"seat","seatElementUuid":"${seating.uuid}","seatId":"A1"}""",
        )
        assertEquals(HttpStatusCode.OK, seat.status, seat.bodyAsText())
        assertNull(seat.body<StageViewpointDto>().eyeX, "a seat view's eye is its seat's")
    }

    @Test
    fun `deleting a region unlinks the platforms that were its deck, and a dangling link does not block an edit`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val region = client.send("POST", "stage-regions", """{"name":"Main stage","widthM":6.3,"depthM":11}""").body<JsonObject>()
        val regionUuid = region["uuid"]!!.jsonPrimitive.content
        val deck = client.send(
            "POST", "stage-elements",
            """{"name":"Main stage","kind":"platform","widthM":6.3,"depthM":11,"heightM":0.95,"params":{"regionUuid":"$regionUuid"}}""",
        ).body<StageElementDto>()
        assertEquals(HttpStatusCode.NoContent, client.send("DELETE", "stage-regions/${region["id"]!!.jsonPrimitive.content}").status)
        val after = client.send("GET", "stage-elements/${deck.id}").body<StageElementDto>()
        assertNull(after.params["regionUuid"], "the link went with the region")

        // A link that dangles anyway — an import from a peer that deleted the region — reads as none,
        // and an edit that leaves it alone goes through.
        transaction(state.database) {
            DaoStageElement.findById(deck.id)!!.params = """{"regionUuid":"4b6e1f1c-58ab-4e5e-9a54-0e2b5d0c9f11"}"""
        }
        val moved = client.send("PUT", "stage-elements/${deck.id}", """{"positionX":1}""")
        assertEquals(HttpStatusCode.OK, moved.status, moved.bodyAsText())
    }

    @Test
    fun `a REST refusal names the pose as the body spells it`() = testApplication {
        mountTestApp(state)
        val bad = jsonClient().send("POST", "stage-elements", """{"name":"Far","kind":"object","positionX":9999,"widthM":1,"depthM":1,"heightM":1}""")
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertTrue("positionX must be between" in bad.bodyAsText(), bad.bodyAsText())
    }

    @Test
    fun `a platform links a region of its own project by uuid`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val region = client.send("POST", "stage-regions", """{"name":"Main stage","widthM":6.3,"depthM":11}""")
            .body<JsonObject>()["uuid"]!!.jsonPrimitive.content
        val deck = """{"name":"Main stage","kind":"platform","positionY":5.5,"widthM":6.3,"depthM":11,"heightM":0.95"""
        val linked = client.send("POST", "stage-elements", "$deck,\"params\":{\"regionUuid\":\"$region\"}}")
        assertEquals(HttpStatusCode.Created, linked.status, linked.bodyAsText())
        val stranger = client.send(
            "POST", "stage-elements",
            deck.replace("Main stage", "Rostrum") + ",\"params\":{\"regionUuid\":\"4b6e1f1c-58ab-4e5e-9a54-0e2b5d0c9f11\"}}",
        )
        assertEquals(HttpStatusCode.BadRequest, stranger.status)
    }
}
