package uk.me.cormack.lighting7.plugins

import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.models.SpeedMasterSource
import uk.me.cormack.lighting7.state.State

// ─── Inbound ────────────────────────────────────────────────────────────

@Serializable
sealed class FxInMessage : InMessage()

@Serializable
@SerialName("fxState")
data object FxStateInMessage : FxInMessage()

@Serializable
@SerialName("setFxBpm")
data class SetFxBpmInMessage(val bpm: Double) : FxInMessage()

@Serializable
@SerialName("tapTempo")
data object TapTempoInMessage : FxInMessage()

@Serializable
@SerialName("addFx")
data class AddFxInMessage(
    val effectType: String,
    val fixtureKey: String,
    val propertyName: String,
    val beatDivision: Double = 1.0,
    val blendMode: String = "OVERRIDE",
    val phaseOffset: Double = 0.0,
) : FxInMessage()

@Serializable
@SerialName("removeFx")
data class RemoveFxInMessage(val effectId: Long) : FxInMessage()

@Serializable
@SerialName("pauseFx")
data class PauseFxInMessage(val effectId: Long) : FxInMessage()

@Serializable
@SerialName("resumeFx")
data class ResumeFxInMessage(val effectId: Long) : FxInMessage()

@Serializable
@SerialName("clearFx")
data object ClearFxInMessage : FxInMessage()

@Serializable
@SerialName("requestBeatSync")
data object RequestBeatSyncInMessage : FxInMessage()

// ─── Outbound ───────────────────────────────────────────────────────────

@Serializable
sealed class FxOutMessage : OutMessage()

@Serializable
data class FxEffectState(
    val id: Long,
    val effectType: String,
    val targetKey: String,
    val isRunning: Boolean,
    val phase: Double,
    val blendMode: String,
    val cueId: Int? = null,
    val cueStackId: Int? = null,
    val timingSource: String = "BEAT",
    /** Speed master this effect subscribes to (null → master 1). */
    val speedMasterUuid: String? = null,
    /** 1-based display index of that master — what the FX-sheet chip renders. */
    val speedMasterIndex: Int = 1,
    /** Wall-clock rate master (null → unscaled); only WALL_CLOCK effects read it. */
    val rateSpeedMasterUuid: String? = null,
    /** 1-based display index of that rate master. */
    val rateSpeedMasterIndex: Int = 1,
)

@Serializable
@SerialName("fxState")
data class FxStateOutMessage(
    val bpm: Double,
    val isClockRunning: Boolean,
    val activeEffects: List<FxEffectState>,
) : FxOutMessage()

@Serializable
enum class FxChangeType {
    @SerialName("added") ADDED,
    @SerialName("removed") REMOVED,
    @SerialName("updated") UPDATED,
    @SerialName("cleared") CLEARED,
}

@Serializable
@SerialName("fxChanged")
data class FxChangedOutMessage(
    val changeType: FxChangeType,
    val effectId: Long? = null,
) : FxOutMessage()

@Serializable
@SerialName("beatSync")
data class BeatSyncOutMessage(
    val beatNumber: Long,
    val bpm: Double,
    val timestampMs: Long,
) : FxOutMessage()

// ─── Handler ────────────────────────────────────────────────────────────

suspend fun handleFx(scope: SocketScope, message: FxInMessage) {
    val engine = scope.state.show.fxEngine
    when (message) {
        is FxStateInMessage -> scope.send(buildFxStateMessage(scope.state))
        // The legacy unkeyed tempo messages mean master 1. Routed through the bank rather
        // than the clock so source tracking, the speedMasters.changed push, and the
        // write-through persister all see the change.
        is SetFxBpmInMessage -> {
            engine.speedMasters.setBpm(null, message.bpm, SpeedMasterSource.MANUAL)
            scope.send(buildFxStateMessage(scope.state))
        }
        is TapTempoInMessage -> {
            engine.speedMasters.tap(null)
            scope.send(buildFxStateMessage(scope.state))
        }
        is RemoveFxInMessage -> {
            engine.removeEffect(message.effectId)
            scope.send(FxChangedOutMessage(FxChangeType.REMOVED, message.effectId))
        }
        is PauseFxInMessage -> {
            engine.pauseEffect(message.effectId)
            scope.send(FxChangedOutMessage(FxChangeType.UPDATED, message.effectId))
        }
        is ResumeFxInMessage -> {
            engine.resumeEffect(message.effectId)
            scope.send(FxChangedOutMessage(FxChangeType.UPDATED, message.effectId))
        }
        is ClearFxInMessage -> {
            engine.clearAllEffects()
            scope.send(FxChangedOutMessage(FxChangeType.CLEARED))
        }
        is AddFxInMessage -> {
            // Complex effect creation goes through the REST API; the WS path is intentionally a no-op.
        }
        is RequestBeatSyncInMessage -> scope.sendNextBeat.set(true)
    }
}

