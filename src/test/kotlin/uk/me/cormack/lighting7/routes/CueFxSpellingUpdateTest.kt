package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.CueAdHocEffectDto
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.models.toDto
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals

/**
 * A cue FX stored in another spelling of what the programmer captures — no element mode on a group
 * effect, the default filter named, a lower-case Order — is the same FX, so an Update after Include
 * that changed nothing writes none of it, and a change Capture does read is still written.
 */
class CueFxSpellingUpdateTest : RouteIntegrationTest() {

    private val fx get() = state.show.fxEngine

    private fun seedQuad() {
        for (i in 0 until 4) LocateTestSupport.seedHex(state, projectId, "hex-$i", 1 + 12 * i)
        LocateTestSupport.seedGroup(state, projectId, "quad", "hex-0", "hex-1", "hex-2", "hex-3")
    }

    private fun pulse(targetType: String, targetKey: String) = CueAdHocEffectDto(
        targetType = targetType, targetKey = targetKey, effectType = "Pulse",
        category = categoryFromPropertyName("dimmer"), propertyName = "dimmer",
        beatDivision = 1.0, blendMode = "override", distribution = "normal",
    )

    private fun storedFx(cueId: Int): List<CueAdHocEffectDto> = transaction(state.database) {
        DaoCue.findById(cueId)!!.adHocEffectsInOrder().map { it.toDto() }
    }

    private suspend fun HttpClient.createCue(effect: CueAdHocEffectDto): Int {
        val created = post("/api/rest/projects/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = "Spelt", adHocEffects = listOf(effect)))
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        return created.body<CueDetails>().id
    }

    private suspend fun HttpClient.include(cueId: Int) {
        val response = post("/api/rest/programmer/include") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerIncludeRequest(projectId = projectId.toString(), cueId = cueId))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    }

    private suspend fun HttpClient.update(): ProgrammerUpdateResult {
        val response = post("/api/rest/programmer/update") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerUpdateRequest(projectId = projectId.toString()))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.body<ProgrammerUpdateResponse>().results.single()
    }

    @Test
    fun `a group FX with no element mode, the default filter named and a lower-case Order is not rewritten`() = testApplication {
        mountTestApp(state)
        seedQuad()
        val client = jsonClient()
        val cueId = client.createCue(
            pulse(TargetRef.Group.TYPE, "quad").copy(elementMode = null, elementFilter = "ALL", direction = "forward"),
        )
        val stored = storedFx(cueId)
        assertEquals(null, stored.single().elementMode)

        client.include(cueId)
        assertEquals(0, client.update().fxWritten)
        assertEquals(stored, storedFx(cueId))
    }

    @Test
    fun `a fixture FX naming an element mode and its default step timing is not rewritten`() = testApplication {
        mountTestApp(state)
        seedQuad()
        val client = jsonClient()
        val cueId = client.createCue(
            pulse(TargetRef.Fixture.TYPE, "hex-1").copy(elementMode = "FLAT", stepTiming = false),
        )
        val stored = storedFx(cueId)

        client.include(cueId)
        assertEquals(0, client.update().fxWritten)
        assertEquals(stored, storedFx(cueId))
    }

    @Test
    fun `a wall-clock FX stored on Seconds is not rewritten`() = testApplication {
        mountTestApp(state)
        seedQuad()
        val client = jsonClient()
        val cueId = client.createCue(
            pulse(TargetRef.Fixture.TYPE, "hex-1").copy(effectType = "CandleFlicker", seconds = true),
        )
        val stored = storedFx(cueId)
        assertEquals(true, stored.single().seconds)

        client.include(cueId)
        assertEquals(0, client.update().fxWritten)
        assertEquals(stored, storedFx(cueId))
    }

    @Test
    fun `an FX stored with its library category rather than its property's is not rewritten`() = testApplication {
        mountTestApp(state)
        seedQuad()
        val client = jsonClient()
        // Capture files a Pulse on UV under `controls`; the picker stores Pulse's own `intensity`.
        val cueId = client.createCue(
            pulse(TargetRef.Fixture.TYPE, "hex-1").copy(propertyName = "uv", category = "intensity"),
        )
        val stored = storedFx(cueId)

        client.include(cueId)
        assertEquals(0, client.update().fxWritten)
        assertEquals(stored, storedFx(cueId))
    }

    @Test
    fun `a spread edited after Include is written`() = testApplication {
        mountTestApp(state)
        seedQuad()
        val client = jsonClient()
        val cueId = client.createCue(pulse(TargetRef.Group.TYPE, "quad"))

        client.include(cueId)
        assertEquals(0, client.update().fxWritten)
        val edited = client.put("/api/rest/fx/${fx.getActiveEffects().single().id}") {
            contentType(ContentType.Application.Json)
            setBody(UpdateEffectRequest(spread = 0.5))
        }
        assertEquals(HttpStatusCode.OK, edited.status, edited.bodyAsText())

        assertEquals(1, client.update().fxWritten)
        assertEquals(0.5, storedFx(cueId).single().spread)
    }
}
