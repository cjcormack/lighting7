package uk.me.cormack.lighting7.sync.dto

import uk.me.cormack.lighting7.fixture.lantern.ShutterBlade
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
    // v20: scenery on cues, stacks and Looks (stage-view plan session 8). `CueJson`, `CueStackJson`
    // and `LookJson` each gain a `scenery` list ([SceneryChangeJson]): an element by uuid, its
    // states as a nested object, and on a cue a `transitionMs`. Empty is omitted, so a show with no
    // scenery exports byte-for-byte as at v19. The writer's number moves by the sharp-edge rule: a
    // v19 reader imports every cue, stack and Look without its scenery and its next wipe-then-export
    // push writes it away for every peer. `minReader` stays at **5** — missing scenery is none.
    // Went without `FU-AUTH-ATTRIBUTION`'s columns, as v18 and v19 did (plan §11 Q2).
    // See `docs/sync-engineering.md` §"Version 20 — scenery on cues, stacks and Looks".
    //
    // v19: the lantern and its focus (stage-view plan session 7). `FixturePatchJson` and
    // `PatchPlacementJson` each gain `lanternType`, `zoomDeg`, `lampRotationDeg`, `shutters` (four
    // `{depth, angleDeg}`, nested, not a string), `gateRotationDeg`, `iris` and `focusSoftness`,
    // every one null by default and omitted then, so a patch with no lantern exports byte-for-byte as
    // at v18. The writer's number moves by the sharp-edge rule: a v18 reader imports every lantern as
    // its kind's default with its blades out, and its next wipe-then-export push writes the focus
    // away for every peer. `minReader` stays at **5** — a missing focus is an unfocused lantern.
    // See `docs/sync-engineering.md` §"Version 19 — lanterns and focus".
    //
    // v18: the scene document (stage-view plan session 2). Two new record folders,
    // `stageElements/` and `stageViewpoints/`: named venue and set elements with each kind's
    // `params` as a nested object, and saved viewpoints, a seat view naming its seating element by
    // `seatElementUuid`. `minReader` stays at **5** — both folders read as empty when missing, so
    // every older archive imports with no scene. The writer's number moves by the sharp-edge rule:
    // a v17 reader imports no scene and its next wipe-then-export push writes none back, deleting
    // every peer's venue. Went without `FU-AUTH-ATTRIBUTION`'s columns (plan §11 Q2: passed).
    //
    // v17: a body's roll. See `docs/sync-engineering.md` §"Version 17 — fixture roll".
    //
    // v16: the operator's head number. `FixturePatchJson.headNumber` carries it (null — omitted —
    // when a head is unnumbered, so an export with none is byte-identical to v15). It bumps for
    // v14's reason: a v15 reader imports every patch unnumbered and writes the numbers away on its
    // next wipe-then-export push. `minReader` stays at **5** — a missing number is unnumbered.
    //
    // v15: a variable-length fixture's length. `FixturePatchJson.lengthM` and
    // `PatchPlacementJson.lengthM` carry a lightstrip's installed length and each of its segments'
    // (`docs/fixtures-engineering.md` §"Variable-length fixtures"). Both default to null and are
    // omitted then, so an export with no lengths is byte-identical to v14. It still bumps, for v14's
    // reason: a v14 reader imports the strip at its type's default and writes the length away on its
    // next wipe-then-export push. `minReader` stays at **5** — a missing length is the default.
    //
    // v12: the busk rig (`docs/plans/completed/busk-further-plan.md` §3.7). A new `buskRig/`
    // folder — one document per **row** with its tiles nested, tiles naming a group or a patch by
    // uuid and a cell by its element key — beside `buskPages/`. The plan's first draft said one
    // `buskRig.json` at the top level; session 2 amended that, because `RecordHasher` treats every
    // top-level file as metadata and a rig outside the record scan would never propagate through
    // the three-way diff. `minReader` stays at **5**: the folder reads as empty when missing, so a
    // v11 archive imports with an empty rig — which is today's band (D1). The writer's number
    // moves for v10's reason: a rig changes what the band offers and what *Next* walks, so a v11
    // reader — which would import no rig and on its next wipe-then-export push write none back,
    // deleting every peer's — must refuse the repo instead.
    //
    // v11: control-surface binding payloads. No folder or field changes — `targetPayload` stays
    // an opaque string — but what it may *contain* grows in two ways. New `type` discriminators
    // (`selectionProperty`, `selectTarget`, `clearSelection`, `locateSelection`; more in later
    // sessions of `docs/plans/completed/midi-surface-plan.md`), and a `cueUuid` / `stackUuid` beside the
    // int on `fireCue` / `cueStack*`, which is what finally lets a clone or a cross-install import
    // keep its cue bindings (`FU-SYNC-BINDING-PAYLOAD-UUIDS`, first half). `minReader` stays at
    // **5**: the reader decodes each binding row on its own and keeps an undecodable one as a
    // dead, rebindable `unknown` target rather than refusing the project, and a uuid it does not
    // read is a field it ignores. The writer's number moves because a v10 reader had neither
    // tolerance — one new discriminator failed its whole binding load — so it must refuse the repo.
    //
    // v10: the busk layout, and a Look on a cue slot. A new `buskPages/` folder — one document per
    // page with its columns, banks and pads nested, pads naming templates, Looks and cues by uuid
    // — and `CueSlotJson.lookUuid` beside `cueUuid`. `minReader` stays at **5**: the folder reads
    // as empty when missing and the field is optional with a null default, so every older archive
    // still imports unchanged.
    //
    // The writer's version moves for v9's reason, verbatim: a layout changes what a busk press
    // *does* on stage (a solo bank releases its siblings), so a v9 reader — which would import no
    // pages, never read the folder, and on its next wipe-then-export push write none back — must
    // refuse the repo rather than silently delete every peer's pages.
    //
    // Session 3 of the same plan **removed** `templateGroups/`, `TemplateJson.groupUuid` and
    // `sortOrder`, `LookJson.sortOrder`, `CueJson.pinnedToBusk` and `CueSlotJson.cueStackUuid`
    // under this same number, rather than bumping again: nothing but the dev desk wrote v10
    // between the two sessions, so no peer can hold a repo the later reader would import wrong and
    // push back. A v10 archive written *before* that session may still carry `cueStackUuid`; the
    // reader drops that slot with a warning rather than failing the pull, which is the same posture
    // a pad naming a missing record takes (a slot, like a pad, is an enrichment).
    //
    // v9: template groups. A new `templateGroups/` folder, and `TemplateJson.groupUuid` naming
    // one. `minReader` stays at **5**: the folder is read as empty when missing and the field is
    // optional with a null default, so every older archive still imports unchanged.
    //
    // The writer's version moves for v6's reason, not v8's. A v8 reader would import a v9 repo with
    // every template ungrouped and never read the folder — and its next push is wipe-then-export,
    // which writes no `templateGroups/` and no `groupUuid`, deleting every peer's groups. That is
    // not the SpeedMaster "null is a valid state" case: a group changes what a busk press *does*
    // (siblings release), so silently dropping one changes behaviour on stage. Bumping the writer
    // makes a v8 install refuse the repo instead, which is the only outcome that loses nothing.
    //
    // v8: a template may hold an **effect** instead of values. `template_effects` travels inline
    // as `TemplateJson.effect`. `minReader` stays at **5**: the field is optional with a null
    // default, so every older archive still imports unchanged.
    //
    // The writer's version moves even though the rule of thumb ("new optional field → no bump")
    // would let it stand still, and for the reason v6 spells out below: this field is the
    // *record's whole content*, not an enrichment. A v7 reader would import an effect template as
    // a hollow rows-less template — one the write boundary would refuse — and then write that back
    // over the effect on its next push. Bumping the writer makes a v7 install refuse the repo
    // instead, which is the only outcome that does not lose data. Contrast `SpeedMasterJson`'s four
    // routing fields, which took no bump: a null usage there is a *valid state* ("routes nothing").
    //
    // v7: the positional colour list is gone. `palette` leaves `looks/`, `cues/` and `cueStacks/`,
    // and `updateGlobalPalette` leaves `cues/`; an effect that wants a named colour references a
    // **template** from its own parameters instead. `minReader` stays at **5**: every removed field
    // has a default, so a v5 or v6 archive still imports — it simply drops colour lists nothing
    // reads any more. Only the writer's version moves, which is what makes an older install refuse
    // a v7 repo rather than silently write those fields back on its next push.
    //
    // v13: a rig row carries `flow` and `width` (the bank's two layout facts), and `BuskFlow` gains
    // `SCROLL` for banks and rows alike. The two new fields default, so on their own this would be
    // the `SpeedMasterJson` no-bump case — but `SCROLL` widens an **existing** field's value space:
    // a v12 reader imports a `SCROLL` bank as `WRAP` (`ProjectImporter`'s fallback) and writes that
    // back on its next push, which is `docs/sync-engineering.md`'s "degraded record" answer. Only
    // the writer moves, so a v12 install refuses a v13 repo rather than re-flowing every peer's
    // banks; `minReader` stays at **5**, since every earlier archive still decodes.
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
    val formatVersion: Int = 20,
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
    /** A `TargetRef` discriminator — `fixture` or `group`. A Look row is always bound. */
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
    val rows: List<LookRowJson> = emptyList(),
    val effects: List<LookEffectJson> = emptyList(),
    /** v20: what the Look shows while it is live. */
    val scenery: List<SceneryChangeJson> = emptyList(),
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
 * A template's one effect — [LookEffectJson] minus `targetType` / `targetKey` (an effect template
 * is always generic, fx-templates D3) and `sortOrder` (at most one, D2).
 *
 * `parameters` may hold a `tmpl:{uuid}` colour reference. It needs no special handling here for
 * the same reason `LookEffectJson`'s does not: `ExportUuidRemapper` rewrites every uuid *string*
 * in the export text, so the reference and the two master uuids are re-pointed on clone with no
 * field-aware code.
 */
