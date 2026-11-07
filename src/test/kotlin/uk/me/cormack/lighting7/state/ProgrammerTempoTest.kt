package uk.me.cormack.lighting7.state

import uk.me.cormack.lighting7.fx.SpeedMasterBank
import uk.me.cormack.lighting7.fx.SpeedMasterSnapshot
import uk.me.cormack.lighting7.models.SpeedMasterSource
import uk.me.cormack.lighting7.plugins.toMessage
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The programmer's tempos (fx-speed plan D8, D9, D11) over a bank: holds run at once, Blind stages
 * them, a rail tap moves a landed hold or, on one only staged, the live tempo, and Include sets the
 * baseline `changedSinceInclude` counts against.
 */
class ProgrammerTempoTest {
    private val u1 = UUID.randomUUID()
    private val u2 = UUID.randomUUID()
    private var bank = SpeedMasterBank().apply { load(rows()) }
    private var blind = false
    private val tempo = ProgrammerTempo(bankOf = { bank }, isBlind = { blind }).apply { attach(PROJECT) }

    private fun rows(withU2: Boolean = true) = listOfNotNull(
        SpeedMasterSnapshot(u1, 1, "Master 1", 120.0, SpeedMasterSource.MANUAL),
        SpeedMasterSnapshot(u2, 2, "Chase", 100.0, SpeedMasterSource.MANUAL).takeIf { withU2 },
    )

    private fun running(uuid: UUID) = bank.masterStates().single { it.uuid == uuid }.bpm
    private fun live(uuid: UUID) = bank.masterStates().single { it.uuid == uuid }.liveBpm

    @Test
    fun `a set holds the master at once, and a release lands its live tempo`() {
        assertIs<ProgrammerTempo.Outcome.Applied>(tempo.set(u2, 140.0))
        assertEquals(140.0, running(u2))
        assertEquals(mapOf(u2 to 140.0), tempo.snapshot().holds)

        assertEquals(1, tempo.release(u2))
        assertEquals(100.0, running(u2))
        assertTrue(tempo.snapshot().holds.isEmpty())
    }

    @Test
    fun `out-of-range, unknown and follower masters are refused`() {
        assertIs<ProgrammerTempo.Outcome.Refused>(tempo.set(u2, 400.0))
        assertIs<ProgrammerTempo.Outcome.Refused>(tempo.set(UUID.randomUUID(), 100.0))
        bank.load(listOf(rows()[0], SpeedMasterSnapshot(u2, 2, "Chase", 100.0, SpeedMasterSource.MANUAL, followNum = 1, followDen = 2)))
        assertIs<ProgrammerTempo.Outcome.Refused>(tempo.set(u2, 100.0))
        assertIs<ProgrammerTempo.Outcome.Refused>(tempo.capture(u2))
        assertTrue(tempo.snapshot().holds.isEmpty())
    }

    @Test
    fun `Capture holds at the running tempo, and a first tap does too`() {
        bank.setBpm(u2, 111.0, SpeedMasterSource.MANUAL)
        tempo.capture(u2)
        assertEquals(mapOf(u2 to 111.0), tempo.snapshot().holds)
        tempo.release(null)

        tempo.tap(u1, now = 10_000)
        assertEquals(mapOf(u1 to 120.0), tempo.snapshot().holds, "the first tap holds it where it runs")
        tempo.tap(u1, now = 10_500)
        assertEquals(120.0, tempo.snapshot().holds.getValue(u1), "two taps half a second apart")
        tempo.tap(u1, now = 11_100)
        assertEquals(109.09, tempo.snapshot().holds.getValue(u1), 0.01)
        assertEquals(running(u1), tempo.snapshot().holds.getValue(u1))
    }

    @Test
    fun `a rail tap on a held master moves the programmer's hold`() {
        tempo.set(u2, 140.0)
        bank.setBpm(u2, 150.0, SpeedMasterSource.MANUAL)
        assertEquals(mapOf(u2 to 150.0), tempo.snapshot().holds)
        assertEquals(100.0, live(u2))
    }

    @Test
    fun `Blind stages a hold, the running tempo stays, and leaving Blind lands it`() {
        tempo.set(u2, 140.0)
        blind = true
        tempo.onBlindChanged()
        assertEquals(140.0, running(u2), "entering Blind changes nothing on stage")

        tempo.set(u2, 90.0)
        tempo.set(u1, 130.0)
        assertEquals(140.0, running(u2))
        assertEquals(120.0, running(u1))
        val staged = tempo.snapshot().toMessage().masters.associateBy { it.speedMasterUuid }
        assertEquals(true, staged.getValue(u2.toString()).staged)
        assertEquals(true, staged.getValue(u1.toString()).staged)

        blind = false
        tempo.onBlindChanged()
        assertEquals(90.0, running(u2))
        assertEquals(130.0, running(u1))
        assertTrue(tempo.snapshot().toMessage().masters.all { it.staged == null })
    }

    @Test
    fun `a release in Blind is staged and listed as releasing`() {
        tempo.set(u2, 140.0)
        blind = true
        tempo.onBlindChanged()
        tempo.release(u2)
        assertEquals(140.0, running(u2))
        val entry = tempo.snapshot().toMessage().masters.single()
        assertEquals(true, entry.released)
        assertEquals(140.0, entry.bpm)
        blind = false
        tempo.onBlindChanged()
        assertEquals(100.0, running(u2))
        assertTrue(tempo.snapshot().toMessage().masters.isEmpty())
    }

