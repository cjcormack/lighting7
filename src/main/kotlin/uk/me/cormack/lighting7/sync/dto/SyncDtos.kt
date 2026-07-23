package uk.me.cormack.lighting7.sync.dto

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import uk.me.cormack.lighting7.fx.EffectMode
import uk.me.cormack.lighting7.fx.FxOutputType
import uk.me.cormack.lighting7.fx.TimingSource
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.FxPresetEffectDto
import uk.me.cormack.lighting7.models.PromptBookRectDto
import uk.me.cormack.lighting7.models.TriggerType
import uk.me.cormack.lighting7.scripts.ScriptType

/**
 * Phase-1 sync DTOs. Every record references peers by **UUID, not int id** — int ids are
 * local-only handles. Property names mirror the DAO column names where reasonable so reading
 * a JSON file maps directly back to the schema. See `docs/sync-engineering.md`.
 *
 * Note: deliberately separate from the existing API DTOs in `models/` — the sync wire format
 * must be stable across schema-internal refactors (e.g. renaming a REST DTO field shouldn't
 * silently change the synced JSON shape).
 */

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class FormatVersionJson(
    // v4: prompt-book script PDFs travel in the repo as `promptScripts/{hash}.pdf`.
    //
    // @EncodeDefault(ALWAYS) forces these to be written despite the canonical encoder's
    // `encodeDefaults = false`. Without it the whole object serialises to `{}` and every
    // reader falls back to its OWN compiled-in default — so the version gate can never see
    // the writer's version and never rejects a too-new repo. Forcing the value is what
    // makes a pre-v4 install actually refuse a v4 repo (and stop it wiping the PDFs).
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val formatVersion: Int = 4,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val minReader: Int = 1,
)

/**
 * Marker written to `tombstones/{tableName}/{uuid}.json` when a previously-synced record
 * has been deleted locally. Body is intentionally minimal and timestamp-free so the
 * file's hash stays stable across re-snapshots — forensics come from `git log` on the
 * path, not from the file contents.
 */
@Serializable
data class TombstoneJson(val tombstone: Boolean = true)

@Serializable
data class InstallsJson(
    val installs: Map<String, String> = emptyMap(),
)

@Serializable
data class ProjectJson(
    val uuid: String,
    val name: String,
    val description: String? = null,
    val stageWidthM: Double? = null,
    val stageDepthM: Double? = null,
    val stageHeightM: Double? = null,
)

@Serializable
data class ScriptMetaJson(
    val uuid: String,
    val name: String,
    val scriptType: ScriptType = ScriptType.GENERAL,
)

@Serializable
data class FxDefinitionJson(
    val uuid: String,
    val effectId: String,
    val name: String,
    val category: String,
    val outputType: FxOutputType,
    val effectMode: EffectMode = EffectMode.STANDARD,
    val parameters: JsonElement? = null,
    val compatibleProperties: List<String> = emptyList(),
    val script: String,
    val defaultStepTiming: Boolean = false,
    val timingSource: TimingSource = TimingSource.BEAT,
)

@Serializable
data class FxPresetPropertyAssignmentJson(
    val uuid: String,
    val propertyName: String,
    val value: String,
    val fadeDurationMs: Long? = null,
    val sortOrder: Int = 0,
    val elementKey: String? = null,
)

@Serializable
data class FxPresetJson(
    val uuid: String,
    val name: String,
    val fixtureType: String,
    val description: String? = null,
    val effects: List<FxPresetEffectDto> = emptyList(),
    val palette: List<String> = emptyList(),
    val propertyAssignments: List<FxPresetPropertyAssignmentJson> = emptyList(),
)

/**
 * Universe config — portable subset only. The `address` column (machine-local controller IP)
 * is intentionally omitted; Phase 2 introduces machine_override for per-install IPs.
 */
@Serializable
data class UniverseConfigJson(
    val uuid: String,
    val subnet: Int = 0,
    val universe: Int,
    val controllerType: String = "ARTNET",
)

@Serializable
data class FixturePatchJson(
    val uuid: String,
    val universeConfigUuid: String,
    val fixtureTypeKey: String,
    val key: String,
    val displayName: String,
    val startChannel: Int,
    val sortOrder: Int = 0,
    val stageX: Double? = null,
    val stageY: Double? = null,
    val stageZ: Double? = null,
    val baseYawDeg: Double? = null,
    val basePitchDeg: Double? = null,
    val riggingUuid: String? = null,
    val beamAngleDeg: Int? = null,
    val gelCode: String? = null,
    val kindOverride: String? = null,
)

/**
 * A first-class rigging — truss, bar, boom, pipe, or floor stand. Carries a 3D pose
 * (position + yaw/pitch/roll) so fixture patches with [FixturePatchJson.riggingUuid]
 * set can express their stage_x/y/z as offsets in the rigging's local frame. See
 * `docs/fixtures-engineering.md` for the v3 Z-up FOH-relative coordinate system.
 */
@Serializable
data class RiggingJson(
    val uuid: String,
    val name: String,
    val kind: String? = null,
    val positionX: Double? = null,
    val positionY: Double? = null,
    val positionZ: Double? = null,
    val yawDeg: Double? = null,
    val pitchDeg: Double? = null,
    val rollDeg: Double? = null,
    val lengthM: Double? = null,
    val sortOrder: Int = 0,
)

/**
 * A rectangular platform forming part of the playable stage surface. Multiple regions
 * describe thrusts, raised platforms, pits, and multi-level stages. Project-level
 * [ProjectJson.stageWidthM] / depth / height stays as a coarse fallback bounding box.
 * [centerZ] = 0 means deck level; > 0 raises the top surface above the deck.
 */
