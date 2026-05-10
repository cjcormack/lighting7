package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.midi.BindingTarget
import uk.me.cormack.lighting7.midi.SoftTakeoverStateMachine

@Serializable
sealed class InMessage

@Serializable
@SerialName("ping")
data object PingInMessage : InMessage()

@Serializable
@SerialName("channelState")
data object ChannelStateInMessage : InMessage()

@Serializable
sealed class OutMessage

@Serializable
data class ChannelState(
    val universe: Int,
    val id: Int,
    val currentLevel: UByte,
)

@Serializable
@SerialName("channelState")
data class ChannelStateOutMessage(
    val channels: List<ChannelState>
): OutMessage()

@Serializable
@SerialName("updateChannel")
data class UpdateChannelInMessage(
    val universe: Int,
    val id: Int,
    val level: UByte,
    val fadeTime: Long,
) : InMessage()

@Serializable
@SerialName("universesState")
data object UniversesStateInMessage : InMessage()
@Serializable
@SerialName("universesState")
data class UniversesStateOutMessage(
    val universes: List<Int>
): OutMessage()

@Serializable
@SerialName("presetListChanged")
data object PresetListChangedOutMessage: OutMessage()

@Serializable
@SerialName("cueListChanged")
data object CueListChangedOutMessage: OutMessage()

@Serializable
@SerialName("cueStackListChanged")
data object CueStackListChangedOutMessage: OutMessage()

@Serializable
@SerialName("cueSlotListChanged")
data object CueSlotListChangedOutMessage: OutMessage()

@Serializable
@SerialName("patchListChanged")
data object PatchListChangedOutMessage: OutMessage()

@Serializable
@SerialName("riggingListChanged")
data object RiggingListChangedOutMessage: OutMessage()

@Serializable
@SerialName("stageRegionListChanged")
data object StageRegionListChangedOutMessage: OutMessage()

@Serializable
@SerialName("showEntriesChanged")
data object ShowEntriesChangedOutMessage: OutMessage()

@Serializable
@SerialName("showChanged")
data class ShowChangedOutMessage(
    val projectId: Int,
    val activeEntryId: Int?,
    val activatedStackId: Int?,
    val activatedStackName: String?,
): OutMessage()

@Serializable
@SerialName("fixturesChanged")
data object FixturesChangedOutMessage: OutMessage()

// Channel mapping messages

@Serializable
@SerialName("channelMappingState")
data object ChannelMappingStateInMessage : InMessage()

@Serializable
data class ChannelMappingEntry(
    val fixtureKey: String,
    val fixtureName: String,
    val description: String
)

@Serializable
@SerialName("channelMappingState")
data class ChannelMappingStateOutMessage(
    val mappings: Map<Int, Map<Int, ChannelMappingEntry>>
) : OutMessage()

// Park-related messages

@Serializable
@SerialName("parkState")
data object ParkStateInMessage : InMessage()

@Serializable
@SerialName("parkChannel")
data class ParkChannelInMessage(
    val universe: Int,
    val channel: Int,
    val value: UByte,
) : InMessage()

@Serializable
@SerialName("unparkChannel")
data class UnparkChannelInMessage(
    val universe: Int,
    val channel: Int,
) : InMessage()

@Serializable
@SerialName("unparkAll")
data object UnparkAllInMessage : InMessage()

@Serializable
data class ParkedChannelState(
    val universe: Int,
    val channel: Int,
    val value: UByte,
)

@Serializable
@SerialName("parkState")
data class ParkStateOutMessage(
    val channels: List<ParkedChannelState>
) : OutMessage()

// FX-related messages

@Serializable
@SerialName("fxState")
data object FxStateInMessage : InMessage()

@Serializable
@SerialName("setFxBpm")
data class SetFxBpmInMessage(val bpm: Double) : InMessage()

@Serializable
@SerialName("tapTempo")
data object TapTempoInMessage : InMessage()

@Serializable
@SerialName("addFx")
data class AddFxInMessage(
    val effectType: String,
    val fixtureKey: String,
    val propertyName: String,
    val beatDivision: Double = 1.0,
    val blendMode: String = "OVERRIDE",
    val phaseOffset: Double = 0.0
) : InMessage()

@Serializable
@SerialName("removeFx")
data class RemoveFxInMessage(val effectId: Long) : InMessage()

@Serializable
@SerialName("pauseFx")
data class PauseFxInMessage(val effectId: Long) : InMessage()

@Serializable
@SerialName("resumeFx")
data class ResumeFxInMessage(val effectId: Long) : InMessage()

@Serializable
@SerialName("clearFx")
data object ClearFxInMessage : InMessage()

@Serializable
@SerialName("requestBeatSync")
data object RequestBeatSyncInMessage : InMessage()

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
    val stackPalettes: Map<Int, List<String>> = emptyMap()
) : OutMessage()

@Serializable
@SerialName("fxChanged")
data class FxChangedOutMessage(
    val changeType: String,  // "added", "removed", "updated", "cleared"
    val effectId: Long? = null
) : OutMessage()

