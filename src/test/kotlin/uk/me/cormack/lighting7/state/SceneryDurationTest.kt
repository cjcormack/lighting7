package uk.me.cormack.lighting7.state

import org.junit.Test
import uk.me.cormack.lighting7.models.ElementStates
import uk.me.cormack.lighting7.show.SceneryResolver
import uk.me.cormack.lighting7.show.SceneryResolver.Source
import java.util.UUID
import kotlin.test.assertEquals

/**
 * How long a scenery move takes (scenery-programmer plan D6, `SceneryService.durationFor`): a GO'd
 * cue's own transition, the programmer's fade over the piece's `travelS`, `travelS` scaled by the
 * share of the travel moved, and a snap with neither.
 */
class SceneryDurationTest {
    /** House tabs, 4 s closed to drawn. */
    private val tabs = SceneryResolver.Element(1, UUID.randomUUID(), "House tabs", setOf("visible", "open"), ElementStates(true, 0.0, null), travelS = 4.0)
    /** The moon: in at 3 m (its Z), out at 7 m (its stored trim), 8 s between. */
    private val moon = SceneryResolver.Element(2, UUID.randomUUID(), "Moon", setOf("visible", "trimM"), ElementStates(true, null, 7.0), travelS = 8.0, inTrimM = 3.0)
    private val snapping = tabs.copy(travelS = null)

    private fun resolved(element: SceneryResolver.Element, state: ElementStates, source: Source) =
        SceneryResolver.Resolved(element, state, SceneryResolver.keysOf(state).associateWith { source } + element.keys.filter { it !in SceneryResolver.keysOf(state) }.associateWith { Source.Base })

    private val drawn = ElementStates(true, 1.0, null)
    private val closed = ElementStates(true, 0.0, null)
    private val cue = Source.CueRow(stackId = 7, cueId = 70, cueLabel = "14", transitionMs = 2500)

    @Test
    fun `a change of the cue just GO'd keeps its own transition, whatever the travel`() {
        val r = resolved(tabs, drawn, cue)
        assertEquals(2500, SceneryService.durationFor(r, closed, gos = mapOf(7 to 70)))
        assertEquals(4000, SceneryService.durationFor(r, closed, gos = emptyMap()), "GO TO landing an earlier cue's change travels")
        assertEquals(0, SceneryService.durationFor(resolved(snapping, drawn, cue), closed, gos = emptyMap()), "and snaps with no travel")
    }

    @Test
    fun `a programmer move takes its fade when above 0, else the piece's travel`() {
        assertEquals(1500, SceneryService.durationFor(resolved(tabs, drawn, Source.Programmer(1500)), closed, emptyMap()))
        assertEquals(4000, SceneryService.durationFor(resolved(tabs, drawn, Source.Programmer(0)), closed, emptyMap()))
        assertEquals(4000, SceneryService.durationFor(resolved(tabs, drawn, Source.Programmer(null)), closed, emptyMap()))
        assertEquals(1500, SceneryService.durationFor(resolved(snapping, drawn, Source.Programmer(1500)), closed, emptyMap()), "a fade moves even a piece with no travel")
    }

    @Test
    fun `travel is scaled by the share moved — open for a drape, the trim over in to out for a flown piece`() {
        val half = ElementStates(true, 0.5, null)
        assertEquals(2000, SceneryService.durationFor(resolved(tabs, half, Source.ProgrammerLook(9)), closed, emptyMap()))
        assertEquals(1000, SceneryService.durationFor(resolved(tabs, ElementStates(true, 0.75, null), Source.StackSet(7)), half, emptyMap()))
        // The moon from out (7) to half way (5): 2 m of a 4 m travel, 4 s of 8.
        assertEquals(4000, SceneryService.durationFor(resolved(moon, ElementStates(true, null, 5.0), Source.Programmer(null)), moon.base, emptyMap()))
        // In equals out: any move is a full travel.
        val level = moon.copy(inTrimM = 7.0)
        assertEquals(8000, SceneryService.durationFor(resolved(level, ElementStates(true, null, 6.9), Source.Programmer(null)), level.base, emptyMap()))
    }

    @Test
    fun `with neither a cue's clock, a fade nor a travel the move snaps, and visible never travels`() {
        assertEquals(0, SceneryService.durationFor(resolved(snapping, drawn, Source.ProgrammerLook(9)), closed, emptyMap()))
        assertEquals(0, SceneryService.durationFor(resolved(tabs, ElementStates(false, 0.0, null), Source.ProgrammerLook(9)), closed, emptyMap()))
        // Unchanged: nothing moved.
        assertEquals(0, SceneryService.durationFor(resolved(tabs, closed, Source.Programmer(3000)), closed, emptyMap()))
    }

    @Test
    fun `a piece the programmer let go on a fade flies home on it, else on its travel`() {
        val home = resolved(tabs, closed, Source.Base)
        assertEquals(3000, SceneryService.durationFor(home, drawn, emptyMap(), releasedFadeMs = 3000))
        assertEquals(4000, SceneryService.durationFor(home, drawn, emptyMap(), releasedFadeMs = null))
        // A GO'd cue's own clock still wins over a release.
        assertEquals(2500, SceneryService.durationFor(resolved(tabs, closed, cue), drawn, mapOf(7 to 70), releasedFadeMs = 3000))
    }
}
