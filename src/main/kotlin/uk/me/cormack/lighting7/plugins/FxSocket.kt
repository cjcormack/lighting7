package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.SerialName
import uk.me.cormack.lighting7.models.Timing
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.fx.FxDto
import uk.me.cormack.lighting7.fx.FxEngine
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.routes.CODE_FX_MOVE_NOT_LOCAL
import uk.me.cormack.lighting7.routes.CODE_FX_NOT_FOUND
import uk.me.cormack.lighting7.routes.CODE_FX_UPDATE_REFUSED
import uk.me.cormack.lighting7.routes.FxUpdateOutcome
import uk.me.cormack.lighting7.routes.UpdateFxRequest
import uk.me.cormack.lighting7.routes.applyFxUpdate
import uk.me.cormack.lighting7.state.State

// ─── Inbound ────────────────────────────────────────────────────────────

@Serializable
sealed class FxInMessage : InMessage()

@Serializable
@SerialName("fxState")
data object FxStateInMessage : FxInMessage()

@Serializable
@SerialName("removeFx")
data class RemoveFxInMessage(val fxId: Long) : FxInMessage()

@Serializable
@SerialName("pauseFx")
data class PauseFxInMessage(val fxId: Long) : FxInMessage()

@Serializable
@SerialName("resumeFx")
data class ResumeFxInMessage(val fxId: Long) : FxInMessage()

@Serializable
@SerialName("clearFx")
data object ClearFxInMessage : FxInMessage()

/**
 * Edit a running effect in place, keeping its id and phase — `PUT /fx/{id}` as a frame, for the
 * live FX editor whose every drag is a write (fixture-fx-sheets plan W3, P4: the FX family's
 * spelling of the boards' `fx.update`). Every field but [fxId] is [UpdateFxRequest]'s, by
 * the same name and with the same "null keeps it" meaning, and [toRequest] hands them to the one
 * parse both doors share ([applyFxUpdate]); `FxLiveEditRoutesTest`'s `the frame carries exactly
 * the request's fields` pins the two field lists equal, so a field added to the request cannot be
 * missed here.
 *
 * Answered `fxChanged(UPDATED)` as `pauseFx` is, or `fxError` for an unknown effect or a refused
 * field. With [targets] naming some of the effect's heads it splits them off (apply-model plan §3.4):
 * answered `fxChanged(REMOVED)` for the effect and `fxChanged(ADDED)` for each copy, the edited
 * first, or `fxError` with `FX_SPLIT_LAYER_FX` / `FX_SPLIT_NOT_LOCAL`. Gated exactly as `pauseFx` and `removeFx` are — an operator gesture on both roles, so
 * `FU-AUTH-WS-PER-MESSAGE` (an *admin-only* operation gaining a socket command) does not fire.
 */
