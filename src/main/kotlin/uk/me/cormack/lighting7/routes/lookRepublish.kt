package uk.me.cormack.lighting7.routes

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fx.CookResult
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.LayerStompSuppression
import uk.me.cormack.lighting7.models.DaoCueLayer
import uk.me.cormack.lighting7.models.DaoCueLayers
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLooks
import uk.me.cormack.lighting7.models.DaoTemplate
import uk.me.cormack.lighting7.models.DaoTemplates
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
internal fun republishForLookEdit(state: State, lookUuid: UUID): LookRepublishOutcome =
    republishForSourceEdit(
        state,
        lookUuid,
        kind = "look",
        invalidate = { state.show.lookRegistry.invalidate(lookUuid) },
        referencing = { activeCueIds -> activeCuesReferencingLook(state, lookUuid, activeCueIds) },
    )

/**
 * The template counterpart, and the reason this body is shared rather than copied: retuning a
 * template has to move every cue layering it *and* the programmer's own stack, by exactly the same
 * four-step order, or the flicker step 2 exists to prevent comes back on one of the two paths.
 *
 * Only two things differ — which cache to drop, and which FK column to search — so those are the
 * two parameters.
 */
internal fun republishForTemplateEdit(state: State, templateUuid: UUID): LookRepublishOutcome =
    republishForSourceEdit(
        state,
        templateUuid,
        kind = "template",
        invalidate = {
            state.show.templateRegistry.invalidate(templateUuid)
            // Re-warm on this thread, immediately. The bump `invalidate` performs is what every
            // running effect's colour cache watches (`TypedParams.invalidateColourCacheIfStale`),
            // so without this line the first re-resolve of a `tmpl:` parameter happens on the
            // 50 Hz tick loop and opens a transaction there — the one thing
            // `prewarmTemplateColours` exists to keep off that thread.
            state.show.templateRegistry.snapshot(templateUuid)
        },
        referencing = { activeCueIds -> activeCuesReferencingTemplate(state, templateUuid, activeCueIds) },
    )

private fun republishForSourceEdit(
    state: State,
    sourceUuid: UUID,
    kind: String,
    invalidate: () -> Unit,
    referencing: (Set<Int>) -> Set<Int>,
): LookRepublishOutcome {
    val engine = state.show.fxEngine

    // 1. Drop the cached snapshot first, so every read below sees the new contents.
    invalidate()

    // 2. Re-cook the programmer's layer stack, if any of its layers name this record. Before
    //    step 3, and that order is load-bearing: `publishCueLayerToControllers` composes the
    //    programmer *over* the cue layer, so stale layer slots would transmit the old value and be
    //    corrected a frame later — a visible flicker on the very fixtures being edited.
    val layerKeys = state.show.programmerLayerStack.recookIfReferences(sourceUuid)

    // 3. Rebuild the live cues that depend on this record, then one republish for all of them.
    val activeCueIds = engine.activeCueAssignmentIds()
    val referencingCues = if (activeCueIds.isEmpty()) emptySet() else referencing(activeCueIds)
    val rebuilt = LinkedHashMap<Int, List<CueAssignmentResolver.Assignment>>()
    val rebuiltStomp = LinkedHashMap<Int, LayerStompSuppression>()
    for (cueId in referencingCues) {
        val cooked = rebuildCueLayerRows(state, cueId) ?: continue
        rebuilt[cueId] = cooked.rows
        // Carried per cue, and *always* — including when it is empty. An edit that deleted the rows
        // a stomping layer used to assert must shrink its suppression set too, and
        // `replaceCueAssignments` reads an absent entry as "this cook found no stomper".
        rebuiltStomp[cueId] = cooked.stompSuppression
    }
    val republished =
        if (rebuilt.isEmpty()) 0 else engine.replaceCueAssignments(rebuilt, rebuiltStomp)

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
        "{} {} edited: {} programmer layer key(s) refreshed, {} of {} active cue(s) republished",
        kind, sourceUuid, programmerKeys.size, republished, activeCueIds.size,
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
 * Which of [activeCueIds] depend on [templateUuid]. The same indexed-FK query as
 * [activeCuesReferencingLook], on the other column.
 */
internal fun activeCuesReferencingTemplate(
    state: State,
    templateUuid: UUID,
    activeCueIds: Set<Int>,
): Set<Int> {
    if (activeCueIds.isEmpty()) return emptySet()
    return transaction(state.database) {
        val template = DaoTemplate.find { DaoTemplates.uuid eq templateUuid }.firstOrNull()
            ?: return@transaction emptySet()
        DaoCueLayer.find {
            (DaoCueLayers.cue inList activeCueIds.toList()) and (DaoCueLayers.template eq template.id)
        }.map { it.cue.id.value }.toSet()
    }
}

/**
 * Rebuild one live cue's cook from its persisted state, or null when the cue has gone.
 *
 * Mirrors [republishCueLayer]'s composition (the cooked layer stack plus local rows) but *returns*
 * it instead of publishing, so a Look edit spanning several cues can publish once.
 */
internal fun rebuildCueLayerRows(state: State, cueId: Int): CookResult? {
    val applyData = transaction(state.database) {
        uk.me.cormack.lighting7.models.DaoCue.findById(cueId)?.let { buildCueApplyData(it) }
    } ?: return null
    return buildCombinedCueLayerRows(state, cueId, applyData)
}
