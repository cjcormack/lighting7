package uk.me.cormack.lighting7.midi

import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.fx.FxInstance
import uk.me.cormack.lighting7.fx.FxSpeed
import uk.me.cormack.lighting7.fx.MasterClock
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.routes.BuskRigRequest
import uk.me.cormack.lighting7.routes.BuskRigRowInput
import uk.me.cormack.lighting7.routes.BuskRigTileInput
import uk.me.cormack.lighting7.routes.BareFxOutcome
import uk.me.cormack.lighting7.routes.ProgrammerFxRequest
import uk.me.cormack.lighting7.routes.pressBareFx
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The surface's FX speed (fx-speed plan D13): every Local FX painting a selected head, each whole —
 * an encoder a quarter of an octave a detent from where its ring shows the lead FX, a fader setting
 * every one from ×¼ to ×4.
 */
class SelectionFxSpeedTest : RouteIntegrationTest() {

    private fun seed() {
        for ((i, key) in listOf("d1", "d2", "d3").withIndex()) {
            LocateTestSupport.seedFixture(state, projectId, "generic-dimmer", key, i + 1)
        }
        state.show.fixtures.patchListChanged()
    }

    private fun fixture(key: String) = CueTargetDto("fixture", key)

    /** A Local Wave on [keys] at [ticks] master ticks a cycle; [stack] adds it over the others. */
    private fun wave(vararg keys: String, ticks: Int, stack: Boolean = false): Long {
        val request = ProgrammerFxRequest(
            fxType = "Wave",
            attribute = "dimmer",
            targets = keys.map(::fixture),
            cycle = ticks.toDouble() / MasterClock.TICKS_PER_BEAT,
            stack = stack,
        )
        return assertIs<BareFxOutcome.Done>(pressBareFx(state, request, null)).response.fxId
    }

    private fun fx(id: Long): FxInstance = state.show.fxEngine.getFx(id)!!

    private fun ticksOf(id: Long) = Math.round(fx(id).timing.cycle * MasterClock.TICKS_PER_BEAT).toInt()

    private val speed get() = state.selectionFxSpeed

    @Test
    fun `an encoder detent moves each a quarter of an octave, each stopping at its own power of two`() = testApplication {
        seed()
        mountTestApp(state)
        val a = wave("d1", ticks = 96) // ×1
        val b = wave("d2", ticks = 53) // ×1.81
        val c = wave("d3", ticks = 96) // not selected
        state.deskSelection.set(listOf(fixture("d1"), fixture("d2")))
        assertEquals(1.0, speed.leadSpeed(), "d1 is first in rig order")

        speed.turn((FxSpeed.ringPosition(1.0) + 1).toUByte(), encoder = true)
        assertEquals(81, ticksOf(a), "×1 a quarter of an octave up is ×1.19, 81 whole ticks")
        assertEquals(48, ticksOf(b), "×1.81 stops at ×2 rather than passing it")
        assertEquals(96, ticksOf(c), "an FX on no selected head is left alone")

        // Between snaps the two keep their ratio: a detent down moves each back a quarter.
        val shown = FxSpeed.ringPosition(speed.leadSpeed()!!)
        speed.turn((shown - 1).toUByte(), encoder = true)
        assertTrue(abs(ticksOf(a) - 96) <= 1, "back to ×1, to the tick: ${ticksOf(a)}")
        val ratio = FxSpeed.runningSpeed(fx(b).timing.cycle, false) / FxSpeed.runningSpeed(fx(a).timing.cycle, false)
        assertTrue(abs(ratio - 2.0.pow(0.75)) < 0.01, "×2 a quarter down beside ×1: $ratio")
    }

    @Test
    fun `the encoder reaches the whole range, past the slider's track to ×32`() = testApplication {
        seed()
        mountTestApp(state)
        val a = wave("d1", ticks = 6) // ×16
        state.deskSelection.set(listOf(fixture("d1")))
        assertEquals(80, FxSpeed.ringPosition(speed.leadSpeed()!!), "a step a quarter of an octave, ×1 at 64")
        for (detent in 1..8) {
            speed.turn((FxSpeed.ringPosition(speed.leadSpeed()!!) + 1).toUByte(), encoder = true)
        }
        assertEquals(3, ticksOf(a), "×32, a cycle of ⅛ beat")
        assertEquals(84, FxSpeed.ringPosition(speed.leadSpeed()!!))
    }