@Serializable
data class TemplateEffectJson(
    val uuid: String,
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
)

/**
 * A template — portable show content, rows *or* one effect embedded inline.
 *
 * Rows address fixtures by *key*, the same as a Look's do, so nothing inside needs reference
 * remapping; the record's own `uuid` matters because a cue layer points at it.
 *
 * [rows] and [effect] are mutually exclusive (fx-templates D1), enforced at the write boundary
 * rather than by the format — the importer writes what it is given, as it does for every other
 * record.
 *
 * There is deliberately **no family field and no fixture type**. The family is derived — from the
 * rows, or from the effect's library `category` — and validated to be exactly one at the write
 * boundary; a template has no fixture type by design, since the values are intents resolved per
 * head at cook. Both would be second sources of truth for something the contents already say.
 *
 * A template carries no position and no group. Both were template *groups*, removed at v10 when the
 * busk page took ownership of the layout: order is a pad's place in a bank, and exclusivity is a
 * solo bank's. `GET /templates` lists by name.
 */
@Serializable
data class TemplateJson(
    val uuid: String,
    val name: String,
    val notes: String? = null,
    val fadeDurationMs: Long? = null,
    val rows: List<TemplateRowJson> = emptyList(),
    val effect: TemplateEffectJson? = null,
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
    /**
     * Routing/follow settings (busking-view plan, session 1). All four are additive optional
     * fields with null defaults — no formatVersion bump, per the "new optional field" rule in
     * docs/sync-engineering.md.
     *
     * [followTargetUuid] is the leader's uuid and travels as a uuid for the usual reason —
     * `ExportUuidRemapper` rewrites it on clone, where an int id or a master index would
     * dangle or silently re-point. Null with a ratio set means master 1, which is what every
     * export written before follow targets existed says.
     */
    val usage: String? = null,
    val followNum: Int? = null,
    val followDen: Int? = null,
    val followTargetUuid: String? = null,
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
    /** The operator's head number (v16); null is unnumbered. Imported as stored — see
     *  `docs/sync-engineering.md` §"Version 16 — head numbers". */
    val headNumber: Int? = null,
    val startChannel: Int,
    val sortOrder: Int = 0,
    val stageX: Double? = null,
    val stageY: Double? = null,
    val stageZ: Double? = null,
    val baseYawDeg: Double? = null,
    val basePitchDeg: Double? = null,
    /** Body roll about its local Z (v17); null is 0. See `docs/sync-engineering.md`
     *  §"Version 17 — fixture roll". */
    val baseRollDeg: Double? = null,
    val riggingUuid: String? = null,
    val beamAngleDeg: Int? = null,
    val gelCode: String? = null,
    val kindOverride: String? = null,
    /** A variable-length type's installed length in metres (v15); null is the type's default. */
    val lengthM: Double? = null,
    val stageHidden: Boolean = false,
    val infrastructure: Boolean = false,
    /** The lantern, by library id, and its focus (v19); every field null is an unfocused lantern of
     *  the kind's default. See `docs/sync-engineering.md` §"Version 19 — lanterns and focus". */
    val lanternType: String? = null,
    val zoomDeg: Double? = null,
    val lampRotationDeg: Double? = null,
    val shutters: List<ShutterBlade>? = null,
    val gateRotationDeg: Double? = null,
    val iris: Double? = null,
    val focusSoftness: Double? = null,
    /**
     * The other places this fixture hangs — a paired dimmer's second lantern. Embedded in
     * list order (so no `sortOrder`), like a group's `members`, and omitted by canonical JSON
     * when empty, so an export with no pairs is byte-identical to one written before the field
     * existed. It still bumped formatVersion to 14: a v13 reader would import a paired patch
     * without its lanterns and write them away on its next push (docs/sync-engineering.md
     * §"Version 14 — paired placements").
     */
    val extraPlacements: List<PatchPlacementJson> = emptyList(),
)

