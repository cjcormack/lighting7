package uk.me.cormack.lighting7.fx

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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.CueStackType
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueAdHocFxEntity
import uk.me.cormack.lighting7.models.DaoCueAttributeAssignment
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueStackEntry
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.routes.CreatePatchRequest
import uk.me.cormack.lighting7.routes.FixturePatchDto
import uk.me.cormack.lighting7.routes.previewCueLook
import uk.me.cormack.lighting7.state.EffectsService
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The hold registry (cue model plan P3, §3.4, session 2): a cue live in two places is one
 * contribution, held until the last (D3); a loose press composes above every stack (D10); a GO
 * between two entries of one cue moves nothing (D7); events fire on the first hold only (D15).
 *
 * The cues share entries the way session 4 will let an operator share them, inserted directly —
 * the routes still keep one entry per cue (P4) — so the runtime is proved before anything can ask it:
 *
 * - stack A: a1 → C, a2 → D, a3 → C (C twice in one stack)
 * - stack B: b1 → C, b2 → E           (C in two stacks)
 *
 * C and D both set hex-1's colour, LTP, so priority decides it: red 100 and red 200. E sets hex-2's.
 * B was made after A, so its band is the higher: where C and D meet on hex-1, C held at B's wins.
 */
class CueHoldsTest : RouteIntegrationTest() {

    private data class Rig(
        val stackA: Int, val stackB: Int,
        val c: Int, val d: Int, val e: Int,
        val a1: Int, val a2: Int, val a3: Int,
        val b1: Int, val b2: Int,
    )

    private val hex1 = 1
    private val hex2 = 21

    /** hex-1's red channel: a hex's colour starts one past its dimmer. */
    private val hex1Red = hex1 + 1

    private val manager get() = state.show.cueStackManager
    private val holds get() = manager.holds

    private fun seed(eFadeMs: Long? = null, cFadeMs: Long? = null, a3Follows: Boolean = false): Rig {
        LocateTestSupport.seedHex(state, projectId, "hex-1", hex1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", hex2)
        return transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            fun stack(name: String, sort: Int) = DaoCueStack.new {
                this.project = project
                this.name = name; loop = false
                type = CueStackType.STACK.name; sortOrder = sort
            }
            fun cue(name: String, fixture: String, level: Int, fadeMs: Long?) = DaoCue.new {
                this.project = project
                this.name = name
                fadeDuration = fadeMs?.let { Duration.ofMillis(it) }
            }.also { cue ->
                DaoCueAttributeAssignment.new {
                    this.cue = cue
                    targetType = "fixture"; targetKey = fixture
                    attribute = "rgbColour"; value = "#%02x0000".format(level); sortOrder = 0
                }
            }
            fun entry(stack: DaoCueStack, cue: DaoCue, ordinal: Double, number: String, follows: Boolean = false) =
                DaoCueStackEntry.new {
                    this.stack = stack; this.cue = cue; this.ordinal = ordinal; cueNumber = number
                    if (follows) {
                        autoAdvance = true
                        autoAdvanceDelay = Duration.ofMinutes(10)
                    }
                }.id.value

            val a = stack("A", 0)
            val b = stack("B", 1)
            val c = cue("C", "hex-1", 100, cFadeMs).also { cue ->
                // And hex-1's dimmer, which only C sets: HTP, so a fade's weight shows in its level.
                DaoCueAttributeAssignment.new {
                    this.cue = cue
                    targetType = "fixture"; targetKey = "hex-1"
                    attribute = "dimmer"; value = "100"; sortOrder = 1
                }
            }
            val d = cue("D", "hex-1", 200, null)
            val e = cue("E", "hex-2", 50, eFadeMs)
            Rig(
                stackA = a.id.value, stackB = b.id.value,
                c = c.id.value, d = d.id.value, e = e.id.value,
                a1 = entry(a, c, 1.0, "1"), a2 = entry(a, d, 2.0, "2"), a3 = entry(a, c, 3.0, "3", follows = a3Follows),
                b1 = entry(b, c, 1.0, "1"), b2 = entry(b, e, 2.0, "2"),
            )
        }
    }

