package uk.me.cormack.lighting7.plugins

import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
)

@Serializable
@SerialName("fxState")
data class FxStateOutMessage(
    val bpm: Double,
    val isClockRunning: Boolean,
    val activeEffects: List<FxEffectState>,
    val palette: List<String> = emptyList(),
    val stackPalettes: Map<Int, List<String>> = emptyMap(),
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
        is SetFxBpmInMessage -> {
            engine.masterClock.setBpm(message.bpm)
            scope.send(buildFxStateMessage(scope.state))
        }
        is TapTempoInMessage -> {
            engine.masterClock.tap()
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
    scope.subscribe(clock.beatFlow.filter { beat -> beat.beatNumber % 16 == 0L || scope.sendNextBeat.get() }) { beat ->
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
        )
    }
    return FxStateOutMessage(
        bpm = engine.masterClock.bpm.value,
        isClockRunning = engine.masterClock.isRunning.value,
        activeEffects = effectStates,
        palette = engine.getPalette().map { it.toSerializedString() },
        stackPalettes = engine.getAllStackPalettes().mapValues { (_, colours) ->
            colours.map { it.toSerializedString() }
        },
    )
}
