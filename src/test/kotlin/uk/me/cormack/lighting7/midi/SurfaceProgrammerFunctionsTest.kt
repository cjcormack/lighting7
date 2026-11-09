package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.fx.IncludedTarget
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.history.HistorySource
import uk.me.cormack.lighting7.models.CueStackType
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.models.entriesOf
import uk.me.cormack.lighting7.routes.BareFxOutcome
import uk.me.cormack.lighting7.routes.ProgrammerFxRequest
import uk.me.cormack.lighting7.routes.pressBareFx
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The surface's programmer verbs and the functions that take a group or fixture (surface-setup plan
 * session 4, D2, D9, D10), through the dispatch the router and OSC share, against a real show.
 */
class SurfaceProgrammerFunctionsTest : RouteIntegrationTest() {

    private val origin = SurfaceDispatch.Origin("x-touch-compact", HistorySource.surface("x-touch"))

    private fun held(controlId: String) = origin.copy(hold = SurfaceDispatch.HoldKey("x-touch", controlId, ControlPart.MAIN))

    private val actions by lazy { DefaultSurfaceActions(state) }
    private val dispatch by lazy { SurfaceDispatch(actions, state.activeBankState, state.encoderBankState, state.surfaceHolds) }

    private fun press(binding: Binding, from: SurfaceDispatch.Origin = origin) =
        dispatch.dispatch(binding, SurfaceDispatch.Input.Press, from)

    private fun fixture(key: String) = CueTargetDto("fixture", key)

    private fun seedHexes() {
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", 13)
        LocateTestSupport.seedHex(state, projectId, "hex-3", 25)
        LocateTestSupport.seedGroup(state, projectId, "front", "hex-1", "hex-2")
    }

    private fun dimmer(key: String, owner: ProgrammerOwner = ProgrammerOwner.SURFACE) =
        state.show.programmerStore.valueFor(owner, key, "dimmer")

    private fun level(key: String, value: Int) =
        actions.writeLevel(Subject.Fixture(key), "dimmer", value.toUByte())

    private fun stack(name: String): UUID = transaction(state.database) {
        DaoCueStack.new {
            project = DaoProject.findById(projectId)!!
            this.name = name; loop = false; type = CueStackType.STACK.name; sortOrder = 0
        }.uuid
    }

    private fun cuesIn(stack: UUID): List<String> = transaction(state.database) {
        val row = DaoCueStack.all().first { it.uuid == stack }
        entriesOf(row).mapNotNull { it.cueId?.let { id -> DaoCue.findById(id)?.name } }
    }

    /** Wait for the serial lane to have done what [done] says. */
    private fun awaitLane(done: () -> Boolean) = runBlocking {
        withTimeout(5_000) { while (!done()) delay(10) }
    }

    @Test
    fun `Record appends a new cue to the stack it names, holding every touched value`() {
        seedHexes()
        val act1 = stack("Act 1")
        level("hex-1", 100)

        assertEquals(ActionOutcome.DONE, press(Binding(Does.Record, Subject.Record(RecordKind.STACK, act1))))
        awaitLane { cuesIn(act1).size == 1 }
        level("hex-2", 60)
        press(Binding(Does.Record, Subject.Record(RecordKind.STACK, act1)))
        awaitLane { cuesIn(act1).size == 2 }

        val (first, second) = transaction(state.database) {
            val row = DaoCueStack.all().first { it.uuid == act1 }
            entriesOf(row).map { entry -> DaoCue.findById(entry.cueId!!)!!.attributeAssignments.map { it.targetKey }.toSet() }
        }
        assertEquals(setOf("hex-1"), first)
        assertEquals(setOf("hex-1", "hex-2"), second, "the second cue is appended, after the first")
    }

    @Test
    fun `Record into a stack the show lacks is refused, and records nothing`() {
        seedHexes()
        level("hex-1", 100)
        val outcome = press(Binding(Does.Record, Subject.Record(RecordKind.STACK, UUID.randomUUID())))
        assertIs<ActionOutcome.NotFound>(outcome)
        assertEquals(0L, transaction(state.database) { DaoCue.count() })
    }

