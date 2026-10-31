package uk.me.cormack.lighting7.fx

import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.CueStackEntryKind
import uk.me.cormack.lighting7.models.CueStackType
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueAttributeAssignment
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueStackEntry
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.MoveInDarkSettings
import uk.me.cormack.lighting7.models.moveInDarkSettings
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.mountTestApp
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Move in dark on the output (move-in-dark plan D4–D7, P4): a mover lit in Q10, faded out by Q11 (which
 * points it elsewhere), lit somewhere else by Q12 — a marker between them — moves to Q12's position once
 * dark for the delay, holds it over Q11's while its dimmer reads 0, and Q12's GO moves no pan or tilt
 * byte across its fade.
 *
 * The spot is a Fusion 100 Spot in 15-channel mode at 1: pan 1, tilt 3, dimmer 6.
 */
class MoveInDarkServiceTest : RouteIntegrationTest() {

    private val pan = 1
    private val tilt = 3
    private val dimmer = 6

    private data class Rig(val stackId: Int, val q10: Int, val q11: Int, val q12: Int)

    private val manager get() = state.show.cueStackManager
    private val controller get() = state.show.fixtures.controllers.first()

    /** [q12ThroughGroup]: Q12 points the spot through a group, Q11 at it directly. */
    private fun seed(on: Boolean, q12ThroughGroup: Boolean = false): Rig {
        LocateTestSupport.seedFixture(state, projectId, "fusion-100-spot-mkii-15ch", "spot", 1)
        if (q12ThroughGroup) LocateTestSupport.seedGroup(state, projectId, "movers", "spot")
        return transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            val stack = DaoCueStack.new {
                this.project = project
                name = "Act 1"; loop = false
                type = CueStackType.STACK.name; sortOrder = 0
                moveInDarkSettings = MoveInDarkSettings.NEW_STACK.copy(
                    on = on, delay = Duration.ofMillis(200), time = Duration.ofMillis(300),
                )
            }
            fun cue(name: String, fadeMs: Long, vararg rows: Pair<String, String>, group: Boolean = false) = DaoCue.new {
                this.project = project
                this.name = name
                fadeDuration = Duration.ofMillis(fadeMs)
            }.also { cue ->
                rows.forEachIndexed { index, (attribute, value) ->
                    DaoCueAttributeAssignment.new {
                        this.cue = cue
                        if (group && attribute == "position") { targetType = "group"; targetKey = "movers" }
                        else { targetType = "fixture"; targetKey = "spot" }
                        this.attribute = attribute; this.value = value; sortOrder = index
                    }
                }
            }
            fun entry(cue: DaoCue, ordinal: Double, number: String) = DaoCueStackEntry.new {
                this.stack = stack; this.cue = cue; this.ordinal = ordinal; cueNumber = number
            }.id.value
            val q10 = entry(cue("Q10", 0, "dimmer" to "255", "position" to "10,10"), 1.0, "10")
            // Q11 keeps a position of its own, so Q12's crossfade would blend from it were the head not held.
            val q11 = entry(cue("Q11", 200, "dimmer" to "0", "position" to "50,50"), 2.0, "11")
            DaoCueStackEntry.new {
                this.stack = stack; kind = CueStackEntryKind.MARKER.name; label = "Scene 2"; ordinal = 2.5
            }
            val q12 = entry(cue("Q12", 600, "dimmer" to "255", "position" to "200,100", group = q12ThroughGroup), 3.0, "12")
            Rig(stack.id.value, q10, q11, q12)
        }
    }

    private fun value(channel: Int) = controller.getValue(channel).toInt()

    private fun awaitUntil(what: String, timeoutMs: Long = 3_000, check: () -> Boolean) = runBlocking {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!check()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            delay(10)
        }
    }

    private fun contributorPublished(stackId: Int) =
        state.show.fxEngine.cueLayer.hasAssignments(MoveInDarkService.contributorId(stackId))

    @Test
    fun `a dark head moves to the next cue that lights it, and that cue's GO moves nothing`() = testApplication {
        mountTestApp(state)
        val rig = seed(on = true)

        manager.activateEntry(state, rig.stackId, rig.q10)
        awaitUntil("Q10 lit") { value(dimmer) == 255 && value(pan) == 10 }

        manager.activateEntry(state, rig.stackId, rig.q11)
        awaitUntil("Q11 faded the head out") { value(dimmer) == 0 }
        awaitUntil("the head in Q12's place") { value(pan) == 200 && value(tilt) == 100 }
        assertEquals(0, value(dimmer), "it moved in the dark")
        val waited = manager.moveInDark!!.statusOf(rig.stackId).getValue("spot")
        assertEquals(rig.q12, waited.targetEntryId, "through the marker to Q12")

        manager.activateEntry(state, rig.stackId, rig.q12)
        val samples = mutableListOf<Pair<Int, Int>>()
        runBlocking {
            val end = System.currentTimeMillis() + 700
            while (System.currentTimeMillis() < end) {
                samples += value(pan) to value(tilt)
                delay(16)
            }
        }
        assertTrue(value(dimmer) == 255, "Q12 brought it up")
        assertEquals(setOf(200 to 100), samples.toSet(), "no pan or tilt byte moved across Q12's fade")
        awaitUntil("the contributor let go once the fade ended") { !contributorPublished(rig.stackId) }
        assertEquals(200, value(pan), "and Q12 holds what it held")
    }

    @Test
    fun `a stack that is off sends what it sends today`() = testApplication {
        mountTestApp(state)
        val rig = seed(on = false)

        manager.activateEntry(state, rig.stackId, rig.q10)
        awaitUntil("Q10 lit") { value(dimmer) == 255 && value(pan) == 10 }
        manager.activateEntry(state, rig.stackId, rig.q11)
        awaitUntil("Q11 faded the head out") { value(dimmer) == 0 }
        val pans = mutableSetOf<Int>()
        runBlocking {
            val end = System.currentTimeMillis() + 900
            while (System.currentTimeMillis() < end) {
                pans += value(pan)
                assertFalse(contributorPublished(rig.stackId), "nothing in the cue tier but the cues")
                delay(20)
            }
        }
        assertEquals(setOf(50), pans, "the head stayed where Q11 points it")
        assertTrue(manager.moveInDark!!.statusOf(rig.stackId).isEmpty())
    }

    @Test
    fun `going back onto a cue that lights the head lets the move go`() = testApplication {
        mountTestApp(state)
        val rig = seed(on = true)
        manager.activateEntry(state, rig.stackId, rig.q10)
        manager.activateEntry(state, rig.stackId, rig.q11)
        awaitUntil("the head in Q12's place") { value(pan) == 200 }

        manager.goToEntry(state, rig.stackId, rig.q10)
        awaitUntil("Q10's position, lit") { value(dimmer) == 255 && value(pan) == 10 && value(tilt) == 10 }
        assertFalse(contributorPublished(rig.stackId), "nothing held while the live cue lights it")
    }

    @Test
    fun `a target that points the head through a group moves it beside the live cue's own row`() = testApplication {
        mountTestApp(state)
        val rig = seed(on = true, q12ThroughGroup = true)
        manager.activateEntry(state, rig.stackId, rig.q10)
        manager.activateEntry(state, rig.stackId, rig.q11)
        awaitUntil("the head in Q12's place") { value(pan) == 200 && value(tilt) == 100 }
    }

    @Test
    fun `Record reads where the cues put a moved head, never the move`() = testApplication {
        mountTestApp(state)
        val rig = seed(on = true)
        manager.activateEntry(state, rig.stackId, rig.q10)
        manager.activateEntry(state, rig.stackId, rig.q11)
        awaitUntil("the head in Q12's place") { value(pan) == 200 }
        val key = CueAssignmentResolver.Key.fixture("spot", "position")
        assertEquals(CueAssignmentResolver.AttributeValue.Position(200u, 100u), state.show.fxEngine.layerResolver.currentCueLayerState[key])
        assertEquals(
            CueAssignmentResolver.AttributeValue.Position(50u, 50u),
            state.show.fxEngine.cueLayer.cuesOnly().state[key],
            "Q11's own position, as Record and Update read it",
        )
    }

    @Test
    fun `stopping the stack lets the move go`() = testApplication {
        mountTestApp(state)
        val rig = seed(on = true)
        manager.activateEntry(state, rig.stackId, rig.q10)
        manager.activateEntry(state, rig.stackId, rig.q11)
        awaitUntil("the head in Q12's place") { value(pan) == 200 }
        assertTrue(contributorPublished(rig.stackId))

        manager.deactivateStack(rig.stackId, state)
        assertFalse(contributorPublished(rig.stackId))
        assertTrue(manager.moveInDark!!.statusOf(rig.stackId).isEmpty())
    }

    @Test
    fun `turning the stack off lets go of a head it moved, without releasing the stack`() = testApplication {
        mountTestApp(state)
        val rig = seed(on = true)
        manager.activateEntry(state, rig.stackId, rig.q10)
        manager.activateEntry(state, rig.stackId, rig.q11)
        awaitUntil("the head in Q12's place") { value(pan) == 200 }

        transaction(state.database) {
            val stack = DaoCueStack.findById(rig.stackId)!!
            stack.moveInDarkSettings = stack.moveInDarkSettings.copy(on = false)
        }
        state.show.moveInDark.onStackSettingsChanged(rig.stackId)
        awaitUntil("the contributor gone") { !contributorPublished(rig.stackId) }
        assertEquals(rig.q11, manager.getActiveEntryId(rig.stackId), "the stack still runs Q11")
    }
}
