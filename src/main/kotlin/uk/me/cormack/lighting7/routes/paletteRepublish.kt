package uk.me.cormack.lighting7.routes

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.ProgrammerValue
import uk.me.cormack.lighting7.fx.canonicalPropertyName
import uk.me.cormack.lighting7.fx.paletteRefValue
import uk.me.cormack.lighting7.models.DaoCuePresetApplication
import uk.me.cormack.lighting7.models.DaoCuePresetApplications
import uk.me.cormack.lighting7.models.DaoCuePropertyAssignment
import uk.me.cormack.lighting7.models.DaoCuePropertyAssignments
import uk.me.cormack.lighting7.models.DaoFxPresetPropertyAssignment
import uk.me.cormack.lighting7.models.DaoFxPresetPropertyAssignments
import uk.me.cormack.lighting7.state.State
import java.util.UUID

private val logger = LoggerFactory.getLogger("paletteRepublish")

/** What a palette edit moved. Reported back so a route can tell the operator. */
internal data class PaletteRepublishOutcome(
    /** Programmer keys whose resolved value changed and were re-transmitted. */
    val programmerKeysRefreshed: Int,
    /** Programmer ref slots the palette no longer covers — kept at their last value. */
    val programmerKeysUncovered: Int,
    val cuesRepublished: List<Int>,
    val activeCuesScanned: Int,
)

/**
 * Re-resolve and republish every live consumer of [paletteUuid] after its contents changed.
 *
 * This is the touring feature: edit a palette once and every look referencing it moves, without
 * re-firing a single cue.
 *
 * **Order of operations matters and is not interchangeable:**
 *
 * 1. Invalidate the registry, before anything reads through it.
 * 2. Re-resolve the programmer's [ProgrammerValue.Ref] slots — *without publishing yet*.
 * 3. Rebuild and replace the affected cues' rows in one [uk.me.cormack.lighting7.fx.FxEngine.replaceCueAssignments].
 * 4. Publish the programmer keys, then emit provenance.
 *
 * Step 2 has to precede step 3 because [uk.me.cormack.lighting7.fx.FxEngine.publishCueLayerToControllers]
 * composes the programmer *over* the cue layer via `LayerResolver.fallbackFor`. With stale
 * `Ref.resolved` values still in the store, every key covered by both layers would transmit the old
 * value and be corrected a frame later — a visible flicker on exactly the fixtures the operator is
 * editing.
 *
 * A ref slot the palette no longer covers **keeps its last resolved value** rather than being
 * dropped. Silently vanishing an operator's programmer entry mid-show is worse than a stale value
 * the sheet marks as broken.
 */
internal fun republishForPaletteEdit(state: State, paletteUuid: UUID): PaletteRepublishOutcome {
    val engine = state.show.fxEngine
    val registry = state.show.paletteRegistry

    // 1. Drop the cached expansion first, so every read below sees the new contents.
    registry.invalidate(paletteUuid)

    // 2. Re-resolve programmer refs in place. No publish yet — see the KDoc.
    var uncovered = 0
    val rewrittenKeys = state.show.programmerStore.rewriteSlotValues { fixtureKey, propertyName, slot ->
        val ref = slot.value as? ProgrammerValue.Ref ?: return@rewriteSlotValues null
        if (ref.paletteUuid != paletteUuid) return@rewriteSlotValues null

        val literal = registry.literalFor(paletteUuid, fixtureKey, propertyName)
        if (literal == null) {
            uncovered++
            return@rewriteSlotValues null
        }
        // Reuse the value already stored to learn the shape we must parse back into: the slot's
        // resolved value came from this property, so its category is settled.
        val reparsed = reparseLike(slot.value.resolved, propertyName, literal)
        if (reparsed == null) {
            logger.warn(
                "palette {}: entry '{}' for {}.{} does not parse — leaving the programmer slot as it was",
                paletteUuid, literal, fixtureKey, propertyName,
            )
            null
        } else {
            ProgrammerValue.Ref(paletteUuid, reparsed)
        }
    }

    // 3. Rebuild the live cues that reference this palette, then one republish for all of them.
    val activeCueIds = engine.activeCueAssignmentIds()
    val referencing = if (activeCueIds.isEmpty()) {
        emptySet()
    } else {
        activeCuesReferencingPalette(state, paletteUuid, activeCueIds)
    }
    val rebuilt = LinkedHashMap<Int, List<CueAssignmentResolver.Assignment>>()
    for (cueId in referencing) {
        val rows = rebuildCueLayerRows(state, cueId) ?: continue
        rebuilt[cueId] = rows
    }
    val republished = if (rebuilt.isEmpty()) 0 else engine.replaceCueAssignments(rebuilt)

    // 4. Now the programmer's own keys, then tell the clients to re-read. republishProgrammerKeys
    //    emits provenance itself, but only when it has keys — so cover the empty case here rather
    //    than emitting twice when it doesn't. (emitProvenanceUpdate coalesces, so a double call is
    //    harmless in a running engine; being exact keeps the intent readable.)
    if (rewrittenKeys.isEmpty()) {
        engine.emitProvenanceUpdate()
    } else {
        engine.republishProgrammerKeys(rewrittenKeys)
    }

    logger.info(
        "palette {} edited: {} programmer key(s) refreshed, {} uncovered, {} of {} active cue(s) republished",
        paletteUuid, rewrittenKeys.size, uncovered, republished, activeCueIds.size,
    )
    return PaletteRepublishOutcome(
        programmerKeysRefreshed = rewrittenKeys.size,
        programmerKeysUncovered = uncovered,
        cuesRepublished = rebuilt.keys.toList(),
        activeCuesScanned = activeCueIds.size,
    )
}

