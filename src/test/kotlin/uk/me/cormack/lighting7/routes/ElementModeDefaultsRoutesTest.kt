package uk.me.cormack.lighting7.routes

import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.fx.ElementMode
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * New FX start on Fixtures (FX editor follow-ups plan D8): each route that starts an FX reads an
 * absent element mode as `WHOLE` and keeps one it is given.
 */
class ElementModeDefaultsRoutesTest : RouteIntegrationTest() {

    private val engine get() = state.show.fxEngine

    private fun seedRig() {
        for (i in 0 until 2) LocateTestSupport.seedFixture(state, projectId, "shehds-led19-rgbw-24ch", "sh-$i", 1 + 24 * i)
        LocateTestSupport.seedGroup(state, projectId, "shehds", "sh-0", "sh-1")
    }

    private val both = listOf(CueTargetDto(TargetRef.Fixture.TYPE, "sh-0"), CueTargetDto(TargetRef.Fixture.TYPE, "sh-1"))

    private fun modeOf(fxId: Long) = assertNotNull(engine.getFx(fxId)).elementMode

    private suspend fun HttpResponse.fxId(): Long {
        assertEquals(HttpStatusCode.OK, status, bodyAsText())
        return body<AddFxResponse>().fxId
    }

    @Test
    fun `POST fx add starts on Fixtures and keeps a mode it is given`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        suspend fun add(mode: String?) = client.post("/api/rest/fx/add") {
            contentType(ContentType.Application.Json)
            setBody(AddFxRequest(fxType = "ColourCycle", attribute = "rgbColour", targets = both, elementMode = mode))
        }.fxId()

        assertEquals(ElementMode.WHOLE, modeOf(add(null)))
        assertEquals(ElementMode.FLAT, modeOf(add("FLAT")))
        assertEquals(ElementMode.PER_FIXTURE, modeOf(add("PER_FIXTURE")))
    }

    @Test
    fun `the bare press starts on Fixtures`() = testApplication {
        mountTestApp(state)
        seedRig()
        val response = jsonClient().post("/api/rest/projects/$projectId/programmer/fx") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerFxRequest(fxType = "ColourCycle", attribute = "rgbColour", targets = both))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val fx = assertNotNull(engine.getFx(response.body<ProgrammerFxResponse>().fxId))
        assertEquals(ElementMode.WHOLE, fx.elementMode)
        assertEquals("WHOLE", engine.fxDto(fx).elementMode)
    }

    @Test
    fun `the group FX route starts on Fixtures and keeps a mode it is given`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        suspend fun add(mode: String?): Long {
            val response = client.post("/api/rest/groups/shehds/fx") {
                contentType(ContentType.Application.Json)
                setBody(AddGroupFxRequest(fxType = "ColourCycle", attribute = "rgbColour", elementMode = mode))
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            return response.body<AddGroupFxResponse>().fxId
        }

        assertEquals(ElementMode.WHOLE, modeOf(add(null)))
        assertEquals(ElementMode.FLAT, modeOf(add("FLAT")))
    }

    @Test
    fun `a lone fixture's FX reports its mode where it lands on elements`() = testApplication {
        mountTestApp(state)
        seedRig()
        val id = jsonClient().post("/api/rest/fx/add") {
            contentType(ContentType.Application.Json)
            setBody(AddFxRequest(fxType = "ColourCycle", attribute = "rgbColour", fixtureKey = "sh-0"))
        }.fxId()
        assertEquals("WHOLE", engine.fxDto(assertNotNull(engine.getFx(id))).elementMode)
    }
}