@Serializable
@SerialName("updateFx")
data class UpdateFxInMessage(
    val fxId: Long,
    val fxType: String? = null,
    val parameters: Map<String, String>? = null,
    val cycle: Double? = null,
    val blendMode: String? = null,
    val phaseOffset: Double? = null,
    val order: String? = null,
    val elementMode: String? = null,
    val elementFilter: String? = null,
    val timing: Timing? = null,
    val speedMasterUuid: String? = null,
    val rateSpeedMasterUuid: String? = null,
    val seconds: Boolean? = null,
    val amount: Double? = null,
    val direction: String? = null,
    val runs: Int? = null,
    val runForever: Boolean = false,
    val spread: Double? = null,
    val wings: Int? = null,
    val segments: Int? = null,
    val parts: Int? = null,
    val spreadUnit: String? = null,
    val targets: List<CueTargetDto>? = null,
) : FxInMessage() {
    fun toRequest() = UpdateFxRequest(
        fxType = fxType,
        parameters = parameters,
        cycle = cycle,
        blendMode = blendMode,
        phaseOffset = phaseOffset,
        order = order,
        elementMode = elementMode,
        elementFilter = elementFilter,
        timing = timing,
        speedMasterUuid = speedMasterUuid,
        rateSpeedMasterUuid = rateSpeedMasterUuid,
        seconds = seconds,
        amount = amount,
        direction = direction,
        runs = runs,
        spread = spread,
        wings = wings,
        segments = segments,
        parts = parts,
        spreadUnit = spreadUnit,
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
    val fxId: Long,
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
    val activeFx: List<FxDto>,
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
    val fxId: Long? = null,
) : FxOutMessage()

/**
 * A refused `updateFx`, to the socket that sent it: [code] is `FX_NOT_FOUND` for an effect that is
 * not running, `FX_UPDATE_REFUSED` for a field the strict policy refuses (an unknown blend, an
 * unknown effect type, a malformed master uuid), `FX_SPLIT_LAYER_FX` / `FX_SPLIT_NOT_LOCAL` for a
 * split of an effect that is not Local — `PUT /fx/{id}`'s 404, 400 and 409, keyed by the effect
 * so a client can replace one toast per effect rather than stack one per drag frame. A refused
 * `programmer.moveFx` is keyed by the FX it moved: `FX_NOT_FOUND` or `FX_MOVE_NOT_LOCAL`.
 */
@Serializable
@SerialName("fxError")
data class FxErrorOutMessage(
    val fxId: Long,
    val code: String,
    val message: String,
) : FxOutMessage()

// ─── Handler ────────────────────────────────────────────────────────────

suspend fun handleFx(scope: SocketScope, message: FxInMessage) {
    val engine = scope.state.show.fxEngine
    when (message) {
        is FxStateInMessage -> scope.send(buildFxStateMessage(scope.state))
        is RemoveFxInMessage -> {
            engine.removeFx(message.fxId)
            scope.send(FxChangedOutMessage(FxChangeType.REMOVED, message.fxId))
        }
        is PauseFxInMessage -> {
            engine.pauseFx(message.fxId)
            scope.send(FxChangedOutMessage(FxChangeType.UPDATED, message.fxId))
        }
        is ResumeFxInMessage -> {
            engine.resumeFx(message.fxId)
            scope.send(FxChangedOutMessage(FxChangeType.UPDATED, message.fxId))
        }
        is ClearFxInMessage -> {
            engine.clearAllFx()
            scope.send(FxChangedOutMessage(FxChangeType.CLEARED))
        }
        is UpdateFxInMessage -> when (val outcome = applyFxUpdate(scope.state, message.fxId, message.toRequest())) {
            is FxUpdateOutcome.Updated -> scope.send(FxChangedOutMessage(FxChangeType.UPDATED, message.fxId))
            is FxUpdateOutcome.Split -> {
                scope.send(FxChangedOutMessage(FxChangeType.REMOVED, message.fxId))
                for (copy in outcome.edited + outcome.rest) scope.send(FxChangedOutMessage(FxChangeType.ADDED, copy.id))
            }
            is FxUpdateOutcome.Conflict -> scope.send(FxErrorOutMessage(message.fxId, outcome.code, outcome.message))
            FxUpdateOutcome.NotFound -> scope.send(
                FxErrorOutMessage(message.fxId, CODE_FX_NOT_FOUND, "FX ${message.fxId} is not running"),
            )
            is FxUpdateOutcome.Refused -> scope.send(
                FxErrorOutMessage(message.fxId, CODE_FX_UPDATE_REFUSED, outcome.message),
            )
        }
        is ProgrammerMoveFxInMessage -> when (val outcome = engine.moveLocalFx(message.fxId, message.above)) {
            is FxEngine.LocalMoveOutcome.Moved ->
                for (id in outcome.changed) scope.send(FxChangedOutMessage(FxChangeType.UPDATED, id))
            is FxEngine.LocalMoveOutcome.NotFound -> scope.send(
                FxErrorOutMessage(message.fxId, CODE_FX_NOT_FOUND, "FX ${outcome.fxId} is not running"),
            )
            is FxEngine.LocalMoveOutcome.NotLocal -> scope.send(
                FxErrorOutMessage(
                    message.fxId, CODE_FX_MOVE_NOT_LOCAL,
                    "FX ${outcome.fxId} is not a Local FX — only Local FX are re-ranked",
                ),
            )
        }
    }
}

// ─── Subscriptions ──────────────────────────────────────────────────────

fun setupFxSubscriptions(scope: SocketScope) {
    scope.subscribeShow({ it.fxEngine.fxStateFlow }) { update ->
        scope.send(FxStateOutMessage(activeFx = update.fxStates.values.toList()))
    }
}

// ─── Helpers ────────────────────────────────────────────────────────────

private fun buildFxStateMessage(state: State): FxStateOutMessage {
    val engine = state.show.fxEngine
    return FxStateOutMessage(activeFx = engine.fxDtos(engine.getActiveFx()))
}
