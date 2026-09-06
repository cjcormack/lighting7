package uk.me.cormack.lighting7.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.show.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The desk selection's head-by-head rules: a group and its members are two spellings of one
 * selection, toggling off narrows, a no-op mutation emits nothing, and a reload prunes.
 */
class DeskSelectionTest {

    private val universe = Universe(0, 0)
    private val hex1 = CueTargetDto("fixture", "hex-1")
    private val hex2 = CueTargetDto("fixture", "hex-2")
    private val hex3 = CueTargetDto("fixture", "hex-3")
    private val wash = CueTargetDto("group", "front-wash")

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

    @Test
    fun `set keeps order and collapses duplicates`() {
        val s = selection()
        s.set(listOf(hex2, hex1, hex2))
        assertEquals(listOf(hex2, hex1), s.targets.value)
    }

    @Test
    fun `toggle appends a target that is not covered and takes it off when it is`() {
        val s = selection()
        assertTrue(s.toggle(hex1))
        assertEquals(listOf(hex1), s.targets.value)
        assertFalse(s.toggle(hex1))
        assertEquals(emptyList(), s.targets.value)
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
        assertEquals(listOf(hex3), s.targets.value)
    }

    @Test
    fun `toggling one member off narrows a group entry to the members left behind`() {
        val s = selection()
        s.set(listOf(wash, hex3))
        assertFalse(s.toggle(hex1))
        assertEquals(listOf(hex2, hex3), s.targets.value, "the group is respelt as its remaining member")
        assertTrue(s.toggle(hex1))
        assertEquals(listOf(hex2, hex3, hex1), s.targets.value)
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
        val seen = mutableListOf<List<CueTargetDto>>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch { s.targets.collect { seen += it } }
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
        assertEquals(listOf(hex3, hex1), s.targets.value)
    }

    @Test
    fun `prune with no show leaves the selection alone`() {
        val s = selection(fixtures = null)
        s.set(listOf(hex1))
        s.prune()
        assertEquals(listOf(hex1), s.targets.value)
    }
}
