package uk.me.cormack.lighting7.routes

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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.ai.AiTools
import uk.me.cormack.lighting7.fx.SpreadUnit
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLooks
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A new step FX starts one step behind (fx-settings plan D7) through every desk door that starts one,
 * `STEP` is refused on an FX with no steps (D6), and a swap off a step FX keeps the heads' offsets.
 */
class SpreadUnitDoorsRoutesTest : RouteIntegrationTest() {

    private val engine get() = state.show.fxEngine

    private fun seedRig() {
        for (i in 0 until 4) LocateTestSupport.seedFixture(state, projectId, "hex", "hex-$i", 1 + 12 * i)
        LocateTestSupport.seedGroup(state, projectId, "hexes", "hex-0", "hex-1", "hex-2", "hex-3")
    }

    private val four = (0 until 4).map { CueTargetDto(TargetRef.Fixture.TYPE, "hex-$it") }

    private suspend fun HttpResponse.fxId(): Long {
        assertEquals(HttpStatusCode.OK, status, bodyAsText())
        return body<AddFxRequestAnswer>().fxId
    }

    @kotlinx.serialization.Serializable
    private data class AddFxRequestAnswer(val fxId: Long)

    private fun dynamicsOf(fxId: Long) = assertNotNull(engine.getFx(fxId)).dynamics

    @Test
    fun `POST fx add starts a step FX one step behind and anything else on the cycle`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        suspend fun add(request: AddFxRequest) = client.post("/api/rest/fx/add") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        val cycle = dynamicsOf(add(AddFxRequest(fxType = "ColourCycle", attribute = "rgbColour", targets = four)).fxId())
        assertEquals(SpreadUnit.STEP, cycle.spreadUnit)
        assertEquals(1.0, cycle.spread)
        val sent = dynamicsOf(add(AddFxRequest(fxType = "ColourCycle", attribute = "rgbColour", targets = four, spread = 0.5)).fxId())
        assertEquals(SpreadUnit.CYCLE, sent.spreadUnit, "a spread sent without a unit is the cycle's")
        val wave = dynamicsOf(add(AddFxRequest(fxType = "Wave", attribute = "dimmer", targets = four)).fxId())
        assertEquals(SpreadUnit.CYCLE, wave.spreadUnit)

