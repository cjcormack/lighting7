package uk.me.cormack.lighting7.midi

import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.routes.SpreadOutcome
import uk.me.cormack.lighting7.routes.SpreadRequest
import uk.me.cormack.lighting7.routes.spreadIntoProgrammer
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.fx.ProgrammerWriter

/**
 * The surface's fan (venue-visit plan S12): a `SelectionFan` encoder fans the desk selection from
 * where the heads were when the gesture began, and starts again from where they are once the
 * selection changes or something else moves a head it fanned.
 */
class SelectionFanTest : RouteIntegrationTest() {

    private val movers = listOf("spot-1", "spot-2", "mac-1")

    private fun seed() {
        LocateTestSupport.seedFixture(state, projectId, "fusion-100-spot-mkii-15ch", "spot-1", 200)
        LocateTestSupport.seedFixture(state, projectId, "fusion-100-spot-mkii-15ch", "spot-2", 220)
        LocateTestSupport.seedFixture(state, projectId, "martin-mac-250-mode-4", "mac-1", 240)
        state.show.fixtures.patchListChanged()
    }

    private fun fixture(key: String) = CueTargetDto("fixture", key)

    private fun place(keys: List<String>, intent: String) {
        val request = SpreadRequest(targets = keys.map(::fixture), property = "position", from = intent, to = intent)
        assertIs<SpreadOutcome.Done>(spreadIntoProgrammer(state, request, null))
    }

    /** A control swept from the centre, a step at a time, to [to] — as a hand turns it. */
    private fun sweep(axis: FanAxis, to: Int) {
        val step = if (to >= 64) 2 else -2
        var at = 64 + step
        while (if (step > 0) at < to else at > to) {
            state.selectionFan.turn(axis, at.toUByte())
            at += step
        }
        state.selectionFan.turn(axis, to.toUByte())
    }

    private fun positions() = movers.map { state.show.programmerStore.get(it, "position")!!.value.resolved.serialize() }

    @Test
    fun `a turn fans the selection from where the heads were, and turning back fans it in`() = testApplication {
        seed()
        mountTestApp(state)
        place(movers, "pos:0,0")
        state.deskSelection.set(movers.map(::fixture))
        val fan = state.selectionFan

        // 64 is no fan, and each 7-bit step is two centre steps.
        sweep(FanAxis.PAN, 74)
        assertEquals(listOf("108,128", "128,128", "148,128"), positions())
        assertEquals(20, fan.amountFor(FanAxis.PAN))
        assertEquals(74, SelectionFan.amountTo7Bit(fan.amountFor(FanAxis.PAN)))

        sweep(FanAxis.TILT, 59)
        assertEquals(listOf("108,138", "128,128", "148,118"), positions(), "both axes fan from the one anchor")

        fan.turn(FanAxis.PAN, 64u)
        fan.turn(FanAxis.TILT, 64u)
        assertEquals(listOf("128,128", "128,128", "128,128"), positions(), "back to 0 is back where they started")
    }

    @Test
    fun `a head moved by anything else, or a new selection, starts the fan again from where they are`() = testApplication {
        seed()
        mountTestApp(state)
        place(movers, "pos:0,0")
        state.deskSelection.set(movers.map(::fixture))
        val fan = state.selectionFan
        sweep(FanAxis.PAN, 74)

        // Someone sets spot-2 from the programmer: the next turn returns the ring to the centre and
        // writes nothing; the one after fans from where the heads now are.
        place(listOf("spot-2"), "pos:12,0")
        fan.turn(FanAxis.PAN, 75u)
        assertEquals(0, fan.amountFor(FanAxis.PAN))
        assertEquals(listOf("108,128", "140,128", "148,128"), positions())
        fan.turn(FanAxis.PAN, 65u)
        assertEquals(listOf("106,128", "140,128", "150,128"), positions())

        state.deskSelection.set(listOf(fixture("spot-1"), fixture("spot-2")))
        assertEquals(0, fan.amountFor(FanAxis.PAN), "the ring shows no fan for a selection it has not fanned")
    }

    @Test
    fun `a tilt written on its own property since the fan last wrote is kept, not snapped back`() = testApplication {
        seed()
        mountTestApp(state)
        place(movers, "pos:0,0")
        state.deskSelection.set(movers.map(::fixture))
        val fan = state.selectionFan
        sweep(FanAxis.PAN, 74)
        val spot1 = state.show.fixtures.untypedGroupableFixture("spot-1")
        state.show.fxEngine.programmer.writeProperties(
            ProgrammerOwner.SURFACE,
            listOf(ProgrammerWriter.PropertyWrite(spot1, "tilt", CueAssignmentResolver.PropertyValue.Slider(180u))),
        )
        fan.turn(FanAxis.PAN, 75u)
        fan.turn(FanAxis.PAN, 65u)
        assertEquals("106,180", positions().first(), "the fan starts again from the tilt the property wrote")
    }

    @Test
    fun `the ring is re-fed on every write, even one equal to the last`() = testApplication {
        seed()
        mountTestApp(state)
        place(movers, "pos:0,0")
        state.deskSelection.set(movers.map(::fixture))
        val fan = state.selectionFan
        fan.turn(FanAxis.PAN, 65u)
        val first = fan.shown.value
        // A new selection: its rebuild showed the ring at 64, and the same turn lands the same amount.
        state.deskSelection.set(movers.take(2).map(::fixture))
        assertEquals(0, fan.amountFor(FanAxis.PAN))
        fan.turn(FanAxis.PAN, 65u)
        assertEquals(first.amounts, fan.shown.value.amounts)
        assertTrue(fan.shown.value.stamp > first.stamp)
    }

    @Test
    fun `a new gesture waits to pick up at the centre`() = testApplication {
        seed()
        mountTestApp(state)
        place(movers, "pos:0,0")
        state.deskSelection.set(movers.map(::fixture))
        val fan = state.selectionFan
        // A control still standing well off the centre — a touched motor fader — moves nothing.
        fan.turn(FanAxis.PAN, 100u)
        assertEquals(listOf("128,128", "128,128", "128,128"), positions())
        assertEquals(0, fan.amountFor(FanAxis.PAN))
        fan.turn(FanAxis.PAN, 66u)
        assertEquals(listOf("124,128", "128,128", "132,128"), positions())
    }

    @Test
    fun `an empty selection drops the turn`() = testApplication {
        seed()
        mountTestApp(state)
        place(movers, "pos:0,0")
        state.selectionFan.turn(FanAxis.PAN, 100u)
        assertEquals(listOf("128,128", "128,128", "128,128"), positions())
        assertEquals(0, state.selectionFan.amountFor(FanAxis.PAN))
    }
}
