package uk.me.cormack.lighting7.osc

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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import uk.me.cormack.lighting7.midi.DefaultSurfaceActions
import uk.me.cormack.lighting7.midi.SurfaceDispatch
import uk.me.cormack.lighting7.models.CueAdHocFxDto
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.routes.CreateLookRequest
import uk.me.cormack.lighting7.routes.CreatePatchRequest
import uk.me.cormack.lighting7.routes.CueDetails
import uk.me.cormack.lighting7.routes.CueStackDetails
import uk.me.cormack.lighting7.routes.FixturePatchDto
import uk.me.cormack.lighting7.routes.LookDetails
import uk.me.cormack.lighting7.routes.NewCue
import uk.me.cormack.lighting7.routes.NewCueStack
import uk.me.cormack.lighting7.state.EffectsService
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.entryIdOf
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * OSC messages through the desk's own handler and actions (OSC plan D2, D4, D11): the explicit
 * forms land the same state however many times they arrive — a repeated GO TO keeps the cue's FX
 * where they are and fires no second events — and a stack stop drops the arm.
 */
class OscActionsTest : RouteIntegrationTest() {

    private val qlab = OscSender(1, "QLab", "127.0.0.1")

    private fun handler() = OscHandler(
        database = state.database,
        currentProjectId = { projectId },
        dispatch = SurfaceDispatch(DefaultSurfaceActions(state), state.activeBankState, state.encoderBankState, state.flashStateTracker),
    )

    private fun OscHandler.send(address: String, vararg args: OscArg): OscHandled =
        handle(OscInbound(Instant.now(), qlab, OscPacket.Item.Message(OscMessage(address, args.toList()))))

