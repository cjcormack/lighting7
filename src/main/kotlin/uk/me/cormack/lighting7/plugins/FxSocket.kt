package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.fx.EffectDto
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

/**
 * The active-effect list. Purely an effect frame: tempo is not in it, and never was in it
 * for more than master 1 — the `bpm` / `isClockRunning` fields carried master 1's clock
 * because this message predates the speed-master bank. Tempo now lives on the
 * `speedMasters.*` family, per-master and keyed.
 */
@Serializable
@SerialName("fxState")
data class FxStateOutMessage(
    val activeEffects: List<EffectDto>,
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
    val engine = scope.state.show.fxEngine

    scope.subscribe(engine.fxStateFlow) { update ->
        scope.send(FxStateOutMessage(activeEffects = update.effectStates.values.toList()))
    }
}

// ─── Helpers ────────────────────────────────────────────────────────────

private fun buildFxStateMessage(state: State): FxStateOutMessage {
    val engine = state.show.fxEngine
    return FxStateOutMessage(activeEffects = engine.effectDtos(engine.getActiveEffects()))
}