    @Test
    fun `Update with nothing included is refused and writes nothing`() {
        seedHexes()
        level("hex-1", 100)
        assertNull(state.show.programmerStore.lastIncludedTarget)
        val outcome = press(Binding(Does.Update))
        assertEquals(ActionOutcome.NOTHING_INCLUDED, assertIs<ActionOutcome.Refused>(outcome).code)
        assertEquals(0L, transaction(state.database) { DaoCue.count() })
    }

    @Test
    fun `Update writes the programmer back into the cue a Record left included`() {
        seedHexes()
        val act1 = stack("Act 1")
        level("hex-1", 100)
        press(Binding(Does.Record, Subject.Record(RecordKind.STACK, act1)))
        awaitLane { state.show.programmerStore.lastIncludedTarget?.kind == IncludedTarget.Kind.CUE }

        val recorded = { transaction(state.database) { DaoCue.all().single().attributeAssignments.single { it.targetKey == "hex-1" }.value } }
        val before = recorded()
        level("hex-1", 30)
        assertEquals(ActionOutcome.DONE, press(Binding(Does.Update)))
        awaitLane { recorded() != before }
        assertEquals(1, cuesIn(act1).size, "Update writes into the cue; it makes none")
    }

    @Test
    fun `Clear takes the whole programmer off`() {
        seedHexes()
        level("hex-1", 100)
        level("hex-3", 100)
        press(Binding(Does.Clear()))
        assertNull(dimmer("hex-1"))
        assertNull(dimmer("hex-3"))
    }

    @Test
    fun `Blind while held is Blind until let go`() {
        seedHexes()
        val binding = Binding(Does.Blind(), how = How.WHILE_HELD)
        dispatch.dispatch(binding, SurfaceDispatch.Input.Press, held("btn-1"))
        assertTrue(state.show.programmerStore.blind)
        dispatch.dispatch(binding, SurfaceDispatch.Input.Release, held("btn-1"))
        assertFalse(state.show.programmerStore.blind)
    }

    @Test
    fun `Release on a group releases its members and nothing else`() {
        seedHexes()
        listOf("hex-1", "hex-2", "hex-3").forEach { level(it, 100) }
        press(Binding(Does.Release(), Subject.Group("front")))
        assertNull(dimmer("hex-1"))
        assertNull(dimmer("hex-2"))
        assertNotNull(dimmer("hex-3"), "outside the group, left alone")
    }

    @Test
    fun `Release on the selection stops a Local FX over several of its targets`() {
        seedHexes()
        val request = ProgrammerFxRequest(fxType = "Wave", attribute = "dimmer", targets = listOf(fixture("hex-1"), fixture("hex-2")))
        val fxId = assertIs<BareFxOutcome.Done>(pressBareFx(state, request, null)).response.fxId
        state.deskSelection.set(listOf(fixture("hex-1"), fixture("hex-2")))

        press(Binding(Does.Release(), Subject.Selection))
        assertNull(state.show.fxEngine.getFx(fxId), "each target's own Release alone would leave it as partial")
    }

    @Test
    fun `Locate on a group or a fixture locates it, and a second press releases it`() {
        seedHexes()
        val located = { state.show.locateManager.activeTargets.value }
        press(Binding(Does.Locate, Subject.Group("front")))
        assertEquals(setOf<TargetRef>(TargetRef.Group("front")), located())
        press(Binding(Does.Locate, Subject.Fixture("hex-3")))
        assertEquals(setOf(TargetRef.Group("front"), TargetRef.Fixture("hex-3")), located())
        press(Binding(Does.Locate, Subject.Group("front")))
        assertEquals(setOf<TargetRef>(TargetRef.Fixture("hex-3")), located())
    }

    @Test
    fun `a flash on the selection releases the heads it wrote, though the selection has moved`() {
        seedHexes()
        state.deskSelection.set(listOf(fixture("hex-1")))
        val binding = Binding(Does.Flash("dimmer"), Subject.Selection)
        dispatch.dispatch(binding, SurfaceDispatch.Input.Press, held("btn-2"))
        assertNotNull(dimmer("hex-1", ProgrammerOwner.FLASH))

        state.deskSelection.set(listOf(fixture("hex-2")))
        dispatch.dispatch(binding, SurfaceDispatch.Input.Release, held("btn-2"))
        assertNull(dimmer("hex-1", ProgrammerOwner.FLASH), "the head the press wrote is let go")
        assertNull(dimmer("hex-2", ProgrammerOwner.FLASH), "the head selected since was never flashed")
    }
}
