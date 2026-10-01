package uk.me.cormack.lighting7.show

import org.junit.Test
import uk.me.cormack.lighting7.models.ElementStates
import uk.me.cormack.lighting7.show.SceneryResolver.Change
import uk.me.cormack.lighting7.show.SceneryResolver.Cue
import uk.me.cormack.lighting7.show.SceneryResolver.CueChange
import uk.me.cormack.lighting7.show.SceneryResolver.LiveStack
import uk.me.cormack.lighting7.show.SceneryResolver.Source
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Scenery resolution (stage-view plan session 8): base, then each live stack's set and its cues
 * tracked down to the live one (stacks oldest GO first), then the Looks the live cues layer, then the
 * programmer's Looks — per element and per state.
 */
class SceneryResolverTest {
    private val tabs = SceneryResolver.Element(1, UUID.randomUUID(), "House tabs", setOf("visible", "open"), ElementStates(true, 1.0, null))
    private val moon = SceneryResolver.Element(2, UUID.randomUUID(), "Moon", setOf("visible", "trimM"), ElementStates(true, null, 7.0))
    private val sofa = SceneryResolver.Element(3, UUID.randomUUID(), "Sofa", setOf("visible"), ElementStates(true, null, null))
    private val elements = listOf(tabs, moon, sofa)

    private fun open(v: Double) = ElementStates(open = v)
    private fun trim(v: Double) = ElementStates(trimM = v)
    private fun visible(v: Boolean) = ElementStates(visible = v)

    /** The prototype's Act 1: Q1 closes the tabs, Q2 draws them over 4 s, Q3 flies the moon in, Q4 leaves the set alone. */
    private val act1 = listOf(
        Cue(11, "1", listOf(CueChange(1, open(0.0), 0))),
        Cue(12, "2", listOf(CueChange(1, open(1.0), 4000))),
        Cue(13, "3", listOf(CueChange(2, trim(3.0), 6000))),
        Cue(14, "4", emptyList()),
    )

    private fun liveAt(cueId: Int, stackId: Int = 1, goSeq: Long = 1, set: List<Change> = emptyList(), looks: List<Int> = emptyList()) =
        LiveStack(stackId, goSeq, set, act1.take(act1.indexOfFirst { it.cueId == cueId } + 1), looks)

    @Test
    fun `nothing live is every element's base`() {
        val r = SceneryResolver.resolve(elements, emptyList(), emptyMap(), emptyList())
        assertEquals(ElementStates(true, 1.0, null), r.getValue(1).state)
        assertEquals(Source.Base, r.getValue(1).sources["open"])
    }

    @Test
    fun `scenery tracks from the top of the list, so GO TO Q4 lands the set`() {
        val r = SceneryResolver.resolve(elements, listOf(liveAt(14)), emptyMap(), emptyList())
        assertEquals(1.0, r.getValue(1).state.open, "Q2 drew the tabs and nothing since closed them")
        assertEquals(3.0, r.getValue(2).state.trimM, "Q3 flew the moon in")
        val src = r.getValue(2).sources["trimM"] as Source.CueRow
        assertEquals(13 to 6000L, src.cueId to src.transitionMs)

        val atQ1 = SceneryResolver.resolve(elements, listOf(liveAt(11)), emptyMap(), emptyList())
        assertEquals(0.0, atQ1.getValue(1).state.open)
        assertEquals(7.0, atQ1.getValue(2).state.trimM, "the moon has not moved yet")
    }

    @Test
    fun `a stack's set sits under its cues and lets go with the stack`() {
        val set = listOf(Change(3, visible(false)), Change(1, open(0.5)))
        val r = SceneryResolver.resolve(elements, listOf(liveAt(11, set = set)), emptyMap(), emptyList())
        assertEquals(false, r.getValue(3).state.visible, "the set hides the sofa")
        assertEquals(0.0, r.getValue(1).state.open, "Q1's change wins over the set")
        assertEquals(Source.StackSet(1), r.getValue(3).sources["visible"])
    }

    @Test
    fun `live Looks win over every cue — the programmer's above the ones a cue layers`() {
        val looks = mapOf(
            100 to listOf(Change(2, trim(3.0)), Change(1, open(0.2))),
            200 to listOf(Change(1, open(0.8))),
        )
        val r = SceneryResolver.resolve(elements, listOf(liveAt(12, looks = listOf(100))), looks, listOf(200))
        assertEquals(3.0, r.getValue(2).state.trimM, "the cue's layered Look flies the moon in over Q1–Q2")
        assertEquals(0.8, r.getValue(1).state.open, "the programmer's Look is on top")
        assertEquals(Source.ProgrammerLook(200), r.getValue(1).sources["open"])
        assertEquals(Source.CueLook(1, 100), r.getValue(2).sources["trimM"])
    }

    @Test
    fun `with two stacks live the most recently GO'd wins per element, not the stack's id`() {
        val other = LiveStack(stackId = 0, goSeq = 5, set = listOf(Change(1, open(0.3))), cues = emptyList(), layeredLookIds = emptyList())
        val r = SceneryResolver.resolve(elements, listOf(liveAt(12, stackId = 1, goSeq = 2), other), emptyMap(), emptyList())
        assertEquals(0.3, r.getValue(1).state.open, "stack 0 went last")

        val reversed = SceneryResolver.resolve(elements, listOf(liveAt(12, stackId = 1, goSeq = 9), other), emptyMap(), emptyList())
        assertEquals(1.0, reversed.getValue(1).state.open, "stack 1 went last")
    }

    @Test
    fun `a state the element's kind cannot take is ignored`() {
        val r = SceneryResolver.resolve(elements, emptyList(), mapOf(1 to listOf(Change(3, ElementStates(open = 0.0, visible = false)))), listOf(1))
        assertEquals(ElementStates(false, null, null), r.getValue(3).state)
    }

    @Test
    fun `a cue's card sees what it tracks, and where from`() {
        val set = listOf(Change(3, visible(false)))
        val tracked = SceneryResolver.trackedAt(elements, set, act1, 13).associate { (r, src) -> r.element.name to src }
        assertEquals(setOf("House tabs", "Sofa"), tracked.keys, "the moon is Q3's own change")
        assertEquals("2", (tracked.getValue("House tabs")["open"] as Source.CueRow).cueLabel)
        assertTrue(tracked.getValue("Sofa")["visible"] is Source.StackSet)
    }
}
