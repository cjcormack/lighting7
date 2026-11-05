package uk.me.cormack.lighting7.routes

import uk.me.cormack.lighting7.fx.AttributeFamily
import uk.me.cormack.lighting7.fx.LayerPressRules
import uk.me.cormack.lighting7.fx.PaletteReferences
import uk.me.cormack.lighting7.fx.PaletteSnapshot
import uk.me.cormack.lighting7.fx.TargetCoverage
import uk.me.cormack.lighting7.fx.canonicalAttributeName
import uk.me.cormack.lighting7.fx.familyOf
import uk.me.cormack.lighting7.models.CueAttributeAssignmentDto
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef
import java.util.UUID

/**
 * A value palette pressed into a **stored** cue's own rows (palette-references plan D13): the
 * programmer's reference press ([uk.me.cormack.lighting7.fx.PaletteReferencePress]) over rows
 * rather than slots, for the cue editor's press and the Timeline's ops.
 *
 * The rows it writes are `pal:{uuid}` on each attribute the palette names, one per pressed target
 * as spelled — a group stays one row, resolved per member — or, for a per-fixture palette, one per
 * pressed head it has rows for. A target the reference reaches nothing on ([landsOn]) gets none.
 *
 * - **Off** when every row it would write is there: each row referencing it whose heads the press
 *   covers comes out.
 * - **On** writes them over every row on the same attribute whose heads they cover, and takes out
 *   every row referencing another value palette of its family ([familyOfPalette]) that lies wholly
 *   within the heads it landed on, on any attribute — each such palette counting as released.
 */
internal class CueReferencePress(
    private val rules: LayerPressRules,
    private val coverage: TargetCoverage,
    private val landsOn: (TargetRef, String, String) -> Boolean,
    private val familyOfPalette: (UUID) -> AttributeFamily?,
) {
    data class Result(
        val applied: Boolean,
        /** The cue's whole row list after the press, in order. */
        val rows: List<CueAttributeAssignmentDto>,
        /** The other palettes whose rows the press took out. */
        val released: Set<UUID>,
        /** The targets it wrote rows on, or took them off. */
        val targets: List<CueTargetDto>,
        val omitted: List<String>,
        val notCovered: List<String>,
    )

    /** [alreadyOn]: the press is on its off arm whatever the rows say — a layer of the palette covers the pressed heads. */
    fun press(
        palette: PaletteSnapshot,
        requested: List<CueTargetDto>,
        rows: List<CueAttributeAssignmentDto>,
        alreadyOn: Boolean = false,
    ): Result {
        val uuid = palette.paletteUuid
        val value = PaletteReferences.valueOf(uuid)
        val pressTargets = rules.pressTargets(uuid, requested)
        val attributes = palette.rows.map { canonicalAttributeName(it.attribute) }.distinct()
        val perFixture = rules.perFixtureRowTargets(uuid) != null
        val writeTargets = if (perFixture) {
            rules.paletteHeads(uuid, pressTargets)?.toList() ?: coverage.expand(pressTargets)
        } else {
            pressTargets
        }
        val desired = writeTargets.flatMap { target ->
            val ref = TargetRef.ofOrNull(target.type, target.key) ?: return@flatMap emptyList()
            attributes.filter { landsOn(ref, it, value) }.map { target to it }
        }
        val landedTargets = desired.map { it.first }.distinct()
        val landedHeads = coverage.expand(landedTargets).toSet()
        val pressedHeads = coverage.expand(pressTargets).toSet() + landedHeads
        val notCovered = coverage.expand(pressTargets).filterNot { coverage.covers(landedHeads, it) }.map { it.key }.distinct()
        val omitted = if (requested.isEmpty()) emptyList() else rules.omittedHeads(uuid, pressTargets)

        fun headsOf(row: CueAttributeAssignmentDto) = coverage.expand(listOf(CueTargetDto(row.targetType, row.targetKey)))
        fun within(row: CueAttributeAssignmentDto, heads: Set<CueTargetDto>) = headsOf(row).all { coverage.covers(heads, it) }

        val on = desired.isNotEmpty() && desired.all { (target, attribute) ->
            rows.any { it.targetType == target.type && it.targetKey == target.key && canonicalAttributeName(it.attribute) == attribute && it.value == value }
        }
        if (on || alreadyOn) {
            val kept = rows.filterNot { it.value == value && within(it, pressedHeads) }
            return Result(false, kept, emptySet(), landedTargets, omitted, notCovered)
        }

        val family = palette.familyOf()
        val written = desired.groupBy({ it.second }, { it.first })
        val released = HashSet<UUID>()
        val kept = rows.filterNot { row ->
            val other = PaletteReferences.uuidOf(row.value)?.takeIf { it != uuid }
            // Another value palette of the family gives up the heads this one landed on, on every
            // attribute, whichever of them this one names there.
            if (other != null && family != null && familyOfPalette(other) == family && within(row, landedHeads)) {
                released += other
                return@filterNot true
            }
            val covering = written[canonicalAttributeName(row.attribute)] ?: return@filterNot false
            val overwritten = within(row, coverage.expand(covering).toSet())
            if (overwritten && other != null) released += other
            overwritten
        }
        val next = (kept.maxOfOrNull { it.sortOrder } ?: -1) + 1
        val added = desired.mapIndexed { index, (target, attribute) ->
            // The palette's own fade, which its layer gave every row it cooked.
            CueAttributeAssignmentDto(target.type, target.key, attribute, value, fadeDurationMs = palette.fadeDurationMs, sortOrder = next + index)
        }
        return Result(true, kept + added, released, landedTargets, omitted, notCovered)
    }

    companion object {
        /** A press over the current show's rig and palettes. */
        fun of(state: uk.me.cormack.lighting7.state.State, rules: LayerPressRules, coverage: TargetCoverage) = CueReferencePress(
            rules = rules,
            coverage = coverage,
            landsOn = { target, attribute, value -> state.show.paletteReferences.landsOn(state.show.fixtures, target, attribute, value) },
            familyOfPalette = { uuid -> state.show.paletteRegistry.snapshot(uuid)?.takeIf { it.effect == null }?.familyOf() },
        )
    }
}