// Beat sync message - sent on each beat for UI synchronization

@Serializable
@SerialName("beatSync")
data class BeatSyncOutMessage(
    val beatNumber: Long,
    val bpm: Double,
    val timestampMs: Long
) : OutMessage()

// Palette-related messages

@Serializable
@SerialName("setPalette")
data class SetPaletteInMessage(val colours: List<String>) : InMessage()

@Serializable
@SerialName("setPaletteColour")
data class SetPaletteColourInMessage(val index: Int, val colour: String) : InMessage()

@Serializable
@SerialName("addPaletteColour")
data class AddPaletteColourInMessage(val colour: String) : InMessage()

@Serializable
@SerialName("removePaletteColour")
data class RemovePaletteColourInMessage(val index: Int) : InMessage()

@Serializable
@SerialName("paletteChanged")
data class PaletteChangedOutMessage(
    val palette: List<String>
) : OutMessage()

@Serializable
@SerialName("stackPalettesChanged")
data class StackPalettesChangedOutMessage(
    val stackPalettes: Map<Int, List<String>>
) : OutMessage()

// Group-related messages

@Serializable
@SerialName("groupsState")
data object GroupsStateInMessage : InMessage()

@Serializable
@SerialName("groupsState")
data class GroupsStateOutMessage(
    val groups: List<GroupSummary>
) : OutMessage()

@Serializable
data class GroupSummary(
    val name: String,
    val memberCount: Int,
    val capabilities: List<String>
)

@Serializable
@SerialName("addGroupFx")
data class AddGroupFxInMessage(
    val groupName: String,
    val effectType: String,
    val propertyName: String,
    val beatDivision: Double = 1.0,
    val blendMode: String = "OVERRIDE",
    val distribution: String = "LINEAR",
    val phaseOffset: Double = 0.0
) : InMessage()


@Serializable
@SerialName("clearGroupFx")
data class ClearGroupFxInMessage(val groupName: String) : InMessage()

@Serializable
@SerialName("groupFxCleared")
data class GroupFxClearedOutMessage(
    val groupName: String,
    val removedCount: Int
) : OutMessage()

// Control-surface MIDI Learn messages

@Serializable
@SerialName("surfaceLearn.begin")
data class SurfaceLearnBeginInMessage(
    val projectId: Int,
    val deviceTypeKey: String? = null,
) : InMessage()

@Serializable
@SerialName("surfaceLearn.cancel")
data class SurfaceLearnCancelInMessage(val sessionId: String) : InMessage()

@Serializable
@SerialName("surfaceLearn.commit")
data class SurfaceLearnCommitInMessage(
    val sessionId: String,
    val bank: String? = null,
    val target: BindingTarget,
    val takeoverPolicy: String? = null,
) : InMessage()

@Serializable
@SerialName("surfaceLearn.started")
data class SurfaceLearnStartedOutMessage(
    val sessionId: String,
    val projectId: Int,
    val deviceTypeKey: String?,
    val deadlineMs: Long,
) : OutMessage()

@Serializable
@SerialName("surfaceLearn.captured")
data class SurfaceLearnCapturedOutMessage(
    val sessionId: String,
    val projectId: Int,
    val deviceTypeKey: String,
    val controlId: String,
) : OutMessage()

@Serializable
@SerialName("surfaceLearn.committed")
data class SurfaceLearnCommittedOutMessage(
    val sessionId: String,
    val bindingId: Int,
    val projectId: Int,
) : OutMessage()

@Serializable
@SerialName("surfaceLearn.cancelled")
data class SurfaceLearnCancelledOutMessage(
    val sessionId: String,
    val reason: String,
) : OutMessage()

@Serializable
@SerialName("surfaceLearn.error")
data class SurfaceLearnErrorOutMessage(
    val sessionId: String?,
    val message: String,
) : OutMessage()

@Serializable
enum class BindingChangeType {
    @SerialName("added") ADDED,
    @SerialName("updated") UPDATED,
    @SerialName("removed") REMOVED,
    @SerialName("reloaded") RELOADED,
}

@Serializable
@SerialName("surfaceBindingsChanged")
data class SurfaceBindingsChangedOutMessage(
    val projectId: Int,
    val changeType: BindingChangeType,
    val bindingId: Int? = null,
) : OutMessage()

// Control-surface Phase 3: active-bank + global-scaler state.

@Serializable
@SerialName("surfaceBank.set")
data class SurfaceBankSetInMessage(
    val deviceTypeKey: String,
    val bank: String?,
) : InMessage()

@Serializable
@SerialName("surfaceBank.state")
data object SurfaceBankStateInMessage : InMessage()

@Serializable
@SerialName("surfaceBank.state")
data class SurfaceBankStateOutMessage(
    /** `deviceTypeKey` → active bank id (null values elided). */
    val activeBanks: Map<String, String>,
) : OutMessage()

