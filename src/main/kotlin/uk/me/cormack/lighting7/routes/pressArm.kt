package uk.me.cormack.lighting7.routes

import uk.me.cormack.lighting7.fx.AppliedExtent
import uk.me.cormack.lighting7.fx.TargetCoverage
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.sameTargets
import uk.me.cormack.lighting7.state.State
import java.util.UUID

/**
 * Which arm a press is on, asked **before** the press: true when `ProgrammerLayerStack.toggle` of
 * [sourceUuid] on [targets] would take the record *off*.
 *
 * The selection's attribute mask is a fact about what a press puts **on** (multi-screen plan D5):
 * a template outside the mask is refused because nothing would land, a Look is narrowed to what
 * would. Neither reading applies to a press that is taking a layer off — `toggle`'s own contract
 * says the mask "is deliberately **not** part of the 'already on' comparison: a pad that re-pressed
 * with a different mask should still take its layer off" — so the doors test the mask only when
 * this answers false. Without it a lit Intensity pad could not be released while a Colour marquee
 * stood, and on a surface the button would read dead.
 *
 * This mirrors `ProgrammerLayerStack.toggle`'s deciding expression exactly, through the stack's
 * own reads rather than a copy of its rule: for a press naming heads, "already on" is the record
 * covering every pressed head, read off [uk.me.cormack.lighting7.fx.ProgrammerLayerStack.appliedState]
 * (whose `ALL` set is `toggle`'s `covered` plus group entries a pressed head can never be, since
 * [TargetCoverage.expand] has already replaced every resolvable group with its members); for a
 * press naming none, it is `toggle`'s twin — a layer of this record with the same (empty) targets.
 * "Covering" is [TargetCoverage.covers], so a cell pressed under a layer on its parent reads as
 * on, and a parent pressed over a layer on its cells does not — `PressArmCellsTest` guards that.
 * `BuskPressRouteTest` holds a guard asserting the two agree on both arms, so a change to `toggle`'s
 * comparison fails there rather than drifting here.
 */
internal fun pressWouldRelease(state: State, sourceUuid: UUID, targets: List<CueTargetDto>): Boolean {
    val layers = state.show.programmerStore.layers
    val coverage = TargetCoverage { state.show.fixtures }
    val pressed = coverage.expand(targets).toSet()
    if (pressed.isEmpty()) {
        return layers.any { it.source.uuid == sourceUuid && sameTargets(it.targets, targets) }
    }
    val covered = state.show.programmerLayerStack.appliedState(layers)
        .firstOrNull { it.source.uuid == sourceUuid }
        ?.targets
        ?.filter { it.extent == AppliedExtent.ALL }
        ?.mapTo(HashSet()) { it.target }
        .orEmpty()
    // `covers`, not `containsAll`: a cell pressed under a layer on its parent is covered by the
    // parent (busk-further plan D11), and the rule lives in [TargetCoverage] alone.
    return pressed.all { coverage.covers(covered, it) }
}
