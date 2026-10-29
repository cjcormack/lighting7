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
 * tracked down to the live one (stacks oldest GO first), then the Looks the live cues layer under each
 * live cue's own changes, then the programmer's Looks — per element and per state.
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
    fun `a cue's Looks sit over earlier cues, and the programmer's over everything a cue holds`() {
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
    fun `the live cue's own change beats the Looks it layers, on the states it sets`() {
        val looks = mapOf(100 to listOf(Change(1, ElementStates(visible = false, open = 0.2)), Change(2, trim(5.0))))
        val r = SceneryResolver.resolve(elements, listOf(liveAt(12, looks = listOf(100))), looks, emptyList())
        assertEquals(1.0, r.getValue(1).state.open, "Q2's own row, not its Look")
        assertEquals(Source.CueRow(1, 12, "2", 4000), r.getValue(1).sources["open"])
        assertEquals(false, r.getValue(1).state.visible, "a state Q2 does not set is still the Look's")
        assertEquals(Source.CueLook(1, 100), r.getValue(1).sources["visible"])
        assertEquals(5.0, r.getValue(2).state.trimM, "the Look flies the moon in")
    }

    @Test
    fun `a cue's Look still beats what earlier cues left`() {
        val looks = mapOf(100 to listOf(Change(1, open(0.2)), Change(2, trim(5.0))))
        val r = SceneryResolver.resolve(elements, listOf(liveAt(13, looks = listOf(100))), looks, emptyList())
        assertEquals(0.2, r.getValue(1).state.open, "Q2's row is only tracked at Q3")
        assertEquals(Source.CueLook(1, 100), r.getValue(1).sources["open"])
        assertEquals(3.0, r.getValue(2).state.trimM, "Q3's own row")
        assertEquals(Source.CueRow(1, 13, "3", 6000), r.getValue(2).sources["trimM"])
    }

    @Test
    fun `a newer stack's Look beats an older stack's live cue`() {
        val looks = mapOf(100 to listOf(Change(1, open(0.2))))
        val other = LiveStack(stackId = 0, goSeq = 5, set = emptyList(), cues = emptyList(), layeredLookIds = listOf(100))
        val r = SceneryResolver.resolve(elements, listOf(liveAt(12, stackId = 1, goSeq = 2, looks = listOf(100)), other), looks, emptyList())
        assertEquals(0.2, r.getValue(1).state.open, "stack 0 went last and layers the Look")
        assertEquals(Source.CueLook(0, 100), r.getValue(1).sources["open"])
    }

    @Test
    fun `a cue whose own change a newer stack displaced does not hold back its Look`() {
        val looks = mapOf(100 to listOf(Change(1, open(0.2))))
        val newer = LiveStack(stackId = 0, goSeq = 5, set = emptyList(), cues = listOf(Cue(90, "Loose", listOf(CueChange(1, open(0.0), 0)))), layeredLookIds = emptyList())
        val r = SceneryResolver.resolve(elements, listOf(liveAt(12, stackId = 1, goSeq = 2, looks = listOf(100)), newer), looks, emptyList())
        assertEquals(0.2, r.getValue(1).state.open, "Q2's Look over the newer cue, as a live Look always was")
        assertEquals(Source.CueLook(1, 100), r.getValue(1).sources["open"])
    }

    @Test
    fun `a cue live on two tracks keeps its own change over the Looks it layers`() {
        val looks = mapOf(100 to listOf(Change(1, open(0.2))))
        val q2 = act1[1]
        val loose = LiveStack(stackId = -12, goSeq = 5, set = emptyList(), cues = listOf(q2), layeredLookIds = listOf(100))
        val alsoInStack2 = LiveStack(stackId = 2, goSeq = 5, set = emptyList(), cues = listOf(q2), layeredLookIds = listOf(100))
        for (other in listOf(loose, alsoInStack2)) {
            val r = SceneryResolver.resolve(elements, listOf(liveAt(12, stackId = 1, goSeq = 2, looks = listOf(100)), other), looks, emptyList())
            assertEquals(1.0, r.getValue(1).state.open, "Q2 also live on stack ${other.stackId}")
            assertEquals(Source.CueRow(other.stackId, 12, "2", 4000), r.getValue(1).sources["open"])
        }
    }

    @Test
    fun `an older stack's Look beats a newer cue's own change, whatever that cue layers`() {
        val looks = mapOf(100 to listOf(Change(1, open(0.2))), 101 to listOf(Change(1, open(0.9))))
        val older = LiveStack(stackId = 0, goSeq = 1, set = emptyList(), cues = emptyList(), layeredLookIds = listOf(100))
        for (own in listOf(emptyList(), listOf(101))) {
            val r = SceneryResolver.resolve(elements, listOf(older, liveAt(12, stackId = 1, goSeq = 5, looks = own)), looks, emptyList())
            assertEquals(0.2, r.getValue(1).state.open, "Q2 layering $own")
            assertEquals(Source.CueLook(0, 100), r.getValue(1).sources["open"])
        }
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

    // ─── The programmer's own scenery (scenery-programmer plan D2, D4) ──────────────────────

    @Test
    fun `the programmer's scenery sits above a pressed Look and above a cue`() {
        val looks = mapOf(200 to listOf(Change(2, trim(5.0)), Change(1, open(0.25))))
        val held = listOf(SceneryResolver.Held(2, trim(1.5), 3000), SceneryResolver.Held(1, open(0.6), null))
        // Q3 flies the moon to 3 on its own clock; the pressed Look says 5; the programmer says 1.5.
        val r = SceneryResolver.resolve(elements, listOf(liveAt(13)), looks, listOf(200), held)
        assertEquals(1.5, r.getValue(2).state.trimM)
        assertEquals(Source.Programmer(3000), r.getValue(2).sources["trimM"])
        assertEquals(0.6, r.getValue(1).state.open)
        assertEquals(Source.Programmer(null), r.getValue(1).sources["open"])
        // Only the states held: the moon's visibility is still the base's.
        assertEquals(Source.Base, r.getValue(2).sources["visible"])

        val withoutHands = SceneryResolver.resolve(elements, listOf(liveAt(13)), looks, listOf(200))
        assertEquals(5.0, withoutHands.getValue(2).state.trimM, "the Look over the cue, as before")
    }

    @Test
    fun `holderOf names the highest tier holding any state, for every tier`() {
        val set = listOf(Change(3, visible(false)))
        val looks = mapOf(100 to listOf(Change(3, visible(true))), 200 to listOf(Change(1, open(0.5))))
        fun holder(stacks: List<LiveStack>, lookScenery: Map<Int, List<Change>>, pl: List<Int>, held: List<SceneryResolver.Held>, id: Int) =
            SceneryResolver.holderOf(SceneryResolver.resolve(elements, stacks, lookScenery, pl, held).getValue(id))

        assertEquals(Source.Base, holder(emptyList(), emptyMap(), emptyList(), emptyList(), 3))
        assertEquals(Source.StackSet(1), holder(listOf(liveAt(11, set = set)), emptyMap(), emptyList(), emptyList(), 3))
        assertEquals(Source.CueRow(1, 12, "2", 4000), holder(listOf(liveAt(12)), emptyMap(), emptyList(), emptyList(), 1))
        assertEquals(Source.CueLook(1, 100), holder(listOf(liveAt(11, set = set, looks = listOf(100))), looks, emptyList(), emptyList(), 3))
        assertEquals(Source.ProgrammerLook(200), holder(listOf(liveAt(12)), looks, listOf(200), emptyList(), 1))
        assertEquals(Source.Programmer(null), holder(listOf(liveAt(12)), looks, listOf(200), listOf(SceneryResolver.Held(1, visible(false), null)), 1))
        // A tier holding one state outranks a lower tier holding another: the moon's trim from Q3,
        // its visibility the base's, is the cue's.
        assertEquals(Source.CueRow(1, 13, "3", 6000), holder(listOf(liveAt(13)), emptyMap(), emptyList(), emptyList(), 2))
    }

    @Test
    fun `the programmer's state on a key the element does not take is ignored`() {
        val r = SceneryResolver.resolve(elements, emptyList(), emptyMap(), emptyList(), listOf(SceneryResolver.Held(3, open(0.0), null)))
        assertEquals(sofa.base, r.getValue(3).state)
        assertEquals(Source.Base, SceneryResolver.holderOf(r.getValue(3)))
    }
}