    /** The red of [fixture]'s composed Layer 4 colour, or null when no cue holds it. */
    private fun red(fixture: String): Int? =
        (state.show.fxEngine.layerResolver.currentCueLayerState[CueAssignmentResolver.Key.fixture(fixture, "rgbColour")]
            as? CueAssignmentResolver.AttributeValue.Colour)?.value?.toSerializedString()?.substring(1, 3)?.toInt(16)

    /** hex-1's composed Layer 4 dimmer — C's alone, scaled by C's fade weight. */
    private fun dimmer(): Int? =
        (state.show.fxEngine.layerResolver.currentCueLayerState[CueAssignmentResolver.Key.fixture("hex-1", "dimmer")]
            as? CueAssignmentResolver.AttributeValue.Slider)?.value?.toInt()

    private fun onStage(cueId: Int) = cueId in state.show.fxEngine.cueLayer.activeCueIds()

    private fun rowPriority(cueId: Int) =
        state.show.fxEngine.cueLayer.assignmentsExcludingCues(emptySet()).first { it.cueId == cueId }.priority

    @Test
    fun `stack A moving on leaves the cue on stage while B holds it`() = testApplication {
        mountTestApp(state)
        val rig = seed()
        manager.activateEntry(state, rig.stackA, rig.a1)
        manager.activateEntry(state, rig.stackB, rig.b1)
        assertEquals(listOf(CueHold.Stack(rig.stackA, rig.a1), CueHold.Stack(rig.stackB, rig.b1)), holds.holdsOf(rig.c))

        manager.advanceStack(state, rig.stackA, CueStackManager.AdvanceDirection.FORWARD)

        assertEquals(rig.a2, manager.getActiveEntryId(rig.stackA))
        assertEquals(listOf(CueHold.Stack(rig.stackB, rig.b1)), holds.holdsOf(rig.c), "A let go; B still holds C")
        assertTrue(onStage(rig.c), "C stays on stage")
        assertTrue(onStage(rig.d))
        assertEquals(100, red("hex-1"), "C, held at B's band, still wins hex-1 over D at A's")
    }

    @Test
    fun `a cue fades out only on its last release`() = testApplication {
        mountTestApp(state)
        // E's fade times B's GO from C to E: 600 ms, long enough to catch it mid-flight.
        val rig = seed(eFadeMs = 600)
        manager.activateEntry(state, rig.stackA, rig.a1)
        manager.activateEntry(state, rig.stackB, rig.b1)

        // B moves on while A still holds C: C is not handed to B's crossfade, and stays at full.
        manager.advanceStack(state, rig.stackB, CueStackManager.AdvanceDirection.FORWARD)
        runBlocking { delay(300) }
        assertEquals(100, dimmer(), "not fading: A still holds it")

        // A lets go — its last hold — and it comes off.
        manager.deactivateStack(rig.stackA, state)
        assertFalse(holds.isHeld(rig.c))
        assertFalse(onStage(rig.c))

        // And on a crossfade, the last release is what fades it: B goes back into C and on again.
        manager.goToEntry(state, rig.stackB, rig.b1)
        assertEquals(100, dimmer())
        manager.advanceStack(state, rig.stackB, CueStackManager.AdvanceDirection.FORWARD)
        runBlocking { delay(300) }
        val mid = dimmer() ?: 0
        assertTrue(mid in 10..90, "the last release crossfades C out on E's fade; hex-1 read $mid mid-fade")
        runBlocking { delay(600) }
        assertFalse(onStage(rig.c), "and the fade's end removes it")
    }

    @Test
    fun `a cue's priority is its highest hold's`() = testApplication {
        mountTestApp(state)
        val rig = seed()
        manager.activateEntry(state, rig.stackA, rig.a3)
        assertEquals(cueDerivedPriority(rig.stackA, 2), holds.priorityOf(rig.c), "a3 is A's third entry")

        manager.activateEntry(state, rig.stackB, rig.b1)
        val bBand = cueDerivedPriority(rig.stackB, 0)
        assertEquals(bBand, holds.priorityOf(rig.c), "B's hold is the higher")
        assertEquals(bBand, rowPriority(rig.c), "and the rows on stage were restamped to it")
        assertEquals(rig.stackB, holds.stackOf(rig.c))

        manager.deactivateStack(rig.stackB, state)
        assertEquals(cueDerivedPriority(rig.stackA, 2), holds.priorityOf(rig.c))
        assertEquals(cueDerivedPriority(rig.stackA, 2), rowPriority(rig.c), "back to A's when B lets go")
    }

