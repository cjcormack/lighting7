package uk.me.cormack.lighting7.osc

import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.midi.Binding
import uk.me.cormack.lighting7.midi.DefaultSurfaceActions
import uk.me.cormack.lighting7.midi.Does
import uk.me.cormack.lighting7.midi.How
import uk.me.cormack.lighting7.midi.RecordKind
import uk.me.cormack.lighting7.midi.Subject
import uk.me.cormack.lighting7.midi.SurfaceDispatch
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.mountTestApp
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Session 2's rows (OSC plan §3, D4, D10) through the desk's own handler and actions: blackout set
 * or toggled, the grand master and a master's tempo taken whole, a tap, and their refusals.
 */
class OscMastersTest : RouteIntegrationTest() {

    private val qlab = OscSender(1, "QLab", "127.0.0.1")

    private fun handler() = OscHandler(
        database = state.database,
        currentProjectId = { projectId },
        dispatch = SurfaceDispatch(DefaultSurfaceActions(state), state.activeBankState, state.encoderBankState),
    )

    private fun OscHandler.send(address: String, vararg args: OscArg): OscHandled =
        handle(OscInbound(Instant.now(), qlab, OscPacket.Item.Message(OscMessage(address, args.toList()))))

    private val scaler get() = state.show.globalScalerState
    private fun master(index: Int) = state.show.speedMasterBank.masterStates().single { it.index == index }

    @Test
    fun `blackout is set by a flag, toggled by nothing, and a flag sent twice changes nothing`() = testApplication {
        mountTestApp(state)
        val osc = handler()
        assertFalse(scaler.blackoutEnabled.value)

        assertEquals("Blackout on · on", osc.send("/lighting7/blackout", OscArg.Int32(1)).detail)
        assertTrue(scaler.blackoutEnabled.value)
        assertEquals("Blackout on · already on", osc.send("/lighting7/blackout", OscArg.Bool(true)).detail)
        assertTrue(scaler.blackoutEnabled.value)
        assertEquals("Blackout off · off", osc.send("/lighting7/blackout", OscArg.Float32(0f)).detail)
        assertFalse(scaler.blackoutEnabled.value)
        assertEquals("Toggle blackout · on", osc.send("/lighting7/blackout").detail)
        assertTrue(scaler.blackoutEnabled.value)
        assertEquals(OscOutcome.BAD_MESSAGE, osc.send("/lighting7/blackout", OscArg.Str("on")).outcome)
        assertTrue(scaler.blackoutEnabled.value)
    }

    @Test
    fun `the grand master takes a float whole, any numeric tag, clamped to 0–1`() = testApplication {
        mountTestApp(state)
        val osc = handler()
        assertEquals("Grand master · 50 %", osc.send("/lighting7/grandmaster", OscArg.Float32(0.5f)).detail)
        assertEquals(50.0, scaler.grandMasterLevel.value, 1e-9)
        // A level taken whole, not through a 7-bit fader's 128 steps (D10).
        osc.send("/lighting7/grandmaster", OscArg.Float64(0.333))
        assertEquals(33.3, scaler.grandMasterLevel.value, 1e-9)
        assertEquals("Grand master · 0 %", osc.send("/lighting7/grandmaster", OscArg.Int32(0)).detail)
        assertEquals("Grand master · 100 %", osc.send("/lighting7/grandmaster", OscArg.Float32(2f)).detail)
        assertEquals(OscOutcome.BAD_MESSAGE, osc.send("/lighting7/grandmaster").outcome)
        assertEquals(OscOutcome.BAD_MESSAGE, osc.send("/lighting7/grandmaster", OscArg.Float32(Float.NaN)).outcome)
        assertEquals(100.0, scaler.grandMasterLevel.value, 1e-9)
    }

    @Test
    fun `a master's tempo is set by index and tapped, and a tempo outside 20–300 or a master the show lacks does nothing`() = testApplication {
        mountTestApp(state)
        val osc = handler()
        val m2 = master(2)

        val set = osc.send("/lighting7/tempo/2", OscArg.Int32(128))
        assertEquals(OscOutcome.DONE, set.outcome, set.detail)
        assertEquals("Tempo of M2 ${m2.name} · 128.0 BPM", set.detail)
        assertEquals(128.0, master(2).bpm, 1e-9)
        assertEquals(OscOutcome.DONE, osc.send("/lighting7/tempo/2", OscArg.Float32(96.5f)).outcome)
        assertEquals(96.5, master(2).bpm, 1e-9)

        val high = osc.send("/lighting7/tempo/2", OscArg.Int32(400))
        assertEquals(OscOutcome.BAD_MESSAGE, high.outcome)
        assertEquals("Tempo is from 20 to 300 BPM, not 400", high.detail)
        assertEquals(96.5, master(2).bpm, 1e-9)
        assertEquals(OscOutcome.BAD_MESSAGE, osc.send("/lighting7/tempo/2").outcome)
        assertEquals("No speed master M9 in this show", osc.send("/lighting7/tempo/9", OscArg.Int32(120)).detail)
        assertEquals(OscOutcome.BAD_MESSAGE, osc.send("/lighting7/tempo/two", OscArg.Int32(120)).outcome)

        val tap = osc.send("/lighting7/tempo/2/tap")
        assertEquals(OscOutcome.DONE, tap.outcome, tap.detail)
        assertEquals("Tap M2 ${m2.name}", tap.detail)
    }

    @Test
    fun `every session-2 row resolves to its binding`() = testApplication {
        mountTestApp(state)
        val lookup = DbOscLookup(projectId)
        fun resolve(address: String, vararg args: OscArg) =
            org.jetbrains.exposed.v1.jdbc.transactions.transaction(state.database) { OscAddresses.resolve(address, args.toList(), lookup) }
        val m3 = Subject.Record(RecordKind.MASTER, assertNotNull(master(3).uuid))

        assertEquals(Binding(Does.Blackout, how = How.TOGGLE), assertIs<OscResolution.Press>(resolve("/lighting7/blackout")).binding)
        assertEquals(Binding(Does.Blackout, how = How.ON), assertIs<OscResolution.Press>(resolve("/lighting7/blackout", OscArg.Int32(5))).binding)
        assertEquals(Binding(Does.Blackout, how = How.OFF), assertIs<OscResolution.Press>(resolve("/lighting7/blackout", OscArg.Int32(0))).binding)
        val grandMaster = assertIs<OscResolution.Level>(resolve("/lighting7/grandmaster", OscArg.Float32(0.25f)))
        assertEquals(Binding(Does.GrandMaster()), grandMaster.binding)
        assertEquals(25.0, grandMaster.value)
        val tempo = assertIs<OscResolution.Level>(resolve("/lighting7/tempo/3", OscArg.Float32(140f)))
        assertEquals(Binding(Does.Tempo(), m3), tempo.binding)
        assertEquals(140.0, tempo.value)
        assertEquals(OscRecordKind.MASTER, tempo.record?.kind)
        assertEquals(Binding(Does.Tap, m3), assertIs<OscResolution.Press>(resolve("/lighting7/tempo/3/tap")).binding)
        assertEquals(4, OscAddresses.entries.count { it.session == 2 })
    }
}
