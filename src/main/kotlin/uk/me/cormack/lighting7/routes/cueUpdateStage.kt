package uk.me.cormack.lighting7.routes

import uk.me.cormack.lighting7.fx.SpreadUnit
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.dmx.EasingCurve
import uk.me.cormack.lighting7.fx.CookLayer
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.CueFxKey
import uk.me.cormack.lighting7.fx.CueHold
import uk.me.cormack.lighting7.fx.FxEnvelope
import uk.me.cormack.lighting7.fx.FxSettingsSnapshot
import uk.me.cormack.lighting7.fx.FxEngine
import uk.me.cormack.lighting7.fx.FxInstance
import uk.me.cormack.lighting7.fx.TimingSource
import uk.me.cormack.lighting7.fx.TransmitFade
import uk.me.cormack.lighting7.fx.buildCombinedCueLayerCook
import uk.me.cormack.lighting7.fx.buildCueApplyData
import uk.me.cormack.lighting7.fx.comparableFxSettings
import uk.me.cormack.lighting7.fx.cueFxKey
import uk.me.cormack.lighting7.fx.settingsSnapshot
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.state.State
import java.util.UUID

private val logger = LoggerFactory.getLogger("CueUpdateStage")

/** *Update stage* on a cue nothing holds. */
internal const val CODE_CUE_NOT_LIVE = "CUE_NOT_LIVE"

/** `POST /cues/{id}/update-stage`'s body. */
@Serializable
data class UpdateStageRequest(val dryRun: Boolean = false)

/**
 * What *Update stage* moved, or with `dryRun` would move: [rows] is the (head, property) keys whose
 * value changes, added and released ones included; [fadeMs] and [curve] are the cue's own fade, which
 * they move over.
 */
@Serializable
data class UpdateStageResponse(val rows: Int, val fx: UpdateStageFx, val fadeMs: Long, val curve: String)

@Serializable
data class UpdateStageFx(val edited: Int, val stopped: Int, val started: Int)

/** One *Update stage* at a time, so two presses cannot each start what the cue newly gained. */
private val updateStageLock = Any()

/**
 * *Update stage* (show-cue-editing plan §3.5, D11): bring a live cue's contribution on stage in line
 * with the cue as stored, over the cue's own fade and curve — what an edit since it came on
 * ([uk.me.cormack.lighting7.fx.CueHolds.markEdited]) left behind. The one way the Show view sends a
 * cue edit to the stage (P2).
 *
 * - **Rows**: the cue's whole Layer 4 cook, published with every key it moves ramping over the cue's
 *   fade ([uk.me.cormack.lighting7.fx.CueHolds.restage]); a snap cue snaps. Not a GO's fade, nor its
 *   stack's crossfade, which a GO between two entries of one cue never has and a loose cue has no
 *   stack for. A key a running effect covers is the effect's, so its base moves with no ramp.
 * - **Effects**: the running instances its untimed effects spawned, reconciled by stored uuid, layer,
 *   target and property against what the cue spawns now. A changed one is edited in place through
 *   [applyFxUpdate] — id and phase kept, but for a move between Seconds and a master, which
 *   restarts its loop — a removed one shrinks to nothing over the fade, and one the stored cue has
 *   gained grows from nothing over it, at the cue's priority. One the cue spawned that is no longer
 *   running — an N-shot run out, one a stomp took off — stays off.
 * - **Not**: its events, its scenery, its stomp, and its timed effects and layers — fired things,
 *   left as its last GO armed them, which its next GO lands.
 *
 * With [dryRun] the same reconcile answers its counts and sends nothing — what the confirm reads.
 * Null when nothing holds [cueId].
 */
internal fun updateStage(state: State, cueId: Int, dryRun: Boolean): UpdateStageResponse? =
    synchronized(updateStageLock) { updateStageLocked(state, cueId, dryRun) }