/**
 * One of a patch's extra placements (`fixture_patch_placements`). Same geometry and
 * rigging-relative rule as the patch's own `stageX`… fields. [riggingUuid] is a reference,
 * so a clone's `ExportUuidRemapper` rewrites it alongside the placement's own [uuid].
 */
@Serializable
data class PatchPlacementJson(
    val uuid: String,
    val label: String? = null,
    val riggingUuid: String? = null,
    val stageX: Double? = null,
    val stageY: Double? = null,
    val stageZ: Double? = null,
    val baseYawDeg: Double? = null,
    val basePitchDeg: Double? = null,
    /** This lantern's body roll (v17); null is 0. */
    val baseRollDeg: Double? = null,
    /** This segment's own length (v15), for a variable-length type laid in segments; null takes
     *  the patch's `lengthM`. */
    val lengthM: Double? = null,
    /** This lantern (v19); null takes the patch's `lanternType`. The six after it are this
     *  lantern's own focus, never inherited. */
    val lanternType: String? = null,
    val zoomDeg: Double? = null,
    val lampRotationDeg: Double? = null,
    val shutters: List<ShutterBlade>? = null,
    val gateRotationDeg: Double? = null,
    val iris: Double? = null,
    val focusSoftness: Double? = null,
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

/**
 * One scene element (v18). [params] is the kind's document as the desk stores it — a nested object,
 * so the canonical encoder sorts its keys and a platform's `regionUuid` is remapped on clone like
 * any other reference. Absent when the kind has nothing to say (an empty document). The pose and
 * size default to 0, and are omitted then.
 */
@Serializable
data class StageElementJson(
    val uuid: String,
    val name: String,
    val kind: String,
    val layer: String,
    val positionX: Double = 0.0,
    val positionY: Double = 0.0,
    val positionZ: Double = 0.0,
    val yawDeg: Double = 0.0,
    val widthM: Double = 0.0,
    val depthM: Double = 0.0,
    val heightM: Double = 0.0,
    val finishColour: String? = null,
    val finishPattern: String? = null,
    val emissive: Boolean = false,
    val params: JsonObject? = null,
    val hidden: Boolean = false,
    val sortOrder: Int = 0,
)

/**
 * One saved viewpoint (v18). A seat view carries [seatElementUuid] and [seatId] instead of an eye;
 * the reference is to a `stageElements/` record, and a dangling one reads as no seat.
 */
@Serializable
data class StageViewpointJson(
    val uuid: String,
    val name: String,
    val kind: String,
    val eyeX: Double? = null,
    val eyeY: Double? = null,
    val eyeZ: Double? = null,
    val targetX: Double? = null,
    val targetY: Double? = null,
    val targetZ: Double? = null,
    val fovDeg: Double? = null,
    val seatElementUuid: String? = null,
    val seatId: String? = null,
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
    /** v20: the stack's *set* — the states its elements hold while it is live. */
    val scenery: List<SceneryChangeJson> = emptyList(),
)

/**
 * One scenery change (stage-view plan session 8), embedded in the cue, stack or Look that owns it,
 * as a Look's rows are: an owner says one thing about each element, and the change is part of the
 * owner's own record. [elementUuid] names a `stageElements/` record — a uuid like every other
 * cross-record reference here, so [uk.me.cormack.lighting7.sync.ExportUuidRemapper] re-points it on
 * clone. [state] is the states as a nested object (`visible`, `open`, `trimM`), stored as the
 * archive holds it. [transitionMs] is a cue's own clock only.
 */
@Serializable
data class SceneryChangeJson(
    val uuid: String,
    val elementUuid: String,
    val state: JsonObject = JsonObject(emptyMap()),
    val transitionMs: Long? = null,
    val sortOrder: Int = 0,
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
    /** v20: what the cue moves on GO, each change on its own clock. */
    val scenery: List<SceneryChangeJson> = emptyList(),
)

@Serializable
data class ShowEntryJson(
    val uuid: String,
    val cueStackUuid: String? = null,
    val entryType: String = "STACK",
    val sortOrder: Int,
    val label: String? = null,
)

/**
 * One tile of the FX cue slots overlay. **Exactly one of [cueUuid] / [lookUuid]** is set, enforced
 * by the importer (a slot naming none or two is malformed input, not a state). [lookUuid] is v10: a
 * Look with no deferred effect, pressed onto its own fixtures.
 *
 * A cue *stack* used to be a third arm. It went with the overlay's own assign flow, under the same
 * v10 (see [FormatVersionJson]); a pre-session-3 v10 archive may still carry `cueStackUuid`, and
 * the importer drops such a slot with a warning rather than failing the pull.
 */
@Serializable
data class CueSlotJson(
    val uuid: String,
    val page: Int,
    val slotIndex: Int,
    val cueUuid: String? = null,
    val lookUuid: String? = null,
)

/**
 * A busk page (v10) — portable show content, its columns, banks and pads embedded inline, the way
 * a Look carries its rows. One document per page: a drag changes one file.
 *
 * Nothing here is patch-shaped; a pad names a library record by uuid ([BuskPadJson]), so
 * `ExportUuidRemapper` re-points every reference on clone with no field-aware code, and a page
 * survives a repatch untouched.
 *
 * The structural fields (`row`, `sortOrder`, `width`, `name`, `solo`, `flow`) have **no defaults**
 * so canonical JSON always writes them: a layout is positions, and a position omitted because it
 * happened to be zero would read as a document with holes.
 */
@Serializable
data class BuskPageJson(
    val uuid: String,
    val name: String,
    val sortOrder: Int,
    val columns: List<BuskColumnJson> = emptyList(),
)

/** A column of a busk page: which row it is in, its place in that row, and its width share in twelfths. */
@Serializable
data class BuskColumnJson(
    val uuid: String,
    val row: Int,
    val sortOrder: Int,
    val width: Int,
    val banks: List<BuskBankJson> = emptyList(),
)

/** A bank in a column: `solo` releases siblings on a press; `flow` is `WRAP`, `COLUMN` or `SCROLL`. */
@Serializable
data class BuskBankJson(
    val uuid: String,
    val sortOrder: Int,
    val name: String,
    val solo: Boolean,
    val flow: String,
    val pads: List<BuskPadJson> = emptyList(),
)

/**
 * A pad: an ordered reference to **exactly one** of a template, a Look or a cue, by uuid — the
 * [CueLayerJson] pattern, three-armed. A pad whose record the archive does not carry, or which
 * names none or two, is **dropped with a warning** on import rather than aborting the pull: a pad
 * is an enrichment of its record (a place on a page), not content, so a page that has lost a pad
 * is still a whole page. Contrast a cue layer, which is nothing without its source and does abort.
 */
@Serializable
data class BuskPadJson(
    val uuid: String,
    val sortOrder: Int,
    val templateUuid: String? = null,
    val lookUuid: String? = null,
    val cueUuid: String? = null,
)

/**
 * A busk rig row (v12): its tiles nested, in `sortOrder`, the way a busk page carries its columns.
 * One document per row so a drag on the band changes one file.
 */
@Serializable
data class BuskRigRowJson(
    val uuid: String,
    val name: String,
    val sortOrder: Int,
    /**
     * The row's layout (2026-09-21): a `BuskFlow` name and a width share in twelfths, the bank's
     * two facts. Both default so an archive written before them decodes; the importer reads an
     * unknown flow as `SCROLL` and a width outside `BUSK_WIDTHS` as 12, as the route's read does.
     */
    val flow: String = "SCROLL",
    val width: Int = 12,
    val tiles: List<BuskRigTileJson> = emptyList(),
)

/**
 * A rig tile: an ordered reference to **exactly one** of a group or a patch, by uuid — the
 * [BuskPadJson] pattern, two-armed — plus how a multi-head fixture shows its cells (busk-further plan
 * D3). [elementKey] is the cell's own key for a tile that is one cell, carried verbatim: element
 * keys are opaque, and the write boundary validates one against the live fixture on the next write.
 * A tile whose group or patch the archive does not carry, or which names none or both, is
 * **dropped with a warning** on import rather than aborting the pull: a tile is an enrichment of its
 * record (a place on the band), not content, and a row that loses every tile goes with them.
 */
@Serializable
data class BuskRigTileJson(
    val uuid: String,
    val sortOrder: Int,
    val groupUuid: String? = null,
    val patchUuid: String? = null,
    val elementKey: String? = null,
    val cellMode: String = "PIPS",
    val cellSplit: Int? = null,
    val label: String? = null,
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
