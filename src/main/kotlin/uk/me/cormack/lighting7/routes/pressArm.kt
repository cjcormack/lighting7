package uk.me.cormack.lighting7.routes

import uk.me.cormack.lighting7.fx.PropertyMaskGroup
import uk.me.cormack.lighting7.models.CueTargetDto
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

/** How a Look press lands: refused, or the layer's targets and mask. */
internal sealed interface LookPressResolution {
    data class Refused(val message: String, val code: String) : LookPressResolution

    /** [propertyMask] and [skippedFamilies] are the on arm's; an off press carries neither. */
    data class Ready(val targets: List<CueTargetDto>, val propertyMask: String?, val skippedFamilies: List<String>) : LookPressResolution
}

/**
 * A Look press's targets and mask, decided arm first — for the cue editor's press, `/looks/{id}/toggle`
 * and the busk pad: a press that would take the Look off is refused neither by its targets
 * ([resolveLookToggleTargets]) nor by the mask ([resolveLookMask]). [wouldRelease] asks the arm over the door's stack. [keepUntargeted] keeps a
 * press naming no targets naming none — a cue layer of the Look's own fixtures, which follows the
 * Look as it is edited — where the programmer names them.
 */
internal fun resolveLookPress(
    requested: List<CueTargetDto>,
    look: LookToggleSource,
    families: Set<PropertyMaskGroup>?,
    keepUntargeted: Boolean = false,
    wouldRelease: (List<CueTargetDto>) -> Boolean,
): LookPressResolution {
    val targets = when (val resolved = resolveLookToggleTargets(requested, look)) {
        is LookTargetResolution.Targets -> if (keepUntargeted && requested.isEmpty()) emptyList() else resolved.targets
        is LookTargetResolution.Refused ->
            if (requested.isEmpty() && wouldRelease(emptyList())) emptyList()
            else return LookPressResolution.Refused(resolved.message, resolved.code)
    }
    if (wouldRelease(targets)) return LookPressResolution.Ready(targets, propertyMask = null, skippedFamilies = emptyList())
    return when (val mask = resolveLookMask(families, look)) {
        is LookMaskResolution.Refused -> LookPressResolution.Refused(mask.message, mask.code)
        is LookMaskResolution.Masked -> LookPressResolution.Ready(targets, mask.propertyMask, mask.skippedFamilies)
    }
}
