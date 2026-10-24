package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.dmx.EasingCurve
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.models.CueAdHocFxDto
import uk.me.cormack.lighting7.models.CueLayerDto
import uk.me.cormack.lighting7.models.LookFxDto
import uk.me.cormack.lighting7.models.LookRowDto
import uk.me.cormack.lighting7.models.CueAttributeAssignmentDto
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueAttributeAssignment
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueStackEntry
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.plugins.CueHoldsStateOutMessage
import uk.me.cormack.lighting7.state.EffectsService
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * *Edited since GO* and *Update stage* (show-cue-editing plan §3.5, session 7): a write to a held
 * cue's rows, layers or effects marks it, its next first hold clears the mark, and Update stage
 * takes the stage to the stored cue over the cue's own fade — rows ramped, effects edited in place,
 * stopped or started — firing no events.
 *
 * Cue Q is in stacks A and B; hex-1's dimmer is its one row. Hex dimmers sit at their start channel.
 */
class CueUpdateStageRouteTest : RouteIntegrationTest() {

    private val hex1Dimmer = 1
    private val base get() = "/api/rest/projects/$projectId"
    private val manager get() = state.show.cueStackManager
    private val holds get() = manager.holds

    private data class Rig(val a: Int, val b: Int, val q: Int, val other: Int, val aQ: Int, val aOther: Int, val bQ: Int)