// ─── Subscriptions ──────────────────────────────────────────────────────

fun setupFxSubscriptions(scope: SocketScope) {
    val state = scope.state
    val engine = state.show.fxEngine
    val clock = engine.masterClock

    scope.subscribe(engine.fxStateFlow) { update ->
        val effectStates = update.effectStates.values.map { effectState ->
            FxEffectState(
                id = effectState.id,
                effectType = effectState.effectType,
                targetKey = effectState.targetKey,
                isRunning = effectState.isRunning,
                phase = effectState.currentPhase,
                blendMode = effectState.blendMode.name,
                cueId = effectState.cueId,
                cueStackId = effectState.cueStackId,
                // timingSource was silently dropped by this remap while buildFxStateMessage
                // set it — carry both it and the master fields so the two FxEffectState
                // producers can't disagree.
                timingSource = effectState.timingSource,
                speedMasterUuid = effectState.speedMasterUuid,
                speedMasterIndex = effectState.speedMasterIndex,
                rateSpeedMasterUuid = effectState.rateSpeedMasterUuid,
                rateSpeedMasterIndex = effectState.rateSpeedMasterIndex,
            )
        }
        scope.send(FxStateOutMessage(
            bpm = clock.bpm.value,
            isClockRunning = clock.isRunning.value,
            activeEffects = effectStates,
        ))
    }

    // Periodic beat sync for UI drift correction (every 16 beats ≈ 8s at 120 BPM), plus an
    // immediate sync on the next beat when [SocketScope.sendNextBeat] is set by a
    // requestBeatSync message.
    //
    // `getAndSet(false)` rather than `get()`: the flag starts life `true` and nothing ever
    // cleared it, so this filter was permanently short-circuited and every connection got a
    // frame on EVERY beat — 16x the intended traffic, and the documented "every 16 beats"
    // cadence was never what actually shipped. Consuming the flag makes the request the
    // one-shot it was always described as. Note this makes the client's local interpolation
    // load-bearing for the first time; BeatIndicator has always had it.
    scope.subscribe(
        clock.beatFlow.filter { beat -> beat.beatNumber % 16 == 0L || scope.sendNextBeat.getAndSet(false) }
    ) { beat ->
        scope.send(BeatSyncOutMessage(
            beatNumber = beat.beatNumber,
            bpm = clock.bpm.value,
            timestampMs = beat.timestampMs,
        ))
    }

    // Immediate beat sync whenever BPM changes (tap tempo, setBpm, etc.). beatNumber=-1 marks
    // these BPM-change syncs so the frontend can distinguish them from beat-boundary sync.
    scope.subscribe(clock.bpm.drop(1)) { newBpm ->
        scope.send(BeatSyncOutMessage(
            beatNumber = -1,
            bpm = newBpm,
            timestampMs = System.currentTimeMillis(),
        ))
    }
}

// ─── Helpers ────────────────────────────────────────────────────────────

private fun buildFxStateMessage(state: State): FxStateOutMessage {
    val engine = state.show.fxEngine
    // Hoisted for the same reason as FxEngine.emitStateUpdate: masterStates() allocates a
    // list per call, and this map runs once per active effect.
    val masterStates = engine.speedMasters.masterStates()
    val effectStates = engine.getActiveEffects().map { effect ->
        FxEffectState(
            id = effect.id,
            effectType = effect.effect.name,
            targetKey = "${effect.target.targetKey}.${effect.target.propertyName}",
            isRunning = effect.isRunning,
            phase = effect.lastPhase,
            blendMode = effect.blendMode.name,
            cueId = effect.cueId,
            cueStackId = effect.cueStackId,
            timingSource = effect.timingSource.name,
            speedMasterUuid = effect.speedMasterUuid?.toString(),
            speedMasterIndex = masterStates.getOrNull(effect.speedMasterSlot)?.index ?: 1,
            rateSpeedMasterUuid = effect.rateSpeedMasterUuid?.toString(),
            rateSpeedMasterIndex = masterStates.getOrNull(effect.rateMasterSlot)?.index ?: 1,
        )
    }
    return FxStateOutMessage(
        bpm = engine.masterClock.bpm.value,
        isClockRunning = engine.masterClock.isRunning.value,
        activeEffects = effectStates,
    )
}
