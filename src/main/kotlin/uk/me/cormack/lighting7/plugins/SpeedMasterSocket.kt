package uk.me.cormack.lighting7.plugins

import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.fx.SpeedMasterBank
import uk.me.cormack.lighting7.fx.speedMasterUuidOrNull
import uk.me.cormack.lighting7.models.SpeedMasterSource
import java.util.UUID

/**
 * The `speedMasters.*` WS family — live tempo control and streaming for the speed-master
 * bank, and the desk's only WS tempo surface. CRUD (create/rename/delete) is REST
 * (`/project/{id}/speed-masters`) with a `speedMasterListChanged` invalidation broadcast;
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
            withWriteTarget(message.masterUuid) { bank.setBpm(it, message.bpm, SpeedMasterSource.MANUAL) }
            scope.send(buildSpeedMastersState(bank))
        }
        is SpeedMastersTapInMessage -> {
            withWriteTarget(message.masterUuid) { bank.tap(it) }
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
 * would let a corrupt frame retune the global tempo. (An unknown-but-well-formed uuid is
 * dropped one layer down, by [SpeedMasterBank]'s write resolution.) The state reply still
 * goes out either way, so a stale client re-syncs.
 */
private inline fun withWriteTarget(raw: String?, write: (UUID?) -> Unit) {
    if (raw == null) {
        write(null)
        return
    }
    speedMasterUuidOrNull(raw)?.let(write)
}

// ─── Subscriptions ──────────────────────────────────────────────────────

fun setupSpeedMasterSubscriptions(scope: SocketScope) {
    val bank = scope.state.show.speedMasterBank

    // Exactly one state frame on connect — the connect burst is documented in
    // WsTestHelpers, and anything per-tick here would be a storm.
    scope.session.launch { scope.send(buildSpeedMastersState(bank)) }

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
            )
        },
    )
