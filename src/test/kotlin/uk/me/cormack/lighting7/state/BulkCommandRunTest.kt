package uk.me.cormack.lighting7.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import uk.me.cormack.lighting7.fixture.CommandRole
import uk.me.cormack.lighting7.state.BulkCommandRun.BulkUnit
import uk.me.cormack.lighting7.state.BulkCommandRun.Purpose
import uk.me.cormack.lighting7.state.BulkCommandRun.Start
import uk.me.cormack.lighting7.state.BulkCommandRun.Status
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A bulk run's order and timing (venue-visit plan S6, D19), on virtual time: lamp-offs and resets
 * together, strikes one at a time a unit's spacing apart, a lamp inside its restrike time waited out
 * and reported, refusals per unit, and cancel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BulkCommandRunTest {

    private fun unit(key: String, restrike: Long = 0, spacing: Long = 0) =
        BulkUnit(key, key.uppercase(), "cmd", restrikeMs = restrike, strikeSpacingMs = spacing)

    /** Every start a run made: the unit and the virtual time it was started at. */
    private class Starts(private val scope: TestScope, private val holdMs: Long = 1_000) {
        val at = mutableListOf<Pair<String, Long>>()
        val refuse = mutableMapOf<String, String>()
        val cutShort = mutableSetOf<String>()

        fun start(unit: BulkUnit): Start {
            refuse[unit.fixtureKey]?.let { return Start.Refused(it, "refused") }
            at += unit.fixtureKey to scope.testScheduler.currentTime
            val done = CompletableDeferred<Boolean>()
            scope.launch {
                kotlinx.coroutines.delay(holdMs)
                done.complete(unit.fixtureKey !in cutShort)
            }
            return Start.Started(scope.testScheduler.currentTime + holdMs, done)
        }
    }

    private fun TestScope.run(
        role: CommandRole,
        units: List<BulkUnit>,
        starts: Starts,
        readyAt: Map<String, Long> = emptyMap(),
    ) = BulkCommandRun(
        id = "r", role = role, purpose = Purpose.BULK, units = units, withoutCommand = 0,
        startedAtMs = testScheduler.currentTime,
        clock = { testScheduler.currentTime },
        readyAt = { readyAt[it] },
        start = starts::start,
        onChange = {},
    )

    private fun BulkCommandRun.statuses() = snapshot().associate { it.unit.fixtureKey to it.status }

    @Test
    fun `lamp-offs start together and the run ends with the last hold`() = runTest {
        val starts = Starts(this)
        val run = run(CommandRole.LAMP_OFF, listOf(unit("a"), unit("b"), unit("c")), starts)
        launch { run.execute() }
        runCurrent()
        assertEquals(listOf("a" to 0L, "b" to 0L, "c" to 0L), starts.at)
        assertEquals(Status.RUNNING, run.statuses()["a"])
        advanceUntilIdle()
        assertTrue(run.finished)
        assertEquals(mapOf("a" to Status.DONE, "b" to Status.DONE, "c" to Status.DONE), run.statuses())
    }

    @Test
    fun `strikes go one at a time, each a unit's own spacing after the last`() = runTest {
        val starts = Starts(this)
        val run = run(CommandRole.LAMP_ON, listOf(unit("a", spacing = 5_000), unit("b", spacing = 2_000), unit("c", spacing = 5_000)), starts)
        launch { run.execute() }
        runCurrent()
        assertEquals(listOf("a" to 0L), starts.at)
        assertEquals(Status.QUEUED, run.statuses()["b"])
        advanceUntilIdle()
        assertEquals(listOf("a" to 0L, "b" to 5_000L, "c" to 7_000L), starts.at)
        assertTrue(run.snapshot().all { it.status == Status.DONE })
    }

    @Test
    fun `a lamp inside its restrike time waits, reported with when, and units ready sooner go first`() = runTest {
        val starts = Starts(this)
        val run = run(
            CommandRole.LAMP_ON,
            listOf(unit("hot", spacing = 1_000), unit("cold", spacing = 1_000)),
            starts,
            readyAt = mapOf("hot" to 60_000L),
        )
        launch { run.execute() }
        runCurrent()
        assertEquals(listOf("cold" to 0L), starts.at, "the unit that can strike now goes first")
        val hot = run.snapshot().single { it.unit.fixtureKey == "hot" }
        assertEquals(Status.WAITING, hot.status)
        assertEquals(60_000L, hot.untilMs)
        advanceTimeBy(59_000)
        assertEquals(1, starts.at.size, "not struck before its restrike time")
        advanceUntilIdle()
        assertEquals(listOf("cold" to 0L, "hot" to 60_000L), starts.at)
    }

    @Test
    fun `a refused unit is skipped by code and spends no spacing`() = runTest {
        val starts = Starts(this).apply { refuse["a"] = "COMMAND_BUSY" }
        val run = run(CommandRole.LAMP_ON, listOf(unit("a", spacing = 5_000), unit("b", spacing = 5_000)), starts)
        launch { run.execute() }
        advanceUntilIdle()
        assertEquals(listOf("b" to 0L), starts.at)
        val a = run.snapshot().single { it.unit.fixtureKey == "a" }
        assertEquals(Status.SKIPPED, a.status)
        assertEquals("COMMAND_BUSY", a.code)
    }

    @Test
    fun `a hold cut short is reported as cut short`() = runTest {
        val starts = Starts(this).apply { cutShort += "b" }
        val run = run(CommandRole.RESET, listOf(unit("a"), unit("b")), starts)
        launch { run.execute() }
        advanceUntilIdle()
        assertEquals(mapOf("a" to Status.DONE, "b" to Status.CUT_SHORT), run.statuses())
    }

    @Test
    fun `cancel stops what has not started and lets a running hold finish`() = runTest {
        val starts = Starts(this, holdMs = 3_000)
        val run = run(CommandRole.LAMP_ON, listOf(unit("a", spacing = 5_000), unit("b", spacing = 5_000), unit("c", spacing = 5_000)), starts)
        launch { run.execute() }
        advanceTimeBy(1_000)
        run.cancel()
        advanceUntilIdle()
        assertEquals(listOf("a" to 0L), starts.at)
        assertEquals(mapOf("a" to Status.DONE, "b" to Status.CANCELLED, "c" to Status.CANCELLED), run.statuses())
        assertTrue(run.finished && run.cancelled)
    }
}