    @Test
    fun `a rail tap in Blind on a master whose hold is only staged moves its live tempo`() {
        blind = true
        tempo.onBlindChanged()
        tempo.set(u2, 140.0)
        bank.setBpm(u2, 105.0, SpeedMasterSource.MANUAL)
        assertEquals(105.0, running(u2), "what is on stage moves")
        assertEquals(mapOf(u2 to 140.0), tempo.snapshot().holds, "and the staged hold waits to land")
        blind = false
        tempo.onBlindChanged()
        assertEquals(140.0, running(u2))
        tempo.release(u2)
        assertEquals(105.0, running(u2))
    }

    @Test
    fun `Include sets the baseline that changedSinceInclude counts against`() {
        assertNull(tempo.snapshot().changedSinceInclude)
        assertEquals(1, tempo.include(PROJECT, mapOf(u2 to 128.0)))
        tempo.includedBaseline(PROJECT, mapOf(u2 to 128.0))
        assertEquals(128.0, running(u2))
        assertEquals(0, tempo.snapshot().changedSinceInclude)
        tempo.set(u2, 132.0)
        tempo.set(u1, 90.0)
        assertEquals(2, tempo.snapshot().changedSinceInclude)
        tempo.rebaseline(PROJECT, written = true)
        assertEquals(0, tempo.snapshot().changedSinceInclude)
    }

    @Test
    fun `restore puts the holds back, and Clear lets them all go`() {
        tempo.set(u2, 140.0)
        val before = tempo.snapshot()
        tempo.set(u1, 90.0)
        tempo.release(u2)
        assertTrue(tempo.restore(before))
        assertEquals(mapOf(u2 to 140.0), tempo.snapshot().holds)
        assertEquals(120.0, running(u1))
        assertEquals(1, tempo.clear())
        assertEquals(100.0, running(u2))
    }

    @Test
    fun `a carried reload keeps the holds whose master survived and lands them on the new bank`() {
        tempo.set(u1, 90.0)
        tempo.set(u2, 140.0)
        bank = SpeedMasterBank().apply { load(rows(withU2 = false)) }
        tempo.attach(PROJECT, reload = true)
        assertEquals(mapOf(u1 to 90.0), tempo.snapshot().holds)
        assertEquals(90.0, running(u1))

        bank = SpeedMasterBank().apply { load(rows()) }
        tempo.attach(OTHER)
        assertTrue(tempo.snapshot().holds.isEmpty(), "another project drops them")
        assertEquals(120.0, running(u1))
    }

    @Test
    fun `a held master the bank drops loses its hold`() {
        tempo.set(u2, 140.0)
        bank.load(rows(withU2 = false))
        assertTrue(tempo.snapshot().holds.isEmpty())
    }

    @Test
    fun `a push from the programmer never puts back a hold the bank has just moved`() {
        tempo.set(u2, 140.0)
        // The rail's write lands in the bank while its report to the programmer is still on its way.
        val report = bank.onHoldChanged
        var pending: (() -> Unit)? = null
        bank.onHoldChanged = { uuid, bpm -> pending = { report?.invoke(uuid, bpm) } }
        bank.setBpm(u2, 150.0, SpeedMasterSource.MANUAL)
        bank.onHoldChanged = report
        tempo.set(u1, 90.0)
        assertEquals(150.0, running(u2), "the programmer's write on M1 leaves M2's moved hold alone")
        pending!!.invoke()
        assertEquals(mapOf(u2 to 150.0, u1 to 90.0), tempo.snapshot().holds)
    }

    @Test
    fun `a hold staged in Blind on a master deleted or linked as a follower is dropped`() {
        blind = true
        tempo.onBlindChanged()
        tempo.set(u2, 140.0)
        bank.load(rows(withU2 = false))
        assertTrue(tempo.snapshot().holds.isEmpty())

        bank.load(rows())
        tempo.set(u2, 150.0)
        bank.load(listOf(rows()[0], SpeedMasterSnapshot(u2, 2, "Chase", 100.0, SpeedMasterSource.MANUAL, followNum = 1, followDen = 2)))
        assertTrue(tempo.snapshot().holds.isEmpty())
    }

    @Test
    fun `a rail tap in Blind on a hold released in Blind lands what it set when Blind is left`() {
        tempo.set(u2, 140.0)
        blind = true
        tempo.onBlindChanged()
        tempo.release(u2)
        bank.setBpm(u2, 128.0, SpeedMasterSource.MANUAL)
        assertEquals(128.0, running(u2))
        blind = false
        tempo.onBlindChanged()
        assertEquals(128.0, running(u2))
        assertTrue(tempo.snapshot().holds.isEmpty())
    }

    @Test
    fun `setting the tempo already held takes no programmer step`() {
        var steps = 0
        tempo.beforeMutation = { steps++ }
        tempo.set(u2, 128.0)
        tempo.set(u2, 128.0)
        tempo.capture(u2)
        assertEquals(1, steps)
    }

    private companion object {
        const val PROJECT = 7
        const val OTHER = 8
    }
}