private fun updateStageLocked(state: State, cueId: Int, dryRun: Boolean): UpdateStageResponse? {
    val holds = state.show.cueStackManager.holds
    val priority = holds.priorityOf(cueId) ?: return null
    val data = transaction(state.database) {
        DaoCue.findById(cueId)?.let { buildCueApplyData(it, null) }
    }?.copy(livePriority = priority) ?: return null
    val engine = state.show.fxEngine

    // Rows and effects from one cook, so both read one version of every Look and palette.
    val cooked = buildCombinedCueLayerCook(state, cueId, data, priority = priority)
    val rows = changedKeyCount(engine.cueLayer.assignmentsOf(cueId), cooked.values.rows)

    val layerFx = cooked.effects.filter { (layer, _, _) -> !layer.isTimed }
    val fresh = holds.cueFxInstances(
        state, cueId,
        layerFx,
        data.adHocFx.filter { it.delayMs == null && it.intervalMs == null },
    )
    val master1 = engine.speedMasters.master1Uuid()
    val plan = reconcile(
        // Timed and script-spawned instances carry no stored uuid, so they are left alone.
        live = engine.cueFx(cueId).filter { it.layerFxUuid != null },
        fresh = fresh,
        spawned = holds.spawnedFxOf(cueId),
        master1 = master1,
    )
    val fadeMs = data.fadeDurationMs ?: 0L
    val curve = runCatching { EasingCurve.valueOf(data.fadeCurve) }.getOrDefault(EasingCurve.LINEAR)
    val counts = UpdateStageFx(edited = plan.edited.size, stopped = plan.stopped.size, started = plan.started.size)
    if (dryRun) return UpdateStageResponse(rows, counts, fadeMs, curve.name)

    // A GO may have taken it off while it was cooked: rows published now would be no one's.
    if (!holds.restage(cueId, cooked.values.rows, cooked.values.stompSuppression, TransmitFade(fadeMs, curve))) return null

    val replaced = mutableListOf<FxInstance>()
    var edited = 0
    for ((instance, freshInstance) in plan.edited) {
        if (alignFx(state, instance.id, instance.settingsSnapshot(master1), freshInstance.settingsSnapshot(master1)) != null) {
            edited++
        } else {
            // An edit the in-place path refuses (or an instance gone meanwhile) is the stored
            // effect started afresh, so the stage still ends up as the cue says.
            logger.info("cue {}: effect {} not edited in place; restarting it", cueId, instance.id)
            engine.fadeOutFx(listOf(instance), 0)
            replaced += freshInstance
        }
    }
    engine.fadeOutFx(plan.stopped, fadeMs, curve)
    val starting = plan.started + replaced
    if (starting.isNotEmpty()) {
        val stackTag = plan.matchedStack ?: (holds.holdsOf(cueId).firstOrNull() as? CueHold.Stack)?.stackId
        val nowMs = System.currentTimeMillis()
        val livePriority = holds.priorityOf(cueId) ?: priority
        for (instance in starting) {
            with(holds) { instance.tagged(cueId, stackTag, livePriority) }
            if (fadeMs > 0 && instance in plan.started) instance.carryEnvelope(FxEnvelope.ramp(0.0, 1.0, fadeMs, curve, nowMs))
        }
        engine.addFxBatch(starting)
        // A release since the rows went up took the cue's effects off without these.
        if (!holds.isHeld(cueId)) {
            engine.removeFxBatch(starting.map { it.id })
            return null
        }
    }
    restampIfMoved(engine, cueId, plan.order.mapNotNull { (liveId, instance) -> liveId?.let(engine::getFx) ?: instance.takeIf { it in starting } })
    holds.setSpawnedFx(cueId, fresh.mapTo(HashSet()) { (instance, _) -> instance.cueFxKey() }, layerFx)
    holds.clearEdited(cueId)
    return UpdateStageResponse(rows, counts.copy(edited = edited, started = plan.started.size + replaced.size), fadeMs, curve.name)
}

/**
 * Give the cue's untimed effects its layer order for same-priority composition when they are out of
 * it, and its other instances — timed, script-spawned — their old order after them.
 */
private fun restampIfMoved(engine: FxEngine, cueId: Int, ordered: List<FxInstance>) {
    if (ordered.zipWithNext().all { (a, b) -> a.composeOrder < b.composeOrder }) return
    val others = engine.cueFx(cueId).filter { it !in ordered }.sortedBy { it.composeOrder }
    engine.restampComposeOrder(ordered + others)
}

/** How the running instances line up with what the stored cue spawns. */
private class ReconcilePlan(
    /** A running instance and the instance the stored effect would spawn, whose settings differ. */
    val edited: List<Pair<FxInstance, FxInstance>>,
    val stopped: List<FxInstance>,
    val started: List<FxInstance>,
    /** The cue's effects in cook order: a matched instance's id, else the fresh one to start. */
    val order: List<Pair<Long?, FxInstance>>,
    /** The stack the cue's running effects were tagged with, for the ones it starts. */
    val matchedStack: Int?,
)

/**
 * Pair each fresh instance with a running one realising the same stored effect — its uuid, the cue
 * layer it came through (none for the cue's own), its target and property — taking the first unused
 * match, so an effect a palette layer runs on three heads pairs head by head. A target that
 * moved is another effect: the old one stops and the new one starts. A fresh one with no match starts
 * only when [spawned] lacks it — otherwise the cue started it and it has since gone.
 */
