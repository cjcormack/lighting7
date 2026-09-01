package uk.me.cormack.lighting7.plugins

import kotlinx.coroutines.flow.filter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.fx.SpeedMasterBank
import uk.me.cormack.lighting7.fx.speedMasterUuidOrNull
import uk.me.cormack.lighting7.models.CODE_SPEED_MASTER_FOLLOWER
import uk.me.cormack.lighting7.models.CODE_SPEED_MASTER_UNKNOWN
import uk.me.cormack.lighting7.models.SpeedMasterSource
import java.util.UUID

/**
 * The `speedMasters.*` WS family — live tempo control and streaming for the speed-master
 * bank, and the desk's only WS tempo surface. CRUD (create/rename/delete) is REST
 * (`/projects/{id}/speed-masters`) with a `speedMasters.listChanged` invalidation broadcast;
 * this family carries only what changes at performance rate: per-master BPM.
 *
 * A master is addressed by uuid throughout, with `null` meaning master 1 on every *inbound*
 * message — for a client that has no uuid to hand yet. Note the asymmetry on the way out:
 * `speedMasters.state` and `.beat` report master 1 by its real uuid once the bank has loaded,
 * and `null` only for the synthetic pre-load master 1. So a client can *ask* about master 1
 * with a null uuid, but must not expect to *recognise* its frames by one.
 */

// ─── Inbound ────────────────────────────────────────────────────────────

@Serializable
sealed class SpeedMasterInMessage : InMessage()

@Serializable
@SerialName("speedMasters.state")
data object SpeedMastersStateInMessage : SpeedMasterInMessage()

/** [masterUuid] null/omitted → master 1. */
@Serializable
@SerialName("speedMasters.setBpm")
data class SpeedMastersSetBpmInMessage(
    val bpm: Double,
    val masterUuid: String? = null,
) : SpeedMasterInMessage()

/** [masterUuid] null/omitted → master 1. */
@Serializable
@SerialName("speedMasters.tap")
data class SpeedMastersTapInMessage(
    val masterUuid: String? = null,
) : SpeedMasterInMessage()

/**
 * Ask for one immediate beat frame for [masterUuid] (null/omitted → master 1, resolved to its
 * live uuid by the handler), so an indicator that has just mounted — or just come back from a
 * backgrounded tab with a drifted local timer — doesn't wait out the throttle to lock phase.
 */
@Serializable
@SerialName("speedMasters.requestBeat")
data class SpeedMastersRequestBeatInMessage(
    val masterUuid: String? = null,
) : SpeedMasterInMessage()

// ─── Outbound ───────────────────────────────────────────────────────────

@Serializable
sealed class SpeedMasterOutMessage : OutMessage()

@Serializable
data class SpeedMasterStateJson(
    /** Null only for the synthetic pre-load master 1 (mid-boot). */
    val uuid: String?,
    val index: Int,
    val name: String,
    val bpm: Double,
    val isRunning: Boolean,
    /** `MANUAL` / `TAP` — how the tempo was last set. */
    val source: String,
    /** Effect-library category this master is the apply-time default for; null routes nothing. */
    val usage: String? = null,
    /** Follow ratio over master 1 (`bpm = m1 × num/den`); both null = manual tempo. */
    val followNum: Int? = null,
    val followDen: Int? = null,
)

/** Full bank snapshot — sent on connect, on request, and as the reply to every write. */
@Serializable
@SerialName("speedMasters.state")
data class SpeedMastersStateOutMessage(
    val masters: List<SpeedMasterStateJson>,
) : SpeedMasterOutMessage()

/** One master's tempo moved — the live-BPM push, at tap rate. */
@Serializable
@SerialName("speedMasters.changed")
data class SpeedMasterChangedOutMessage(
    val masterUuid: String?,
    val index: Int,
    val bpm: Double,
    val source: String,
    val timestampMs: Long,
) : SpeedMasterOutMessage()

/**
 * A tempo write was refused — the narrow failure ack (docs/websocket-engineering.md §"Reply
 * shape", option 2, the `surfaceLearn.error` precedent). Unicast to the writer, followed by the
 * usual full-state reply so a stale client snaps back to the truth it disagreed with.
 * [code] is `SPEED_MASTER_FOLLOWER` (the master follows master 1 — unlink it in the sheet) or
 * `SPEED_MASTER_UNKNOWN` (well-formed uuid naming no master — the write was dropped, not
 * redirected to master 1).
 */
@Serializable
@SerialName("speedMasters.error")
data class SpeedMasterErrorOutMessage(
    val masterUuid: String?,
    val code: String,
    val message: String,
) : SpeedMasterOutMessage()

/**
 * One master crossed a beat boundary.
 *
 * Throttled to one frame per [BEAT_FRAME_INTERVAL] beats (plus `speedMasters.requestBeat`):
 * the client runs a local timer off [bpm] between frames and only needs the server to correct
 * its drift. Every master rides this stream, master 1 included — under its real uuid once the
 * bank has loaded, so a client keys beats exactly as it keys `speedMasters.state`.
 */
@Serializable
@SerialName("speedMasters.beat")
data class SpeedMasterBeatOutMessage(
    val masterUuid: String?,
    val index: Int,
    val beatNumber: Long,
    val bpm: Double,
    val timestampMs: Long,
) : SpeedMasterOutMessage()

/** Beats between unsolicited beat frames — ~8s at 120 BPM. */
private const val BEAT_FRAME_INTERVAL = 16L

// ─── Handler ────────────────────────────────────────────────────────────

