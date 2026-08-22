package uk.me.cormack.lighting7.routes

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.models.DaoCueLayer
import uk.me.cormack.lighting7.models.DaoCueLayers
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLooks
import uk.me.cormack.lighting7.state.State
import java.util.UUID

private val logger = LoggerFactory.getLogger("lookRepublish")

/** What a Look edit moved. Reported back so a route can tell the operator. */
internal data class LookRepublishOutcome(
    /** Programmer keys whose resolved value changed and were re-transmitted. */
    val programmerKeysRefreshed: Int,
    /**
     * Always 0 since the `ref:` grammar retired, and kept as a field rather than removed because it
     * is on the wire (`ProgrammerLookUpdateResult`). A layer that stops covering a key loses its
     * slot on the recook instead, which is a value change rather than a partial failure — there is
     * no longer a state where the programmer holds a reference the Look cannot answer.
     */
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
 * 2. Re-cook the programmer's layer stack — *without publishing yet*.
 * 3. Rebuild and replace the affected cues' rows in one [uk.me.cormack.lighting7.fx.FxEngine.replaceCueAssignments].
 * 4. Publish the programmer keys, then emit provenance.
 *
 * Step 2 has to precede step 3 because [uk.me.cormack.lighting7.fx.FxEngine.publishCueLayerToControllers]
 * composes the programmer *over* the cue layer via `LayerResolver.fallbackFor`. With stale layer
 * slots still in the store, every key covered by both layers would transmit the old value and be
 * corrected a frame later — a visible flicker on exactly the fixtures the operator is editing.
 *
 * Before session 4 step 2 also re-resolved per-slot `ref:` values, and had to decide what to do
 * about a reference the edited Look no longer covered. The layer stack has no such case: a key the
 * cooked stack stops naming simply loses its layer slot and whatever is underneath shows through.
 */
internal fun republishForLookEdit(state: State, lookUuid: UUID): LookRepublishOutcome {
    val engine = state.show.fxEngine
    val registry = state.show.lookRegistry

    // 1. Drop the cached expansion first, so every read below sees the new contents.
    registry.invalidate(lookUuid)

    // 2. Re-cook the programmer's layer stack, if any of its layers name this Look. Before
    //    step 3, and that order is load-bearing: `publishCueLayerToControllers` composes the
    //    programmer *over* the cue layer, so stale layer slots would transmit the old value and be
    //    corrected a frame later — a visible flicker on the very fixtures being edited.
    val layerKeys = state.show.programmerLayerStack.recookIfReferences(lookUuid)

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
    val programmerKeys = layerKeys
    if (programmerKeys.isEmpty()) {
        engine.emitProvenanceUpdate()
    } else {
        engine.republishProgrammerKeys(programmerKeys)
    }

    logger.info(
        "look {} edited: {} programmer layer key(s) refreshed, {} of {} active cue(s) republished",
        lookUuid, programmerKeys.size, republished, activeCueIds.size,
    )
    return LookRepublishOutcome(
        programmerKeysRefreshed = programmerKeys.size,
        programmerKeysUncovered = 0,
        cuesRepublished = rebuilt.keys.toList(),
        activeCuesScanned = activeCueIds.size,
    )
}

/**
 * Which of [activeCueIds] depend on [lookUuid] — now only ever through a layer.
 *
 * A plain **indexed FK query**, which is the structural win of the merge: a layer references its
 * Look through a real column, where the palette era could only scan opaque `value` text for an
 * exact string match. That second scan retired with the `ref:` grammar in session 4.
 */
internal fun activeCuesReferencingLook(
    state: State,
    lookUuid: UUID,
    activeCueIds: Set<Int>,
): Set<Int> {
    if (activeCueIds.isEmpty()) return emptySet()
    return transaction(state.database) {
        val look = DaoLook.find { DaoLooks.uuid eq lookUuid }.firstOrNull()

        if (look == null) {
            emptySet()
        } else {
            DaoCueLayer.find {
                (DaoCueLayers.cue inList activeCueIds.toList()) and (DaoCueLayers.look eq look.id)
            }.map { it.cue.id.value }.toSet()
        }
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
