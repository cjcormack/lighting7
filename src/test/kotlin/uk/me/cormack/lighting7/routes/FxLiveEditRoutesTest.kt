package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.fx.BlendMode
import uk.me.cormack.lighting7.fx.EffectDto
import uk.me.cormack.lighting7.fx.FxInstance
import uk.me.cormack.lighting7.fx.ResetToTemplateOutcome
import uk.me.cormack.lighting7.models.TemplateEffectDto
import uk.me.cormack.lighting7.plugins.FxChangeType
import uk.me.cormack.lighting7.plugins.FxChangedOutMessage
import uk.me.cormack.lighting7.plugins.FxErrorOutMessage
import uk.me.cormack.lighting7.plugins.InMessage
import uk.me.cormack.lighting7.plugins.UpdateFxInMessage
import uk.me.cormack.lighting7.testsupport.EffectTestSupport
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.awaitOfType
import uk.me.cormack.lighting7.testsupport.createWsClient
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The live FX editor's desk half (fixture-fx-sheets plan W3, W5) and the two checks session 5
 * relies on:
 *
 * - `updateFx` edits in place — id and phase kept — through the same parse as `PUT /fx/{id}`, and
 *   refuses with `fxError` keyed by the effect;
 * - `POST /fx/{id}/reset` puts a template layer's current effect back on the instance it spawned;
 * - an instance edited through `updateFx` **survives a re-cook** of its layer (a patch, a move, an
 *   unrelated add), because `syncEffects` matches it on the key it was spawned with;
 * - what a template-effect `PUT` does to an instance already applied: nothing, until the stack next
 *   recooks — which then respawns it from the edited template, since the template's entry *is* the
 *   spawn key (recorded as the plan's session 1 amendment).
 */
class FxLiveEditRoutesTest : RouteIntegrationTest() {

    private val engine get() = state.show.fxEngine
    private val stack get() = state.show.programmerLayerStack

    private fun templates() = "/api/rest/projects/$projectId/templates"

    private val pulse = TemplateEffectDto(
        effectType = "Pulse",
        category = "dimmer",
        propertyName = "dimmer",
        beatDivision = 0.5,
        blendMode = "OVERRIDE",
        distribution = "LINEAR",
    )

    private suspend fun HttpClient.template(name: String, effect: TemplateEffectDto = pulse): TemplateDto =
        post(templates()) {
            contentType(ContentType.Application.Json)
            setBody(TemplateInput(name = name, effect = effect))
        }.body()

    private suspend fun HttpClient.press(template: TemplateDto, key: String = "hex-1") =
        post("${templates()}/${template.id}/toggle") {
            contentType(ContentType.Application.Json)
            setBody(ToggleTemplateRequest(targets = listOf(TemplateTargetDto("fixture", key))))
        }

    /** The one band instance a template layer spawned on [key]. */
    private fun spawned(key: String = "hex-1"): FxInstance =
        engine.getActiveEffects().single { it.target.targetKey == key && it.programmerLayerEffectKey != null }

    /** What phase is derived from: an edit that kept these kept the phase. */
    private fun FxInstance.clock() = Triple(startedAtMs, startedAtBeat, accumulatedScaledMs)

    // ── W3: updateFx ──────────────────────────────────────────────────────────

    @Test
    fun `updateFx edits in place, keeping the id and the phase, and answers fxChanged`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val before = EffectTestSupport.start(state, "Pulse", "hex-1", "dimmer", programmerBand = true)
        val clock = before.clock()

        createWsClient().webSocket("/api") {
            sendSerialized<InMessage>(
                UpdateFxInMessage(before.id, beatDivision = 2.0, blendMode = "ADDITIVE", parameters = mapOf("min" to "40")),
            )
            val changed = awaitOfType<FxChangedOutMessage>()
            assertEquals(FxChangeType.UPDATED, changed.changeType)
            assertEquals(before.id, changed.effectId)
        }
        val after = assertNotNull(engine.getEffect(before.id))
        assertEquals(2.0, after.timing.beatDivision)
        assertEquals(BlendMode.ADDITIVE, after.blendMode)
        assertEquals("40", after.effect.parameters["min"])
        assertEquals(clock, after.clock(), "the phase is kept: nothing it is derived from moved")
    }

    @Test
    fun `updateFx on an unknown effect answers fxError keyed by the effect`() = testApplication {
        mountTestApp(state)
        createWsClient().webSocket("/api") {
            sendSerialized<InMessage>(UpdateFxInMessage(9_999, beatDivision = 1.0))
            val error = awaitOfType<FxErrorOutMessage>()
            assertEquals(9_999, error.effectId)
            assertEquals(CODE_FX_NOT_FOUND, error.code)
        }
    }

    @Test
    fun `updateFx refuses a blend the strict policy does not know, and changes nothing`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val effect = EffectTestSupport.start(state, "Pulse", "hex-1", "dimmer", programmerBand = true)

        createWsClient().webSocket("/api") {
            sendSerialized<InMessage>(UpdateFxInMessage(effect.id, beatDivision = 4.0, blendMode = "SIDEWAYS"))
            val error = awaitOfType<FxErrorOutMessage>()
            assertEquals(effect.id, error.effectId)
            assertEquals(CODE_FX_UPDATE_REFUSED, error.code)
            assertTrue(error.message.contains("SIDEWAYS"), error.message)
        }
        val live = engine.getEffect(effect.id)!!
        assertSame(effect, live, "a refused update moves nothing — not even the division beside the bad blend")
        assertEquals(0.5, live.timing.beatDivision)
    }

    @Test
    fun `PUT and updateFx are one parse — the same refusal, and the same 404`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val effect = EffectTestSupport.start(state, "Pulse", "hex-1", "dimmer")
        val client = jsonClient()

        val refused = client.put("/api/rest/fx/${effect.id}") {
            contentType(ContentType.Application.Json)
            setBody(UpdateEffectRequest(blendMode = "SIDEWAYS"))
        }
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        assertEquals(CODE_FX_UPDATE_REFUSED, refused.body<ErrorResponse>().code)

        val missing = client.put("/api/rest/fx/9999") {
            contentType(ContentType.Application.Json)
            setBody(UpdateEffectRequest(beatDivision = 1.0))
        }
        assertEquals(HttpStatusCode.NotFound, missing.status)

        val ok = client.put("/api/rest/fx/${effect.id}") {
            contentType(ContentType.Application.Json)
            setBody(UpdateEffectRequest(beatDivision = 1.0))
        }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals(1.0, ok.body<EffectDto>().beatDivision)
    }

    @Test
    fun `the frame carries exactly the request's fields`() {
        val request = UpdateEffectRequest.serializer().descriptor
        val frame = UpdateFxInMessage.serializer().descriptor
        val requestFields = (0 until request.elementsCount).map { request.getElementName(it) }.toSet()
        val frameFields = (0 until frame.elementsCount).map { frame.getElementName(it) }.toSet()
        assertEquals(requestFields + "effectId", frameFields, "a field added to one door must be added to the other")
    }

    @Test
    fun `a type swap takes the new type's clock with it`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val effect = EffectTestSupport.start(state, "Pulse", "hex-1", "dimmer", programmerBand = true)
        val wallClock = state.show.fxRegistry.getRegistration("CandleFlicker")!!.timingSource

        val outcome = applyEffectUpdate(state, effect.id, UpdateEffectRequest(effectType = "CandleFlicker"))
        val updated = (outcome as EffectUpdateOutcome.Updated).instance
        assertEquals(wallClock, updated.timingSource)
        assertEquals(effect.id, updated.id)
    }

    // ── W5: reset to template ──────────────────────────────────────────────────

    @Test
    fun `reset puts the template's effect back, keeping the id and the phase`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        val template = client.template("pulse")
        client.press(template)
        val instance = spawned()
        val clock = instance.clock()

        applyEffectUpdate(
            state, instance.id,
            UpdateEffectRequest(beatDivision = 4.0, blendMode = "ADDITIVE", parameters = mapOf("min" to "90")),
        )
        assertEquals(4.0, engine.getEffect(instance.id)!!.timing.beatDivision)

        val reset = client.post("/api/rest/fx/${instance.id}/reset")
        assertEquals(HttpStatusCode.OK, reset.status)
        val dto = reset.body<EffectDto>()
        assertEquals(instance.id, dto.id)
        val live = engine.getEffect(instance.id)!!
        assertEquals(0.5, live.timing.beatDivision)
        assertEquals(BlendMode.OVERRIDE, live.blendMode)
        assertNotEquals("90", live.effect.parameters["min"], "the edited parameter is gone")
        assertEquals(clock, live.clock(), "the phase is kept")
    }

    @Test
    fun `reset is refused for an instance no template spawned, and 404s an unknown one`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val manual = EffectTestSupport.start(state, "Pulse", "hex-1", "dimmer", programmerBand = true)
        val client = jsonClient()

        val refused = client.post("/api/rest/fx/${manual.id}/reset")
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals(ResetToTemplateOutcome.NOT_FROM_TEMPLATE, refused.body<ErrorResponse>().code)

        assertEquals(HttpStatusCode.NotFound, client.post("/api/rest/fx/9999/reset").status)
    }

    // ── The two checks ─────────────────────────────────────────────────────────

    @Test
    fun `an instance edited through updateFx survives a patch, a move and an unrelated add`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", 20)
        val client = jsonClient()
        val template = client.template("pulse")
        client.press(template)
        val instance = spawned()
        applyEffectUpdate(state, instance.id, UpdateEffectRequest(beatDivision = 4.0, parameters = mapOf("min" to "90")))
        val layerId = instance.programmerLayerId!!

        fun assertEdited(after: String) {
            val live = engine.getEffect(instance.id)
            assertNotNull(live, "respawned after $after")
            assertEquals(4.0, live.timing.beatDivision, "the edit was lost after $after")
            assertEquals("90", live.effect.parameters["min"], "the edit was lost after $after")
        }

        stack.patch(layerId, amount = 0.5)
        assertEdited("a patch")

        client.press(client.template("other"), key = "hex-2")
        assertEdited("an unrelated add")

        stack.move(layerId, 1)
        assertEdited("a move")
    }

    @Test
    fun `a template-effect PUT leaves an applied instance alone until the stack next recooks`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        val template = client.template("pulse")
        client.press(template)
        val instance = spawned()

        val edited = client.put("${templates()}/${template.id}") {
            contentType(ContentType.Application.Json)
            setBody(TemplateInput(effect = pulse.copy(beatDivision = 2.0)))
        }
        assertEquals(HttpStatusCode.OK, edited.status)
        assertSame(instance, engine.getEffect(instance.id), "the PUT itself recooks values only")
        assertEquals(0.5, instance.timing.beatDivision)

        // The next recook of the stack for any reason reads the edited template, whose effect entry
        // no longer matches the instance's spawn key: the instance is retracted and respawned.
        stack.patch(instance.programmerLayerId!!, amount = 0.8)
        val respawned = spawned()
        assertNotEquals(instance.id, respawned.id)
        assertEquals(2.0, respawned.timing.beatDivision)
    }

    @Test
    fun `reset re-keys the instance, so a recook after a template edit keeps it`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        val template = client.template("pulse")
        client.press(template)
        val instance = spawned()
        client.put("${templates()}/${template.id}") {
            contentType(ContentType.Application.Json)
            setBody(TemplateInput(effect = pulse.copy(beatDivision = 2.0)))
        }

        assertEquals(HttpStatusCode.OK, client.post("/api/rest/fx/${instance.id}/reset").status)
        assertEquals(2.0, engine.getEffect(instance.id)!!.timing.beatDivision, "reset reads the template as it is now")

        stack.patch(instance.programmerLayerId!!, amount = 0.8)
        assertEquals(instance.id, spawned().id, "the reset instance is the template's current effect, so it is kept")
    }
}