private fun reconcile(
    live: List<FxInstance>,
    fresh: List<Pair<FxInstance, CookLayer?>>,
    spawned: Set<CueFxKey>,
    master1: UUID?,
): ReconcilePlan {
    val pool = live.toMutableList()
    val edited = mutableListOf<Pair<FxInstance, FxInstance>>()
    val started = mutableListOf<FxInstance>()
    val order = mutableListOf<Pair<Long?, FxInstance>>()
    for ((instance, _) in fresh) {
        val key = instance.cueFxKey()
        val match = pool.firstOrNull { it.cueFxKey() == key }
        if (match == null) {
            if (key !in spawned) {
                started += instance
                order += null to instance
            }
            continue
        }
        pool.remove(match)
        if (match.comparableFxSettings(master1) != instance.comparableFxSettings(master1)) edited += match to instance
        order += match.id to instance
    }
    return ReconcilePlan(edited, stopped = pool, started, order, matchedStack = live.firstNotNullOfOrNull { it.cueStackId })
}

/**
 * Edit running effect [fxId] in place from [from]'s settings to [to]'s, through
 * [applyFxUpdate] on [alignmentRequest] — a GO handing an effect run on with new settings, and
 * undoing that when the GO gives it back ([uk.me.cormack.lighting7.fx.CueHolds]). Answers the
 * instance running now, which a swapping edit replaces, or null when the edit was refused or the
 * effect is gone.
 */
internal fun alignFx(state: State, fxId: Long, from: FxSettingsSnapshot, to: FxSettingsSnapshot): FxInstance? =
    when (val outcome = applyFxUpdate(state, fxId, alignmentRequest(from, to))) {
        is FxUpdateOutcome.Updated -> outcome.instance
        else -> {
            logger.info("effect {} not edited in place ({})", fxId, outcome)
            null
        }
    }

/**
 * The in-place edit that takes [live] to [fresh]'s settings: every field that differs, each in the
 * form [UpdateFxRequest] reads as a change — master 1 by its uuid, forever as [UpdateFxRequest.runForever],
 * Seconds as [UpdateFxRequest.seconds] — and nothing else, so no field is re-sent that a refusal
 * could catch on. No `targets`: a cue's effect never splits (`FX_SPLIT_NOT_LOCAL`).
 */
internal fun alignmentRequest(live: FxSettingsSnapshot, fresh: FxSettingsSnapshot): UpdateFxRequest {
    val was = live.settings
    val now = fresh.settings
    val wasDyn = live.dynamics
    val nowDyn = fresh.dynamics
    val typeMoved = now.fxType != was.fxType
    val beat = fresh.timingSource == TimingSource.BEAT
    val toSeconds = fresh.onSeconds && !live.onSeconds
    val fromSeconds = !fresh.onSeconds && live.onSeconds
    val speedMaster = when {
        toSeconds || !beat -> null
        fromSeconds || now.speedMasterUuid != was.speedMasterUuid ->
            now.speedMasterUuid?.toString()
        else -> null
    }
    // A wall-clock effect off Seconds has a rate master; one with none is on Seconds (toSeconds).
    val rateMaster = if (!beat && !toSeconds && (fromSeconds || now.rateSpeedMasterUuid != was.rateSpeedMasterUuid)) {
        now.rateSpeedMasterUuid?.toString()
    } else null
    return UpdateFxRequest(
        fxType = now.fxType.takeIf { typeMoved },
        parameters = now.parameters.takeIf { typeMoved || it != was.parameters },
        cycle = now.cycle.takeIf { it != was.cycle },
        blendMode = now.blendMode.takeIf { it != was.blendMode },
        phaseOffset = nowDyn.phaseOffset.takeIf { it != wasDyn.phaseOffset },
        order = now.order.takeIf { nowDyn.order != wasDyn.order },
        elementMode = nowDyn.elementMode.name.takeIf { nowDyn.elementMode != wasDyn.elementMode },
        elementFilter = nowDyn.elementFilter.name.takeIf { nowDyn.elementFilter != wasDyn.elementFilter },
        timing = nowDyn.timing.takeIf { it != wasDyn.timing },
        speedMasterUuid = speedMaster,
        rateSpeedMasterUuid = rateMaster,
        seconds = when {
            toSeconds -> true
            fromSeconds -> false
            else -> null
        },
        amount = now.amount.takeIf { it != was.amount },
        direction = now.direction.takeIf { it != was.direction },
        runs = now.runs.takeIf { it != was.runs },
        runForever = now.runs == null && was.runs != null,
        spread = now.spread.takeIf { it != was.spread },
        wings = now.wings.takeIf { it != was.wings },
        segments = now.segments.takeIf { it != was.segments },
        parts = now.parts.takeIf { it != was.parts },
        spreadUnit = SpreadUnit.stored(now.spreadUnit).name.takeIf { now.spreadUnit != was.spreadUnit },
    )
}

/** How many (head, property) keys [after] lands differently from [before] — added, changed or gone. */
private fun changedKeyCount(
    before: List<CueAssignmentResolver.Assignment>,
    after: List<CueAssignmentResolver.Assignment>,
): Int {
    val was = before.associate { (it.targetKey to it.attribute) to it.value }
    val now = after.associate { (it.targetKey to it.attribute) to it.value }
    return (was.keys + now.keys).count { was[it] != now[it] }
}
