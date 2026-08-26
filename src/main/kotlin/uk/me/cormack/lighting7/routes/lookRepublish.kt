package uk.me.cormack.lighting7.routes

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.LayerStompSuppression
import uk.me.cormack.lighting7.fx.buildCueApplyDataForCues
import uk.me.cormack.lighting7.fx.buildCombinedCueLayerRows
import uk.me.cormack.lighting7.models.DaoCueLayer
import uk.me.cormack.lighting7.models.DaoCueLayers
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLooks
import uk.me.cormack.lighting7.models.DaoTemplate
import uk.me.cormack.lighting7.models.DaoTemplates
import uk.me.cormack.lighting7.state.State
import java.util.UUID

private val logger = LoggerFactory.getLogger("lookRepublish")

/**
 * Upper bound on the stable-set re-scan in [republishForSourceEdit]. Two passes settle every
 * benign case (pass 1 does the work, pass 2 sees no new active cues and stops); the slack
 * exists only for cues registering mid-republish, and a bound keeps a pathological GO storm
 * from pinning the request.
 */
private const val MAX_ACTIVE_CUE_SCANS = 3

/** What a Look edit moved. Reported back so a route can tell the operator. */
internal data class LookRepublishOutcome(
    /** Programmer keys whose resolved value changed and were re-transmitted. */
    val programmerKeysRefreshed: Int,
    /** The cues whose Layer 4 rows were actually replaced — not merely attempted. */
    val cuesRepublished: List<Int>,
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
 * 3. Rebuild and replace the affected cues' rows via [uk.me.cormack.lighting7.fx.FxEngine.replaceCueAssignments],
 *    one call per scan pass — normally exactly one.
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
        // One call, not the old invalidate-then-re-warm pair: `refresh` reads the new snapshot on
        // this thread and publishes it *with* the version bump. That bump is what a running effect
        // naming this template watches (`TypedParams`, keyed on `TemplateRegistry.versionFor`), so
        // any gap between the two lets the first re-resolve of a `tmpl:` parameter happen on the
        // 50 Hz tick loop and open a transaction there — the one thing `prewarmTemplateColours`
        // exists to keep off that thread.
        invalidate = { state.show.templateRegistry.refresh(templateUuid) },
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

    // 3. Rebuild the live cues that depend on this record, then one republish per scan pass.
    //    The scan loops until the active set is stable: a cue fired concurrently with this edit
    //    can cook pre-edit content *before* step 1's invalidate yet register its assignments
    //    *after* a single scan — neither republished here nor cooked fresh, leaving the pre-edit
    //    Look on stage until re-fired. Re-scanning catches such a cue once it registers. (A fire
    //    that outlives every pass can still slip through; closing that fully needs fire-vs-
    //    republish serialisation, which no path has today.)
    val republished = LinkedHashSet<Int>()
    val scanned = HashSet<Int>()
    var pass = 0
    while (pass++ < MAX_ACTIVE_CUE_SCANS) {
        val newlyActive = engine.activeCueAssignmentIds() - scanned
        if (newlyActive.isEmpty()) break
        scanned += newlyActive
        val referencingCues = referencing(newlyActive)
        if (referencingCues.isEmpty()) continue
        val rebuilt = LinkedHashMap<Int, List<CueAssignmentResolver.Assignment>>()
        val rebuiltStomp = LinkedHashMap<Int, LayerStompSuppression>()
        // One transaction and one query per relation for the whole set. Per cue it was a
        // transaction each, plus a Look/template lookup per layer inside it — an edit touching a
        // dozen live cues took dozens of round-trips against a size-1 pool while the operator
        // waited on the save.
        val applyDataByCue = transaction(state.database) { buildCueApplyDataForCues(referencingCues) }
        for (cueId in referencingCues) {
            val applyData = applyDataByCue[cueId]
            if (applyData == null) {
                // The cue's row vanished between the referencing query and the rebuild. Nothing
                // to republish for it, but never silently: its live Layer 4 rows (if any) are
                // the delete path's to tear down, and this log is the only trace if they leak.
                logger.warn(
                    "{} {} republish: cue {} disappeared before its rows could be rebuilt; skipped",
                    kind, sourceUuid, cueId,
                )
                continue
            }
            val cooked = buildCombinedCueLayerRows(state, cueId, applyData)
            rebuilt[cueId] = cooked.rows
            // Carried per cue, and *always* — including when it is empty. An edit that deleted the
            // rows a stomping layer used to assert must shrink its suppression set too, and
            // `replaceCueAssignments` reads an absent entry as "this cook found no stomper".
            rebuiltStomp[cueId] = cooked.stompSuppression
        }
        if (rebuilt.isNotEmpty()) republished += engine.replaceCueAssignments(rebuilt, rebuiltStomp)
    }

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
        kind, sourceUuid, programmerKeys.size, republished.size, scanned.size,
    )
    return LookRepublishOutcome(
        programmerKeysRefreshed = programmerKeys.size,
        cuesRepublished = republished.toList(),
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