suspend fun handleSpeedMasters(scope: SocketScope, message: SpeedMasterInMessage) {
    val bank = scope.state.show.speedMasterBank
    when (message) {
        is SpeedMastersStateInMessage -> scope.send(buildSpeedMastersState(bank))
        is SpeedMastersSetBpmInMessage -> {
            val outcome = withWriteTarget(message.masterUuid) { bank.setBpm(it, message.bpm, SpeedMasterSource.MANUAL) }
            reportTempoWrite(scope, message.masterUuid, outcome)
            scope.send(buildSpeedMastersState(bank))
        }
        is SpeedMastersTapInMessage -> {
            val outcome = withWriteTarget(message.masterUuid) { bank.tap(it) }
            reportTempoWrite(scope, message.masterUuid, outcome)
            scope.send(buildSpeedMastersState(bank))
        }
        is SpeedMastersRequestBeatInMessage -> {
            // An omitted uuid resolves to master 1's *live* uuid rather than parking `null`.
            // Requests are matched against `SpeedMasterBank.Beat.uuid`, which is tagged from
            // the bank entry, so a loaded master 1 emits its real uuid — a parked `null`
            // could never match, leaving the request silently unsatisfiable and the entry in
            // the set for the life of the connection. Pre-load, `master1Uuid()` is itself
            // null, which is exactly what the synthetic master's beats carry.
            //
            // Unlike a tempo write, a garbled uuid here is harmless — it just parks a
            // request nothing will ever match — but drop it anyway rather than letting it
            // resolve to master 1 and pulse the wrong indicator.
            if (message.masterUuid == null) {
                scope.pendingBeatRequests.add(bank.master1Uuid())
            } else {
                speedMasterUuidOrNull(message.masterUuid)?.let { scope.pendingBeatRequests.add(it) }
            }
        }
    }
}

/**
 * Resolve a tempo-write target. Null/omitted means master 1 (the strip's M1 tile, and any
 * caller with no uuid yet); a present-but-garbled uuid DROPS the write — degrading it to master 1
 * would let a corrupt frame retune the global tempo — and reports [UnknownMaster], the same
 * outcome the bank's own write resolution answers for an unknown-but-well-formed uuid. The
 * state reply still goes out either way, so a stale client re-syncs.
 */
private inline fun withWriteTarget(
    raw: String?,
    write: (UUID?) -> SpeedMasterBank.TempoWriteOutcome,
): SpeedMasterBank.TempoWriteOutcome {
    if (raw == null) return write(null)
    val uuid = speedMasterUuidOrNull(raw) ?: return SpeedMasterBank.TempoWriteOutcome.UnknownMaster
    return write(uuid)
}

/** Send the failure ack for a refused/dropped tempo write; [TempoWriteOutcome.Applied] is silent. */
private suspend fun reportTempoWrite(
    scope: SocketScope,
    requestedUuid: String?,
    outcome: SpeedMasterBank.TempoWriteOutcome,
) {
    when (outcome) {
        SpeedMasterBank.TempoWriteOutcome.Applied -> {}

        SpeedMasterBank.TempoWriteOutcome.UnknownMaster -> scope.send(
            SpeedMasterErrorOutMessage(
                masterUuid = requestedUuid,
                code = CODE_SPEED_MASTER_UNKNOWN,
                message = "No speed master with that uuid — the write was dropped",
            )
        )

        is SpeedMasterBank.TempoWriteOutcome.RefusedFollower -> scope.send(
            SpeedMasterErrorOutMessage(
                masterUuid = requestedUuid,
                code = CODE_SPEED_MASTER_FOLLOWER,
                message = outcome.describe,
            )
        )
    }
}

// ─── Subscriptions ──────────────────────────────────────────────────────

fun setupSpeedMasterSubscriptions(scope: SocketScope) {
    val bank = scope.state.show.speedMasterBank

    // Exactly one state frame on connect (the one-snapshot rule in
    // docs/websocket-engineering.md); anything per-tick here would be a storm.
    scope.sendSnapshot { send(buildSpeedMastersState(bank)) }

    scope.subscribe(bank.changes) { change ->
        scope.send(
            SpeedMasterChangedOutMessage(
                masterUuid = change.uuid?.toString(),
                index = change.index,
                bpm = change.bpm,
                source = change.source.name,
                timestampMs = change.timestampMs,
            )
        )
    }

    // One subscription for the whole bank, not one per master: `bank.beats` is already
    // tagged at emit time, so a master added or removed by a reload needs no re-binding
    // here. `remove` is the consume half of the one-shot request — it returns whether the
    // request was pending AND clears it, so the throttle resumes on the next beat.
    scope.subscribe(
        bank.beats.filter { beat ->
            beat.beatNumber % BEAT_FRAME_INTERVAL == 0L || scope.pendingBeatRequests.remove(beat.uuid)
        }
    ) { beat ->
        scope.send(
            SpeedMasterBeatOutMessage(
                masterUuid = beat.uuid?.toString(),
                index = beat.index,
                beatNumber = beat.beatNumber,
                bpm = beat.bpm,
                timestampMs = beat.timestampMs,
            )
        )
    }
}

// ─── Helpers ────────────────────────────────────────────────────────────

private fun buildSpeedMastersState(bank: SpeedMasterBank): SpeedMastersStateOutMessage =
    SpeedMastersStateOutMessage(
        masters = bank.masterStates().map {
            SpeedMasterStateJson(
                uuid = it.uuid?.toString(),
                index = it.index,
                name = it.name,
                bpm = it.bpm,
                isRunning = it.isRunning,
                source = it.source.name,
                usage = it.usage,
                followNum = it.followNum,
                followDen = it.followDen,
            )
        },
    )
