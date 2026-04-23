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
import uk.me.cormack.lighting7.fx.parseExtendedColour
import uk.me.cormack.lighting7.models.CueAdHocEffectDto
import uk.me.cormack.lighting7.models.CuePropertyAssignmentDto
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueAdHocEffect
import uk.me.cormack.lighting7.models.DaoCuePresetApplication
import uk.me.cormack.lighting7.models.DaoCuePropertyAssignment
import uk.me.cormack.lighting7.models.DaoFxPreset
import uk.me.cormack.lighting7.models.FxPresetEffectDto
import uk.me.cormack.lighting7.routes.CueApplyData
import uk.me.cormack.lighting7.routes.TogglePresetTarget
import uk.me.cormack.lighting7.routes.applyCue
import uk.me.cormack.lighting7.routes.buildCueApplyData
import uk.me.cormack.lighting7.routes.buildLayer3AssignmentsForCue
import uk.me.cormack.lighting7.routes.buildLayer3AssignmentsForPreset
import uk.me.cormack.lighting7.routes.createInstanceFromPresetForCue
import uk.me.cormack.lighting7.routes.cueDerivedPriority
import uk.me.cormack.lighting7.routes.resolveTargetForCue
import uk.me.cormack.lighting7.routes.toPropertyAssignmentDtos
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
    /**
     * Stack ID when editing a cue that belongs to a cue stack. `null` for standalone cues.
     *
     * Tracked so cleanup paths (`endEdit`, `setMode LIVE→BLIND`, `endSessionOnDisconnect`) know
     * whether to route through `CueStackManager` rather than the fixture-level
     * `removeEffectsForCue` / `deactivateTriggersForCue` pair used for standalone cues.
     */
    val cueStackId: Int? = null,
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

@Serializable
@SerialName("cueEdit.setMode")
data class CueEditSetModeInMessage(val cueId: Int, val mode: String) : InMessage()

@Serializable
@SerialName("cueEdit.setPalette")
data class CueEditSetPaletteInMessage(val cueId: Int, val palette: List<String>) : InMessage()

@Serializable
@SerialName("cueEdit.addPresetApplication")
data class CueEditAddPresetApplicationInMessage(
    val cueId: Int,
    val presetId: Int,
    val targets: List<CueTargetDto>,
    val delayMs: Long? = null,
    val intervalMs: Long? = null,
    val randomWindowMs: Long? = null,
) : InMessage()

@Serializable
@SerialName("cueEdit.addAdHocEffect")
data class CueEditAddAdHocEffectInMessage(
    val cueId: Int,
    val effect: CueAdHocEffectDto,
) : InMessage()

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
@SerialName("cueEdit.paletteChanged")
data class CueEditPaletteChangedOutMessage(
    val cueId: Int,
    val palette: List<String>,
) : OutMessage()

@Serializable
@SerialName("cueEdit.presetApplicationAdded")
data class CueEditPresetApplicationAddedOutMessage(
    val cueId: Int,
    val presetId: Int,
) : OutMessage()

