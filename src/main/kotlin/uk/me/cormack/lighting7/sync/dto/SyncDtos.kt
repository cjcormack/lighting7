package uk.me.cormack.lighting7.sync.dto

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import uk.me.cormack.lighting7.fx.EffectMode
import uk.me.cormack.lighting7.fx.FxOutputType
import uk.me.cormack.lighting7.fx.TimingSource
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.LookEffectSpec
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
    // v7: the positional colour list is gone. `palette` leaves `looks/`, `cues/` and `cueStacks/`,
    // and `updateGlobalPalette` leaves `cues/`; an effect that wants a named colour references a
    // **template** from its own parameters instead. `minReader` stays at **5**: every removed field
    // has a default, so a v5 or v6 archive still imports — it simply drops colour lists nothing
    // reads any more. Only the writer's version moves, which is what makes an older install refuse
    // a v7 repo rather than silently write those fields back on its next push.
    //
    // v6: templates become their own entity — a `templates/` folder, and `CueLayerJson.lookUuid`
    // becomes optional beside a new `templateUuid`. `minReader` stays at **5**, deliberately: a v5
    // repo has no `templates/` folder (the importer reads a missing directory as empty) and every
    // one of its cue layers carries a `lookUuid`, so a v5 archive still imports exactly as before.
    // Only the writer's version moves, which is what makes a v5 install refuse a v6 repo — where a
    // cue layer may carry `templateUuid` alone, and a v5 reader would take `lookUuid`'s null
    // straight into `UUID.fromString` (a Java platform type, so no compile-time stop) and fail with
    // an NPE naming nothing.
    //
    // v5: FX presets and named palettes collapse into `looks/`, and `cuePresetApplications/`
    // becomes `cueLayers/`. `minReader` jumps to 5 because `CuePresetApplicationJson.presetUuid`
    // — a required field — is gone: an older reader would fail to parse a v5 repo rather than
    // silently drop the composition, so there is nothing to be gained by letting it try.
    //
    // v4: prompt-book script PDFs travel in the repo as `promptScripts/{hash}.pdf`.
    //
    // @EncodeDefault(ALWAYS) forces these to be written despite the canonical encoder's
    // `encodeDefaults = false`. Without it the whole object serialises to `{}` and every
    // reader falls back to its OWN compiled-in default — so the version gate can never see
    // the writer's version and never rejects a too-new repo. Forcing the value is what
    // makes a pre-v4 install actually refuse a v4 repo (and stop it wiping the PDFs).
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val formatVersion: Int = 7,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val minReader: Int = 5,
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
data class LookRowJson(
    val uuid: String,
    /** A `TargetRef` discriminator, or `"deferred"` when the row takes its targets from the layer. */
    val targetType: String,
    val targetKey: String,
    val propertyName: String,
    val value: String,
    val fadeDurationMs: Long? = null,
    val elementKey: String? = null,
    val sortOrder: Int = 0,
)

@Serializable
data class LookEffectJson(
    val uuid: String,
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
    /** Speed master uuid, remapped by ExportUuidRemapper like any uuid. */
    val speedMasterUuid: String? = null,
    val rateSpeedMasterUuid: String? = null,
    val sortOrder: Int = 0,
)

/**
 * A Look — portable show content, rows and effects embedded inline.
 *
 * Rows address fixtures and groups by *key*, the same as cue assignments do, so nothing inside
 * needs reference remapping. The record's own `uuid` does matter beyond identity: a cue layer
 * points at it, and until the `ref:` grammar is retired a stored value may still name it as
 * `ref:{uuid}`. [uk.me.cormack.lighting7.sync.ExportUuidRemapper] rewrites both, which is why the
 * reference is a uuid rather than an int id.
 *
 * There is deliberately **no attribute-type field**: which families a Look touches is derived from
 * its rows, so a Look can grow from one family to several with no format change.
 */
@Serializable
data class LookJson(
    val uuid: String,
    val name: String,
    val notes: String? = null,
    val sortOrder: Int = 0,
    /** The positional colour list (`P1` / `P2`), not the Look's own rows. */
    val rows: List<LookRowJson> = emptyList(),
    val effects: List<LookEffectJson> = emptyList(),
)



