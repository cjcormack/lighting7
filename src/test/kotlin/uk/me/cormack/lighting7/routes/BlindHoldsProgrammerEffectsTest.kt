package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.fx.BlindHold
import uk.me.cormack.lighting7.fx.FxEngine
import uk.me.cormack.lighting7.fx.FxInstance
import uk.me.cormack.lighting7.fx.ProvenanceSource
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.LayerSource
import uk.me.cormack.lighting7.models.LookEffectDto
import uk.me.cormack.lighting7.plugins.KeyStackLayerDto
import uk.me.cormack.lighting7.plugins.ProgrammerHandler
import uk.me.cormack.lighting7.plugins.ProgrammerKeyStackInMessage
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Blind holds the programmer's effects (show-cue-editing plan D7, §3.6), from the routes that put an
 * effect in the programmer: a busk (`POST /fx/add` with `programmerOwned`), Include, and a programmer
 * layer. Each lands in the programmer priority band, so [BlindHold] holds it — and `programmer.keyStack`
 * says so, `heldBack` with the reason `BLIND`, off stage — while a cue's own effect keeps painting.
 *
 * What reaches the controller is `BlindHoldTest`'s, on synthetic ticks: the engine's real loops run
 * under these tests, so a channel read here would race them. This suite pins the other half — which
 * priority each route stamps, and that every reader of the rule (the stack, provenance, Record) agrees.
 */
class BlindHoldsProgrammerEffectsTest : RouteIntegrationTest() {

    private val engine get() = state.show.fxEngine

    private fun setBlind(on: Boolean) = engine.programmer.setBlind(on)

    private fun blind() = state.show.programmerStore.blind

    /** The one effect layer on `(hex-1, dimmer)`'s stack, whichever kind it is. */
    private fun effectLayer(): KeyStackLayerDto {
        val answer = ProgrammerHandler.keyStack(
            state, ProgrammerKeyStackInMessage("fixture", "hex-1", "dimmer", requestId = "r"),
        )
        return answer.stacks.single().layers.single { it.effectId != null }
    }

    private fun assertHeldByBlind(instance: FxInstance, what: String) {
        assertTrue(FxEngine.isProgrammerFxPriority(instance.priority), "$what lands in the programmer band")
        assertTrue(BlindHold.holds(blind(), instance), "$what is held while blind")
        val layer = effectLayer()
        assertEquals("PROGRAMMER_EFFECT", layer.kind)
        assertEquals(true, layer.heldBack, "$what: held back")
        assertEquals("BLIND", layer.heldBackReason, "$what: by Blind")
        assertFalse(layer.onStage, "$what: not on stage")
    }

    private suspend fun HttpClient.busk() {
        val response = post("/api/rest/fx/add") {
            contentType(ContentType.Application.Json)
            setBody(AddEffectRequest(effectType = "Pulse", fixtureKey = "hex-1", propertyName = "dimmer", programmerOwned = true))
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `a busked effect is held by Blind and paints again when it goes off`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        client.busk()
        val busk = engine.getActiveEffects().single()

        assertTrue(effectLayer().onStage, "paints before Blind")
        setBlind(true)
        assertHeldByBlind(busk, "a busk")

        setBlind(false)
        val layer = effectLayer()
        assertEquals(false, layer.heldBack)
        assertEquals(null, layer.heldBackReason)
        assertTrue(layer.onStage, "the same instance paints again")
        assertEquals(busk.id, layer.effectId, "held, never stopped")
    }

    @Test
    fun `including a cue under Blind puts its effect in the programmer held`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val cueId = ProgrammerRouteTestSupport.createCue(
            client, projectId, "chase",
            adHoc = listOf(CueAdHocEffectSpec(targetType = "fixture", targetKey = "hex-1", effectType = "SineWave", propertyName = "dimmer")),
        )

        setBlind(true)
        val response: ProgrammerIncludeResponse = client.post("/api/rest/programmer/include") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerIncludeRequest(projectId = projectId.toString(), cueId = cueId))
        }.body()
        assertEquals(1, response.fxSpawned)

        assertHeldByBlind(engine.getActiveEffects().single(), "an included cue's effect")
    }

    @Test
    fun `a programmer layer's effect is held by Blind`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val look = ProgrammerRouteTestSupport.createLookBoundTo(
            client, projectId, "Pulse", rows = mapOf("dimmer" to "200"),
            effects = listOf(
                LookEffectDto(
                    targetType = DEFERRED_TARGET_TYPE, targetKey = "",
                    effectType = "Pulse", category = "dimmer", propertyName = "dimmer",
                    beatDivision = 0.5, blendMode = "OVERRIDE", distribution = "LINEAR",
                ),
            ),
        )
        state.show.programmerLayerStack.add(
            source = LayerSource.look(look.id, UUID.fromString(look.uuid), look.name),
            targets = listOf(CueTargetDto("fixture", "hex-1")),
        )
        val spawned = engine.getActiveEffects().single()
        assertNotNull(spawned.programmerLayerId, "spawned by the layer")

        setBlind(true)
        assertHeldByBlind(spawned, "a programmer layer's effect")
    }

    @Test
    fun `a live cue's own effect keeps painting under Blind`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val cueId = ProgrammerRouteTestSupport.createCue(
            client, projectId, "chase",
            adHoc = listOf(CueAdHocEffectSpec(targetType = "fixture", targetKey = "hex-1", effectType = "SineWave", propertyName = "dimmer")),
        )
        client.post("/api/rest/projects/$projectId/cues/$cueId/apply")
        val cueEffect = engine.getActiveEffects().single()

        setBlind(true)
        assertFalse(FxEngine.isProgrammerFxPriority(cueEffect.priority))
        assertFalse(BlindHold.holds(blind(), cueEffect), "not the programmer's")
        val layer = effectLayer()
        assertEquals("EFFECT", layer.kind)
        assertEquals(false, layer.heldBack)
        assertTrue(layer.onStage, "the cue's effect is what the rig shows")
    }

    @Test
    fun `provenance does not credit an effect Blind holds`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        client.busk()

        fun dimmerSource() = engine.provenance.compute()
            .singleOrNull { it.targetKey == "hex-1" && it.propertyName == "dimmer" }?.source

        assertEquals(ProvenanceSource.EFFECT, dimmerSource(), "the busk is what the rig shows")
        setBlind(true)
        assertEquals(null, dimmerSource(), "held: nothing but the base is on the key")
    }

    @Test
    fun `Record under Blind still captures the programmer's effects`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val stackId = ProgrammerRouteTestSupport.createStack(client, projectId, "stack-a")

        setBlind(true)
        client.busk()

        val response: ProgrammerRecordResponse = client.post("/api/rest/programmer/record") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerRecordRequest(projectId = projectId.toString(), mode = "CREATE", cueStackId = stackId, name = "blind"))
        }.body()

        val recorded = response.cue.adHocEffects.single()
        assertEquals("hex-1", recorded.targetKey)
        assertEquals("dimmer", recorded.propertyName)
        assertTrue(blind(), "Record leaves Blind as it found it")
    }
}
