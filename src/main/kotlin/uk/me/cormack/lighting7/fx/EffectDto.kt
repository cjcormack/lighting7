package uk.me.cormack.lighting7.fx

import kotlinx.serialization.Serializable

/**
 * One running effect, as every transport reports it.
 *
 * There is deliberately **one** of these and **one** builder ([toEffectDto]). Before sweep item
 * F8 the same concept existed three times over — the REST `EffectDto`, the WebSocket
 * `FxEffectState`, and `FxEngine.FxInstanceState` feeding it — with three field sets, two
 * spellings of the phase field, two meanings of `targetKey`, and two different answers for
 * `effectType`. They had already diverged in ways nobody intended: the WS frame reported
 * `effect.name` where [FxInstance.effectTypeId] is the value a client can hand back, and dropped
 * `timingSource` on the live-push path while the reconnect answer set it.
 *
 * It lives in `fx` rather than in `routes`/`plugins` because both transports depend on this
 * package and neither depends on the other; the engine's state flow carries this type directly.
 */
@Serializable
data class EffectDto(
    val id: Long,
    /**
     * The registry id ([FxInstance.effectTypeId]), not the effect's display name — this is the
     * string a client hands back on Update, and only the registry id resolves for a
     * user-defined FX definition.
     */
    val effectType: String,
    /** The fixture or group key, *without* the property suffix — [propertyName] carries that. */
    val targetKey: String,
    val propertyName: String,
    val beatDivision: Double,
    val blendMode: String,
    val isRunning: Boolean,
    val phaseOffset: Double,
    val currentPhase: Double,
    val parameters: Map<String, String>,
    val isGroupTarget: Boolean,
    val distributionStrategy: String? = null,
    val elementMode: String? = null,
    val elementFilter: String? = null,
    val stepTiming: Boolean = false,
    /**
     * The Look this effect came from, when it came from one. The field the busking pads' active
     * ring matches on.
     */
    val lookId: Int? = null,
    /** The programmer layer that spawned this effect, when one did. */
    val programmerLayerId: Int? = null,
    val cueId: Int? = null,
    /** The cue stack the [cueId] belongs to, when the effect was spawned by a cue. */
    val cueStackId: Int? = null,
    val timingSource: String = "BEAT",
    /** True when this effect sits in the programmer's reserved priority band. */
    val programmerOwned: Boolean = false,
    /** Fade envelope in `[0, 1]`; the effect's output is scaled by this before blending. */
    val intensityMultiplier: Double = 1.0,
    /** Speed master this effect subscribes to (null → master 1). */
    val speedMasterUuid: String? = null,
    /** 1-based display index of that master — what the FX-sheet chip renders. */
    val speedMasterIndex: Int = 1,
    /** Wall-clock rate master (null → unscaled). Only meaningful for WALL_CLOCK effects. */
    val rateSpeedMasterUuid: String? = null,
    /** 1-based display index of that rate master. */
    val rateSpeedMasterIndex: Int = 1,
)

/**
 * The speed master to *report* for this effect, as opposed to the one it stores.
 *
 * A BEAT effect reads only [FxInstance.speedMasterUuid] and a WALL_CLOCK one only
 * [FxInstance.rateSpeedMasterUuid], so reporting both would put a live chip on the FX sheet for a
 * field the effect cannot read — sweep item B4. Every effect report has to gate identically, or
 * the direct and indirect views of the same effect disagree, which is why this lives here rather
 * than being written out at each builder.
 *
 * The paired `*Index* fields stay unconditional, matching the null-uuid → index-1 "master 1"
 * convention used everywhere else, rather than adding a second gate that could disagree.
 */
internal val FxInstance.reportedSpeedMasterUuid: String?
    get() = if (timingSource == TimingSource.BEAT) speedMasterUuid?.toString() else null

/** The wall-clock rate master to report; see [reportedSpeedMasterUuid]. */
internal val FxInstance.reportedRateSpeedMasterUuid: String?
    get() = if (timingSource == TimingSource.WALL_CLOCK) rateSpeedMasterUuid?.toString() else null

/**
 * Build the report for one live effect. The only producer of [EffectDto].
 *
 * Prefer [FxEngine.effectDto] / [FxEngine.effectDtos], which supply both arguments from the
 * engine; this overload exists for the engine's own emit, which already holds them.
 *
 * @param masterStates one [SpeedMasterBank.masterStates] snapshot for the whole batch — it maps
 *   every slot into a fresh list, so calling it per effect makes the caller O(effects x masters).
 * @param isMultiElementExpanded [FxEngine.isMultiElementExpanded] for this instance; it gates the
 *   element fields, and only the engine can answer it. Deliberately has no default: defaulting it
 *   to `false` silently suppresses the element fields for any caller that forgets it.
 */
internal fun FxInstance.toEffectDto(
    masterStates: List<SpeedMasterBank.MasterState>,
    isMultiElementExpanded: Boolean,
): EffectDto {
    val showDistribution = isGroupEffect || isMultiElementExpanded
    return EffectDto(
        id = id,
        effectType = effectTypeId,
        targetKey = target.targetKey,
        propertyName = target.propertyName,
        beatDivision = timing.beatDivision,
        blendMode = blendMode.name,
        isRunning = isRunning,
        phaseOffset = phaseOffset,
        currentPhase = lastPhase,
        parameters = effect.parameters,
        isGroupTarget = isGroupEffect,
        distributionStrategy = if (showDistribution) distributionStrategy.javaClass.simpleName else null,
        elementMode = if (isGroupEffect && isMultiElementExpanded) elementMode.name else null,
        elementFilter = if (showDistribution && elementFilter != ElementFilter.ALL) elementFilter.name else null,
        stepTiming = stepTiming,
        lookId = lookId,
        programmerLayerId = programmerLayerId,
        cueId = cueId,
        cueStackId = cueStackId,
        timingSource = timingSource.name,
        programmerOwned = FxEngine.isProgrammerFxPriority(priority),
        intensityMultiplier = intensityMultiplier,
        speedMasterUuid = reportedSpeedMasterUuid,
        speedMasterIndex = masterStates.getOrNull(speedMasterSlot)?.index ?: 1,
        rateSpeedMasterUuid = reportedRateSpeedMasterUuid,
        rateSpeedMasterIndex = masterStates.getOrNull(rateMasterSlot)?.index ?: 1,
    )
}
