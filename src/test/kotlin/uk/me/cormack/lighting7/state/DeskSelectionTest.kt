package uk.me.cormack.lighting7.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fx.PropertyMaskGroup
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.show.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desk selection's head-by-head rules: a group and its members are two spellings of one
 * selection, toggling off narrows, a no-op mutation emits nothing, and a reload prunes — and the
 * mask rules that make the selection and its attribute mask one fact (multi-screen plan D2):
 * `set` replaces the whole fact, `toggle` keeps the mask, `clear` drops it, `prune` keeps it.
 */
class DeskSelectionTest {

    private val universe = Universe(0, 0)
    private val hex1 = CueTargetDto("fixture", "hex-1")
    private val hex2 = CueTargetDto("fixture", "hex-2")
    private val hex3 = CueTargetDto("fixture", "hex-3")
    private val wash = CueTargetDto("group", "front-wash")
    private val screen1 = SelectionSource.window("Screen 1")
    private val screen2 = SelectionSource.window("Screen 2")
    private val colour = setOf(PropertyMaskGroup.COLOUR)

    private fun fixtures(): Fixtures {
        val fixtures = Fixtures()
        fixtures.register {
            val h1 = addFixture(HexFixture(universe, "hex-1", "Hex 1", firstChannel = 1))
            val h2 = addFixture(HexFixture(universe, "hex-2", "Hex 2", firstChannel = 13))
            addFixture(HexFixture(universe, "hex-3", "Hex 3", firstChannel = 25))
            createGroup<HexFixture>("front-wash") { addSpread(listOf(h1, h2)) }
        }
        return fixtures
    }

    private fun selection(fixtures: Fixtures? = fixtures()) = DeskSelection { fixtures }

    private val DeskSelection.targets get() = state.value.targets

    // ─── Heads ──────────────────────────────────────────────────────────

    @Test
    fun `set keeps order and collapses duplicates`() {
        val s = selection()
        s.set(listOf(hex2, hex1, hex2))
        assertEquals(listOf(hex2, hex1), s.targets)
    }

    @Test
    fun `toggle appends a target that is not covered and takes it off when it is`() {
        val s = selection()
        assertTrue(s.toggle(hex1))
        assertEquals(listOf(hex1), s.targets)
        assertFalse(s.toggle(hex1))
        assertEquals(emptyList(), s.targets)
    }

    @Test
    fun `a group is covered by its members and covers them`() {
        val s = selection()
        s.set(listOf(hex1, hex2))
        assertTrue(s.covers(wash), "both members selected: the group reads as selected")
        assertTrue(s.covers(hex1))
        s.set(listOf(wash))
        assertTrue(s.covers(hex1), "the group selected: each member reads as selected")
        assertTrue(s.covers(hex2))
        assertFalse(s.covers(hex3))
        s.set(listOf(hex1))
        assertFalse(s.covers(wash), "one member is not the group")
    }

    @Test
    fun `toggling a covered group off takes its members out however they were spelt`() {
        val s = selection()
        s.set(listOf(hex1, hex2, hex3))
        assertFalse(s.toggle(wash))
        assertEquals(listOf(hex3), s.targets)
    }

    @Test
    fun `toggling one member off narrows a group entry to the members left behind`() {
        val s = selection()
        s.set(listOf(wash, hex3))
        assertFalse(s.toggle(hex1))
        assertEquals(listOf(hex2, hex3), s.targets, "the group is respelt as its remaining member")
        assertTrue(s.toggle(hex1))
        assertEquals(listOf(hex2, hex3, hex1), s.targets)
    }

    @Test
    fun `coverage expands groups and leaves an unresolvable group standing for itself`() {
        val s = selection()
        val ghost = CueTargetDto("group", "gone")
        s.set(listOf(wash, ghost))
        assertEquals(listOf(hex1, hex2, ghost), s.coverage())
    }

