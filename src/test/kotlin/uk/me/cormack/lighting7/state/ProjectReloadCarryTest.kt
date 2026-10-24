package uk.me.cormack.lighting7.state

import uk.me.cormack.lighting7.testsupport.entryIdOf
import uk.me.cormack.lighting7.testsupport.inStack
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.FxEngine
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.fx.PropertyChannelWriter
import uk.me.cormack.lighting7.fx.takeLooseCue
import uk.me.cormack.lighting7.models.CueStackType
import uk.me.cormack.lighting7.models.CueType
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCuePropertyAssignment
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoParkedChannel
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.testsupport.FxTestSupport
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.programmerValue
import uk.me.cormack.lighting7.testsupport.seedMinimalProject
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A project reload and a project switch never take a channel through zero on the way to where the
 * next show puts it (`ProjectManager.switchProject`, `ShowCarry`, `Show.resume`):
 *
 * - **Reload** — the current project again, as a sync pull, a venue Update, Detach, Extract or a
 *   venue's Discard does it — starts the rebuilt show on the old one's last frame and brings back its
 *   live cues, programmer and hand-run effects; a channel nothing covers any more goes to zero.
 * - **Switch** — leaves a channel the incoming project parks as it was until that park takes it, and
 *   blacks out the rest.
 */
class ProjectReloadCarryTest : RouteIntegrationTest() {

    private val universe = Universe(0, 0)

    private fun mock(): MockDmxController = state.show.fixtures.controller(universe) as MockDmxController

    private fun dimmerChannel(fixtureKey: String): Int =
        PropertyChannelWriter.resolve(
            state.show.fixtures.untypedGroupableFixture(fixtureKey), "dimmer",
            CueAssignmentResolver.PropertyValue.Slider(1u),
        ).single().channel

    private fun seedStackWithDimmerCue(fixtureKey: String, level: Int): Pair<Int, Int> = transaction(state.database) {
        val project = DaoProject.findById(projectId)!!
        val stack = DaoCueStack.new {
            this.project = project
            name = "stack"; loop = false
            type = CueStackType.STACK.name; sortOrder = 0
        }
        val cue = DaoCue.new {
            this.project = project
            name = "cue"
        }.inStack(stack, sortOrder = 0)
        DaoCuePropertyAssignment.new {
            this.cue = cue
            targetType = "fixture"; targetKey = fixtureKey
            attribute = "dimmer"; value = "$level"; sortOrder = 0
        }
        stack.id.value to cue.id.value
    }

    private fun parkRow(project: Int, channel: Int, value: Int) = transaction(state.database) {
        DaoParkedChannel.new {
            this.project = DaoProject.findById(project)!!
            this.universe = 0
            this.channel = channel
            this.value = value
        }
    }