    @Test
    fun `a lead held at the end of the range re-shows the ring, so each detent stays a quarter of an octave`() = testApplication {
        seed()
        mountTestApp(state)
        wave("d1", ticks = 3) // ×32, the lead and the end of the range
        val other = wave("d2", ticks = 96) // ×1
        state.deskSelection.set(listOf(fixture("d1"), fixture("d2")))
        assertEquals(84, FxSpeed.ringPosition(speed.leadSpeed()!!))

        val before = speed.shown.value
        speed.turn(85u, encoder = true)
        assertEquals(81, ticksOf(other), "×1 a quarter of an octave up")
        assertNotEquals(before, speed.shown.value, "the device counted past the ring: it is shown 84 again")

        // Shown 84 again, the device's next detent is 85 once more: one more quarter, not two.
        speed.turn(85u, encoder = true)
        assertEquals(68, ticksOf(other), "×1.41, two quarters in all")
    }

    @Test
    fun `a turn far from the ring's position is taken whole, the jump rule being the publisher's`() = testApplication {
        seed()
        mountTestApp(state)
        val a = wave("d1", ticks = 96)
        state.deskSelection.set(listOf(fixture("d1")))
        // The publisher reshows a ring turned from a stale position (SoftTakeoverStateMachineTest), so
        // a turn that does arrive is read as its detents: 56 of them, past ×32.
        speed.turn(120u, encoder = true)
        assertEquals(3, ticksOf(a), "×32, the end of the range")
    }

    @Test
    fun `a fader sets every one from a quarter to four, stuck at the powers of two`() = testApplication {
        seed()
        mountTestApp(state)
        val a = wave("d1", ticks = 96)
        val b = wave("d2", ticks = 40)
        state.deskSelection.set(listOf(fixture("d1"), fixture("d2")))

        speed.turn(96u, encoder = false)
        assertEquals(48, ticksOf(a))
        assertEquals(48, ticksOf(b))
        speed.turn(0u, encoder = false)
        assertEquals(384, ticksOf(a), "×¼ is four bars")
        assertEquals(384, ticksOf(b))
    }

    @Test
    fun `a Local FX partly inside the selection changes whole, keeping its id`() = testApplication {
        seed()
        mountTestApp(state)
        val both = wave("d1", "d2", ticks = 96)
        state.deskSelection.set(listOf(fixture("d2")))

        speed.turn(96u, encoder = false)
        assertEquals(48, ticksOf(both), "the FX on d1 and d2 moves on both")
        assertEquals(setOf("d1", "d2"), state.show.fxEngine.paintedHeads(fx(both)).map { it.key }.toSet(), "nothing split")
    }

    @Test
    fun `the lead is the FX on the first selected head in rig order, the lowest Local rank where two paint it`() = testApplication {
        seed()
        mountTestApp(state)
        wave("d2", ticks = 96) // ×1
        wave("d1", ticks = 48) // ×2
        state.deskSelection.set(listOf(fixture("d2"), fixture("d1")))
        assertEquals(2.0, speed.leadSpeed(), "d1 comes first in rig order, whatever the selection's order")

        wave("d1", ticks = 24, stack = true) // ×4, ranked above
        assertEquals(2.0, speed.leadSpeed(), "the lower Local rank leads")
    }

    @Test
    fun `a rig change moves the lead without a new selection`() = testApplication {
        seed()
        mountTestApp(state)
        wave("d1", ticks = 96) // ×1
        wave("d2", ticks = 48) // ×2
        state.deskSelection.set(listOf(fixture("d1"), fixture("d2")))
        assertEquals(1.0, speed.leadSpeed())

        val ids = transaction(state.database) {
            listOf("d2", "d1").map { key -> DaoFixturePatch.find { DaoFixturePatches.key eq key }.first().id.value }
        }
        val rig = jsonClient().put("/api/rest/projects/$projectId/busk/rig") {
            contentType(ContentType.Application.Json)
            setBody(BuskRigRequest(listOf(BuskRigRowInput(name = "Back to front", tiles = ids.map { BuskRigTileInput(patchId = it) }))))
        }
        assertEquals(HttpStatusCode.OK, rig.status, rig.bodyAsText())
        assertEquals(2.0, speed.leadSpeed(), "d2 is first in the rig now")
    }

    @Test
    fun `with no Local FX on the selection the move is dropped and the ring reads nothing`() = testApplication {
        seed()
        mountTestApp(state)
        val a = wave("d1", ticks = 96)
        state.deskSelection.set(listOf(fixture("d3")))
        assertNull(speed.leadSpeed())
        speed.turn(127u, encoder = false)
        assertEquals(96, ticksOf(a))

        state.deskSelection.set(emptyList())
        assertNull(speed.leadSpeed())
    }

}
