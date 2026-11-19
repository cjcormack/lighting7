package uk.me.cormack.lighting7.state

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import uk.me.cormack.lighting7.dmx.ParkSource
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.Source4RevolutionFixture
import uk.me.cormack.lighting7.show.Fixtures
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** A hold whose own coroutine fails still ends, so nobody waits on it and the fixture is free again. */
class CommandOutputHoldFailureTest {

    private class FailingParks : ParkSource {
        val failing = AtomicBoolean(false)
        override fun getParkedValue(universe: Int, channel: Int): UByte? = null
        override fun isParked(universe: Int, channel: Int): Boolean {
            if (failing.get()) throw IllegalStateException("park lookup failed")
            return false
        }
    }

    @Test
    fun `a hold that throws ends interrupted and frees the fixture`() {
        val fixtures = Fixtures()
        fixtures.register {
            addFixture(Source4RevolutionFixture.BaseFrame31Ch(Universe(0, 0), "rev", "Rev", firstChannel = 1))
        }
        val parks = FailingParks()
        val output = CommandOutput(fixtures, parks, isBlind = { false })
        output.holdMsFor = { 5_000 }
        try {
            val started = assertIs<CommandOutput.Outcome.Started>(output.run("rev", "reset"))
            parks.failing.set(true)

            val ended = runBlocking { withTimeoutOrNull(2_000) { started.running.done.await() } }
            assertEquals(CommandOutput.Ended.INTERRUPTED, ended, "the hold never ended")

            parks.failing.set(false)
            assertIs<CommandOutput.Outcome.Started>(output.run("rev", "reset"), "the fixture is still busy")
        } finally {
            output.close()
        }
    }
}
