package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fixture.DmxFixture
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.dmx.DmxColour
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureSetting
import uk.me.cormack.lighting7.fixture.dmx.DmxSlider
import uk.me.cormack.lighting7.models.CuePropertyAssignmentDto
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCuePropertyAssignment
import uk.me.cormack.lighting7.routes.applyCue
import uk.me.cormack.lighting7.routes.buildCueApplyData
import uk.me.cormack.lighting7.routes.buildLayer3AssignmentsForCue
import uk.me.cormack.lighting7.state.State
import java.util.concurrent.atomic.AtomicReference

/**
 * Per-connection cue-edit session state. A single WebSocket connection can hold at most one
 * open session at a time; a second `cueEdit.beginEdit` on the same connection replaces it.
 *
 * The snapshot is the full [CuePropertyAssignmentDto] list from the DB at the moment
 * `beginEdit` ran — used by `cueEdit.discardChanges` to restore the pre-edit state.
 */
data class CueEditSessionState(
    val cueId: Int,
    val mode: CueEditMode,
    val snapshot: List<CuePropertyAssignmentDto>,
)

enum class CueEditMode {
    LIVE,
    BLIND;

    companion object {
        fun parseOrNull(value: String): CueEditMode? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

// ── Inbound messages ────────────────────────────────────────────────────────

@Serializable
@SerialName("cueEdit.beginEdit")
data class CueEditBeginEditInMessage(val cueId: Int, val mode: String) : InMessage()

@Serializable
@SerialName("cueEdit.endEdit")
data class CueEditEndEditInMessage(val cueId: Int) : InMessage()

@Serializable
@SerialName("cueEdit.setChannel")
data class CueEditSetChannelInMessage(
    val cueId: Int,
    val universe: Int,
    val channel: Int,
    val level: UByte,
) : InMessage()

@Serializable
@SerialName("cueEdit.setProperty")
data class CueEditSetPropertyInMessage(
    val cueId: Int,
    val targetType: String,
    val targetKey: String,
    val propertyName: String,
    val value: String,
) : InMessage()

@Serializable
@SerialName("cueEdit.discardChanges")
data class CueEditDiscardChangesInMessage(val cueId: Int) : InMessage()

/** Stubs for the follow-up message set — accepted so the client doesn't error on unknown type. */
@Serializable
@SerialName("cueEdit.setMode")
data class CueEditSetModeInMessage(val cueId: Int, val mode: String) : InMessage()

@Serializable
@SerialName("cueEdit.setPalette")
data class CueEditSetPaletteInMessage(val cueId: Int, val palette: List<String>) : InMessage()

@Serializable
@SerialName("cueEdit.addPresetApplication")
data class CueEditAddPresetApplicationInMessage(val cueId: Int) : InMessage()

@Serializable
@SerialName("cueEdit.addAdHocEffect")
data class CueEditAddAdHocEffectInMessage(val cueId: Int) : InMessage()

@Serializable
@SerialName("cueEdit.clearAssignment")
data class CueEditClearAssignmentInMessage(
    val cueId: Int,
    val targetType: String,
    val targetKey: String,
    val propertyName: String,
) : InMessage()

// ── Outbound messages ───────────────────────────────────────────────────────

@Serializable
@SerialName("cueEdit.sessionStarted")
data class CueEditSessionStartedOutMessage(
    val cueId: Int,
    val mode: String,
) : OutMessage()

@Serializable
@SerialName("cueEdit.sessionEnded")
data class CueEditSessionEndedOutMessage(val cueId: Int) : OutMessage()

@Serializable
@SerialName("cueEdit.assignmentChanged")
data class CueEditAssignmentChangedOutMessage(
    val cueId: Int,
    val targetType: String,
    val targetKey: String,
    val propertyName: String,
    val value: String,
) : OutMessage()

@Serializable
@SerialName("cueEdit.changesDiscarded")
data class CueEditChangesDiscardedOutMessage(val cueId: Int) : OutMessage()

@Serializable
@SerialName("cueEdit.assignmentCleared")
data class CueEditAssignmentClearedOutMessage(
    val cueId: Int,
    val targetType: String,
    val targetKey: String,
    val propertyName: String,
) : OutMessage()

@Serializable
@SerialName("cueEdit.error")
data class CueEditErrorOutMessage(
    val cueId: Int?,
    val message: String,
) : OutMessage()

// ── Handler ─────────────────────────────────────────────────────────────────

/**
 * Dispatch for `cueEdit.*` messages received on a WebSocket connection.
 *
 * Each connection owns its own [sessionRef] — the cue-edit session lifecycle is local to the
 * connection that opened it. Closing the connection triggers [endSessionOnDisconnect].
 */
object CueEditSessionHandler {
    private val logger = LoggerFactory.getLogger(CueEditSessionHandler::class.java)

    /**
     * Begin editing [cueId] in [mode]. Snapshots current assignments into the session, and in
     * `LIVE` mode applies the cue to the stage (same effect as `POST /cues/{id}/apply`).
     */
    fun beginEdit(
        state: State,
        sessionRef: AtomicReference<CueEditSessionState?>,
        cueId: Int,
        modeStr: String,
    ): OutMessage {
        val mode = CueEditMode.parseOrNull(modeStr)
            ?: return CueEditErrorOutMessage(cueId, "Unknown mode '$modeStr'")

        // Snapshot assignments + gather cue apply data inside a single transaction.
        val applyData = transaction(state.database) {
            val cue = DaoCue.findById(cueId) ?: return@transaction null
            val project = cue.project
            if (project.id != state.projectManager.currentProject.id) {
                return@transaction null
            }
            buildCueApplyData(cue)
        } ?: return CueEditErrorOutMessage(cueId, "Cue not found in current project")

        sessionRef.set(CueEditSessionState(
            cueId = cueId,
            mode = mode,
            snapshot = applyData.propertyAssignments,
        ))

        if (mode == CueEditMode.LIVE) {
            // Going through applyCue() directly for a stack-member cue would bypass
            // CueStackManager's stack state. Reject; Blind mode is fine because it doesn't
            // touch the stage.
            if (applyData.cueStackId != null) {
                return CueEditErrorOutMessage(
                    cueId,
                    "Live edit of stack cues not supported yet — use Blind mode or edit via the stack",
                )
            }
            try {
                applyCue(state, applyData, replaceAll = false)
            } catch (e: Exception) {
                logger.warn("cueEdit.beginEdit applyCue failed for cue {}: {}", cueId, e.message)
                sessionRef.set(null)
                return CueEditErrorOutMessage(cueId, "Failed to apply cue: ${e.message}")
            }
        }

        return CueEditSessionStartedOutMessage(cueId, mode.name)
    }

    /**
     * Mid-session transition between Live and Blind. The snapshot is preserved — [discardChanges]
     * always reverts to the state captured at the original [beginEdit], regardless of how many
     * [setMode] flips happened in between.
     *
     * - `LIVE → BLIND`: stop the cue on stage (same semantics as [endEdit] in Live mode), but
     *   keep the session open so further edits still route to the cue.
     * - `BLIND → LIVE`: apply the cue's current persisted state to the stage — same path as
     *   [beginEdit]'s Live branch. Stack cues still reject (no CueStackManager integration yet).
     * - Same-mode call: no-op success; returns [CueEditSessionStartedOutMessage] to confirm.
     */
    fun setMode(
        state: State,
        sessionRef: AtomicReference<CueEditSessionState?>,
        cueId: Int,
        modeStr: String,
    ): OutMessage {
        val newMode = CueEditMode.parseOrNull(modeStr)
            ?: return CueEditErrorOutMessage(cueId, "Unknown mode '$modeStr'")

        val session = sessionRef.get()
        if (session == null || session.cueId != cueId) {
            return CueEditErrorOutMessage(cueId, "No active cueEdit session for this cue")
        }
        if (session.mode == newMode) {
            return CueEditSessionStartedOutMessage(cueId, newMode.name)
        }

        when (newMode) {
            CueEditMode.BLIND -> {
                // LIVE → BLIND: release the cue from stage but keep the session open.
                state.cueTriggerManager.deactivateTriggersForCue(cueId)
                state.show.fxEngine.removeEffectsForCue(cueId)
            }
            CueEditMode.LIVE -> {
                // BLIND → LIVE: apply the cue's current persisted state to the stage. Fresh
                // read inside a transaction so any edits made during the Blind pass take effect.
                val applyData = transaction(state.database) {
                    val cue = DaoCue.findById(cueId) ?: return@transaction null
                    if (cue.project.id != state.projectManager.currentProject.id) return@transaction null
                    buildCueApplyData(cue)
                } ?: return CueEditErrorOutMessage(cueId, "Cue not found in current project")

                if (applyData.cueStackId != null) {
                    return CueEditErrorOutMessage(
                        cueId,
                        "Live edit of stack cues not supported yet — keep the session Blind or edit via the stack",
                    )
                }
                try {
                    applyCue(state, applyData, replaceAll = false)
                } catch (e: Exception) {
                    logger.warn("cueEdit.setMode applyCue failed for cue {}: {}", cueId, e.message)
                    return CueEditErrorOutMessage(cueId, "Failed to apply cue: ${e.message}")
                }
            }
        }

        sessionRef.set(session.copy(mode = newMode))
        return CueEditSessionStartedOutMessage(cueId, newMode.name)
    }

    /**
     * Delete one property assignment from the open cue. Matches by
     * `(targetType, targetKey, propertyName)`; silently succeeds if no row matches (idempotent).
     * In `LIVE` mode, republishes Layer 3 so the channel releases to the layer below.
     */
    fun clearAssignment(
        state: State,
        sessionRef: AtomicReference<CueEditSessionState?>,
        cueId: Int,
        targetType: String,
        targetKey: String,
        propertyName: String,
    ): OutMessage {
        val session = sessionRef.get()
        if (session == null || session.cueId != cueId) {
            return CueEditErrorOutMessage(cueId, "No active cueEdit session for this cue")
        }

        val applyData = try {
            transaction(state.database) {
                val cue = DaoCue.findById(cueId) ?: error("Cue not found")
                val row = cue.propertyAssignments.firstOrNull {
                    it.targetType == targetType &&
                        it.targetKey == targetKey &&
                        it.propertyName == propertyName
                }
                row?.delete()
                if (session.mode == CueEditMode.LIVE) buildCueApplyData(cue) else null
            }
        } catch (e: Exception) {
            return CueEditErrorOutMessage(cueId, "Clear failed: ${e.message}")
        }

        if (applyData != null) republishLayer3(state, cueId, applyData)

        state.show.fixtures.cueListChanged()
        return CueEditAssignmentClearedOutMessage(cueId, targetType, targetKey, propertyName)
    }

    /**
     * End the current session. In `LIVE` mode, stops the cue on stage. In `BLIND`, no stage
     * interaction. Session state is cleared regardless.
     */
    fun endEdit(
        state: State,
        sessionRef: AtomicReference<CueEditSessionState?>,
        cueId: Int,
    ): OutMessage {
        val session = sessionRef.get()
        if (session == null || session.cueId != cueId) {
            return CueEditErrorOutMessage(cueId, "No active cueEdit session for this cue")
        }
        sessionRef.set(null)

        if (session.mode == CueEditMode.LIVE) {
            state.cueTriggerManager.deactivateTriggersForCue(cueId)
            state.show.fxEngine.removeEffectsForCue(cueId)
        }
        return CueEditSessionEndedOutMessage(cueId)
    }

    /**
     * Upsert a property assignment for (targetType, targetKey, propertyName) = value. In
     * `LIVE` mode, republish the cue's Layer 3 state so the change is visible immediately.
     */
    fun setProperty(
        state: State,
        sessionRef: AtomicReference<CueEditSessionState?>,
        cueId: Int,
        targetType: String,
        targetKey: String,
        propertyName: String,
        value: String,
    ): OutMessage {
        val session = sessionRef.get()
        if (session == null || session.cueId != cueId) {
            return CueEditErrorOutMessage(cueId, "No active cueEdit session for this cue")
        }

        val upserted = CuePropertyAssignmentDto(
            targetType = targetType,
            targetKey = targetKey,
            propertyName = propertyName,
            value = value,
        )
        val applyData = try {
            transaction(state.database) {
                val cue = DaoCue.findById(cueId) ?: error("Cue not found")
                upsertAssignment(cue, upserted)
                if (session.mode == CueEditMode.LIVE) buildCueApplyData(cue) else null
            }
        } catch (e: Exception) {
            return CueEditErrorOutMessage(cueId, "Persist failed: ${e.message}")
        }

        if (applyData != null) republishLayer3(state, cueId, applyData)

        state.show.fixtures.cueListChanged()
        return CueEditAssignmentChangedOutMessage(cueId, targetType, targetKey, propertyName, value)
    }

    /**
     * Resolve a (universe, channel) to the backing fixture + property and upsert an assignment
     * for that property at [level]. Colour sub-channels and position sub-channels currently
     * reject — callers should use [setProperty] with `rgbColour` / `position` directly.
     */
    fun setChannel(
        state: State,
        sessionRef: AtomicReference<CueEditSessionState?>,
        cueId: Int,
        universe: Int,
        channel: Int,
        level: UByte,
    ): OutMessage {
        val session = sessionRef.get()
        if (session == null || session.cueId != cueId) {
            return CueEditErrorOutMessage(cueId, "No active cueEdit session for this cue")
        }

        val resolved = resolveChannelToProperty(state, universe, channel)
            ?: return CueEditErrorOutMessage(
                cueId,
                "No fixture/property backs universe=$universe channel=$channel",
            )

        return when (resolved) {
            is ResolvedChannel.SingleProperty -> setProperty(
                state, sessionRef, cueId,
                targetType = "fixture",
                targetKey = resolved.fixtureKey,
                propertyName = resolved.propertyName,
                value = level.toString(),
            )
            is ResolvedChannel.ColourSubChannel -> CueEditErrorOutMessage(
                cueId,
                "Channel $channel is a colour sub-channel (${resolved.component}) on " +
                    "${resolved.fixtureKey}; use cueEdit.setProperty with propertyName='rgbColour'",
            )
        }
    }

    /**
     * Restore the cue's `propertyAssignments` to the session snapshot. Deletes all current
     * assignments and re-creates them from the snapshot, then republishes Layer 3 if Live.
     */
    fun discardChanges(
        state: State,
        sessionRef: AtomicReference<CueEditSessionState?>,
        cueId: Int,
    ): OutMessage {
        val session = sessionRef.get()
        if (session == null || session.cueId != cueId) {
            return CueEditErrorOutMessage(cueId, "No active cueEdit session for this cue")
        }

        val applyData = try {
            transaction(state.database) {
                val cue = DaoCue.findById(cueId) ?: error("Cue not found")
                cue.propertyAssignments.forEach { it.delete() }
                for (assignment in session.snapshot) {
                    DaoCuePropertyAssignment.new {
                        this.cue = cue
                        targetType = assignment.targetType
                        targetKey = assignment.targetKey
                        propertyName = assignment.propertyName
                        value = assignment.value
                        fadeDurationMs = assignment.fadeDurationMs
                        sortOrder = assignment.sortOrder
                    }
                }
                if (session.mode == CueEditMode.LIVE) buildCueApplyData(cue) else null
            }
        } catch (e: Exception) {
            return CueEditErrorOutMessage(cueId, "Discard failed: ${e.message}")
        }

        if (applyData != null) republishLayer3(state, cueId, applyData)
        state.show.fixtures.cueListChanged()
        return CueEditChangesDiscardedOutMessage(cueId)
    }

    /**
     * Called on WebSocket disconnect to release any open session. In Live mode, also stops
     * the cue on stage — otherwise a client crash would leave the cue stuck on.
     */
    fun endSessionOnDisconnect(
        state: State,
        sessionRef: AtomicReference<CueEditSessionState?>,
    ) {
        val session = sessionRef.getAndSet(null) ?: return
        if (session.mode == CueEditMode.LIVE) {
            runCatching {
                state.cueTriggerManager.deactivateTriggersForCue(session.cueId)
                state.show.fxEngine.removeEffectsForCue(session.cueId)
            }
        }
    }

    // ── Internals ───────────────────────────────────────────────────────────

    /**
     * Upsert an assignment row on [cue]: match by (targetType, targetKey, propertyName) and
     * replace the value / fade / sortOrder, or insert if absent.
     */
    internal fun upsertAssignment(
        cue: DaoCue,
        dto: CuePropertyAssignmentDto,
    ) {
        val existing = cue.propertyAssignments.firstOrNull {
            it.targetType == dto.targetType &&
                it.targetKey == dto.targetKey &&
                it.propertyName == dto.propertyName
        }
        if (existing != null) {
            existing.value = dto.value
            existing.fadeDurationMs = dto.fadeDurationMs
            existing.sortOrder = dto.sortOrder
        } else {
            DaoCuePropertyAssignment.new {
                this.cue = cue
                targetType = dto.targetType
                targetKey = dto.targetKey
                propertyName = dto.propertyName
                value = dto.value
                fadeDurationMs = dto.fadeDurationMs
                sortOrder = dto.sortOrder
            }
        }
    }

    /**
     * Republish Layer 3 for [cueId] from pre-built [applyData]. Callers build the apply data
     * inside the persist transaction so we don't round-trip to the DB twice per edit.
     */
    private fun republishLayer3(state: State, cueId: Int, applyData: uk.me.cormack.lighting7.routes.CueApplyData) {
        val built = buildLayer3AssignmentsForCue(state.show.fixtures, applyData)
        if (built.isNotEmpty()) {
            state.show.fxEngine.setCueAssignments(cueId, built)
        } else {
            state.show.fxEngine.removeCueAssignments(cueId)
        }
    }

    /**
     * Walk the fixture that owns [channel] in [universe] and identify the property it backs.
     *
     * Returns:
     * - [ResolvedChannel.SingleProperty] for sliders and settings (1:1 channel → property).
     * - [ResolvedChannel.ColourSubChannel] for the red / green / blue sub-channels of a
     *   [DmxColour] property — callers should reject or merge client-side.
     * - `null` if the channel is unmapped or owned by a property type we don't expose here
     *   (e.g. position pan / tilt — handle via setProperty with propertyName='position').
     */
    private fun resolveChannelToProperty(
        state: State,
        universe: Int,
        channel: Int,
    ): ResolvedChannel? {
        val mappings = state.show.fixtures.getChannelMappings()
        val fixtureKey = mappings[universe]?.get(channel)?.fixtureKey ?: return null
        val fixture = runCatching { state.show.fixtures.untypedFixture(fixtureKey) }
            .getOrNull() as? DmxFixture ?: return null

        for (prop in fixture.fixtureProperties) {
            val value = prop.classProperty.call(fixture) ?: continue
            when (value) {
                is DmxSlider -> if (value.channelNo == channel) {
                    return ResolvedChannel.SingleProperty(fixture.key, prop.name)
                }
                is DmxFixtureSetting<*> -> if (value.channelNo == channel) {
                    return ResolvedChannel.SingleProperty(fixture.key, prop.name)
                }
                is DmxColour -> when (channel) {
                    value.redSlider.channelNo ->
                        return ResolvedChannel.ColourSubChannel(fixture.key, "red")
                    value.greenSlider.channelNo ->
                        return ResolvedChannel.ColourSubChannel(fixture.key, "green")
                    value.blueSlider.channelNo ->
                        return ResolvedChannel.ColourSubChannel(fixture.key, "blue")
                }
            }
        }
        return null
    }

    internal sealed class ResolvedChannel {
        data class SingleProperty(val fixtureKey: String, val propertyName: String) : ResolvedChannel()
        data class ColourSubChannel(val fixtureKey: String, val component: String) : ResolvedChannel()
    }
}
