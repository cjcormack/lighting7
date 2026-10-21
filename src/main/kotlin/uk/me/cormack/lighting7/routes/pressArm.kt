package uk.me.cormack.lighting7.routes

import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.state.State
import java.util.UUID

/**
 * Which arm a palette press is on, asked **before** the press: true when
 * `ProgrammerLayerStack.toggle` of [sourceUuid] on [targets] would take the record *off*.
 *
 * The selection's attribute mask is a fact about what a press puts **on** (multi-screen plan D5): a
 * palette outside the mask is refused because nothing would land. That reading does not apply to a
 * press that is taking a layer off — `toggle`'s own contract says the mask "is deliberately **not**
 * part of the 'already on' comparison: a pad that re-pressed with a different mask should still take
 * its layer off" — so the doors test the mask only when this answers false. Without it a lit
 * Intensity pad could not be released while a Colour marquee stood, and on a surface the button
 * would read dead. A Look press takes no mask at all (absolute-looks plan D3), so no door asks.
 *
 * The rule is [uk.me.cormack.lighting7.fx.LayerPress.wouldRelease] — the same decision
 * [uk.me.cormack.lighting7.fx.LayerPress.press] makes — asked over the programmer's stack through
 * [uk.me.cormack.lighting7.fx.ProgrammerLayerStack.wouldRelease], so the arm a door tests is the arm
 * `toggle` takes. A stored cue's press (`routes/cueLayerPress.kt`) asks the same function over the
 * cue's layers. For a press naming heads, "already on" is the record covering every pressed head
 * ([uk.me.cormack.lighting7.fx.TargetCoverage.covers], so a cell pressed under a layer on its parent
 * reads as on, and a parent pressed over a layer on its cells does not — `PressArmCellsTest` guards
 * that); for a press naming none, a layer of this record with the same (empty) targets.
 * `BuskPressRouteTest` holds a guard asserting the arm and `toggle` agree on both arms.
 */
internal fun pressWouldRelease(state: State, sourceUuid: UUID, requested: List<CueTargetDto>): Boolean =
    state.show.programmerLayerStack.wouldRelease(sourceUuid, requested)