    @Test
    fun `a reload starts on the old frame and brings back cues, programmer, effects and parks`() = runBlocking {
        LocateTestSupport.seedHex(state, projectId, "hex-1", startChannel = 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", startChannel = 20)
        LocateTestSupport.seedHex(state, projectId, "hex-3", startChannel = 40)
        val cueDimmer = dimmerChannel("hex-1")
        val programmerDimmer = dimmerChannel("hex-2")
        val (stackId, cueId) = seedStackWithDimmerCue("hex-1", 200)

        state.show.cueStackManager.activateEntry(state, stackId, state.entryIdOf(cueId))
        state.show.fxEngine.programmer.writeProperty(
            ProgrammerOwner.WEB,
            state.show.fixtures.untypedGroupableFixture("hex-2"),
            "dimmer",
            CueAssignmentResolver.PropertyValue.Slider(150u),
        )
        FxTestSupport.start(state, "SineWave", "hex-3", "dimmer", programmerBand = true)
        state.show.parkManager.park(0, 300, 255u)
        // A channel no fixture covers, left up by a raw write: nothing will hold it after the reload.
        mock().setValue(400, 90u)

        val old = mock()
        assertEquals(200u.toUByte(), old.getEffectiveValue(cueDimmer))
        assertEquals(150u.toUByte(), old.getEffectiveValue(programmerDimmer))
        val oldShow = state.show

        state.projectManager.switchProject(projectId, reload = true)

        // The outgoing controller went on the frame it had, park on top: no blackout.
        val frozen = assertNotNullFrozen(old)
        assertEquals(200u.toUByte(), frozen[cueDimmer])
        assertEquals(150u.toUByte(), frozen[programmerDimmer])
        assertEquals(255u.toUByte(), old.transmittedValue(300), "the park stands to the last frame")

        val fresh = mock()
        assertNotSame(old, fresh)
        assertNotSame(oldShow, state.show)
        assertNull(fresh.frozen, "the resume lifted the freeze")
        assertTrue(fresh.writesTo(cueDimmer).none { it == 0u.toUByte() }, "the cue's dimmer never went to zero: ${fresh.writesTo(cueDimmer)}")
        assertTrue(fresh.writesTo(programmerDimmer).none { it == 0u.toUByte() }, "the programmer's dimmer never went to zero")
        assertEquals(200u.toUByte(), fresh.getEffectiveValue(cueDimmer))
        assertEquals(150u.toUByte(), fresh.getEffectiveValue(programmerDimmer))
        assertEquals(255u.toUByte(), fresh.transmittedValue(300))
        assertEquals(0u.toUByte(), fresh.getEffectiveValue(400), "an uncovered channel nothing holds goes to zero")

        assertEquals(cueId, state.show.cueStackManager.getActiveCueId(stackId), "the live cue is live again")
        assertEquals(
            CueAssignmentResolver.PropertyValue.Slider(150u),
            programmerValue(state, "hex-2", "dimmer"),
            "the programmer came across",
        )
        assertTrue(
            state.show.fxEngine.getActiveFx().any { it.target.targetKey == "hex-3" && it.cueId == null },
            "the hand-run effect is running again",
        )
    }

    @Test
    fun `a reload keeps the order of the Local FX on a key, a moved one included`() = runBlocking {
        LocateTestSupport.seedHex(state, projectId, "hex-1", startChannel = 1)
        val types = listOf("SineWave", "Pulse", "CandleFlicker")
        val started = types.map { FxTestSupport.start(state, it, "hex-1", "dimmer", programmerBand = true) }
        assertTrue(state.show.fxEngine.moveLocalFx(started.last().id, aboveId = started.first().id) is FxEngine.LocalMoveOutcome.Moved)
        fun applyOrder() = state.show.fxEngine.getActiveFx()
            .sortedWith(compareBy({ it.priority }, { it.composeOrder }, { it.id }))
            .map { it.registrationId }
        val before = applyOrder()
        assertEquals(listOf("CandleFlicker", "SineWave", "Pulse"), before)

        state.projectManager.switchProject(projectId, reload = true)

        assertEquals(before, applyOrder())
    }

    @Test
    fun `a reload crossfades a value the reload changed, and releases one nothing drives any more`() = runBlocking {
        LocateTestSupport.seedHex(state, projectId, "hex-1", startChannel = 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", startChannel = 20)
        val cueDimmer = dimmerChannel("hex-1")
        val strandedDimmer = dimmerChannel("hex-2")
        val (stackId, cueId) = seedStackWithDimmerCue("hex-1", 200)
        transaction(state.database) {
            DaoCuePropertyAssignment.new {
                this.cue = DaoCue.findById(cueId)!!
                targetType = "fixture"; targetKey = "hex-2"
                attribute = "dimmer"; value = "120"; sortOrder = 1
            }
        }
        state.show.cueStackManager.activateEntry(state, stackId, state.entryIdOf(cueId))
        assertEquals(120u.toUByte(), mock().getEffectiveValue(strandedDimmer))

        // What a pull brings in: the cue now sets hex-1 to 100 and no longer touches hex-2.
        transaction(state.database) {
            DaoCue.findById(cueId)!!.propertyAssignments.forEach {
                if (it.targetKey == "hex-1") it.value = "100" else it.delete()
            }
        }
        state.projectManager.switchProject(projectId, reload = true)

        val fresh = mock()
        val changes = fresh.changesTo(cueDimmer)
        assertEquals(100u.toUByte(), fresh.getEffectiveValue(cueDimmer))
        assertTrue(changes.isNotEmpty() && changes.last().fadeMs > 0, "200 → 100 is a crossfade: $changes")
        assertTrue(changes.none { it.newValue == 0u.toUByte() }, "and never through zero: $changes")
        assertEquals(0u.toUByte(), fresh.getEffectiveValue(strandedDimmer), "hex-2 released to its base")
        assertTrue(fresh.changesTo(strandedDimmer).last().fadeMs > 0, "released on a fade, not a snap")
    }

    @Test
    fun `a park the reload drops hands its value down rather than restarting from the buffer under it`() = runBlocking {
        LocateTestSupport.seedHex(state, projectId, "hex-1", startChannel = 1)
        val dimmer = dimmerChannel("hex-1")
        val (stackId, cueId) = seedStackWithDimmerCue("hex-1", 200)
        state.show.parkManager.park(0, dimmer, 255u)
        state.show.cueStackManager.activateEntry(state, stackId, state.entryIdOf(cueId))

        // What a pull that unparked it brings in: the row is gone.
        transaction(state.database) {
            DaoProject.findById(projectId)!!.parkedChannels.forEach { it.delete() }
        }
        state.projectManager.switchProject(projectId, reload = true)

        val fresh = mock()
        val changes = fresh.changesTo(dimmer)
        assertEquals(200u.toUByte(), fresh.getEffectiveValue(dimmer), "the cue takes it once the park is gone")
        assertTrue(changes.isNotEmpty() && changes.last().fadeMs > 0, "255 → 200 on a crossfade: $changes")
        assertTrue(fresh.writesTo(dimmer).none { it == 0u.toUByte() }, "never through zero: ${fresh.writesTo(dimmer)}")
    }

    @Test
    fun `a reload re-arms each stack's standby`() = runBlocking {
        LocateTestSupport.seedHex(state, projectId, "hex-1", startChannel = 1)
        val (stackId, cueId) = seedStackWithDimmerCue("hex-1", 200)
        val next = transaction(state.database) {
            DaoCue.new {
                this.project = DaoProject.findById(projectId)!!
                name = "next"
            }.inStack(DaoCueStack.findById(stackId)!!, sortOrder = 1).id.value
        }
        state.show.cueStackManager.activateEntry(state, stackId, state.entryIdOf(cueId))
        state.show.cueStackManager.runState.setStandby(state, stackId, next)

        val locate = state.show.locateManager
        state.projectManager.switchProject(projectId, reload = true)

        assertEquals(cueId, state.show.cueStackManager.getActiveCueId(stackId))
        assertEquals(next, state.show.cueStackManager.runState.getStandbyCueId(stackId), "standby is armed again")
        assertSame(locate, state.show.locateManager, "Locate's bookkeeping travels with the programmer's LOCATE slots")
    }

    @Test
    fun `a reload keeps the desk selection, less what the reload removed`() = runBlocking {
        LocateTestSupport.seedHex(state, projectId, "hex-1", startChannel = 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", startChannel = 20)
        state.deskSelection.set(listOf(CueTargetDto("fixture", "hex-1"), CueTargetDto("fixture", "hex-2")))

        // What a pull brings in: hex-2 is unpatched.
        transaction(state.database) {
            DaoFixturePatch.find { DaoFixturePatches.key eq "hex-2" }.single().delete()
        }
        state.projectManager.switchProject(projectId, reload = true)

        // The project collector runs on its own coroutine.
        val ok = withTimeoutOrNull(5_000) {
            while (state.deskSelection.state.value.targets != listOf(CueTargetDto("fixture", "hex-1"))) delay(20)
            true
        }
        assertEquals(true, ok, "selection: ${state.deskSelection.state.value.targets}")
    }

    @Test
    fun `choosing the current project without reload is a clean start that keeps only the parks`() = runBlocking {
        LocateTestSupport.seedHex(state, projectId, "hex-1", startChannel = 1)
        val dimmer = dimmerChannel("hex-1")
        val (stackId, cueId) = seedStackWithDimmerCue("hex-1", 200)
        state.show.cueStackManager.activateEntry(state, stackId, state.entryIdOf(cueId))
        state.show.parkManager.park(0, 300, 255u)
        val old = mock()

        state.projectManager.switchProject(projectId)

        val frozen = assertNotNullFrozen(old)
        assertEquals(0u.toUByte(), frozen[dimmer], "a clean start blacks out what is not parked")
        assertEquals(255u.toUByte(), old.transmittedValue(300))
        assertNull(state.show.cueStackManager.getActiveCueId(stackId), "nothing is carried")
        assertNotSame(old, mock())
        assertEquals(255u.toUByte(), mock().transmittedValue(300), "the park holds its level")
        assertEquals(0u.toUByte(), mock().transmittedValue(dimmer))
    }

    @Test
    fun `a switch keeps a channel the incoming project parks off zero and blacks out the rest`() = runBlocking {
        state.show.parkManager.park(0, 5, 200u)
        mock().setValue(6, 180u)
        mock().setValue(7, 90u)
        val old = mock()

        val incoming = seedMinimalProject(state, projectName = "Incoming")
        parkRow(incoming, 5, 150)
        parkRow(incoming, 6, 170)

        state.projectManager.switchProject(incoming)

        val frozen = assertNotNullFrozen(old)
        assertEquals(200u.toUByte(), old.transmittedValue(5), "both park channel 5: the outgoing park to the last frame")
        assertEquals(180u.toUByte(), frozen[6], "only the incoming project parks 6: it is held as it was, not blacked out")
        assertEquals(0u.toUByte(), frozen[7], "nobody parks 7: blacked out")

        val fresh = mock()
        assertEquals(150u.toUByte(), fresh.transmittedValue(5))
        assertEquals(170u.toUByte(), fresh.transmittedValue(6))
        assertEquals(0u.toUByte(), fresh.transmittedValue(7))
    }

    private fun assertNotNullFrozen(controller: MockDmxController): Map<Int, UByte> =
        checkNotNull(controller.frozen) { "the outgoing controller was frozen on its final frame" }

    @Test
    fun `a reload takes the loose holds again, in press order, beside the stacks`() = runBlocking {
        LocateTestSupport.seedHex(state, projectId, "hex-1", startChannel = 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", startChannel = 20)
        val (stackId, stacked) = seedStackWithDimmerCue("hex-1", 200)
        // Two cues in no stack: a loose hold needs none.
        val (first, second) = transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            fun loose(level: Int) = DaoCue.new { this.project = project; name = "loose $level" }.also { cue ->
                DaoCuePropertyAssignment.new {
                    this.cue = cue
                    targetType = "fixture"; targetKey = "hex-2"
                    attribute = "dimmer"; value = "$level"; sortOrder = 0
                }
            }.id.value
            loose(120) to loose(60)
        }

        // A stack's cue, also pressed loose; then two loose presses, oldest first (cue model plan §3.4).
        state.show.cueStackManager.activateEntry(state, stackId, state.entryIdOf(stacked))
        state.show.cueStackManager.holds.takeLooseCue(state, stacked)
        state.show.cueStackManager.holds.takeLooseCue(state, first)
        state.show.cueStackManager.holds.takeLooseCue(state, second)
        val before = state.show.cueStackManager.holds.looseCueIdsInPressOrder()
        assertEquals(listOf(stacked, first, second), before)

        state.projectManager.switchProject(projectId, reload = true)

        val holds = state.show.cueStackManager.holds
        assertEquals(before, holds.looseCueIdsInPressOrder(), "the same loose holds, in the same press order")
        assertEquals(state.entryIdOf(stacked), state.show.cueStackManager.getActiveEntryId(stackId), "and the stack's entry")
        assertEquals(2, holds.holdsOf(stacked).size, "the stacked cue held twice again: by its stack and loose")
        assertTrue(holds.priorityOf(second)!! > holds.priorityOf(first)!!, "the newest press still on top")
    }
}
