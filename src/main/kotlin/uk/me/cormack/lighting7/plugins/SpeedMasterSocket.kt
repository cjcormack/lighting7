package uk.me.cormack.lighting7.plugins

import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.fx.SpeedMasterBank
import uk.me.cormack.lighting7.fx.speedMasterUuidOrNull
import uk.me.cormack.lighting7.models.SpeedMasterSource
import java.util.UUID

/**
 * The `speedMasters.*` WS family — live tempo control and streaming for the speed-master
 * bank. CRUD (create/rename/delete) is REST (`/project/{id}/speed-masters`) with a
 * `speedMasterListChanged` invalidation broadcast; this family carries only what changes
 * at performance rate: per-master BPM.
 *
 * The legacy unkeyed `setFxBpm`/`tapTempo`/`beatSync` messages stay bound to master 1
 * (see `FxSocket`) — this family is the keyed superset, not a replacement.
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

/** One master's tempo moved. The per-master analogue of the master-1-only `beatSync` push. */
@Serializable
@SerialName("speedMasters.changed")
data class SpeedMasterChangedOutMessage(
    val masterUuid: String?,
    val index: Int,
    val bpm: Double,
    val source: String,
    val timestampMs: Long,
) : SpeedMasterOutMessage()

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
    }
}

/**
 * Resolve a tempo-write target. Null/omitted means master 1 (the strip's M1 tile and the
 * legacy surfaces); a present-but-garbled uuid DROPS the write — degrading it to master 1
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
