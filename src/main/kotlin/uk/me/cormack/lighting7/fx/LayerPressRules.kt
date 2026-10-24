package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef
import java.util.UUID

/**
 * A per-fixture value palette's row targets, distinct, from its rows' targets ([rowTargets], null
 * for a deferred row); null for an effect palette, an empty one, or one with any deferred row.
 * Read off the registry's snapshot by the press and off the stored rows by the timeline.
 */
internal fun perFixtureRowTargetsOf(hasFx: Boolean, rowTargets: List<TargetRef?>): List<CueTargetDto>? {
    if (hasFx || rowTargets.isEmpty() || rowTargets.any { it == null }) return null
    return rowTargets.filterNotNull().distinct().map(::CueTargetDto)
}

/**
 * What a press of a palette needs to know before [LayerPress] runs, over any layer list: the targets
 * it presses, the palettes it replaces and the heads it replaces them on. Read from the palettes,
 * never from a store, so the programmer's stack ([ProgrammerLayerStack.toggle]) and a stored cue's
 * layers (`POST /cues/{id}/layers/press`) find them one way (apply-model plan §3.6).
 *
 * A palette snapshot can open a transaction, so a caller reads these before it takes any lock.
 */
internal class LayerPressRules(
    private val paletteRegistry: () -> PaletteRegistry,
    private val coverage: TargetCoverage,
) {
    /**
     * The targets a press of [sourceUuid] puts on: [targets] as given, except that a per-fixture
     * palette pressed with none names its own rows' targets — the heads it lands on — so the press,
     * its family replacement and the arm the doors test all read one set of heads.
     */
    fun pressTargets(sourceUuid: UUID, targets: List<CueTargetDto>): List<CueTargetDto> =
        if (targets.isNotEmpty()) targets else perFixtureRowTargets(sourceUuid) ?: targets

    /** A per-fixture value palette's row targets, distinct; null for any other record. */
    fun perFixtureRowTargets(uuid: UUID): List<CueTargetDto>? {
        val snapshot = paletteRegistry().snapshot(uuid) ?: return null
        return perFixtureRowTargetsOf(snapshot.effect != null, snapshot.rows.map { it.target })
    }

    /**
     * The palettes among [stack] an *on* press of [sourceUuid] replaces (apply-model D2, D6): every
     * other palette of its family and kind. [stack] is the uuids of the palette layers on the list;
     * empty for a Look ([isPalette] false) and for a palette with no family.
     */
    fun familyReplacements(isPalette: Boolean, sourceUuid: UUID, stack: Collection<UUID>): Set<UUID> {
        if (!isPalette) return emptySet()
        val kind = paletteKind(sourceUuid) ?: return emptySet()
        return stack.filterTo(HashSet()) { it != sourceUuid && paletteKind(it) == kind }
    }

    /**
     * The pressed heads a palette lands on, when that is fewer than the press names: a per-fixture
     * palette's rows name their own heads, so only the pressed heads its rows reach (or the rows'
     * cells within a pressed parent) are its to take off other palettes. Null — every pressed head —
     * for a generic palette, an effect palette, and one not loaded.
     */
    fun paletteHeads(uuid: UUID, targets: List<CueTargetDto>): Set<CueTargetDto>? {
        val rowHeads = coverage.expand(perFixtureRowTargets(uuid) ?: return null).toSet()
        return coverage.expand(targets).flatMapTo(HashSet()) { head ->
            if (coverage.covers(rowHeads, head)) listOf(head) else coverage.cells(head).filter { it in rowHeads }
        }
    }

    /**
     * Each palette layer naming no targets, by uuid, mapped to its per-fixture rows' heads — what
     * [LayerPress.Ops.boundHeads] answers. [unTargeted] is the uuids of the palette layers on the list
     * that name no targets.
     */
    fun boundHeads(unTargeted: Collection<UUID>): Map<UUID, List<CueTargetDto>?> =
        unTargeted.associateWith(::perFixtureRowTargets)

    /** A palette's family, and whether it holds an effect rather than values. */
    private fun paletteKind(uuid: UUID): Pair<PropertyMaskGroup, Boolean>? =
        paletteRegistry().snapshot(uuid)?.let { snapshot -> snapshot.familyOf()?.let { it to (snapshot.effect != null) } }
}
