package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.fx.EffectDto
import uk.me.cormack.lighting7.fx.FxEngine
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.routes.CODE_FX_MOVE_NOT_LOCAL
import uk.me.cormack.lighting7.routes.CODE_FX_NOT_FOUND
import uk.me.cormack.lighting7.routes.CODE_FX_UPDATE_REFUSED
import uk.me.cormack.lighting7.routes.EffectUpdateOutcome
import uk.me.cormack.lighting7.routes.UpdateEffectRequest
import uk.me.cormack.lighting7.routes.applyEffectUpdate
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

/**
 * Edit a running effect in place, keeping its id and phase — `PUT /fx/{id}` as a frame, for the
 * live FX editor whose every drag is a write (fixture-fx-sheets plan W3, P4: the FX family's
 * spelling of the boards' `fx.update`). Every field but [effectId] is [UpdateEffectRequest]'s, by
 * the same name and with the same "null keeps it" meaning, and [toRequest] hands them to the one
 * parse both doors share ([applyEffectUpdate]); `FxLiveEditRoutesTest`'s `the frame carries exactly
 * the request's fields` pins the two field lists equal, so a field added to the request cannot be
 * missed here.
 *
 * Answered `fxChanged(UPDATED)` as `pauseFx` is, or `fxError` for an unknown effect or a refused
 * field. With [targets] naming some of the effect's heads it splits them off (apply-model plan §3.4):
 * answered `fxChanged(REMOVED)` for the effect and `fxChanged(ADDED)` for each copy, the edited
 * first, or `fxError` with `FX_SPLIT_LAYER_EFFECT` / `FX_SPLIT_NOT_LOCAL`. Gated exactly as `pauseFx` and `removeFx` are — an operator gesture on both roles, so
 * `FU-AUTH-WS-PER-MESSAGE` (an *admin-only* operation gaining a socket command) does not fire.
 */
@Serializable
@SerialName("updateFx")
data class UpdateFxInMessage(
    val effectId: Long,
    val effectType: String? = null,
    val parameters: Map<String, String>? = null,
    val beatDivision: Double? = null,
    val blendMode: String? = null,
    val phaseOffset: Double? = null,
    val distributionStrategy: String? = null,
    val elementMode: String? = null,
    val elementFilter: String? = null,
    val stepTiming: Boolean? = null,
    val speedMasterUuid: String? = null,
    val rateSpeedMasterUuid: String? = null,
    val seconds: Boolean? = null,
    val size: Double? = null,
    val direction: String? = null,
    val nShot: Int? = null,
    val runForever: Boolean = false,
    val spread: Double? = null,
    val wings: Int? = null,
    val blocks: Int? = null,
    val groups: Int? = null,
    val targets: List<CueTargetDto>? = null,
) : FxInMessage() {
    fun toRequest() = UpdateEffectRequest(
        effectType = effectType,
        parameters = parameters,
        beatDivision = beatDivision,
        blendMode = blendMode,
        phaseOffset = phaseOffset,
        distributionStrategy = distributionStrategy,
        elementMode = elementMode,
        elementFilter = elementFilter,
        stepTiming = stepTiming,
        speedMasterUuid = speedMasterUuid,
        rateSpeedMasterUuid = rateSpeedMasterUuid,
        seconds = seconds,
        size = size,
        direction = direction,
        nShot = nShot,
        spread = spread,
        wings = wings,
        blocks = blocks,
        groups = groups,
        runForever = runForever,
        targets = targets,
    )
}

/**
 * Re-rank a Local FX in apply order (FX editor plan §3.5): directly below [above]'s FX, or at the
 * bottom of the Local FX when [above] is null. Answered `fxChanged(UPDATED)` for each FX whose
 * priority moved, or `fxError` — `FX_MOVE_NOT_LOCAL` when either FX is not Local, `FX_NOT_FOUND` when
 * either is not running. An operator gesture, so `FU-AUTH-WS-PER-MESSAGE` does not fire.
 */
@Serializable
@SerialName("programmer.moveFx")
data class ProgrammerMoveFxInMessage(
    val effectId: Long,
    val above: Long? = null,
) : FxInMessage()

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

/**
 * A refused `updateFx`, to the socket that sent it: [code] is `FX_NOT_FOUND` for an effect that is
 * not running, `FX_UPDATE_REFUSED` for a field the strict policy refuses (an unknown blend, an
 * unknown effect type, a malformed master uuid), `FX_SPLIT_LAYER_EFFECT` / `FX_SPLIT_NOT_LOCAL` for a
 * split of an effect that is not Local — `PUT /fx/{id}`'s 404, 400 and 409, keyed by the effect
 * so a client can replace one toast per effect rather than stack one per drag frame. A refused
 * `programmer.moveFx` is keyed by the FX it moved: `FX_NOT_FOUND` or `FX_MOVE_NOT_LOCAL`.
 */
@Serializable
@SerialName("fxError")
data class FxErrorOutMessage(
    val effectId: Long,
    val code: String,
    val message: String,
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
        is UpdateFxInMessage -> when (val outcome = applyEffectUpdate(scope.state, message.effectId, message.toRequest())) {
            is EffectUpdateOutcome.Updated -> scope.send(FxChangedOutMessage(FxChangeType.UPDATED, message.effectId))
            is EffectUpdateOutcome.Split -> {
                scope.send(FxChangedOutMessage(FxChangeType.REMOVED, message.effectId))
                for (copy in outcome.edited + outcome.rest) scope.send(FxChangedOutMessage(FxChangeType.ADDED, copy.id))
            }
            is EffectUpdateOutcome.Conflict -> scope.send(FxErrorOutMessage(message.effectId, outcome.code, outcome.message))
            EffectUpdateOutcome.NotFound -> scope.send(
                FxErrorOutMessage(message.effectId, CODE_FX_NOT_FOUND, "Effect ${message.effectId} is not running"),
            )
            is EffectUpdateOutcome.Refused -> scope.send(
                FxErrorOutMessage(message.effectId, CODE_FX_UPDATE_REFUSED, outcome.message),
            )
        }
        is ProgrammerMoveFxInMessage -> when (val outcome = engine.moveLocalEffect(message.effectId, message.above)) {
            is FxEngine.LocalMoveOutcome.Moved ->
                for (id in outcome.changed) scope.send(FxChangedOutMessage(FxChangeType.UPDATED, id))
            is FxEngine.LocalMoveOutcome.NotFound -> scope.send(
                FxErrorOutMessage(message.effectId, CODE_FX_NOT_FOUND, "Effect ${outcome.effectId} is not running"),
            )
            is FxEngine.LocalMoveOutcome.NotLocal -> scope.send(
                FxErrorOutMessage(
                    message.effectId, CODE_FX_MOVE_NOT_LOCAL,
                    "Effect ${outcome.effectId} is not a Local FX — only Local FX are re-ranked",
                ),
            )
        }
    }
}

// ─── Subscriptions ──────────────────────────────────────────────────────

fun setupFxSubscriptions(scope: SocketScope) {
    scope.subscribeShow({ it.fxEngine.fxStateFlow }) { update ->
        scope.send(FxStateOutMessage(activeEffects = update.effectStates.values.toList()))
    }
}

// ─── Helpers ────────────────────────────────────────────────────────────

private fun buildFxStateMessage(state: State): FxStateOutMessage {
    val engine = state.show.fxEngine
    return FxStateOutMessage(activeEffects = engine.effectDtos(engine.getActiveEffects()))
}