        val refused = add(AddFxRequest(fxType = "Wave", attribute = "dimmer", targets = four, spreadUnit = "STEP"))
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        assertTrue("Wave" in refused.bodyAsText(), refused.bodyAsText())
        val tooFar = add(AddFxRequest(fxType = "ColourCycle", attribute = "rgbColour", targets = four, spread = 17.0, spreadUnit = "STEP"))
        assertEquals(HttpStatusCode.BadRequest, tooFar.status)
    }

    @Test
    fun `the bare press and the group route start a step FX one step behind`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val press = client.post("/api/rest/projects/$projectId/programmer/fx") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerFxRequest(fxType = "ColourCycle", attribute = "rgbColour", targets = four))
        }
        assertEquals(HttpStatusCode.OK, press.status, press.bodyAsText())
        assertEquals(SpreadUnit.STEP, dynamicsOf(press.body<ProgrammerFxResponse>().fxId).spreadUnit)

        val group = client.post("/api/rest/groups/hexes/fx") {
            contentType(ContentType.Application.Json)
            setBody(AddGroupFxRequest(fxType = "ColourCycle", attribute = "rgbColour"))
        }
        assertEquals(HttpStatusCode.OK, group.status, group.bodyAsText())
        val fx = assertNotNull(engine.getFx(group.body<AddGroupFxResponse>().fxId))
        assertEquals(SpreadUnit.STEP, fx.dynamics.spreadUnit)
        assertEquals("STEP", engine.fxDto(fx).spreadUnit)
    }

    @Test
    fun `PUT fx refuses STEP on a Wave and a swap off a step FX keeps the offsets`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val wave = client.post("/api/rest/fx/add") {
            contentType(ContentType.Application.Json)
            setBody(AddFxRequest(fxType = "Wave", attribute = "dimmer", targets = four))
        }.fxId()
        val refused = client.put("/api/rest/fx/$wave") {
            contentType(ContentType.Application.Json)
            setBody(UpdateFxRequest(spreadUnit = "STEP"))
        }
        assertFalse(refused.status.isSuccess(), refused.bodyAsText())
        assertEquals(SpreadUnit.CYCLE, dynamicsOf(wave).spreadUnit)

        val chase = client.post("/api/rest/fx/add") {
            contentType(ContentType.Application.Json)
            setBody(AddFxRequest(fxType = "ColourCycle", attribute = "rgbColour", targets = four, order = "NORMAL"))
        }.fxId()
        val before = engine.paintedHeads(assertNotNull(engine.getFx(chase))).map { it.offset }
        assertEquals(listOf(0.0, 1.0 / 3, 2.0 / 3, 1.0), before)
        // Onto Rainbow Cycle, which has no steps: four heads one step of three apart is 4/3 of the
        // cycle over four slots.
        val swapped = client.put("/api/rest/fx/$chase") {
            contentType(ContentType.Application.Json)
            setBody(UpdateFxRequest(fxType = "RainbowCycle"))
        }
        assertEquals(HttpStatusCode.OK, swapped.status, swapped.bodyAsText())
        val dyn = dynamicsOf(chase)
        assertEquals(SpreadUnit.CYCLE, dyn.spreadUnit)
        assertEquals(4.0 / 3, dyn.spread, 1e-9)
        assertEquals(before, engine.paintedHeads(assertNotNull(engine.getFx(chase))).map { it.offset })
    }

    private fun HttpStatusCode.isSuccess() = value in 200..299

    @Test
    fun `MCP writes a step FX one step behind and refuses STEP on a Wave`() {
        seedRig()
        val tools = AiTools(state)
        fun createLook(name: String, fxType: String, family: String, extra: String) = runBlocking {
            val fx = """{"targetType":"group","targetKey":"hexes","fxType":"$fxType","family":"$family","blendMode":"OVERRIDE","parameters":{}$extra}"""
            tools.executeTool("create_look", Json.parseToJsonElement("""{"name":"$name","fx":[$fx]}""").jsonObject)
        }
        fun stored(look: String) = transaction(state.database) {
            DaoLook.find { DaoLooks.name eq look }.single().effects.single().let { it.spreadUnit to it.spread }
        }

        assertTrue(createLook("Chase", "ColourCycle", "colour", "").success)
        assertEquals("STEP" to 1.0, stored("Chase"))
        assertTrue(createLook("Ripple", "ColourCycle", "colour", ""","spread":0.5""").success)
        assertEquals(null to 0.5, stored("Ripple"))
        assertTrue(createLook("Two", "ColourCycle", "colour", ""","spread":2,"spreadUnit":"STEP"""").success)
        assertEquals("STEP" to 2.0, stored("Two"))
        assertFalse(createLook("Bad", "Wave", "intensity", ""","spreadUnit":"STEP"""").success)
    }

    @Test
    fun `MCP reads say a step FX's spread and unit, so a write-back keeps it on the cycle`() = testApplication {
        mountTestApp(state)
        seedRig()
        val id = jsonClient().post("/api/rest/fx/add") {
            contentType(ContentType.Application.Json)
            setBody(AddFxRequest(fxType = "ColourCycle", attribute = "rgbColour", targets = four, spread = 1.0, spreadUnit = "CYCLE"))
        }.fxId()
        assertEquals(SpreadUnit.CYCLE, dynamicsOf(id).spreadUnit)
        val read = AiTools(state).executeTool("get_current_state", Json.parseToJsonElement("{}").jsonObject)
        assertTrue(read.success, read.result)
        assertTrue("\"spreadUnit\":\"CYCLE\"" in read.result, read.result)
        assertTrue("\"spread\":1.0" in read.result, read.result)
    }
}
