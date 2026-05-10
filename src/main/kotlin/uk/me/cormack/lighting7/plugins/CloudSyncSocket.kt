package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.sync.SyncLogEntryDto

// ─── Outbound (no inbound — events come from REST handlers via State.cloudSyncEventsFlow) ───

@Serializable
sealed class CloudSyncOutMessage : OutMessage()

/**
 * Lifecycle broadcasts for the unified `POST /sync/run` endpoint. The frontend uses these
 * to disable the Sync-now button while one is in flight, refresh status / log panels on
 * completion, and surface toasts. Only emitted on transitions — single done/fail message
 * is enough for the data volumes we support.
 */
@Serializable
@SerialName("cloudSyncStarted")
data class CloudSyncStartedOutMessage(val projectId: Int) : CloudSyncOutMessage()

@Serializable
@SerialName("cloudSyncDone")
data class CloudSyncDoneOutMessage(
    val projectId: Int,
    val outcome: String,
    val headSha: String,
    val pushed: Int,
    val pulled: Int,
    val replaced: Int,
    val message: String,
) : CloudSyncOutMessage()

@Serializable
@SerialName("cloudSyncFailed")
data class CloudSyncFailedOutMessage(
    val projectId: Int,
    val errorCode: String,
    val message: String,
) : CloudSyncOutMessage()

/**
 * Emitted when [uk.me.cormack.lighting7.sync.RemoteSyncEngine.runSync] finds a Diverged
 * history with at least one EDIT_EDIT conflict and persists a `sync_session` row. The
 * frontend's `<ConflictPanel>` reacts by fetching the conflict list and asking the user
 * to pick `LOCAL` / `REMOTE` per record.
 */
@Serializable
@SerialName("cloudSyncConflictsPending")
data class CloudSyncConflictsPendingOutMessage(
    val projectId: Int,
    val sessionId: Int,
    val conflictCount: Int,
) : CloudSyncOutMessage()

/**
 * Broadcast on every persisted [uk.me.cormack.lighting7.sync.SyncLogger] write. The
 * frontend's activity feed appends the entry — re-fetching `/sync/log/entries` is
 * unnecessary while the WS is connected.
 */
@Serializable
@SerialName("cloudSyncLogAppended")
data class CloudSyncLogAppendedOutMessage(
    val projectId: Int,
    val entry: SyncLogEntryDto,
) : CloudSyncOutMessage()

/**
 * Emitted after a successful `POST /api/rest/cloud-sync/import` clones a remote repo into
 * a brand-new local project. Other tabs / connected clients use this to refresh their
 * project list and sync-config map without round-tripping the REST endpoints.
 */
@Serializable
@SerialName("cloudSyncProjectImported")
data class CloudSyncProjectImportedOutMessage(
    val projectId: Int,
    val projectUuid: String,
    val name: String,
) : CloudSyncOutMessage()

/**
 * Broadcast when the install-wide GitHub OAuth identity changes — the user connects,
 * refreshes (login unchanged but expiry shifts), or disconnects. The sync config UI uses
 * this to live-update the "Connected as @login" row without polling.
 */
@Serializable
@SerialName("oauthIdentityChanged")
data class OAuthIdentityChangedOutMessage(
    val provider: String,
    val connected: Boolean,
    val login: String? = null,
    val accessExpiresAtMs: Long? = null,
    val refreshExpiresAtMs: Long? = null,
) : CloudSyncOutMessage()

// ─── Subscriptions ──────────────────────────────────────────────────────

fun setupCloudSyncSubscriptions(scope: SocketScope) {
    scope.subscribe(scope.state.cloudSyncEventsFlow) { message -> scope.send(message) }
}
