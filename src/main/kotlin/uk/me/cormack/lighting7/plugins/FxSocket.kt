package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.fx.TimingSource
import uk.me.cormack.lighting7.state.State

// ─── Inbound ────────────────────────────────────────────────────────────

@Serializable
sealed class FxInMessage : InMessage()

@Serializable
@SerialName("fxState")
data object FxStateInMessage : FxInMessage()

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

/**
 * The active-effect list. Purely an effect frame: tempo is not in it, and never was in it
 * for more than master 1 — the `bpm` / `isClockRunning` fields carried master 1's clock
 * because this message predates the speed-master bank. Tempo now lives on the
 * `speedMasters.*` family, per-master and keyed.
 */
@Serializable
@SerialName("fxState")
data class FxStateOutMessage(
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

// ─── Handler ────────────────────────────────────────────────────────────

suspend fun handleFx(scope: SocketScope, message: FxInMessage) {
    val engine = scope.state.show.fxEngine
    when (message) {
        is FxStateInMessage -> scope.send(buildFxStateMessage(scope.state))
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
    }
}

// ─── Subscriptions ──────────────────────────────────────────────────────

fun setupFxSubscriptions(scope: SocketScope) {
    val state = scope.state
    val engine = state.show.fxEngine

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
        scope.send(FxStateOutMessage(activeEffects = effectStates))
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
            // BEAT effects read only speedMasterSlot, WALL_CLOCK only rateMasterSlot — report
            // just the consumed identity, not both (sweep item B4). Must match
            // FxEngine.emitStateUpdate's gating exactly, or a reconnect answer and the next live
            // push disagree. The paired *Index* field stays unconditional, same as there.
            speedMasterUuid = if (effect.timingSource == TimingSource.BEAT)
                effect.speedMasterUuid?.toString() else null,
            speedMasterIndex = masterStates.getOrNull(effect.speedMasterSlot)?.index ?: 1,
            rateSpeedMasterUuid = if (effect.timingSource == TimingSource.WALL_CLOCK)
                effect.rateSpeedMasterUuid?.toString() else null,
            rateSpeedMasterIndex = masterStates.getOrNull(effect.rateMasterSlot)?.index ?: 1,
        )
    }
    return FxStateOutMessage(activeEffects = effectStates)
}