    @Test
    fun `a loose press beats both stacks, newest on top`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val rig = seed()
        manager.activateEntry(state, rig.stackA, rig.a2) // D on hex-1 at A's band
        manager.activateEntry(state, rig.stackB, rig.b1) // C on hex-1 at B's, the higher
        assertEquals(100, red("hex-1"))

        // Press D loose — the door a cue slot and the hand use.
        assertEquals(HttpStatusCode.OK, client.post("/api/rest/projects/$projectId/cues/${rig.d}/apply").status)
        assertEquals(200, red("hex-1"), "loose D composes above both stacks (D10)")
        assertTrue(holds.looseHeld(rig.d))
        assertTrue(holds.priorityOf(rig.d)!! >= CueHolds.LOOSE_BAND)
        assertEquals(listOf(rig.d), holds.looseCueIdsInPressOrder())

        // A newer loose press of C goes on top of it.
        client.post("/api/rest/projects/$projectId/cues/${rig.c}/apply")
        assertEquals(100, red("hex-1"), "the newest loose press is on top")
        assertEquals(listOf(rig.d, rig.c), holds.looseCueIdsInPressOrder())

        // Letting both go leaves the stacks as they were.
        client.post("/api/rest/projects/$projectId/cues/${rig.c}/stop")
        client.post("/api/rest/projects/$projectId/cues/${rig.d}/stop")
        assertFalse(holds.looseHeld(rig.d))
        assertTrue(onStage(rig.d), "A still holds D")
        assertEquals(100, red("hex-1"), "B's C wins again")
    }

    @Test
    fun `a crossfade between two entries of the same cue moves nothing`() = testApplication {
        mountTestApp(state)
        val rig = seed(cFadeMs = 2_000, a3Follows = true)
        transaction(state.database) {
            DaoCueAdHocFxEntity.new {
                this.cue = DaoCue[rig.c]
                targetType = "fixture"; targetKey = "hex-2"
                fxType = "SineWave"; family = "intensity"; attribute = "dimmer"
                cycle = 1.0; blendMode = "OVERRIDE"; order = "NORMAL"
                parameters = emptyMap()
            }
        }
        manager.activateEntry(state, rig.stackA, rig.a1)
        val effects = state.show.fxEngine.getActiveFx().filter { it.cueId == rig.c }.map { it.id }.toSet()
        assertEquals(1, effects.size, "C's effect is running")

        // a1 to a3: both name C. C has a 2 s fade, and there is an outgoing entry — yet nothing fades.
        manager.goToEntry(state, rig.stackA, rig.a3)

        assertEquals(listOf(CueHold.Stack(rig.stackA, rig.a3)), holds.holdsOf(rig.c))
        assertEquals(effects, state.show.fxEngine.getActiveFx().filter { it.cueId == rig.c }.map { it.id }.toSet(), "no respawn")
        val run = manager.runState.runStateFor(state, rig.stackA)
        assertNull(run.fadeElapsedMs, "no crossfade ran")
        assertEquals(rig.a3, run.activeEntryId)
        assertTrue(run.autoAdvance, "the follow timer is the incoming entry's (a3 follows, a1 does not)")
        runBlocking { delay(200) }
        assertEquals(100, dimmer(), "C never dipped")
    }

    @Test
    fun `a GO onto the entry the stack is already on re-fires its cue rather than taking it off`() = testApplication {
        mountTestApp(state)
        val rig = seed(cFadeMs = 2_000)
        manager.activateEntry(state, rig.stackA, rig.a1)
        assertEquals(100, red("hex-1"))

        // The outgoing and incoming holds are one key: restamp-then-release used to empty it.
        manager.goToEntry(state, rig.stackA, rig.a1)
        assertEquals(listOf(CueHold.Stack(rig.stackA, rig.a1)), holds.holdsOf(rig.c), "still held")
        assertTrue(onStage(rig.c), "still on stage")
        assertEquals(100, red("hex-1"))
        assertNull(manager.runState.runStateFor(state, rig.stackA).fadeElapsedMs, "a re-fire snaps")

        // Held by B as well, a re-fire on A only restamps: B keeps C up throughout.
        manager.activateEntry(state, rig.stackB, rig.b1)
        manager.goToEntry(state, rig.stackA, rig.a1)
        assertEquals(listOf(CueHold.Stack(rig.stackB, rig.b1), CueHold.Stack(rig.stackA, rig.a1)), holds.holdsOf(rig.c))
        assertTrue(onStage(rig.c))
    }

    @Test
    fun `two presses of one cue never leave it held loose twice`() = testApplication {
        mountTestApp(state)
        val rig = seed()
        val presses = (1..8).map { java.util.concurrent.CompletableFuture.runAsync { holds.takeLooseCue(state, rig.c) } }
        presses.forEach { it.join() }
        assertEquals(1, holds.holdsOf(rig.c).count { it is CueHold.Loose }, "one loose hold per cue")
        holds.dropLoose(state, rig.c)
        assertFalse(holds.isHeld(rig.c), "one stop lets it go")
        assertFalse(onStage(rig.c))
    }

    @Test
    fun `preview keeps a cue another stack holds`() = testApplication {
        mountTestApp(state)
        val rig = seed()
        manager.activateEntry(state, rig.stackA, rig.a1)
        manager.activateEntry(state, rig.stackB, rig.b1)

        // A's next is a2 (D). The GO would let go of A's hold on C, but B still holds it — so the
        // preview composes D against C, and C at B's band wins hex-1.
        val preview = previewCueLook(state, rig.stackA, null)!!
        assertEquals(rig.d, preview.cueId)
        assertEquals(100, preview.channels.first { it.channel == hex1Red }.value.toInt())

        // With B stopped, A was C's only holder: the preview lets C go and D lands.
        manager.deactivateStack(rig.stackB, state)
        assertEquals(200, previewCueLook(state, rig.stackA, null)!!.channels.first { it.channel == hex1Red }.value.toInt())
    }

    @Test
    fun `events fire once, on the first hold, and GO BACK fires none`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val rig = seed()
        val cannon = client.post("/api/rest/projects/$projectId/patches") {
            contentType(ContentType.Application.Json)
            setBody(CreatePatchRequest(universe = 0, fixtureTypeKey = "equinox-twin-shot-mkii", key = "cannon", name = "Cannon", startChannel = 40))
        }.body<FixturePatchDto>().id
        val set = client.put("/api/rest/projects/$projectId/cues/${rig.c}/events") {
            contentType(ContentType.Application.Json)
            setBody("""{"events":[{"patchId":$cannon,"trigger":"A"}]}""")
        }
        assertEquals(HttpStatusCode.OK, set.status, set.bodyAsText())

        // Unarmed, so each time C's events would fire the desk announces one skip for them instead.
        val skips = mutableListOf<EffectsService.Skipped>()
        runBlocking {
            val collecting = launch(start = CoroutineStart.UNDISPATCHED) {
                state.effectsService.skipped.collect { if (it.cueId == rig.c) skips += it }
            }
            suspend fun announced(): Int {
                delay(150)
                return skips.size
            }

            manager.activateEntry(state, rig.stackB, rig.b1)
            assertEquals(1, announced(), "B's GO into C brings it on: its events go")
            manager.activateEntry(state, rig.stackA, rig.a1)
            assertEquals(1, announced(), "A's GO into C, already live through B, fires nothing (D15)")
            manager.advanceStack(state, rig.stackA, CueStackManager.AdvanceDirection.FORWARD)
            manager.goToEntry(state, rig.stackA, rig.a3)
            assertEquals(1, announced(), "a3 names C, still live through B")

            manager.deactivateStack(rig.stackB, state)
            manager.deactivateStack(rig.stackA, state)
            assertFalse(holds.isHeld(rig.c))
            manager.activateEntry(state, rig.stackA, rig.a2)
            manager.advanceStack(state, rig.stackA, CueStackManager.AdvanceDirection.BACKWARD)
            assertEquals(rig.a1, manager.getActiveEntryId(rig.stackA))
            assertEquals(1, announced(), "GO BACK into C brings it on but fires none")

            manager.deactivateStack(rig.stackA, state)
            client.post("/api/rest/projects/$projectId/cues/${rig.c}/apply")
            assertEquals(2, announced(), "a loose press brings it on, and its events go")
            assertEquals("C", skips.last().cueLabel, "a loose cue is announced by its name")

            collecting.cancelAndJoin()
        }
    }
}