@Serializable
@SerialName("cueEdit.adHocEffectAdded")
data class CueEditAdHocEffectAddedOutMessage(
    val cueId: Int,
    val effectType: String,
    val targetKey: String,
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
        handle: Any = sessionRef,
    ): OutMessage {
        val mode = CueEditMode.parseOrNull(modeStr)
            ?: return CueEditErrorOutMessage(cueId, "Unknown mode '$modeStr'")

        val applyData = loadCueApplyData(state, cueId)
            ?: return CueEditErrorOutMessage(cueId, "Cue not found in current project")

        val newSession = CueEditSessionState(
            cueId = cueId,
            mode = mode,
            snapshot = applyData.propertyAssignments,
            cueStackId = applyData.cueStackId,
        )
        sessionRef.set(newSession)

        if (mode == CueEditMode.LIVE) {
            try {
                applyCueForLiveEdit(state, applyData)
            } catch (e: Exception) {
                logger.warn("cueEdit.beginEdit apply failed for cue {}: {}", cueId, e.message)
                sessionRef.set(null)
                return CueEditErrorOutMessage(cueId, "Failed to apply cue: ${e.message}")
            }
        }

        state.cueEditSessionRegistry.register(handle, state.projectManager.currentProject.id.value, newSession)
        return CueEditSessionStartedOutMessage(cueId, mode.name)
    }

    /**
     * Mid-session transition between Live and Blind. The snapshot is preserved — [discardChanges]
     * always reverts to the state captured at the original [beginEdit], regardless of how many
     * [setMode] flips happened in between.
     *
     * - `LIVE → BLIND`: stop the cue on stage (stack cues tear down the whole stack; standalone
     *   drops effects + triggers) but keep the session open so further edits still route to the cue.
     * - `BLIND → LIVE`: fresh-read the cue and apply it — same path as [beginEdit]'s Live branch,
     *   picking up any edits made while Blind.
     * - Same-mode call: no-op success; returns [CueEditSessionStartedOutMessage] to confirm.
     */
    fun setMode(
        state: State,
        sessionRef: AtomicReference<CueEditSessionState?>,
        cueId: Int,
        modeStr: String,
        handle: Any = sessionRef,
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

        val newCueStackId: Int?
        when (newMode) {
            CueEditMode.BLIND -> {
                // Stack cues tear down the whole stack (drops effects, Layer 3, triggers, and
                // the paused auto-advance job in one call); standalone cues just drop their
                // own effects + triggers.
                if (session.cueStackId != null) {
                    state.show.cueStackManager.deactivateStack(session.cueStackId, state)
                } else {
                    state.cueTriggerManager.deactivateTriggersForCue(cueId)
                    state.show.fxEngine.removeEffectsForCue(cueId)
                }
                newCueStackId = session.cueStackId
            }
            CueEditMode.LIVE -> {
                val applyData = loadCueApplyData(state, cueId)
                    ?: return CueEditErrorOutMessage(cueId, "Cue not found in current project")

                try {
                    applyCueForLiveEdit(state, applyData)
                } catch (e: Exception) {
                    logger.warn("cueEdit.setMode apply failed for cue {}: {}", cueId, e.message)
                    return CueEditErrorOutMessage(cueId, "Failed to apply cue: ${e.message}")
                }
                newCueStackId = applyData.cueStackId
            }
        }

        val updated = session.copy(mode = newMode, cueStackId = newCueStackId)
        sessionRef.set(updated)
        state.cueEditSessionRegistry.register(handle, state.projectManager.currentProject.id.value, updated)
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

        state.cueEditSessionRegistry.notifyAssignmentCleared(
            state.projectManager.currentProject.id.value, cueId, targetType, targetKey, propertyName,
        )
        state.show.fixtures.cueListChanged()
        return CueEditAssignmentClearedOutMessage(cueId, targetType, targetKey, propertyName)
    }

    /**
     * End the current session. In `LIVE` mode, releases stage ownership via [releaseLiveSession]
     * — standalone cues stop on stage; stack cues leave the stack running with auto-advance
     * resumed. In `BLIND`, no stage interaction. Session state is cleared regardless.
     */
    fun endEdit(
        state: State,
        sessionRef: AtomicReference<CueEditSessionState?>,
        cueId: Int,
        handle: Any = sessionRef,
    ): OutMessage {
        val session = sessionRef.get()
        if (session == null || session.cueId != cueId) {
            return CueEditErrorOutMessage(cueId, "No active cueEdit session for this cue")
        }
        sessionRef.set(null)
        state.cueEditSessionRegistry.unregister(handle)

        if (session.mode == CueEditMode.LIVE) releaseLiveSession(state, session)
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
        return setPropertyForSession(state, session, targetType, targetKey, propertyName, value)
    }

    /**
     * Core upsert + republish for a property assignment. Used both by the WS-level
     * [setProperty] and by server-side callers (e.g. [SurfaceInputRouter]) that already hold
     * a validated [session]. Does not revalidate session identity; the caller should pass the
     * exact [CueEditSessionState] retrieved from the registry.
     */
    fun setPropertyForSession(
        state: State,
        session: CueEditSessionState,
        targetType: String,
        targetKey: String,
        propertyName: String,
        value: String,
    ): OutMessage {
        val cueId = session.cueId
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

        state.cueEditSessionRegistry.notifyAssignmentChanged(
            state.projectManager.currentProject.id.value, cueId, targetType, targetKey, propertyName, value,
        )
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
        state.cueEditSessionRegistry.notifyAssignmentsReloaded(
            state.projectManager.currentProject.id.value, cueId, session.snapshot,
        )
        state.show.fixtures.cueListChanged()
        return CueEditChangesDiscardedOutMessage(cueId)
    }

    /**
     * Replace the cue's palette. In Live mode also updates the stage palette via
     * [FxEngine.setCuePalette] so running effects that reference palette entries pick up the
     * change on the next tick.
     */
    fun setPalette(
        state: State,
        sessionRef: AtomicReference<CueEditSessionState?>,
        cueId: Int,
        palette: List<String>,
    ): OutMessage {
        val session = sessionRef.get()
        if (session == null || session.cueId != cueId) {
            return CueEditErrorOutMessage(cueId, "No active cueEdit session for this cue")
        }

        try {
            transaction(state.database) {
                val cue = DaoCue.findById(cueId) ?: error("Cue not found")
                cue.palette = palette
            }
        } catch (e: Exception) {
            return CueEditErrorOutMessage(cueId, "Persist failed: ${e.message}")
        }

        if (session.mode == CueEditMode.LIVE) {
            val colours = runCatching { palette.map { parseExtendedColour(it) } }.getOrNull()
            if (colours != null) state.show.fxEngine.setCuePalette(cueId, colours)
        }

        state.show.fixtures.cueListChanged()
        return CueEditPaletteChangedOutMessage(cueId, palette)
    }

    /**
     * Append a preset application to the cue. In Live mode also spawns the effect immediately
     * via the same path as [applyCue], so operators see the effect on stage as soon as they
     * add it. Timed presets (delayMs/intervalMs) are persisted but not spawned — the cue-
     * trigger manager handles those when the cue is applied normally.
     */
    fun addPresetApplication(
        state: State,
        sessionRef: AtomicReference<CueEditSessionState?>,
        cueId: Int,
        presetId: Int,
        targets: List<CueTargetDto>,
        delayMs: Long?,
        intervalMs: Long?,
        randomWindowMs: Long?,
    ): OutMessage {
        val session = sessionRef.get()
        if (session == null || session.cueId != cueId) {
            return CueEditErrorOutMessage(cueId, "No active cueEdit session for this cue")
        }

        val shouldSpawn = session.mode == CueEditMode.LIVE && delayMs == null && intervalMs == null
        val result = try {
            transaction(state.database) {
                val cue = DaoCue.findById(cueId) ?: error("Cue not found")
                val preset = DaoFxPreset.findById(presetId) ?: error("Preset not found")
                DaoCuePresetApplication.new {
                    this.cue = cue
                    this.preset = preset
                    this.targets = targets
                    this.delayMs = delayMs
                    this.intervalMs = intervalMs
                    this.randomWindowMs = randomWindowMs
                    this.sortOrder = cue.presetApplications.count().toInt()
                }
                val applyData = if (session.mode == CueEditMode.LIVE) buildCueApplyData(cue) else null
                val effects = if (shouldSpawn) preset.effects else null
                applyData to effects
            }
        } catch (e: Exception) {
            return CueEditErrorOutMessage(cueId, "Persist failed: ${e.message}")
        }

        val (applyData, presetEffects) = result
        if (applyData != null) {
            // Republish Layer 3 so the new preset's property assignments land on stage before
            // effects spawn — effects reset-to-layer-below sees the preset-contributed baseline.
            republishLayer3(state, cueId, applyData)

            if (presetEffects != null) {
                for (target in targets) {
                    val toggleTarget = TogglePresetTarget(type = target.type, key = target.key)
                    for (presetEffect in presetEffects) {
                        val fxTarget = try {
                            resolveTargetForCue(state, toggleTarget, presetEffect)
                        } catch (_: Exception) { null } ?: continue
                        val instance = createInstanceFromPresetForCue(
                            presetEffect, fxTarget, presetId, state, cueId
                        )
                        instance.cueId = cueId
                        instance.priority = cueDerivedPriority(applyData)
                        state.show.fxEngine.addEffect(instance)
                    }
                }
            }
        }

        state.show.fixtures.cueListChanged()
        return CueEditPresetApplicationAddedOutMessage(cueId, presetId)
    }

    /**
     * Append an ad-hoc effect to the cue. In Live mode spawns the effect immediately
     * (immediate effects only — timed ones are persisted for [CueTriggerManager]).
     */
    fun addAdHocEffect(
        state: State,
        sessionRef: AtomicReference<CueEditSessionState?>,
        cueId: Int,
        effect: CueAdHocEffectDto,
    ): OutMessage {
        val session = sessionRef.get()
        if (session == null || session.cueId != cueId) {
            return CueEditErrorOutMessage(cueId, "No active cueEdit session for this cue")
        }

        val applyData = try {
            transaction(state.database) {
                val cue = DaoCue.findById(cueId) ?: error("Cue not found")
                val nextSort = cue.adHocEffects.count().toInt()
                DaoCueAdHocEffect.new {
                    this.cue = cue
                    targetType = effect.targetType
                    targetKey = effect.targetKey
                    effectType = effect.effectType
                    category = effect.category
                    propertyName = effect.propertyName
                    beatDivision = effect.beatDivision
                    blendMode = effect.blendMode
                    distribution = effect.distribution
                    phaseOffset = effect.phaseOffset
                    elementMode = effect.elementMode
                    elementFilter = effect.elementFilter
                    stepTiming = effect.stepTiming
                    parameters = effect.parameters
                    delayMs = effect.delayMs
                    intervalMs = effect.intervalMs
                    randomWindowMs = effect.randomWindowMs
                    sortOrder = effect.sortOrder.takeIf { it > 0 } ?: nextSort
                }
                if (session.mode == CueEditMode.LIVE) buildCueApplyData(cue) else null
            }
        } catch (e: Exception) {
            return CueEditErrorOutMessage(cueId, "Persist failed: ${e.message}")
        }

        if (applyData != null && effect.delayMs == null && effect.intervalMs == null) {
            val target = TogglePresetTarget(type = effect.targetType, key = effect.targetKey)
            val presetEffectDto = FxPresetEffectDto(
                effectType = effect.effectType,
                category = effect.category,
                propertyName = effect.propertyName,
                beatDivision = effect.beatDivision,
                blendMode = effect.blendMode,
                distribution = effect.distribution,
                phaseOffset = effect.phaseOffset,
                elementMode = effect.elementMode,
                elementFilter = effect.elementFilter,
                stepTiming = effect.stepTiming,
                parameters = effect.parameters,
            )
            val fxTarget = runCatching {
                resolveTargetForCue(state, target, presetEffectDto)
            }.getOrNull()
            if (fxTarget != null) {
                val instance = createInstanceFromPresetForCue(
                    presetEffectDto, fxTarget, null, state, cueId
                )
                instance.cueId = cueId
                instance.priority = cueDerivedPriority(applyData)
                state.show.fxEngine.addEffect(instance)
            }
        }

        state.show.fixtures.cueListChanged()
        return CueEditAdHocEffectAddedOutMessage(cueId, effect.effectType, effect.targetKey)
    }

    /**
     * Called on WebSocket disconnect to release any open session. Mirrors [endEdit]'s Live
     * cleanup but wrapped in [runCatching] so a stale DB / manager state can't propagate an
     * exception up the disconnect handler.
     */
    fun endSessionOnDisconnect(
        state: State,
        sessionRef: AtomicReference<CueEditSessionState?>,
        handle: Any = sessionRef,
    ) {
        val session = sessionRef.getAndSet(null) ?: return
        state.cueEditSessionRegistry.unregister(handle)
        if (session.mode == CueEditMode.LIVE) {
            runCatching { releaseLiveSession(state, session) }
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
     * Load a cue's apply data, gating on project membership. Returns `null` for missing cues
     * or cues outside the current project — callers turn that into `CueEditErrorOutMessage`.
     */
    private fun loadCueApplyData(state: State, cueId: Int): CueApplyData? =
        transaction(state.database) {
            val cue = DaoCue.findById(cueId) ?: return@transaction null
            if (cue.project.id != state.projectManager.currentProject.id) return@transaction null
            buildCueApplyData(cue)
        }

    /**
     * Apply [applyData] to stage for a Live edit session. Stack cues delegate to
     * [CueStackManager.activateCueInStack] so the stack's active-cue state and crossfade
     * ownership stay coherent, and pause auto-advance so the edit session isn't yanked
     * forward by a timer. Standalone cues go through [applyCue] as before.
     */
    private fun applyCueForLiveEdit(state: State, applyData: CueApplyData) {
        val stackId = applyData.cueStackId
        if (stackId != null) {
            state.show.cueStackManager.activateCueInStack(state, stackId, applyData.cueId)
            state.show.cueStackManager.pauseAutoAdvance(stackId)
        } else {
            applyCue(state, applyData, replaceAll = false)
        }
    }

    /**
     * Release stage ownership for a Live session. Stack cues leave the stack active with the
     * edited cue as its active cue and resume auto-advance — a closed editor mid-show keeps
     * the show rolling. Standalone cues drop their effects + triggers.
     */
    private fun releaseLiveSession(state: State, session: CueEditSessionState) {
        if (session.cueStackId != null) {
            state.show.cueStackManager.resumeAutoAdvance(state, session.cueStackId)
        } else {
            state.cueTriggerManager.deactivateTriggersForCue(session.cueId)
            state.show.fxEngine.removeEffectsForCue(session.cueId)
        }
    }

    /**
     * Republish Layer 3 for [cueId] from pre-built [applyData]. Callers build the apply data
     * inside the persist transaction so we don't round-trip to the DB twice per edit.
     *
     * Combines the cue's own property assignments with property assignments from each
     * immediate preset application (timed presets don't contribute — matches [applyCue]).
     */
    private fun republishLayer3(state: State, cueId: Int, applyData: CueApplyData) {
        val cueOwn = buildLayer3AssignmentsForCue(state.show.fixtures, applyData)
        val priority = cueDerivedPriority(applyData)
        val presetRows = transaction(state.database) {
            applyData.presetApplications
                .filter { it.delayMs == null && it.intervalMs == null }
                .flatMap { app ->
                    val preset = DaoFxPreset.findById(app.presetId) ?: return@flatMap emptyList()
                    buildLayer3AssignmentsForPreset(
                        state.show.fixtures, cueId, priority,
                        app.presetId, preset.toPropertyAssignmentDtos(), app.targets,
                    )
                }
        }
        val combined = when {
            presetRows.isEmpty() -> cueOwn
            cueOwn.isEmpty() -> presetRows
            else -> cueOwn + presetRows
        }
        if (combined.isNotEmpty()) {
            state.show.fxEngine.setCueAssignments(cueId, combined)
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
