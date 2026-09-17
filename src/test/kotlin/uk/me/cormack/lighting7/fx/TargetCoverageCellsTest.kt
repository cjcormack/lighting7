package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fixture.dmx.LedLightbar12PixelFixture
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.show.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The parent↔cell coverage rule of the busk-further plan (D11), stated once in [TargetCoverage]:
 * **a parent covers its own cells, and cells never cover their parent.** A cell is an ordinary
 * fixture-typed target whose key is an element key — resolved through `Fixtures`, never parsed.
 */
class TargetCoverageCellsTest {

    private val universe = Universe(0, 0)
    private val bar = CueTargetDto("fixture", "bar-1")
    private val hex = CueTargetDto("fixture", "hex-1")
    private fun cell(i: Int) = CueTargetDto("fixture", "bar-1.pixel-$i")
    private val cells = (0 until 12).map { cell(it) }

    private fun coverage(): TargetCoverage {
        val fixtures = Fixtures()
        fixtures.register {
            addFixture(LedLightbar12PixelFixture.Mode48Ch(universe, "bar-1", "Bar 1", 1))
            addFixture(HexFixture(universe, "hex-1", "Hex 1", firstChannel = 100))
        }
        return TargetCoverage { fixtures }
    }

    // ─── covers ─────────────────────────────────────────────────────────

    @Test
    fun `a parent covers its own cell`() {
        assertTrue(coverage().covers(setOf(bar), cell(3)))
    }

    @Test
    fun `cells never cover their parent, even all of them`() {
        val rule = coverage()
        assertFalse(rule.covers(cells.take(4).toSet(), bar))
        assertFalse(rule.covers(cells.toSet(), bar), "the parent has properties no cell holds")
    }

    @Test
    fun `a cell covers itself and nothing else does`() {
        val rule = coverage()
        assertTrue(rule.covers(setOf(cell(3)), cell(3)))
        assertFalse(rule.covers(setOf(cell(2)), cell(3)), "a sibling cell is not this cell")
        assertFalse(rule.covers(setOf(hex), cell(3)), "another fixture is not this cell's parent")
        assertFalse(rule.covers(emptySet(), cell(3)))
    }

    @Test
    fun `a key that does not resolve is covered only by itself`() {
        val rule = coverage()
        val gone = CueTargetDto("fixture", "gone")
        val goneCell = CueTargetDto("fixture", "gone.pixel-1")
        assertFalse(rule.covers(setOf(gone), goneCell), "no parent to resolve through, no parsing of the key")
        assertTrue(rule.covers(setOf(goneCell), goneCell))
    }

    @Test
    fun `expand passes a cell through unchanged`() {
        assertEquals(listOf(cell(3)), coverage().expand(listOf(cell(3))))
    }

    // ─── narrow ─────────────────────────────────────────────────────────

    @Test
    fun `narrow rewrites a parent as the cells the press did not name`() {
        val remaining = coverage().narrow(listOf(bar), cells.take(4).toSet())
        assertEquals(cells.drop(4), remaining)
    }

    @Test
    fun `narrow of every cell empties the parent`() {
        assertEquals(emptyList(), coverage().narrow(listOf(bar), cells.toSet()))
    }

    @Test
    fun `narrow takes a held cell off when its parent is pressed`() {
        val remaining = coverage().narrow(listOf(cell(0), cell(1), hex), setOf(bar))
        assertEquals(listOf(hex), remaining)
    }

    @Test
    fun `narrow leaves a parent the press does not touch by identity`() {
        val held = listOf(bar, hex)
        val remaining = coverage().narrow(held, setOf(CueTargetDto("fixture", "hex-2")))
        assertSame(held, remaining)
    }

    @Test
    fun `narrow keeps an untouched parent spelled as itself beside a narrowed one`() {
        val remaining = coverage().narrow(listOf(bar, hex), setOf(cell(0)))
        assertEquals(cells.drop(1) + hex, remaining)
    }
}