    private suspend fun HttpClient.stack(name: String): Int =
        post("/api/rest/projects/$projectId/cue-stacks") {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = name))
        }.body<CueStackDetails>().id

    private suspend fun HttpClient.cue(name: String, stackId: Int?, number: String?, fx: Boolean = false): Int {
        val resp = post("/api/rest/projects/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(
                NewCue(
                    name = name, cueStackId = stackId, cueNumber = number,
                    adHocFx = if (fx) listOf(CueAdHocFxDto("fixture", "hex-1", "Wave", "intensity", "dimmer", 1.0, "OVERRIDE", "NORMAL")) else emptyList(),
                ),
            )
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body<CueDetails>().id
    }

    private fun fxOf(cueId: Int) = state.show.fxEngine.getActiveFx().filter { it.cueId == cueId }.map { it.id }.toSet()

    @Test
    fun `a GO TO sent twice keeps the cue's FX and fires its events once`() = testApplication {
        mountTestApp(state)
        val http = jsonClient()
        LocateTestSupport.seedHex(state, projectId, "hex-1", 20)
        state.show.fixtures.patchListChanged()
        val cannon = http.post("/api/rest/projects/$projectId/patches") {
            contentType(ContentType.Application.Json)
            setBody(CreatePatchRequest(universe = 0, fixtureTypeKey = "equinox-twin-shot-mkii", key = "cannon", name = "Cannon", startChannel = 1))
        }.body<FixturePatchDto>().id
        val act = http.stack("Act 1")
        http.cue("Opening", act, "1")
        val storm = http.cue("Storm", act, "5", fx = true)
        http.put("/api/rest/projects/$projectId/cues/$storm/events") {
            contentType(ContentType.Application.Json)
            setBody("""{"events":[{"patchId":$cannon,"trigger":"A","offsetMs":0}]}""")
        }
        val osc = handler()

        runBlocking {
            // Unarmed, a GO into the cue announces its events: one announcement per GO.
            val skipped = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(3_000) { state.effectsService.skipped.first() } }
            val first = osc.send("/lighting7/stack/act-1/cue/5/go")
            assertEquals(OscOutcome.DONE, first.outcome, first.detail)
            assertEquals("GO TO 5 Storm in Act 1", first.detail)
            assertEquals(EffectsService.SkipReason.UNARMED, skipped.await().reason)
            val running = fxOf(storm)
            assertEquals(1, running.size)

            val again = async(start = CoroutineStart.UNDISPATCHED) { withTimeoutOrNull(500) { state.effectsService.skipped.first() } }
            val second = osc.send("/lighting7/stack/act-1/cue/5/go")
            assertEquals(OscOutcome.DONE, second.outcome)
            assertEquals("GO TO 5 Storm in Act 1 · already live", second.detail)
            assertNull(again.await(), "a second GO TO fired the cue's events again")
            assertEquals(running, fxOf(storm), "a second GO TO respawned the cue's FX")
            assertEquals(state.entryIdOf(storm), state.show.cueStackManager.getActiveEntryId(act))
        }
    }

    @Test
    fun `standby arms the entry, and stop drops the desk's arm`() = testApplication {
        mountTestApp(state)
        val http = jsonClient()
        val act = http.stack("Act 1")
        http.cue("One", act, "1")
        val two = http.cue("Two", act, "2")
        val osc = handler()

        assertEquals(OscOutcome.DONE, osc.send("/lighting7/stack/act-1/cue/2/standby").outcome)
        assertEquals(state.entryIdOf(two), state.show.cueStackManager.runState.getStandbyEntryId(act))
        assertEquals(OscOutcome.DONE, osc.send("/lighting7/stack/act-1/go").outcome)
        assertEquals(state.entryIdOf(two), state.show.cueStackManager.getActiveEntryId(act), "GO fires the armed standby")

        state.effectsService.arm(30)
        assertNotNull(state.effectsService.armed.value.armedUntilMs)
        assertEquals(OscOutcome.DONE, osc.send("/lighting7/stack/act-1/stop").outcome)
        assertFalse(state.show.cueStackManager.isStackActive(act))
        assertNull(state.effectsService.armed.value.armedUntilMs, "a stop drops the arm")
        assertEquals("Stop Act 1 · was not running", osc.send("/lighting7/stack/act-1/stop").detail)

        val amount = osc.send("/lighting7/stack/act-1/fx-amount", OscArg.Float32(0.4f))
        assertEquals("FX amount of Act 1 · 40 %", amount.detail)
        assertEquals(0.4, state.show.cueStackManager.fxAmounts.of(act), 1e-6)
    }

    @Test
    fun `a loose cue's on and off land the same state however many times they arrive`() = testApplication {
        mountTestApp(state)
        val http = jsonClient()
        val thunder = http.cue("Thunder", null, null)
        val osc = handler()
        val holds = state.show.cueStackManager.holds

        assertEquals("Cue Thunder on", osc.send("/lighting7/cue/thunder/on").detail)
        assertTrue(holds.looseHeld(thunder))
        assertEquals("Cue Thunder on · already on", osc.send("/lighting7/cue/thunder/on").detail)
        assertTrue(holds.looseHeld(thunder))
        assertEquals("Cue Thunder off", osc.send("/lighting7/cue/thunder/off").detail)
        assertFalse(holds.looseHeld(thunder))
        assertEquals("Cue Thunder off · already off", osc.send("/lighting7/cue/thunder/off").detail)
        // The toggle stays a toggle.
        assertEquals("Press cue Thunder · on", osc.send("/lighting7/cue/thunder/press").detail)
        assertEquals("Press cue Thunder · off", osc.send("/lighting7/cue/thunder/press").detail)
    }

    @Test
    fun `a Look's on and off land the same state however many times they arrive, and Blind says it is staged`() = testApplication {
        mountTestApp(state)
        val http = jsonClient()
        val haze = http.post("/api/rest/projects/$projectId/looks") {
            contentType(ContentType.Application.Json)
            setBody(CreateLookRequest(name = "Haze fill"))
        }.body<LookDetails>().id
        val uuid = transaction(state.database) { DaoLook[haze].uuid }
        val osc = handler()
        fun applied() = state.show.programmerLayerStack.appliedState().any { it.source.uuid == uuid }

        assertEquals(OscOutcome.DONE, osc.send("/lighting7/look/haze-fill/on").outcome)
        assertTrue(applied())
        assertEquals("Look Haze fill on · already on", osc.send("/lighting7/look/haze-fill/on").detail)
        assertEquals(1, state.show.programmerStore.layers.count { it.source.uuid == uuid }, "a second on adds no layer")
        osc.send("/lighting7/look/haze-fill/off")
        assertFalse(applied())
        assertEquals("Look Haze fill off · already off", osc.send("/lighting7/look/haze-fill/off").detail)

        state.show.programmerStore.blind = true
        assertEquals("Look Haze fill on · staged: Blind", osc.send("/lighting7/look/haze-fill/on").detail)
        state.show.programmerStore.blind = false
    }

    @Test
    fun `a message for a record the show lacks is a not-found row and dispatches nothing`() = testApplication {
        mountTestApp(state)
        val handled = handler().send("/lighting7/stack/act-9/go")
        assertEquals(OscOutcome.NOT_FOUND, handled.outcome)
        assertEquals("No stack `act-9` in this show", handled.detail)
        assertFalse(handled.resolved)
    }
}
