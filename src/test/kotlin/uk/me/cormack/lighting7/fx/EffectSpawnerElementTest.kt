package uk.me.cormack.lighting7.fx

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.LookEffectSpec
import uk.me.cormack.lighting7.models.TemplateEffectDto
import uk.me.cormack.lighting7.routes.TemplateDto
import uk.me.cormack.lighting7.routes.TemplateInput
import uk.me.cormack.lighting7.routes.TemplateTargetDto
import uk.me.cormack.lighting7.routes.ToggleTemplateRequest
import uk.me.cormack.lighting7.routes.ToggleTemplateResponse
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * An effect template pressed on one **cell** runs on that cell — the effect door of the element
 * arm. `EffectSpawner.createFixtureTargetForCue` resolved its fixture through the register only,
 * and the engine's fixture expansion did the same, so an effect aimed at an element key found
 * nothing to paint.
 */
class EffectSpawnerElementTest : RouteIntegrationTest() {

    private fun cell(i: Int) = "bar-1.pixel-$i"

    /** First DMX channel of pixel [i] on a 48-channel bar patched at 1: red, green, blue, white. */
    private fun firstChannelOf(i: Int) = 1 + i * 4

    private fun seedBar() {
        LocateTestSupport.seedFixture(state, projectId, "led-lightbar-12-pixel-48ch", "bar-1", 1)
    }

    private suspend fun HttpClient.createEffectTemplate(name: String): Int {
        val resp = post("/api/rest/projects/$projectId/templates") {
            contentType(ContentType.Application.Json)
            setBody(
                TemplateInput(
                    name = name,
                    effect = TemplateEffectDto(
                        effectType = "RainbowCycle", category = "colour", propertyName = "rgbColour",
                        beatDivision = 1.0, blendMode = "OVERRIDE", distribution = "LINEAR",
                    ),
                ),
            )
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body<TemplateDto>().id
    }

    private suspend fun HttpClient.toggle(templateId: Int, vararg keys: String): ToggleTemplateResponse {
        val resp = post("/api/rest/projects/$projectId/templates/$templateId/toggle") {
            contentType(ContentType.Application.Json)
            setBody(ToggleTemplateRequest(targets = keys.map { TemplateTargetDto("fixture", it) }))
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        return resp.body()
    }

    private fun tick(n: Long): MasterClock.ClockTick {
        val ticksPerBeat = MasterClock.TICKS_PER_BEAT.toLong()
        val tickInBeat = (n % ticksPerBeat).toInt()
        return MasterClock.ClockTick(
            tickNumber = n,
            beatNumber = n / ticksPerBeat,
            tickInBeat = tickInBeat,
            phase = tickInBeat.toDouble() / MasterClock.TICKS_PER_BEAT,
            timestampMs = 1_000_000L + n * 20L,
        )
    }

    private fun rgbOf(i: Int): List<UByte> {
        val controller = state.show.fixtures.controller(Universe(0, 0)) as MockDmxController
        return (0 until 3).map { controller.getValue(firstChannelOf(i) + it) }
    }

    @Test
    fun `an effect template pressed on one cell runs on that cell`() = testApplication {
        mountTestApp(state)
        seedBar()
        val client = jsonClient()
        val rainbow = client.createEffectTemplate("rainbow")

        val on = client.toggle(rainbow, cell(2))
        assertEquals("applied", on.action)
        assertEquals(1, on.effectCount, "one instance, on the cell")
        val instance = state.show.fxEngine.getActiveEffects().single { it.programmerLayerId != null }
        assertEquals(cell(2), instance.target.targetKey)

        state.show.fxEngine.processBeatTick(tick(3))
        assertTrue(rgbOf(2).any { it > 0u }, "the pressed cell is painted: ${rgbOf(2)}")
        assertTrue(rgbOf(3).all { it == 0.toUByte() }, "its neighbour is not: ${rgbOf(3)}")
        assertTrue(rgbOf(1).all { it == 0.toUByte() }, "nor the one before: ${rgbOf(1)}")

        assertEquals("removed", client.toggle(rainbow, cell(2)).action)
        assertTrue(state.show.fxEngine.getActiveEffects().none { it.programmerLayerId != null })
    }

    @Test
    fun `a property named by the spec resolves against the cell's own catalogue`() = testApplication {
        mountTestApp(state)
        seedBar()
        // `white` is not one of the names the factory dispatches on, so it reaches the reflective
        // fallback — which used to see no fixture at all for an element key and answer a
        // SettingTarget that discards every slider frame.
        val spec = LookEffectSpec(
            effectType = "Pulse", category = "dimmer", propertyName = "white",
            beatDivision = 0.5, blendMode = "OVERRIDE", distribution = "LINEAR",
        )
        val target = EffectSpawner.resolveTargetForCue(state, CueTargetDto("fixture", cell(2)), spec)
        assertIs<SliderTarget>(target)
        assertEquals("white", target.propertyName)
        assertEquals(cell(2), target.targetKey)
    }
}
