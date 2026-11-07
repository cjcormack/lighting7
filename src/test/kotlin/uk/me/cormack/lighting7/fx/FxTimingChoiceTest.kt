package uk.me.cormack.lighting7.fx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** `fx/FxTimingChoice.kt`'s reading of an effect's clock (fx-speed plan D3–D5). */
class FxTimingChoiceTest {

    @Test
    fun `the built-in wall-clock set is every built-in wall-clock effect`() {
        val registry = FxRegistry().also { FxFileLoader(FxScriptCompiler()).loadBuiltInFx(it) }
        val wallClock = registry.getLibrary().filter { it.timingSource == TimingSource.WALL_CLOCK.name }.map { it.name }.toSet()
        assertEquals(wallClock, BUILT_IN_WALL_CLOCK_FX, "the database step and the importer tell Seconds by this set")
    }

    @Test
    fun `a request's old seconds is Own tempo 120 at twice its cycle, else the followed master's tempo`() {
        val beat = TimingSource.BEAT
        assertEquals(RequestedClock(120.0, false, 10.0), resolveRequestedClock(beat, null, true, null, 5.0) { error("unasked") })
        assertEquals(RequestedClock(90.0, false, null), resolveRequestedClock(beat, null, true, null, null) { 90.0 })
        assertEquals(RequestedClock(96.0, false, 3.0), resolveRequestedClock(beat, 96.0, true, null, 3.0) { error("unasked") })
        assertEquals(RequestedClock(null, false, 2.0), resolveRequestedClock(beat, null, false, null, 2.0) { error("unasked") })
        assertFailsWith<IllegalArgumentException> { resolveRequestedClock(beat, null, null, true, null) { 120.0 } }

        val wall = TimingSource.WALL_CLOCK
        assertEquals(RequestedClock(null, true, 4.0), resolveRequestedClock(wall, null, true, null, 4.0) { error("unasked") })
        assertEquals(RequestedClock(null, true, null), resolveRequestedClock(wall, null, null, true, null) { error("unasked") })
        assertFailsWith<IllegalArgumentException> { resolveRequestedClock(wall, 96.0, null, null, null) { 120.0 } }
    }

    @Test
    fun `an own clock beside a master is refused, and an Own tempo out of range`() {
        assertNotNull(ownTempoTimingConflict(96.0, null, null, "m", null))
        assertNotNull(ownTempoTimingConflict(null, true, null, null, "m"))
        assertNotNull(ownTempoTimingConflict(null, null, true, "m", null))
        assertNotNull(ownTempoTimingConflict(96.0, null, true, null, null))
        assertNotNull(ownTempoTimingConflict(19.0, null, null, null, null))
        assertNotNull(ownTempoTimingConflict(301.0, null, null, null, null))
        assertNotNull(ownTempoTimingConflict(Double.NaN, null, null, null, null))
        assertNull(ownTempoTimingConflict(20.0, false, false, null, null))
        assertNull(ownTempoTimingConflict(null, false, false, "m", "m"))
    }

    @Test
    fun `a stored write keeps Own tempo, reads seconds by D5, and keeps no master beside an own clock`() {
        assertEquals(StoredWriteClock(120.0, 12.0, true), storedWriteClock(TimingSource.BEAT, null, true, 6.0))
        assertEquals(StoredWriteClock(120.0, 12.0, true), storedWriteClock(null, null, true, 6.0), "an unknown type is a beat one")
        assertEquals(StoredWriteClock(80.0, 6.0, true), storedWriteClock(TimingSource.BEAT, 80.0, true, 6.0))
        assertEquals(StoredWriteClock(null, 6.0, false), storedWriteClock(TimingSource.BEAT, null, false, 6.0))
        assertEquals(StoredWriteClock(null, 6.0, true), storedWriteClock(TimingSource.WALL_CLOCK, null, true, 6.0))
        assertEquals(
            StoredWriteClock(null, 4.0, true), storedWriteClock(TimingSource.WALL_CLOCK, 120.0, false, 8.0),
            "a wall-clock row sent back with an Own tempo is kept on Natural at the seconds it meant",
        )
        assertNotNull(storedClockProblem(TimingSource.BEAT, false, 500.0))
        assertNull(storedClockProblem(TimingSource.BEAT, false, 96.0))
        assertNull(storedClockProblem(TimingSource.WALL_CLOCK, true, null))
        assertNotNull(storedClockProblem(TimingSource.WALL_CLOCK, true, 96.0), "a wall-clock FX the step names is refused (D4)")
        assertNull(storedClockProblem(TimingSource.WALL_CLOCK, false, 96.0), "a script's, the step's blind spot, is kept on Natural")
    }

    @Test
    fun `a wall-clock FX is told by name as the registry resolves one, a project's own definitions included`() {
        val builtIn = wallClockFxTypes()
        for (name in listOf("CandleFlicker", "candle flicker", "Fluorescent_Flicker")) {
            assertEquals(TimingSource.WALL_CLOCK, timingSourceByName(name, builtIn), name)
        }
        assertEquals(TimingSource.BEAT, timingSourceByName("Wave", builtIn))
        assertEquals(TimingSource.BEAT, timingSourceByName("Smoke", builtIn))
        assertEquals(TimingSource.WALL_CLOCK, timingSourceByName("smoke", wallClockFxTypes(listOf("Smoke"))))
    }

    @Test
    fun `a stored wall-clock row naming an Own tempo runs on Natural at the seconds it meant`() {
        assertEquals(null to 4.0, storedClock(TimingSource.WALL_CLOCK, 120.0, 8.0))
        assertEquals(96.0 to 8.0, storedClock(TimingSource.BEAT, 96.0, 8.0))
        assertEquals(300.0 to 8.0, storedClock(TimingSource.BEAT, 900.0, 8.0), "held to the range, leniently")
        assertEquals(null to 8.0, storedClock(TimingSource.BEAT, null, 8.0))
    }
}
