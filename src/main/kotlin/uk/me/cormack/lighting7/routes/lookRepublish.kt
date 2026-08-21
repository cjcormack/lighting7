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
import uk.me.cormack.lighting7.models.DaoCueLayer
import uk.me.cormack.lighting7.models.DaoCueLayers
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLooks
import uk.me.cormack.lighting7.models.DaoCuePropertyAssignment
import uk.me.cormack.lighting7.models.DaoCuePropertyAssignments
import uk.me.cormack.lighting7.state.State
import java.util.UUID

private val logger = LoggerFactory.getLogger("lookRepublish")

/** What a palette edit moved. Reported back so a route can tell the operator. */
internal data class LookRepublishOutcome(
    /** Programmer keys whose resolved value changed and were re-transmitted. */
    val programmerKeysRefreshed: Int,
    /** Programmer ref slots the palette no longer covers — kept at their last value. */
    val programmerKeysUncovered: Int,
    val cuesRepublished: List<Int>,
    val activeCuesScanned: Int,
)

/**
 * Re-resolve and republish every live consumer of [lookUuid] after its contents changed.
 *
 * This is the touring feature: edit a Look once and every cue layering it moves, without re-firing
 * a single cue.
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
 * A reference slot the Look no longer covers **keeps its last resolved value** rather than being
 * dropped. Silently vanishing an operator's programmer entry mid-show is worse than a stale value
 * the sheet marks as broken.
 */
internal fun republishForLookEdit(state: State, lookUuid: UUID): LookRepublishOutcome {
    val engine = state.show.fxEngine
    val registry = state.show.lookRegistry

    // 1. Drop the cached expansion first, so every read below sees the new contents.
    registry.invalidate(lookUuid)

    // 2. Re-resolve programmer refs in place. No publish yet — see the KDoc.
    var uncovered = 0
    val rewrittenKeys = state.show.programmerStore.rewriteSlotValues { fixtureKey, propertyName, slot ->
        val ref = slot.value as? ProgrammerValue.Ref ?: return@rewriteSlotValues null
        if (ref.paletteUuid != lookUuid) return@rewriteSlotValues null

        val literal = registry.literalFor(lookUuid, fixtureKey, propertyName)
        if (literal == null) {
            uncovered++
            return@rewriteSlotValues null
        }
        // Reuse the value already stored to learn the shape we must parse back into: the slot's
        // resolved value came from this property, so its category is settled.
        val reparsed = reparseLike(slot.value.resolved, propertyName, literal)
        if (reparsed == null) {
            logger.warn(
                "look {}: row '{}' for {}.{} does not parse — leaving the programmer slot as it was",
                lookUuid, literal, fixtureKey, propertyName,
            )
            null
        } else {
            ProgrammerValue.Ref(lookUuid, reparsed)
        }
    }

    // 3. Rebuild the live cues that depend on this Look, then one republish for all of them.
    val activeCueIds = engine.activeCueAssignmentIds()
    val referencing = if (activeCueIds.isEmpty()) {
        emptySet()
    } else {
        activeCuesReferencingLook(state, lookUuid, activeCueIds)
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
        "look {} edited: {} programmer key(s) refreshed, {} uncovered, {} of {} active cue(s) republished",
        lookUuid, rewrittenKeys.size, uncovered, republished, activeCueIds.size,
    )
    return LookRepublishOutcome(
        programmerKeysRefreshed = rewrittenKeys.size,
        programmerKeysUncovered = uncovered,
        cuesRepublished = rebuilt.keys.toList(),
        activeCuesScanned = activeCueIds.size,
    )
}

/**
 * Which of [activeCueIds] depend on [lookUuid] — through a layer, or through a local row whose value
 * is exactly `ref:{lookUuid}`.
 *
 * The layer half is a plain **indexed FK query**, which is the structural win of the merge: a layer
 * references its Look through a real column, where the palette era could only scan opaque `value`
 * text for an exact string match. The `ref:` half survives only until the grammar is retired, and
 * keeps exact equality rather than a `LIKE` prefix — a prefix match becomes a latent bug the moment
 * the reference form grows a suffix.
 */
internal fun activeCuesReferencingLook(
    state: State,
    lookUuid: UUID,
    activeCueIds: Set<Int>,
): Set<Int> {
    if (activeCueIds.isEmpty()) return emptySet()
    val refValue = paletteRefValue(lookUuid)
    return transaction(state.database) {
        val look = DaoLook.find { DaoLooks.uuid eq lookUuid }.firstOrNull()

        val viaLayers = if (look == null) {
            emptyList()
        } else {
            DaoCueLayer.find {
                (DaoCueLayers.cue inList activeCueIds.toList()) and (DaoCueLayers.look eq look.id)
            }.map { it.cue.id.value }
        }

        val viaLocalRefs = DaoCuePropertyAssignment.find {
            (DaoCuePropertyAssignments.cue inList activeCueIds.toList()) and
                (DaoCuePropertyAssignments.value eq refValue)
        }.map { it.cue.id.value }

        (viaLayers + viaLocalRefs).toSet()
    }
}

/**
 * Rebuild one live cue's Layer 4 rows from its persisted state, or null when the cue has gone.
 *
 * Mirrors [republishCueLayer]'s composition (the cooked layer stack plus local rows) but *returns*
 * the rows instead of publishing, so a Look edit spanning several cues can publish once.
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
