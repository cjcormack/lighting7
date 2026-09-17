package uk.me.cormack.lighting7.routes

import io.ktor.server.testing.testApplication
import org.junit.Test
import uk.me.cormack.lighting7.fx.AppliedExtent
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.LayerSource
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.mountTestApp
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [pressWouldRelease] and `ProgrammerLayerStack.appliedState` under the parent↔cell rule
 * (busk-further plan D11): a layer on the whole bar covers a press on four of its cells, a layer
 * on four cells does not cover the whole bar. Both read `TargetCoverage.covers`; neither restates it.
 */
class PressArmCellsTest : RouteIntegrationTest() {

    private val bar = CueTargetDto("fixture", "bar-1")
    private fun cell(i: Int) = CueTargetDto("fixture", "bar-1.pixel-$i")
    private val fourCells = (0 until 4).map { cell(it) }

    private val uuid: UUID = UUID.fromString("7b2d0c1e-4a5f-4e1b-9c3d-0f6a8e2b1d47")
    private val source = LayerSource.look(41, uuid, "Bar Look")

    private fun seedBar() {
        LocateTestSupport.seedFixture(state, projectId, "led-lightbar-12-pixel-48ch", "bar-1", 1)
    }

    private fun applied(): Map<CueTargetDto, AppliedExtent> =
        state.show.programmerLayerStack.appliedState()
            .single { it.source.uuid == uuid }
            .targets.associate { it.target to it.extent }

    @Test
    fun `a cell press under a whole-bar layer reads as lit`() = testApplication {
        mountTestApp(state)
        seedBar()
        state.show.programmerLayerStack.add(source = source, targets = listOf(bar))

        assertTrue(pressWouldRelease(state, uuid, fourCells), "the bar covers its cells")

        val extents = applied()
        assertEquals(AppliedExtent.ALL, extents[bar])
        for (c in fourCells) assertEquals(AppliedExtent.ALL, extents[c], "cell $c reads ALL under its parent")
    }

    @Test
    fun `a whole-bar press over a cell layer adds`() = testApplication {
        mountTestApp(state)
        seedBar()
        state.show.programmerLayerStack.add(source = source, targets = fourCells)

        assertFalse(pressWouldRelease(state, uuid, listOf(bar)), "cells do not cover their parent")

        val extents = applied()
        assertEquals(AppliedExtent.SOME, extents[bar], "the parent reads partial over four of twelve cells")
        assertEquals(AppliedExtent.ALL, extents[cell(0)])
        assertNull(extents[cell(5)], "an unheld cell is not reported")
    }

    @Test
    fun `pressWouldRelease agrees with toggle on the cell arms`() = testApplication {
        mountTestApp(state)
        seedBar()
        val stack = state.show.programmerLayerStack

        fun check(targets: List<CueTargetDto>, expectRemoved: Boolean) {
            val predicted = pressWouldRelease(state, uuid, targets)
            val outcome = stack.toggle(source = source, targets = targets)
            assertEquals(predicted, outcome.action == "removed", "targets=$targets")
            assertEquals(expectRemoved, outcome.action == "removed", "targets=$targets")
        }
        check(listOf(bar), expectRemoved = false)   // on: the whole bar
        check(fourCells, expectRemoved = true)       // covered by the parent → off, narrowed to the rest
        val remaining = state.show.programmerStore.layers.single { it.source.uuid == uuid }.targets
        assertEquals((4 until 12).map { cell(it) }, remaining)
        check(listOf(bar), expectRemoved = false)   // eight cells do not cover the bar → on, and subsume them
        assertEquals(listOf(bar), state.show.programmerStore.layers.single { it.source.uuid == uuid }.targets)
        check(listOf(bar), expectRemoved = true)    // off
        assertTrue(state.show.programmerStore.layers.none { it.source.uuid == uuid })
    }

    @Test
    fun `a group whose member is held only through its cells reads partial, never all`() = testApplication {
        mountTestApp(state)
        seedBar()
        LocateTestSupport.seedGroup(state, projectId, "bars", "bar-1")
        val bars = CueTargetDto("group", "bars")

        state.show.programmerLayerStack.add(source = source, targets = fourCells)
        assertEquals(AppliedExtent.SOME, applied()[bars], "four of twelve cells")

        state.show.programmerLayerStack.release(setOf(uuid))
        state.show.programmerLayerStack.add(source = source, targets = (0 until 12).map { cell(it) })
        assertEquals(AppliedExtent.SOME, applied()[bars], "every cell held is still not the bar")

        state.show.programmerLayerStack.release(setOf(uuid))
        state.show.programmerLayerStack.add(source = source, targets = listOf(bar))
        assertEquals(AppliedExtent.ALL, applied()[bars])
    }
}