@Serializable
data class StageRegionJson(
    val uuid: String,
    val name: String,
    val centerX: Double? = null,
    val centerY: Double? = null,
    val centerZ: Double? = null,
    val widthM: Double? = null,
    val depthM: Double? = null,
    val heightM: Double? = null,
    val yawDeg: Double? = null,
    val sortOrder: Int = 0,
)

@Serializable
data class FixtureGroupMemberJson(
    val uuid: String,
    val fixturePatchUuid: String,
    val sortOrder: Int = 0,
    val panOffset: Double = 0.0,
    val tiltOffset: Double = 0.0,
)

@Serializable
data class FixtureGroupJson(
    val uuid: String,
    val name: String,
    val members: List<FixtureGroupMemberJson> = emptyList(),
)

@Serializable
data class CueStackJson(
    val uuid: String,
    val name: String,
    val palette: List<String> = emptyList(),
    val loop: Boolean = false,
)

@Serializable
data class CuePropertyAssignmentJson(
    val uuid: String,
    val cueUuid: String,
    val targetType: String,
    val targetKey: String,
    val propertyName: String,
    val value: String,
    val fadeDurationMs: Long? = null,
    val sortOrder: Int = 0,
    val moveInDark: Boolean = false,
)

@Serializable
data class CuePresetApplicationJson(
    val uuid: String,
    val cueUuid: String,
    val presetUuid: String,
    val targets: List<CueTargetDto> = emptyList(),
    val delayMs: Long? = null,
    val intervalMs: Long? = null,
    val randomWindowMs: Long? = null,
    val sortOrder: Int = 0,
)

@Serializable
data class CueAdHocEffectJson(
    val uuid: String,
    val cueUuid: String,
    val targetType: String,
    val targetKey: String,
    val effectType: String,
    val category: String,
    val propertyName: String? = null,
    val beatDivision: Double,
    val blendMode: String,
    val distribution: String,
    val phaseOffset: Double = 0.0,
    val elementMode: String? = null,
    val elementFilter: String? = null,
    val stepTiming: Boolean? = null,
    val parameters: Map<String, String> = emptyMap(),
    val delayMs: Long? = null,
    val intervalMs: Long? = null,
    val randomWindowMs: Long? = null,
    val sortOrder: Int = 0,
)

@Serializable
data class CueTriggerJson(
    val uuid: String,
    val cueUuid: String,
    val triggerType: TriggerType,
    val scriptUuid: String,
    val delayMs: Long? = null,
    val intervalMs: Long? = null,
    val randomWindowMs: Long? = null,
    val sortOrder: Int = 0,
)

@Serializable
data class CueJson(
    val uuid: String,
    val cueStackUuid: String? = null,
    val name: String,
    val palette: List<String> = emptyList(),
    val updateGlobalPalette: Boolean = false,
    val sortOrder: Int = 0,
    val autoAdvance: Boolean = false,
    val autoAdvanceDelayMs: Long? = null,
    val fadeDurationMs: Long? = null,
    val fadeCurve: String = "LINEAR",
    val cueNumber: String? = null,
    val notes: String? = null,
    val cueType: String = "STANDARD",
    val stomp: Boolean = false,
)

@Serializable
data class ShowEntryJson(
    val uuid: String,
    val cueStackUuid: String? = null,
    val entryType: String = "STACK",
    val sortOrder: Int,
    val label: String? = null,
)

@Serializable
data class CueSlotJson(
    val uuid: String,
    val page: Int,
    val slotIndex: Int,
    val cueUuid: String? = null,
    val cueStackUuid: String? = null,
)

/**
 * Parked DMX channel — a channel locked at a fixed output value that overrides every other
 * source. Parking is portable show content: operators routinely use it to pin "house lights at
 * 50%" or to protect a channel that drives a hard-powered fixture plugged into a dimmer, both
 * of which travel with the project.
 *
 * `(universe, channel)` is the natural key on disk and in the DB unique index; `uuid` exists
 * solely to give the sync engine a stable record identity across renames-of-value.
 */
@Serializable
data class ParkedChannelJson(
    val uuid: String,
    val universe: Int,
    val channel: Int,
    val value: Int,
)

@Serializable
data class ControlSurfaceBindingJson(
    val uuid: String,
    val deviceTypeKey: String,
    val controlId: String,
    val bank: String? = null,
    val targetType: String,
    val targetPayload: String,
    val takeoverPolicy: String? = null,
    val sortOrder: Int = 0,
)

/**
 * Prompt-book: binds an imported PDF script (identified by content hash) to the
 * project's show. As of format v4 the PDF bytes travel too, as a binary blob at
 * `promptScripts/{scriptHash}.pdf` moved by `PromptScriptRepoSync` (never through
 * this JSON path). On an install still missing the bytes — e.g. a book created
 * before v4 whose PDF reached no peer — the client offers a re-import by hash.
 */
@Serializable
data class PromptBookJson(
    val uuid: String,
    val scriptHash: String,
    val scriptFileName: String? = null,
    val pageCount: Int,
    /** Leading front-matter (cover/title) pages before the script's printed page 1. */
    val coverPages: Int = 0,
)

@Serializable
data class PromptBookAnchorJson(
    val uuid: String,
    val cueUuid: String,
    val region: List<PromptBookRectDto>,
    val label: String? = null,
)

@Serializable
data class PromptBookAnnotationJson(
    val uuid: String,
    val kind: String,
    val region: List<PromptBookRectDto>,
    val text: String? = null,
    val color: String? = null,
    val tone: String? = null,
)