/**
 * Which of [activeCueIds] hold a row — their own, or one of their immediate presets' — whose value
 * is exactly `ref:{paletteUuid}`.
 *
 * A DB scan rather than state tracked at build time. Tracking would mean threading a referenced-set
 * through both assignment builders, `applyCue`, `republishCueLayer` and `setCueAssignments`, and it
 * would still be *incomplete*: timed-preset rows arrive via `appendCueAssignments`, which carries no
 * palette information at all. The scan is two indexed queries against a handful of live cues.
 *
 * Exact equality, not a `LIKE` prefix — a prefix match is a latent bug the moment the reference form
 * grows a suffix.
 */
internal fun activeCuesReferencingPalette(
    state: State,
    paletteUuid: UUID,
    activeCueIds: Set<Int>,
): Set<Int> {
    if (activeCueIds.isEmpty()) return emptySet()
    val refValue = paletteRefValue(paletteUuid)
    return transaction(state.database) {
        val direct = DaoCuePropertyAssignment.find {
            (DaoCuePropertyAssignments.cue inList activeCueIds.toList()) and
                (DaoCuePropertyAssignments.value eq refValue)
        }.map { it.cue.id.value }

        // Preset rows reach the cue layer through its preset applications, so a palette edit has to
        // republish the cue even though the referencing row belongs to the preset.
        val presetIds = DaoFxPresetPropertyAssignment
            .find { DaoFxPresetPropertyAssignments.value eq refValue }
            .map { it.preset.id.value }
            .distinct()
        val viaPresets = if (presetIds.isEmpty()) {
            emptyList()
        } else {
            DaoCuePresetApplication.find {
                (DaoCuePresetApplications.cue inList activeCueIds.toList()) and
                    (DaoCuePresetApplications.preset inList presetIds)
            }.map { it.cue.id.value }
        }

        (direct + viaPresets).toSet()
    }
}

/**
 * Rebuild one live cue's Layer 4 rows from its persisted state, or null when the cue has gone.
 *
 * Mirrors [republishCueLayer]'s composition (cue rows plus immediate-preset rows) but *returns*
 * the rows instead of publishing, so a palette edit spanning several cues can publish once.
 */
internal fun rebuildCueLayerRows(state: State, cueId: Int): List<CueAssignmentResolver.Assignment>? {
    val applyData = transaction(state.database) {
        uk.me.cormack.lighting7.models.DaoCue.findById(cueId)?.let { buildCueApplyData(it) }
    } ?: return null
    return buildCombinedCueLayerRows(state, cueId, applyData)
}

/**
 * Reparse [literal] into the same [CueAssignmentResolver.PropertyValue] shape as [like].
 *
 * A programmer slot doesn't remember the property's `PropertyCategory`, but it does hold a value of
 * the right shape — which is all the parser dispatch actually needs, and it avoids a patch lookup
 * per slot. `position` is the one case the property name decides.
 */
private fun reparseLike(
    like: CueAssignmentResolver.PropertyValue,
    propertyName: String,
    literal: String,
): CueAssignmentResolver.PropertyValue? {
    val category = when (like) {
        is CueAssignmentResolver.PropertyValue.Colour -> uk.me.cormack.lighting7.fixture.PropertyCategory.COLOUR
        is CueAssignmentResolver.PropertyValue.Setting -> uk.me.cormack.lighting7.fixture.PropertyCategory.SETTING
        is CueAssignmentResolver.PropertyValue.Position,
        is CueAssignmentResolver.PropertyValue.Slider,
        -> uk.me.cormack.lighting7.fixture.PropertyCategory.DIMMER
    }
    return CueAssignmentResolver.parseAssignmentValue(category, canonicalPropertyName(propertyName), literal)
}