@Serializable
@SerialName("surfaceBank.changed")
data class SurfaceBankChangedOutMessage(
    val deviceTypeKey: String,
    val previousBank: String?,
    val newBank: String?,
) : OutMessage()

@Serializable
@SerialName("surfaceScaler.state")
data object SurfaceScalerStateInMessage : InMessage()

@Serializable
@SerialName("surfaceScaler.state")
data class SurfaceScalerStateOutMessage(
    val blackoutEnabled: Boolean,
    val grandMasterEnabled: Boolean,
) : OutMessage()

@Serializable
@SerialName("surfaceScaler.setBlackout")
data class SurfaceScalerSetBlackoutInMessage(val enabled: Boolean) : InMessage()

@Serializable
@SerialName("surfaceScaler.setGrandMaster")
data class SurfaceScalerSetGrandMasterInMessage(val enabled: Boolean) : InMessage()

/**
 * Emitted when a non-motor fader transitions between engaged and pickup-awaiting state.
 * Frontend renders a pickup indicator near the relevant control.
 */
@Serializable
@SerialName("surfacePickup.changed")
data class SurfacePickupChangedOutMessage(
    val displayKey: String,
    val controlId: String,
    val state: SoftTakeoverStateMachine.State,
    /** Target 7-bit value that the fader must cross to re-engage. Null when engaged. */
    val target: Int?,
) : OutMessage()

// Phase 5: enumerate attached MIDI devices (both profile-matched and unmatched raw ports).

@Serializable
data class SurfaceDeviceInfo(
    val displayKey: String,
    val displayName: String,
    /** Null when the device did not match any `@ControlSurfaceType`. */
    val typeKey: String?,
    val isMatched: Boolean,
    val hasInputPort: Boolean,
    val hasOutputPort: Boolean,
    /** Convenience mirror of `activeBankState` for the device's `typeKey`, or null. */
    val activeBank: String?,
)

@Serializable
@SerialName("surfaceDevices.state")
data object SurfaceDevicesStateInMessage : InMessage()

@Serializable
@SerialName("surfaceDevices.state")
data class SurfaceDevicesStateOutMessage(
    val devices: List<SurfaceDeviceInfo>,
) : OutMessage()

// Project-related messages

@Serializable
@SerialName("projectState")
data object ProjectStateInMessage : InMessage()

@Serializable
@SerialName("projectState")
data class ProjectStateOutMessage(
    val projectId: Int,
    val projectName: String,
    val description: String?
) : OutMessage()

@Serializable
@SerialName("projectChanged")
data class ProjectChangedOutMessage(
    val previousProjectId: Int?,
    val newProjectId: Int,
    val newProjectName: String
) : OutMessage()

// ─── Cloud sync (phase 4) ────────────────────────────────────────────

/**
 * Lifecycle broadcasts for the unified `POST /sync/run` endpoint. The frontend uses
 * these to disable the Sync-now button while one is in flight, refresh status / log
 * panels on completion, and surface toasts. Only emitted on transitions — there is no
 * streaming progress (single done/fail message is enough for the data volumes we
 * support; revisit if a project ever feels unresponsive).
 */
@Serializable
@SerialName("cloudSyncStarted")
data class CloudSyncStartedOutMessage(val projectId: Int) : OutMessage()

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
) : OutMessage()

@Serializable
@SerialName("cloudSyncFailed")
data class CloudSyncFailedOutMessage(
    val projectId: Int,
    val errorCode: String,
    val message: String,
) : OutMessage()

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
) : OutMessage()

/**
 * Broadcast on every persisted [uk.me.cormack.lighting7.sync.SyncLogger] write. The
 * frontend's activity feed appends the entry — re-fetching `/sync/log/entries` is
 * unnecessary while the WS is connected.
 */
@Serializable
@SerialName("cloudSyncLogAppended")
data class CloudSyncLogAppendedOutMessage(
    val projectId: Int,
    val entry: uk.me.cormack.lighting7.sync.SyncLogEntryDto,
) : OutMessage()

/**
 * Emitted after a successful `POST /api/rest/cloud-sync/import` clones a remote repo
 * into a brand-new local project. Other tabs / connected clients use this to refresh
 * their project list and sync-config map without round-tripping the REST endpoints.
 */
@Serializable
@SerialName("cloudSyncProjectImported")
data class CloudSyncProjectImportedOutMessage(
    val projectId: Int,
    val projectUuid: String,
    val name: String,
) : OutMessage()

/**
 * Broadcast when the install-wide GitHub OAuth identity changes — the user connects,
 * refreshes (login unchanged but expiry shifts), or disconnects. The sync config UI
 * uses this to live-update the "Connected as @login" row without polling.
 */
@Serializable
@SerialName("oauthIdentityChanged")
data class OAuthIdentityChangedOutMessage(
    val provider: String,
    val connected: Boolean,
    val login: String? = null,
    val accessExpiresAtMs: Long? = null,
    val refreshExpiresAtMs: Long? = null,
) : OutMessage()