    @Test
    fun `a mutation that changes nothing emits nothing`() = runBlocking {
        val s = selection()
        val seen = mutableListOf<DeskSelection.Snapshot>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch { s.state.collect { seen += it } }
        assertEquals(1, seen.size, "the StateFlow replays its current value on subscribe")
        s.set(listOf(hex1))
        yield()
        assertEquals(2, seen.size)
        s.set(listOf(hex1))
        s.clear()
        s.clear()
        yield()
        assertEquals(3, seen.size, "the repeat set and the second clear are no-ops")
        job.cancel()
    }

    @Test
    fun `prune drops targets that no longer resolve and keeps the rest in order`() {
        val fixtures = fixtures()
        val s = selection(fixtures)
        s.set(listOf(hex3, CueTargetDto("group", "gone"), hex1, CueTargetDto("fixture", "hex-9")))
        s.prune()
        assertEquals(listOf(hex3, hex1), s.targets)
    }

    @Test
    fun `prune with no show leaves the selection alone`() {
        val s = selection(fixtures = null)
        s.set(listOf(hex1))
        s.prune()
        assertEquals(listOf(hex1), s.targets)
    }

    // ─── The mask and the mover (multi-screen plan D2, D7) ──────────────

    @Test
    fun `set replaces the whole fact — heads, mask and mover`() {
        val s = selection()
        s.set(listOf(hex1, hex2), colour, screen1)
        assertEquals(DeskSelection.Snapshot(listOf(hex1, hex2), colour, screen1), s.state.value)

        // A replace that names no mask is every attribute: a stale Colour mask must not ride onto
        // heads picked with no column in mind — the narrow-width picker, a surface's REPLACE.
        s.set(listOf(hex3), source = screen2)
        assertEquals(DeskSelection.Snapshot(listOf(hex3), null, screen2), s.state.value)
    }

    @Test
    fun `an empty or complete mask is no mask`() {
        val s = selection()
        s.set(listOf(hex1), emptySet())
        assertNull(s.state.value.families, "nothing named is every attribute")
        s.set(listOf(hex1), PropertyMaskGroup.entries.toSet())
        assertNull(s.state.value.families, "every group named is every attribute, as parseMaskGroups reads it")
    }

    @Test
    fun `toggle edits the heads, keeps the mask and stamps the mover`() {
        val s = selection()
        s.set(listOf(hex1), colour, screen1)

        assertTrue(s.toggle(hex2, SelectionSource.SURFACE))
        assertEquals(listOf(hex1, hex2), s.targets)
        assertEquals(colour, s.state.value.families, "a tap adds a head under the standing mask")
        assertEquals(SelectionSource.SURFACE, s.state.value.source, "the surface moved it last")

        assertFalse(s.toggle(hex1, screen2))
        assertEquals(listOf(hex2), s.targets)
        assertEquals(colour, s.state.value.families, "taking a head off keeps the mask too")
        assertEquals(screen2, s.state.value.source)
    }

    @Test
    fun `clear drops the heads, the mask and the mover`() {
        val s = selection()
        s.set(listOf(hex1), colour, screen1)
        s.clear()
        assertEquals(DeskSelection.Snapshot(), s.state.value)
    }

    @Test
    fun `prune keeps the mask and the mover`() {
        val s = selection()
        s.set(listOf(hex1, CueTargetDto("fixture", "hex-9")), colour, screen1)
        s.prune()
        assertEquals(DeskSelection.Snapshot(listOf(hex1), colour, screen1), s.state.value, "a repatch is not a gesture")
    }

    @Test
    fun `the same heads moved by another window is a change, and the same mover is not`() = runBlocking {
        val s = selection()
        s.set(listOf(hex1), colour, screen1)
        val seen = mutableListOf<DeskSelection.Snapshot>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch { s.state.collect { seen += it } }
        assertEquals(1, seen.size)

        s.set(listOf(hex1), colour, screen1)
        yield()
        assertEquals(1, seen.size, "the identical fact from the same window emits nothing")

        s.set(listOf(hex1), colour, screen2)
        yield()
        assertEquals(2, seen.size, "who moved it last is true information — the chip changes")
        assertEquals(screen2, seen.last().source)
        job.cancel()
    }
}