    private fun seed(fadeMs: Long? = null, curve: EasingCurve = EasingCurve.LINEAR): Rig {
        LocateTestSupport.seedHex(state, projectId, "hex-1", hex1Dimmer)
        LocateTestSupport.seedHex(state, projectId, "hex-2", 21)
        return transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            fun stack(name: String, sort: Int) = DaoCueStack.new {
                this.project = project
                this.name = name; loop = false
                type = uk.me.cormack.lighting7.models.CueStackType.STACK.name; sortOrder = sort
            }
            fun cue(name: String, level: Int) = DaoCue.new {
                this.project = project
                this.name = name
                fadeDuration = fadeMs?.let { Duration.ofMillis(it) }
                fadeCurve = curve.name
            }.also { cue ->
                DaoCueAttributeAssignment.new {
                    this.cue = cue
                    targetType = "fixture"; targetKey = "hex-1"
                    attribute = "dimmer"; value = "$level"; sortOrder = 0
                }
            }
            fun entry(stack: DaoCueStack, cue: DaoCue, ordinal: Double) =
                DaoCueStackEntry.new { this.stack = stack; this.cue = cue; this.ordinal = ordinal; cueNumber = "$ordinal" }.id.value
            val a = stack("A", 0)
            val b = stack("B", 1)
            val q = cue("Q", 100)
            val other = cue("Other", 30)
            Rig(a.id.value, b.id.value, q.id.value, other.id.value, entry(a, q, 1.0), entry(a, other, 2.0), entry(b, q, 1.0))
        }
    }

    private val controller get() = state.show.fixtures.controllerOrNull(Universe(0, 0)) as MockDmxController

    /** Whether `cueHolds.state` marks [cueId] edited, as the socket sends it. */
    private fun editedOnWire(cueId: Int): Boolean =
        CueHoldsStateOutMessage.of(state.cueHoldsFrame.value).cues.firstOrNull { it.cueId == cueId }?.edited == true

    private suspend fun HttpClient.patchCue(cueId: Int, body: String): HttpResponse = patch("$base/cues/$cueId") {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpClient.setDimmer(cueId: Int, level: Int) =
        assertEquals(HttpStatusCode.OK, patchCue(cueId, """{"attributeAssignments":[{"targetType":"fixture","targetKey":"hex-1","attribute":"dimmer","value":"$level"}]}""").status)

    private suspend fun HttpClient.updateStage(cueId: Int, dryRun: Boolean = false): HttpResponse = post("$base/cues/$cueId/update-stage") {
        contentType(ContentType.Application.Json)
        setBody(UpdateStageRequest(dryRun = dryRun))
    }

    @Test
    fun `an own-row edit to a held cue marks it, and a scenery, name or unheld edit does not`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val rig = seed()

        client.setDimmer(rig.other, 40)
        assertFalse(holds.isEdited(rig.other), "a cue nothing holds is not marked")

        manager.activateEntry(state, rig.a, rig.aQ)
        assertEquals(HttpStatusCode.OK, client.patchCue(rig.q, """{"name":"Q renamed","fadeDurationMs":1500}""").status)
        assertFalse(holds.isEdited(rig.q), "a name or fade edit changes nothing on stage")
        val scenery = client.put("$base/cues/${rig.q}/scenery") {
            contentType(ContentType.Application.Json)
            setBody("""{"scenery":[]}""")
        }
        assertEquals(HttpStatusCode.OK, scenery.status, scenery.bodyAsText())
        assertFalse(holds.isEdited(rig.q), "scenery is drawn from the stored rows at once")
        // Re-sending the rows it already has changes nothing either.
        client.setDimmer(rig.q, 100)
        assertFalse(holds.isEdited(rig.q))

        client.setDimmer(rig.q, 200)
        assertTrue(holds.isEdited(rig.q))
        assertTrue(editedOnWire(rig.q), "cueHolds.state carries edited")
        assertEquals(100, controller.getValue(hex1Dimmer).toInt(), "the edit sends nothing to the stage")
    }

    @Test
    fun `its first hold clears the mark, and a GO into it another hold keeps live does not`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val rig = seed()
        manager.activateEntry(state, rig.a, rig.aQ)
        client.setDimmer(rig.q, 200)
        assertTrue(holds.isEdited(rig.q))

        manager.activateEntry(state, rig.b, rig.bQ)
        assertTrue(holds.isEdited(rig.q), "B's GO into Q, already live through A, lands nothing")
        assertEquals(100, controller.getValue(hex1Dimmer).toInt())

        manager.deactivateStack(rig.a, state)
        manager.deactivateStack(rig.b, state)
        assertFalse(editedOnWire(rig.q), "a cue nothing holds is not on the wire")
        manager.activateEntry(state, rig.a, rig.aQ)
        assertFalse(holds.isEdited(rig.q), "coming on lands the edit")
        assertEquals(200, controller.getValue(hex1Dimmer).toInt())
    }

    @Test
    fun `update stage fades the rows over the cue's own fade and curve, and clears the mark`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val rig = seed(fadeMs = 2_000, curve = EasingCurve.SINE_IN)
        manager.activateEntry(state, rig.a, rig.aQ)
        client.setDimmer(rig.q, 200)

        val dry = client.updateStage(rig.q, dryRun = true)
        assertEquals(HttpStatusCode.OK, dry.status, dry.bodyAsText())
        assertEquals(UpdateStageResponse(rows = 1, fx = UpdateStageFx(0, 0, 0), fadeMs = 2_000, curve = "SINE_IN"), dry.body())
        assertEquals(100, controller.getValue(hex1Dimmer).toInt(), "a dry run sends nothing")
        assertTrue(holds.isEdited(rig.q))

        val sent = client.updateStage(rig.q)
        assertEquals(dry.body<UpdateStageResponse>(), sent.body<UpdateStageResponse>())
        val change = controller.changesTo(hex1Dimmer).last()
        assertEquals(200, change.newValue.toInt())
        assertEquals(2_000L, change.fadeMs)
        assertEquals(EasingCurve.SINE_IN, change.curve)
        assertFalse(holds.isEdited(rig.q))
    }

    @Test
    fun `a snap cue snaps, and a released row ramps to what is under it`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val rig = seed()
        manager.activateEntry(state, rig.a, rig.aQ)
        client.setDimmer(rig.q, 200)
        client.updateStage(rig.q)
        val snap = controller.changesTo(hex1Dimmer).last()
        assertEquals(200, snap.newValue.toInt())
        assertEquals(0L, snap.fadeMs)

        client.patchCue(rig.q, """{"fadeDurationMs":800}""")
        client.patchCue(rig.q, """{"attributeAssignments":[]}""")
        assertEquals(UpdateStageResponse(1, UpdateStageFx(0, 0, 0), 800, "LINEAR"), client.updateStage(rig.q).body())
        val released = controller.changesTo(hex1Dimmer).last()
        assertEquals(0, released.newValue.toInt(), "nothing else holds hex-1's dimmer")
        assertEquals(800L, released.fadeMs)
    }

    @Test
    fun `an edited effect keeps its id and phase, a removed one stops and a new one starts`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val rig = seed()
        fun effect(fixture: String, type: String, attribute: String, division: Double = 0.5, size: Double = 1.0) = CueAdHocFxDto(
            targetType = "fixture", targetKey = fixture, fxType = type, family = "intensity",
            attribute = attribute, cycle = division, blendMode = "OVERRIDE", order = "NORMAL", amount = size,
        )
        val sine = effect("hex-1", "SineWave", "dimmer")
        val pulse = effect("hex-2", "Pulse", "dimmer")
        client.patch("$base/cues/${rig.q}") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("adHocFx" to listOf(sine, pulse)))
        }
        manager.activateEntry(state, rig.a, rig.aQ)
        val engine = state.show.fxEngine
        fun running(fixture: String) = engine.cueFx(rig.q).firstOrNull { it.target.targetKey == fixture && !it.leaving }
        val sineBefore = assertNotNull(running("hex-1"))
        val sineId = sineBefore.id
        val sineStarted = sineBefore.startedAtMs
        assertNotNull(running("hex-2"))

        // Sine slowed and halved, Pulse gone, a UV pulse on hex-1 new.
        client.patch("$base/cues/${rig.q}") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("adHocFx" to listOf(sine.copy(cycle = 1.0, amount = 0.5), effect("hex-1", "Pulse", "uv"))))
        }
        assertTrue(holds.isEdited(rig.q))
        val dry = client.updateStage(rig.q, dryRun = true).body<UpdateStageResponse>()
        assertEquals(UpdateStageFx(edited = 1, stopped = 1, started = 1), dry.fx)
        assertNotNull(running("hex-2"), "a dry run stops nothing")
        assertEquals(1.0, running("hex-1")!!.dynamics.amount)

        assertEquals(dry, client.updateStage(rig.q).body())
        val sineAfter = assertNotNull(engine.cueFx(rig.q).firstOrNull { it.target.targetKey == "hex-1" && it.target.attribute == "dimmer" })
        assertEquals(sineId, sineAfter.id, "edited in place")
        assertEquals(sineStarted, sineAfter.startedAtMs, "phase kept")
        assertEquals(1.0, sineAfter.timing.cycle)
        assertEquals(0.5, sineAfter.dynamics.amount)
        assertNull(running("hex-2"), "the removed effect stopped (a snap cue's, at once)")
        val uv = assertNotNull(engine.cueFx(rig.q).firstOrNull { it.target.attribute == "uv" }, "the new effect started")
        assertEquals(sineAfter.priority, uv.priority, "at the cue's priority")
        assertEquals(rig.a, uv.cueStackId)

        // Nothing left to bring in line.
        assertEquals(UpdateStageResponse(0, UpdateStageFx(0, 0, 0), 0, "LINEAR"), client.updateStage(rig.q, dryRun = true).body())
    }

    @Test
    fun `changed layers bring their Looks' effects in line, head by head`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val rig = seed()
        // A Look per head, each a Pulse on its head: a Look lands whole.
        val looks = listOf("hex-1", "hex-2").associateWith { head ->
            val pulse = LookFxDto(
                targetType = "fixture", targetKey = head, fxType = "Pulse", family = "intensity",
                attribute = "dimmer", cycle = 0.5, blendMode = "OVERRIDE", order = "NORMAL",
            )
            client.post("$base/looks") {
                contentType(ContentType.Application.Json)
                setBody(CreateLookRequest(name = "Throb $head", fx = listOf(pulse)))
            }.body<LookDetails>().id
        }
        suspend fun layerOn(vararg fixtures: String) = assertEquals(
            HttpStatusCode.OK,
            client.patch("$base/cues/${rig.q}") {
                contentType(ContentType.Application.Json)
                setBody(mapOf("layers" to fixtures.mapIndexed { i, it -> CueLayerDto(lookId = looks.getValue(it), sortOrder = i) }))
            }.status,
        )
        layerOn("hex-1")
        manager.activateEntry(state, rig.a, rig.aQ)
        fun pulsing() = state.show.fxEngine.cueFx(rig.q).map { it.target.targetKey }.sorted()
        val hex1 = state.show.fxEngine.cueFx(rig.q).single().id
        assertEquals(listOf("hex-1"), pulsing())

        layerOn("hex-1", "hex-2")
        assertEquals(UpdateStageFx(edited = 0, stopped = 0, started = 1), client.updateStage(rig.q).body<UpdateStageResponse>().fx)
        assertEquals(listOf("hex-1", "hex-2"), pulsing())
        assertEquals(hex1, state.show.fxEngine.cueFx(rig.q).first { it.target.targetKey == "hex-1" }.id, "the head it kept runs on")

        layerOn("hex-2")
        assertEquals(UpdateStageFx(edited = 0, stopped = 1, started = 0), client.updateStage(rig.q).body<UpdateStageResponse>().fx)
        assertEquals(listOf("hex-2"), pulsing())
    }

    private fun dimmerEffect(fixture: String, type: String = "SineWave", division: Double = 0.5) = CueAdHocFxDto(
        targetType = "fixture", targetKey = fixture, fxType = type, family = "intensity",
        attribute = "dimmer", cycle = division, blendMode = "OVERRIDE", order = "NORMAL",
    )

    private suspend fun HttpClient.setFx(cueId: Int, vararg effects: CueAdHocFxDto) = assertEquals(
        HttpStatusCode.OK,
        patch("$base/cues/$cueId") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("adHocFx" to effects.toList()))
        }.status,
    )

    @Test
    fun `an effect the cue started that has since gone stays off`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val rig = seed()
        client.setFx(rig.q, dimmerEffect("hex-2"))
        manager.activateEntry(state, rig.a, rig.aQ)
        val engine = state.show.fxEngine
        // An N-shot run out, or a stomp from another cue, removes the instance.
        engine.removeFx(engine.cueFx(rig.q).single().id)

        client.setDimmer(rig.q, 200)
        assertEquals(UpdateStageResponse(1, UpdateStageFx(0, 0, 0), 0, "LINEAR"), client.updateStage(rig.q).body())
        assertTrue(engine.cueFx(rig.q).isEmpty())

        // One it gains starts, though.
        client.setFx(rig.q, dimmerEffect("hex-2"), dimmerEffect("hex-1", "Pulse"))
        assertEquals(UpdateStageFx(0, 0, 1), client.updateStage(rig.q).body<UpdateStageResponse>().fx)
        assertEquals(listOf("hex-1"), engine.cueFx(rig.q).map { it.target.targetKey })
    }

    @Test
    fun `the mark follows what Update stage sends, and a Record republish leaves it`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val rig = seed()
        val timed = dimmerEffect("hex-2").copy(delayMs = 500)
        client.setFx(rig.q, timed)
        manager.activateEntry(state, rig.a, rig.aQ)

        val timing = """{"attributeAssignments":[{"targetType":"fixture","targetKey":"hex-1","attribute":"dimmer","value":"100","fadeDurationMs":900,"moveInDark":true}]}"""
        assertEquals(HttpStatusCode.OK, client.patchCue(rig.q, timing).status)
        assertFalse(holds.isEdited(rig.q), "a row's own fade and move-in-dark are not sent")
        client.setFx(rig.q, timed.copy(delayMs = 1_500, cycle = 1.0))
        assertFalse(holds.isEdited(rig.q), "a timed effect is left to the next GO")

        client.setDimmer(rig.q, 200)
        assertTrue(holds.isEdited(rig.q))
        assertTrue(republishCueIfLive(state, rig.q))
        assertTrue(holds.isEdited(rig.q), "a republish does not bring its effects in line")
    }

    @Test
    fun `an effect moved off Seconds onto master 1 matches its stored effect once updated`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val rig = seed()
        val onSeconds = dimmerEffect("hex-1").copy(seconds = true, cycle = 2.0)
        client.setFx(rig.q, onSeconds)
        manager.activateEntry(state, rig.a, rig.aQ)
        val id = state.show.fxEngine.cueFx(rig.q).single().id

        client.setFx(rig.q, onSeconds.copy(seconds = false, cycle = 1.0))
        assertEquals(UpdateStageFx(1, 0, 0), client.updateStage(rig.q).body<UpdateStageResponse>().fx)
        val updated = state.show.fxEngine.cueFx(rig.q).single()
        assertEquals(id, updated.id)
        assertFalse(updated.seconds)
        assertEquals(
            UpdateStageFx(0, 0, 0),
            client.updateStage(rig.q, dryRun = true).body<UpdateStageResponse>().fx,
            "master 1 by its uuid is the stored null",
        )
    }

    @Test
    fun `a Look edit that moves its effects marks the live cue layering it, and a values edit does not`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val rig = seed()
        val pulse = LookFxDto(
            targetType = "fixture", targetKey = "hex-2", fxType = "Pulse", family = "intensity",
            attribute = "dimmer", cycle = 0.5, blendMode = "OVERRIDE", order = "NORMAL",
        )
        val look = client.post("$base/looks") {
            contentType(ContentType.Application.Json)
            setBody(CreateLookRequest(name = "Throb", fx = listOf(pulse)))
        }.body<LookDetails>().id
        client.patch("$base/cues/${rig.q}") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("layers" to listOf(CueLayerDto(lookId = look))))
        }
        manager.activateEntry(state, rig.a, rig.aQ)
        suspend fun putLook(body: Any) {
            val response = client.put("$base/looks/$look") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        }

        putLook(mapOf("rows" to listOf(LookRowDto(targetType = "fixture", targetKey = "hex-2", attribute = "uv", value = "90"))))
        assertFalse(holds.isEdited(rig.q), "its values republish")
        putLook(mapOf("fx" to listOf(pulse)))
        assertFalse(holds.isEdited(rig.q), "the same effect again")
        putLook(mapOf("fx" to listOf(pulse.copy(cycle = 1.0))))
        assertTrue(holds.isEdited(rig.q))
        assertEquals(UpdateStageFx(1, 0, 0), client.updateStage(rig.q).body<UpdateStageResponse>().fx)
        assertEquals(1.0, state.show.fxEngine.cueFx(rig.q).single().timing.cycle)
    }

    @Test
    fun `a Look effect edit marks a live cue with no rows of its own`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val rig = seed()
        val pulse = LookFxDto(
            targetType = "fixture", targetKey = "hex-2", fxType = "Pulse", family = "intensity",
            attribute = "dimmer", cycle = 0.5, blendMode = "OVERRIDE", order = "NORMAL",
        )
        val look = client.post("$base/looks") {
            contentType(ContentType.Application.Json)
            setBody(CreateLookRequest(name = "Chase", fx = listOf(pulse)))
        }.body<LookDetails>().id
        assertEquals(HttpStatusCode.OK, client.patchCue(rig.q, """{"attributeAssignments":[]}""").status)
        client.patch("$base/cues/${rig.q}") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("layers" to listOf(CueLayerDto(lookId = look))))
        }
        manager.activateEntry(state, rig.a, rig.aQ)
        assertFalse(state.show.fxEngine.cueLayer.hasAssignments(rig.q), "it publishes no rows")

        val response = client.put("$base/looks/$look") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("fx" to listOf(pulse.copy(cycle = 1.0))))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertTrue(holds.isEdited(rig.q))
    }

    @Test
    fun `update stage fires no events`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val rig = seed()
        val cannon = client.post("$base/patches") {
            contentType(ContentType.Application.Json)
            setBody(CreatePatchRequest(universe = 0, fixtureTypeKey = "equinox-twin-shot-mkii", key = "cannon", name = "Cannon", startChannel = 40))
        }.body<FixturePatchDto>().id
        client.put("$base/cues/${rig.q}/events") {
            contentType(ContentType.Application.Json)
            setBody("""{"events":[{"patchId":$cannon,"trigger":"A"}]}""")
        }
        // Unarmed, a cue's events announce one skip each time they would fire.
        val skips = mutableListOf<EffectsService.Skipped>()
        runBlocking {
            val collecting = launch(start = CoroutineStart.UNDISPATCHED) {
                state.effectsService.skipped.collect { if (it.cueId == rig.q) skips += it }
            }
            manager.activateEntry(state, rig.a, rig.aQ)
            delay(150)
            assertEquals(1, skips.size, "its GO fired them")
            client.setDimmer(rig.q, 200)
            assertEquals(HttpStatusCode.OK, client.updateStage(rig.q).status)
            delay(150)
            assertEquals(1, skips.size, "Update stage fired none")
            collecting.cancelAndJoin()
        }
    }

    @Test
    fun `a cue nothing holds is CUE_NOT_LIVE, and another project's cue is not found`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val rig = seed()
        val refused = client.updateStage(rig.q)
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals(CODE_CUE_NOT_LIVE, refused.body<ErrorResponse>().code)
        assertEquals(HttpStatusCode.NotFound, client.updateStage(999_999).status)
        // A loose hold is a hold.
        client.post("$base/cues/${rig.q}/apply")
        assertEquals(HttpStatusCode.OK, client.updateStage(rig.q, dryRun = true).status)
        assertEquals(HttpStatusCode.OK, client.get("$base/cues/${rig.q}").status)
    }
}