/**
 * One template row: a target (or none) and the intent it holds.
 *
 * Carries its own [uuid] like [LookRowJson] does, so a re-export of unchanged data is byte-identical
 * and the sync engine sees no change — a regenerated uuid per export would make every snapshot a
 * diff.
 */
@Serializable
data class TemplateRowJson(
    val uuid: String,
    /** `deferred` for a generic row, `fixture` for a per-fixture one. Never `group`. */
    val targetType: String,
    val targetKey: String,
    val propertyName: String,
    /** A `TemplateIntent` in serialised form — `#FF9D4A;policy=extract`, `pct:75`, `deg:45,12.5`. */
    val value: String,
    val sortOrder: Int = 0,
)

/**
 * A template — portable show content, rows embedded inline.
 *
 * Rows address fixtures by *key*, the same as a Look's do, so nothing inside needs reference
 * remapping; the record's own `uuid` matters because a cue layer points at it.
 *
 * There is deliberately **no family field and no fixture type**. The family is derived from the
 * rows (and validated to be exactly one at the write boundary), and a template has no fixture type
 * by design — the values are intents resolved per head at cook. Both would be second sources of
 * truth for something the rows already say.
 */
@Serializable
data class TemplateJson(
    val uuid: String,
    val name: String,
    val notes: String? = null,
    val sortOrder: Int = 0,
    val fadeDurationMs: Long? = null,
    val rows: List<TemplateRowJson> = emptyList(),
)

/**
 * Speed master — portable show content. The exported [bpm] is the master's starting tempo
 * (the live value is written through on change, so this is "wherever the tempo was last
 * set"). Preset and cue effects reference a master by uuid inside their own records;
 * [uk.me.cormack.lighting7.sync.ExportUuidRemapper] rewrites those occurrences along with
 * this record's `uuid`, which is why the reference is a uuid rather than an int id.
 */
@Serializable
data class SpeedMasterJson(
    val uuid: String,
    val masterIndex: Int,
    val name: String,
    val bpm: Double = 120.0,
    val source: String = "MANUAL",
    val notes: String? = null,
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
    val stageHidden: Boolean = false,
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
    val loop: Boolean = false,
    val sortOrder: Int = 0,
    /** "STACK" (default) or "SEPARATOR". */
    val type: String = "STACK",
    val label: String? = null,
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


/**
 * One line of a cue's ordered layer composition. Its own top-level folder, the way the retired
 * `CuePresetApplicationJson` was — a cue child that points at a second entity, so it cannot be
 * embedded in either one.
 *
 * **Exactly one of [lookUuid] / [templateUuid]** is set: a layer applies a Look or a template. Both
 * are uuids rather than ids for the usual reason — int PKs are re-minted on import, and
 * [uk.me.cormack.lighting7.sync.ExportUuidRemapper] rewrites uuid occurrences across the export.
 */
@Serializable
data class CueLayerJson(
    val uuid: String,
    val cueUuid: String,
    val lookUuid: String? = null,
    val templateUuid: String? = null,
    val sortOrder: Int = 0,
    val enabled: Boolean = true,
    val targets: List<CueTargetDto> = emptyList(),
    /** Comma-separated `PropertyMaskGroup` names; null = every property. */
    val propertyMask: String? = null,
    val blendMode: String = "OVERRIDE",
    val amount: Double = 1.0,
    val stomp: Boolean = false,
    /** Per-layer speed-master override, remapped by ExportUuidRemapper like any uuid. */
    val speedMasterUuid: String? = null,
    val rateSpeedMasterUuid: String? = null,
    val delayMs: Long? = null,
    val intervalMs: Long? = null,
    val randomWindowMs: Long? = null,
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
    /** Speed master this effect subscribes to (null → master 1). */
    val speedMasterUuid: String? = null,
    /** Wall-clock rate master (null → unscaled). */
    val rateSpeedMasterUuid: String? = null,
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
    val sortOrder: Int = 0,
    val autoAdvance: Boolean = false,
    val autoAdvanceDelayMs: Long? = null,
    val fadeDurationMs: Long? = null,
    val fadeCurve: String = "LINEAR",
    val cueNumber: String? = null,
    val cueNumberAuto: Boolean = false,
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
