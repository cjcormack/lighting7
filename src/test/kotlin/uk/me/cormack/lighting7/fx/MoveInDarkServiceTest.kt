package uk.me.cormack.lighting7.fx

import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.dmx.ChannelChange
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.models.CueStackEntryKind
import uk.me.cormack.lighting7.models.CueStackType
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueAttributeAssignment
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueStackEntry
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.MoveInDarkSettings
import uk.me.cormack.lighting7.models.moveInDarkSettings
import uk.me.cormack.lighting7.plugins.MoveInDarkPositionDto
import uk.me.cormack.lighting7.plugins.toMessage
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

    /** [q13] lights the head at 30,220 after Q12; [q11Cue] and [q12Cue] are the cues' ids. */
    private data class Rig(val stackId: Int, val q10: Int, val q11: Int, val q12: Int, val q13: Int, val q11Cue: Int, val q12Cue: Int)

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
            val q12Cue = cue("Q12", 600, "dimmer" to "255", "position" to "200,100", group = q12ThroughGroup)
            val q12 = entry(q12Cue, 3.0, "12")
            val q13 = entry(cue("Q13", 600, "dimmer" to "255", "position" to "30,220"), 4.0, "13")
            Rig(stack.id.value, q10, q11, q12, q13, DaoCueStackEntry.findById(q11)!!.cueId!!, q12Cue.id.value)
        }
    }

    private fun value(channel: Int) = controller.getValue(channel).toInt()

    /** The mock writes a fade's end value at once and logs its time: each write to [channel] since [from]. */
    private fun changesTo(channel: Int, from: Int = 0) =
        (controller as MockDmxController).changeLog.drop(from).filter { it.first == channel }.map { it.second }

    private fun changeCount() = (controller as MockDmxController).changeLog.size

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
        awaitUntil("nothing held once the GO has landed") { !contributorPublished(rig.stackId) }
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

    // ─── Session 2: every edge ───────────────────────────────────────────────────────────────

    /** (pan, tilt, dimmer) every frame for [ms]. */
    private fun sample(ms: Long): List<Triple<Int, Int, Int>> = runBlocking {
        val out = mutableListOf<Triple<Int, Int, Int>>()
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            out += Triple(value(pan), value(tilt), value(dimmer))
            delay(10)
        }
        out
    }

    private fun setRow(cueId: Int, attribute: String, value: String) = transaction(state.database) {
        DaoCueAttributeAssignment.all().first { it.cue.id.value == cueId && it.attribute == attribute }.value = value
    }

    /** Live on Q11 with the head set at Q12's 200,100. */
    private fun setAtQ12(rig: Rig) {
        manager.activateEntry(state, rig.stackId, rig.q10)
        awaitUntil("Q10 lit") { value(dimmer) == 255 }
        manager.activateEntry(state, rig.stackId, rig.q11)
        awaitUntil("the head set in Q12's place") {
            value(pan) == 200 && manager.moveInDark!!.statusOf(rig.stackId)["spot"]?.state == MoveInDarkService.HeadState.SET
        }
    }

    @Test
    fun `an edit of the target cue re-plans on cueListChanged`() = testApplication {
        mountTestApp(state)
        val rig = seed(on = true)
        setAtQ12(rig)
        setRow(rig.q12Cue, "position", "220,90")
        state.show.fixtures.cueListChanged()
        awaitUntil("the head at Q12's new place") { value(pan) == 220 && value(tilt) == 90 }
        assertEquals(0, value(dimmer), "moved in the dark")
    }

    @Test
    fun `a Look or palette retune re-plans on cuesRecomposed`() = testApplication {
        mountTestApp(state)
        val rig = seed(on = true)
        setAtQ12(rig)
        setRow(rig.q12Cue, "position", "180,60")
        state.show.fixtures.cuesRecomposed(listOf(rig.q12Cue))
        awaitUntil("the head at Q12's new place") { value(pan) == 180 && value(tilt) == 60 }
    }

    @Test
    fun `an entry placed ahead re-plans on cueStackListChanged, and the head moves to it in the dark`() = testApplication {
        mountTestApp(state)
        val rig = seed(on = true)
        setAtQ12(rig)
        val inserted = transaction(state.database) {
            val cue = DaoCue.new {
                project = DaoProject.findById(projectId)!!
                name = "Q11.5"
                fadeDuration = Duration.ofMillis(0)
            }
            for ((index, row) in listOf("dimmer" to "255", "position" to "90,140").withIndex()) {
                DaoCueAttributeAssignment.new {
                    this.cue = cue; targetType = "fixture"; targetKey = "spot"
                    attribute = row.first; value = row.second; sortOrder = index
                }
            }
            DaoCueStackEntry.new {
                stack = DaoCueStack.findById(rig.stackId)!!; this.cue = cue; ordinal = 2.2; cueNumber = "11.5"
            }.id.value
        }
        val from = changeCount()
        state.show.fixtures.cueStackListChanged()
        awaitUntil("the head at Q11.5's place") { value(pan) == 90 && value(tilt) == 140 }
        assertEquals(0, value(dimmer), "moved in the dark")
        assertEquals(listOf(300L), changesTo(pan, from).filter { it.newValue.toInt() == 90 }.map { it.fadeMs }, "over the move time")
        assertEquals(inserted, manager.moveInDark!!.statusOf(rig.stackId).getValue("spot").targetEntryId)
    }

    @Test
    fun `a target that no longer lights the head takes it back to the live cue's place in the dark`() = testApplication {
        mountTestApp(state)
        val rig = seed(on = true)
        setAtQ12(rig)
        setRow(rig.q12Cue, "dimmer", "0")
        setRow(transaction(state.database) { DaoCueStackEntry.findById(rig.q13)!!.cueId!! }, "dimmer", "0")
        val from = changeCount()
        state.show.fixtures.cueListChanged()
        awaitUntil("back at Q11's own place") { value(pan) == 50 }
        assertEquals(0, value(dimmer), "moved in the dark")
        assertEquals(listOf(300L), changesTo(pan, from).map { it.fadeMs }, "back over the move time")
        awaitUntil("nothing left in the contributor") { !contributorPublished(rig.stackId) }
    }

    @Test
    fun `arming a standby re-plans for the armed entry`() = testApplication {
        mountTestApp(state)
        val rig = seed(on = true)
        setAtQ12(rig)
        manager.runState.setStandby(state, rig.stackId, rig.q13)
        awaitUntil("the head at Q13's place") { value(pan) == 30 && value(tilt) == 220 }
        assertEquals(rig.q13, manager.moveInDark!!.statusOf(rig.stackId).getValue("spot").targetEntryId)
    }

    @Test
    fun `a GO that comes too soon lands the rest of the move at once, and nothing moves while the head is up`() = testApplication {
        mountTestApp(state)
        val rig = seed(on = true)
        manager.activateEntry(state, rig.stackId, rig.q10)
        awaitUntil("Q10 lit") { value(dimmer) == 255 && value(pan) == 10 }
        manager.activateEntry(state, rig.stackId, rig.q11)
        awaitUntil("moving") { manager.moveInDark!!.statusOf(rig.stackId)["spot"]?.state == MoveInDarkService.HeadState.MOVING }

        val from = changeCount()
        manager.activateEntry(state, rig.stackId, rig.q12)
        assertTrue(ChannelChange(200u, 0L) in changesTo(pan, from), "the ramp in progress ended at the GO: ${changesTo(pan, from)}")
        val samples = sample(700)
        val lit = samples.filter { it.third > 0 }
        assertTrue(lit.isNotEmpty(), "Q12 brought it up")
        assertEquals(setOf(200 to 100), lit.map { it.first to it.second }.toSet(), "every frame it was up, it was already in place")
    }

    @Test
    fun `GO TO a cue lands its dark heads at once, even one set for another cue`() = testApplication {
        mountTestApp(state)
        val rig = seed(on = true)
        setAtQ12(rig)

        manager.goToEntry(state, rig.stackId, rig.q13)
        val samples = sample(700)
        val lit = samples.filter { it.third > 0 }
        assertTrue(lit.isNotEmpty(), "Q13 brought it up")
        assertEquals(setOf(30 to 220), lit.map { it.first to it.second }.toSet(), "in Q13's place before it was seen")
        awaitUntil("the hand-over over") { !contributorPublished(rig.stackId) }
        assertEquals(30, value(pan))
    }

    @Test
    fun `a press that lights a set head lets it go over the move time, and it moves again once dark`() = testApplication {
        mountTestApp(state)
        val rig = seed(on = true)
        setAtQ12(rig)
        val press = transaction(state.database) {
            DaoCue.new {
                project = DaoProject.findById(projectId)!!
                name = "Flash"
                fadeDuration = Duration.ofMillis(0)
            }.also { cue ->
                DaoCueAttributeAssignment.new {
                    this.cue = cue; targetType = "fixture"; targetKey = "spot"
                    attribute = "dimmer"; value = "255"; sortOrder = 0
                }
            }.id.value
        }

        val from = changeCount()
        manager.holds.takeLooseCue(state, press)
        awaitUntil("back at Q11's place, lit") { value(pan) == 50 && value(dimmer) == 255 }
        assertEquals(listOf(300L), changesTo(pan, from).map { it.fadeMs }, "back over the move time, not at once")
        assertEquals(MoveInDarkService.HeadState.LIT_EARLY, manager.moveInDark!!.statusOf(rig.stackId).getValue("spot").state)

        manager.holds.dropLoose(state, press)
        awaitUntil("dark again") { value(dimmer) == 0 }
        awaitUntil("waiting again") { manager.moveInDark!!.statusOf(rig.stackId)["spot"]?.state == MoveInDarkService.HeadState.WAITING }
        assertEquals(50, value(pan), "nothing moves before the delay")
        awaitUntil("moved again") { value(pan) == 200 }
    }

    @Test
    fun `a reload moves nothing`() = testApplication {
        mountTestApp(state)
        val rig = seed(on = true)
        setAtQ12(rig)

        runBlocking { state.projectManager.switchProject(projectId, reload = true) }
        // Tilt is left out: a reload zeroes this spot's tilt channel with Move in dark off too.
        val samples = sample(800)
        assertEquals(setOf(200 to 0), samples.map { it.first to it.third }.toSet(), "the head stayed where it was, dark")
        assertEquals(
            CueAssignmentResolver.AttributeValue.Position(200u, 100u),
            state.show.fxEngine.layerResolver.currentCueLayerState[CueAssignmentResolver.Key.fixture("spot", "position")],
            "the move is still held",
        )
        assertEquals(MoveInDarkService.HeadState.SET, state.show.moveInDark.statusOf(rig.stackId).getValue("spot").state)
    }

    @Test
    fun `two stacks on one head, the higher stack's move wins`() = testApplication {
        mountTestApp(state)
        val rig = seed(on = true)
        val (higher, b1, b2) = transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            val stack = DaoCueStack.new {
                this.project = project
                name = "Act 2"; loop = false
                type = CueStackType.STACK.name; sortOrder = 1
                moveInDarkSettings = MoveInDarkSettings.NEW_STACK.copy(delay = Duration.ofMillis(200), time = Duration.ofMillis(300))
            }
            fun entry(name: String, ordinal: Double, vararg rows: Pair<String, String>) = DaoCueStackEntry.new {
                this.stack = stack; this.ordinal = ordinal; cueNumber = name
                cue = DaoCue.new { this.project = project; this.name = name; fadeDuration = Duration.ZERO }.also { cue ->
                    rows.forEachIndexed { index, (attribute, value) ->
                        DaoCueAttributeAssignment.new {
                            this.cue = cue; targetType = "fixture"; targetKey = "spot"
                            this.attribute = attribute; this.value = value; sortOrder = index
                        }
                    }
                }
            }.id.value
            Triple(stack.id.value, entry("B1", 1.0, "dimmer" to "0"), entry("B2", 2.0, "dimmer" to "255", "position" to "120,60"))
        }
        assertTrue(higher > rig.stackId, "the second stack composes above the first")
        setAtQ12(rig)
        manager.activateEntry(state, higher, b1)
        awaitUntil("the higher stack's target") { value(pan) == 120 && value(tilt) == 60 }
        assertEquals(b2, manager.moveInDark!!.statusOf(higher).getValue("spot").targetEntryId)
        assertTrue(contributorPublished(rig.stackId), "the lower stack still holds its own move, under it")
    }

    @Test
    fun `the key stack names the move, above the cue it beats`() = testApplication {
        mountTestApp(state)
        val rig = seed(on = true)
        setAtQ12(rig)
        val stack = state.show.fxEngine.provenance.keyStack("spot", "position")!!
        val kinds = stack.layers.map { it.kind }
        val move = stack.layers.single { it.kind == KeyStackLayer.Kind.MOVE_IN_DARK }
        assertTrue(move.onStage)
        assertEquals(CueAssignmentResolver.AttributeValue.Position(200u, 100u), move.value)
        assertEquals(rig.stackId, move.cueStackId)
        assertEquals(rig.q12, move.targetEntryId)
        assertEquals(rig.q12Cue, move.targetCueId)
        assertEquals("Q12", move.label)
        val cue = stack.layers.single { it.kind == KeyStackLayer.Kind.CUE }
        assertFalse(cue.onStage, "Q11 is under the move")
        assertEquals(rig.q11Cue, cue.cueId)
        assertEquals(CueAssignmentResolver.AttributeValue.Position(50u, 50u), cue.value)
        assertTrue(kinds.indexOf(KeyStackLayer.Kind.MOVE_IN_DARK) < kinds.indexOf(KeyStackLayer.Kind.CUE))
    }

    @Test
    fun `the frame reports each head's state as it changes`() = testApplication {
        mountTestApp(state)
        val rig = seed(on = true)
        val seen = java.util.concurrent.CopyOnWriteArrayList<MoveInDarkService.HeadState>()
        val collector = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            state.show.moveInDark.state.collect { snapshot ->
                snapshot.stacks[rig.stackId]?.firstOrNull { it.head == "spot" }?.let { if (seen.lastOrNull() != it.state) seen += it.state }
            }
        }
        try {
            setAtQ12(rig)
            awaitUntil("the frame says set") { seen.lastOrNull() == MoveInDarkService.HeadState.SET }
            assertEquals(
                listOf(MoveInDarkService.HeadState.WAITING, MoveInDarkService.HeadState.MOVING, MoveInDarkService.HeadState.SET),
                seen.toList(),
            )
            val head = state.show.moveInDark.state.value.stacks.getValue(rig.stackId).single()
            assertEquals(rig.q12, head.targetEntryId)
            assertEquals(setOf(AttributeFamily.POSITION), head.families)
            assertEquals(CueAssignmentResolver.AttributeValue.Position(200u, 100u), head.position)

            val message = state.show.moveInDark.state.value.toMessage(projectId)
            val dto = message.stacks.single().heads.single()
            assertEquals("SET", dto.state)
            assertEquals(listOf("POSITION"), dto.families)
            assertEquals(MoveInDarkPositionDto(200, 100), dto.position)

            manager.deactivateStack(rig.stackId, state)
            awaitUntil("an empty frame once the stack stops") { state.show.moveInDark.state.value.stacks.isEmpty() }
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun `a GO before the stack's first plan still catches its dark heads up`() = testApplication {
        mountTestApp(state)
        val rig = seed(on = true)
        // The stack's first GO, straight into the dark cue, then Q12 before any plan can have landed.
        manager.activateEntry(state, rig.stackId, rig.q11)
        manager.activateEntry(state, rig.stackId, rig.q12)
        val lit = sample(700).filter { it.third > 0 }
        assertTrue(lit.isNotEmpty(), "Q12 brought it up")
        assertEquals(setOf(200 to 100), lit.map { it.first to it.second }.toSet(), "in Q12's place before it was seen")
    }

    @Test
    fun `a GO onto a target edited since it was planned brings the head up where the cue now says`() = testApplication {
        mountTestApp(state)
        val rig = seed(on = true)
        setAtQ12(rig)
        // An edit nothing has announced yet, so the held move is still the old one.
        setRow(rig.q12Cue, "position", "220,90")
        manager.activateEntry(state, rig.stackId, rig.q12)
        val lit = sample(700).filter { it.third > 0 }
        assertTrue(lit.isNotEmpty(), "Q12 brought it up")
        assertEquals(setOf(220 to 90), lit.map { it.first to it.second }.toSet(), "the cue's own place, not the stale move")
    }

    @Test
    fun `a head the live cue lights but park holds dark is never moved`() = testApplication {
        mountTestApp(state)
        val rig = seed(on = true)
        manager.activateEntry(state, rig.stackId, rig.q10)
        awaitUntil("Q10 lit") { value(dimmer) == 255 && value(pan) == 10 }
        runBlocking { state.show.parkManager.park(universe = controller.universe.universe, channel = dimmer, value = 0u) }
        awaitUntil("dark under the park") { value(dimmer) == 0 }
        val pans = sample(900).map { it.first }.toSet()
        assertEquals(setOf(10), pans, "Q10 still points it")
        assertTrue(manager.moveInDark!!.statusOf(rig.stackId).isEmpty(), "nothing waits to move it")
        assertFalse(contributorPublished(rig.stackId))
    }
}
