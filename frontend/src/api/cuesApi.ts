import type { SpreadUnit } from '@/lib/spreadShape'
import type { SceneryChange, TrackedScenery } from './sceneryApi'
import type { CueType } from './cueStacksApi'
import type { FxDirection, Timing } from './fxApi'

// Cue target (fixture or group)
export interface CueTarget {
  type: 'group' | 'fixture'
  key: string
}

/**
 * One line of a cue's ordered Look composition.
 *
 * The cue's layers are cooked down — in `sortOrder`, later winning — to exactly one contributor per
 * (fixture, property) before the resolver ever sees them, and the cue's own `attributeAssignments`
 * are the last layer and beat all of them. That holds for *every* attribute, intensity included:
 * within a cue, layering a dim look over a bright one really does dim. Across cues, HTP still
 * applies. See `lighting7/docs/lighting-composition-model.md` §"Looks and layers".
 */
/**
 * What a layer applies — a **Look** or a **template**.
 *
 * One nested value rather than the `lookId`/`lookName` pair it replaces, and that was the point of
 * shaping it this way: a layer's referent became polymorphic in session 3, and a field called
 * `lookName` holding a template's name is a lie no compiler can find. Collapsing the three fields
 * into one object made every reader visit exactly once.
 *
 * All three identifiers earn their place: `id` addresses REST paths, `uuid` is the portable identity
 * (int PKs are re-minted on sync import) and `name` is what an operator reads when asking why a
 * fixture is the colour it is.
 */
export interface LayerSource {
  kind: 'LOOK' | 'PALETTE'
  id: number
  uuid: string
  name: string
}

export interface CueLayer {
  /**
   * What this layer applies: **exactly one** of `lookId` / `paletteId`.
   *
   * Two ids rather than a `(kind, id)` pair, because that is what a client has in hand — it picked a
   * row out of one library or the other — and because a request naming both is then a shape error
   * the route refuses rather than a discriminator it has to trust.
   */
  lookId?: number | null
  paletteId?: number | null
  sortOrder?: number
  enabled?: boolean
  /**
   * The target set this layer operates over. One meaning, two jobs: it **supplies** targets to a
   * template's generic rows and **filters** a Look's bound ones. Empty means the source's own
   * targets, so a template layer with no targets contributes nothing.
   */
  targets: CueTarget[]
  /**
   * Comma-separated `AttributeFamily` names; null/absent = every property. This is what replaces
   * value-level references: "this cue's colour comes from Warm, everything else local" is one
   * COLOUR-masked layer rather than a separate feature.
   */
  attributeMask?: string | null
  /** `OVERRIDE` | `MAX` | `MIN` | `MULTIPLY` | `ADDITIVE`. */
  blendMode?: string
  /** Mix of this layer over what has accumulated beneath it, in [0, 1]. */
  amount?: number
  /**
   * Switch off the effects of every layer *below* this one, on every property this layer asserts.
   *
   * The escape hatch for the one thing layer order cannot express: effects are Layer 3 and values
   * Layer 4, so a lower layer's colour effect beats a higher layer's static colour whatever the
   * order says. Suppression rather than removal — clearing it brings those effects back mid-phase.
   */
  stomp?: boolean
  /** Per-layer speed-master override (null → each Look effect's own → master 1). */
  speedMasterUuid?: string | null
  /** Wall-clock rate master (null → unscaled); only WALL_CLOCK effects read it. */
  rateSpeedMasterUuid?: string | null
  delayMs?: number | null
  intervalMs?: number | null
  randomWindowMs?: number | null
}

/**
 * A layer as the server reads it back, carrying what it applies — kind, id, uuid and name — so a cue
 * card can label the line without a second fetch.
 *
 * `source` and `id` are **read-only** and must never be sent back: `lookId` / `paletteId` are the write
 * fields. Same contract as `presetName` was and as `health` is on an assignment. `buildCueInput`
 * strips it, and a regression test pins that.
 */
export interface CueLayerDetail extends CueLayer {
  source: LayerSource | null
  /**
   * The stored layer's id, read-only: a cue write keeps every layer it keeps (show-cue-editing
   * plan §3.4), so this names one layer across writes — what the cue editor's Looks tab finds a
   * gesture's layer by. Never sent back. Absent from a desk that predates it.
   */
  id?: number
}

// Ad-hoc effect stored inline in a cue (with optional timing)
export interface CueAdHocFx {
  /**
   * `selection` is one effect over an ordered list of fixtures and groups (effects plan D3), its
   * [targetKey] the list's canonical JSON — what Record writes for an effect started on a marquee.
   */
  targetType: 'group' | 'fixture' | 'selection'
  targetKey: string
  fxType: string
  family: string
  attribute: string | null
  cycle: number
  blendMode: string
  order: string
  phaseOffset: number
  elementMode: string | null
  elementFilter: string | null
  timing: Timing | null
  parameters: Record<string, string>
  delayMs?: number | null
  intervalMs?: number | null
  randomWindowMs?: number | null
  sortOrder?: number
  /** Speed master this effect subscribes to, as the master's uuid (null → master 1). */
  speedMasterUuid?: string | null
  /** Wall-clock rate master (null → unscaled); only WALL_CLOCK effects read it. */
  rateSpeedMasterUuid?: string | null
  /**
   * On *Seconds* (venue-visit plan D7): the effect follows no master, and a beat effect's
   * `cycle` is its cycle in seconds. A wall-clock effect is on Seconds whenever it has no rate
   * master, flag or not — read it through `isOnSeconds` (`lib/fxTiming.ts`). Absent = false.
   */
  seconds?: boolean
  /** Amount (effects plan D2), 0–1; absent at 1. */
  amount?: number
  /** Direction (D5); absent when forward. */
  direction?: FxDirection
  /** Runs (D9); absent or null runs forever. */
  runs?: number | null
  /** The spread's shape (effects plan D4) — `lib/spreadShape.ts`; each absent at its default. */
  spread?: number
  wings?: number
  segments?: number
  parts?: number
  /** What `spread` counts (fx-settings plan D6): `STEP`, the FX's own steps; absent is `CYCLE`. */
  spreadUnit?: SpreadUnit
  /**
   * The stored effect's uuid, on read only: a cue write keeps the uuid of every effect it keeps, so
   * this names one effect across writes. The desk ignores it on write and `buildCueInput` strips it.
   * Absent from a desk that predates it.
   */
  uuid?: string
}

/**
 * Validation status for a persisted fixture reference (cue property assignment, preset
 * property assignment). `ok` is the happy path; the remaining variants indicate dead
 * references where the fixture/group/property no longer exists on the current patch.
 * Server-side only: clients never construct these.
 */
export type AssignmentHealth =
  | { type: 'ok' }
  | { type: 'missingFixture'; fixtureKey: string }
  | { type: 'missingGroup'; groupName: string }
  | { type: 'missingAttribute'; targetKey: string; attribute: string }
// Four arms, down from seven. Two described a row whose value was `ref:{uuid}` and whose Look was
// gone or no longer covered the target, and a third before them named a wrong-type reference. All three retired with the `ref:` value grammar in
// session 4: a row's value is always a literal, so a cue's only remaining dependency on a Look is a
// layer — guarded by an indexed FK rather than by a health diagnosis.

// Layer 3 property assignment on a cue.
export interface CueAttributeAssignment {
  targetType: 'fixture' | 'group'
  targetKey: string
  attribute: string
  /** Canonical property-level form: "0".."255" for sliders/settings, extended-colour, "pan,tilt". */
  value: string
  fadeDurationMs?: number | null
  sortOrder?: number
  /**
   * Phase 6 dead-reference diagnostic. Absent on client-side drafts / pre-Phase-6 payloads
   * (treated as `{ type: 'ok' }`). Populated by the server when the cue is read back.
   */
  health?: AssignmentHealth
}

// ─── Script trigger types ──────────────────────────────────────────

export type TriggerType = 'ACTIVATION' | 'DEACTIVATION'

/** Script trigger definition for create/update */
export interface CueTrigger {
  triggerType: TriggerType
  delayMs?: number | null
  intervalMs?: number | null
  randomWindowMs?: number | null
  scriptId: number
  sortOrder?: number
}

/**
 * One speed master a cue sets as it comes on (venue-visit plan S16, D20): on every GO into one of
 * its entries, GO TO and GO BACK included, and every loose press — never a reload. The cue's, not
 * an entry's, so a shared cue sets the same tempo from every stack; it never tracks.
 */
export interface CueSpeedMaster {
  speedMasterUuid: string
  /** 20–300, a master's range. */
  bpm: number
}

/** Script trigger with resolved name (from API response) */
export interface CueTriggerDetail {
  triggerType: TriggerType
  delayMs?: number | null
  intervalMs?: number | null
  randomWindowMs?: number | null
  scriptId: number
  scriptName?: string | null
  sortOrder?: number
}

// Full cue from API
export interface Cue {
  id: number
  name: string
  /** The cue's ordered Look composition, in `sortOrder`. */
  layers: CueLayerDetail[]
  adHocFx: CueAdHocFx[]
  attributeAssignments: CueAttributeAssignment[]
  triggers: CueTriggerDetail[]
  /**
   * The **context entry** (lighting7 cue model plan): the entry named by the read's `?entryId=`, else
   * the cue's only entry. A cue in no stack (*loose*) or in several, read without `?entryId=`, has
   * none, and these are null or their defaults. Its every place is `entries`.
   */
  cueStackId: number | null
  cueStackName: string | null
  /** The context entry. Optional: a desk that predates entries omits it. */
  entryId?: number | null
  /** The entry's position in its stack's list, from 0, markers counted. */
  sortOrder: number
  /** The entry's follow — written through the entry PATCH, never the cue's. */
  autoAdvance: boolean
  autoAdvanceDelayMs: number | null
  fadeDurationMs: number | null
  fadeCurve: string
  /** The entry's number — written through the entry PATCH, never the cue's. */
  cueNumber: string | null
  /** True when `cueNumber` was derived from the entry's position rather than typed by the operator. */
  cueNumberAuto: boolean
  /** Every entry naming the cue, in show order. Optional: an older desk omits it. */
  entries?: CueEntryPlace[]
  /** What else names the cue — its busk pads, cue slots and bindings. Only the project's cue list carries it. */
  usedBy?: CueUsedBy
  notes: string | null
  /**
   * Cue-level stomp: firing this cue removes the effects running under it, rather than layering
   * over them. Distinct from `CueLayerDetail.stomp`, which suppresses effects *within* one cue's
   * composition — this one is cross-cue and destructive.
   *
   * Optional because the server omits it when false (`encodeDefaults = false`); absent means off.
   */
  stomp?: boolean
  cueType: CueType
  canEdit: boolean
  canDelete: boolean
  /**
   * What this cue moves on GO (stage-view plan session 8), each change on its own clock. Edited
   * through `PUT cues/{id}/scenery`, never through the cue's own PUT/PATCH. Optional: the desk
   * omits an empty list.
   */
  scenery?: SceneryChange[]
  /** The scenery this cue shows without moving it — tracked from earlier cues, or the stack's set. */
  trackedScenery?: TrackedScenery[]
  /**
   * The tubes this cue fires on GO into it, each at its offset, while the desk is armed (stage-view
   * plan session 9). Edited through `PUT cues/{id}/events`, never the cue's own PUT/PATCH. Never
   * tracked: a later cue carries none of an earlier one's. Optional: the desk omits an empty list.
   */
  events?: CueEvent[]
  /**
   * The masters this cue sets on GO, in order — written whole through the cue's own PATCH.
   * Optional: the desk omits an empty list, and an older desk sends none.
   */
  speedMasters?: CueSpeedMaster[]
  /** Move in dark on the heads this cue lights (move-in-dark plan D3). Absent from an older desk. */
  moveInDark?: CueMoveInDark
}

/**
 * A cue's own Move in dark: `STACK` follows its stack, `EARLY` and `LATE` apply on a stack that is
 * off, `OFF` moves its heads in its fade, lit. A null delay or move time is the stack's; `live` keeps
 * heads lit as they move, and `unknownLive` (read only) names those the rig lacks.
 */
export interface CueMoveInDark {
  mode: 'STACK' | 'EARLY' | 'LATE' | 'OFF'
  delayMs?: number | null
  timeMs?: number | null
  live: string[]
  unknownLive?: string[]
}

/** One entry naming a cue — one place it sits in a stack's list. */
export interface CueEntryPlace {
  entryId: number
  stackId: number
  stackName: string
  /** Position in its stack's list, from 0, markers counted. */
  sortOrder: number
  cueNumber: string | null
  cueNumberAuto: boolean
  autoAdvance: boolean
  autoAdvanceDelayMs: number | null
}

/** The cue list's *Used by*: what names a cue beside its entries (lighting7 cue model plan §3.7). */
export interface CueUsedBy {
  pads?: CueUses['pads']
  slots?: CueUses['slots']
  bindings?: CueUses['bindings']
}

/** Everything that names a cue — what a delete without force refuses over (`CUE_IN_USE`). */
export interface CueUses {
  cueId: number
  name: string
  entries: { stackId: number; stackName: string; entryId: number; cueNumber: string | null }[]
  pads: { padId: number; pageId: number; pageName: string }[]
  slots: { slotId: number; label: string }[]
  anchors: { entryId: number; label: string | null }[]
  bindings: { bindingId: number; label: string }[]
}

/** `POST cues/delete`. A dry run deletes nothing and answers the uses. */
export interface DeleteCuesRequest {
  cueIds: number[]
  force?: boolean
  dryRun?: boolean
}

/** `POST cues/{id}/duplicate`: the copy, and its place when it has one. */
export interface DuplicateCueResponse {
  cueId: number
  name: string
  stackId?: number | null
  entryId?: number | null
}

/** `POST cues/copy-from` (lighting7 cue model plan §3.8): cues of another project copied into this one. */
export interface CopyCuesFromRequest {
  fromProjectId: number
  /** In the order they are placed. */
  cueIds: number[]
  /** The stack each copy gets a new entry in, at its end; absent leaves every copy loose. */
  stackId?: number
  /** An entry of `stackId` the copies go after; absent is the end. */
  after?: number
  /** The same run rolled back: what a copy would do, nothing written. */
  dryRun?: boolean
}

/** A reference the copy could not carry, and the records — `cue 'Storm'`, `Look 'Night'` — that hold it. */
export interface CopyUnresolved {
  problem: string
  in: string[]
}

export interface CopyCuesFromResponse {
  dryRun: boolean
  /** Each copy, and its new entry when it went into a stack. A dry run's ids name nothing. */
  copied: { fromCueId: number; cueId: number; entryId?: number | null }[]
  unresolved: CopyUnresolved[]
  stackId?: number | null
  looksCopied?: string[]
  looksLinked?: string[]
  palettesCopied?: string[]
  palettesLinked?: string[]
}

/**
 * `POST cues/{id}/layers/press` (apply-model plan §3.6): a palette or a Look pressed into the stored
 * cue — exactly one of the two ids. A palette's press lands on `targets` under the selection's
 * `families`; a Look's names neither, since it lands whole (absolute-looks plan D1).
 */
export interface CueLayerPressRequest {
  paletteId?: number
  lookId?: number
  targets?: CueTarget[]
  families?: string[]
}

/** What the press did to the stored cue, and the cue as stored after it. */
export interface CueLayerPressResponse {
  action: 'applied' | 'removed'
  /** The other palettes' layers the press narrowed or dropped on its heads. */
  released?: number
  attributeMask?: string | null
  cue: Cue
}

/**
 * `POST cues/{id}/update-stage`: what *Update stage* moved, or with `dryRun` would move — the
 * (head, property) values whose level changes, and the cue's effects edited, stopped and started.
 */
export interface UpdateStageResponse {
  rows: number
  fx: { edited: number; stopped: number; started: number }
  /** The cue's own fade and curve, which the values move over; 0 is a snap. */
  fadeMs: number
  curve: string
}

export interface DeleteCuesResponse {
  deleted: number[]
  uses: CueUses[]
}

/** One cue event: a one-shot trigger on a patch, fired [offsetMs] after GO into its cue. */
export interface CueEvent {
  uuid: string
  patchId: number
  patchUuid: string
  fixtureKey: string
  fixtureName: string
  /** The trigger's name on its fixture type (`output1`). */
  trigger: string
  /** Its short name (`A`). */
  triggerLabel: string
  offsetMs: number
  sortOrder: number
}

/** One event of a whole-list write: `PUT cues/{id}/events`. `trigger` takes a name or a label. */
export interface CueEventWriteItem {
  patchId: number
  trigger: string
  offsetMs: number
}

// Input for create/update
export interface CueInput {
  name: string
  layers: CueLayer[]
  adHocFx: CueAdHocFx[]
  attributeAssignments?: CueAttributeAssignment[]
  triggers?: CueTrigger[]
  /** POST only: the stack the new cue's entry goes in. Absent makes a loose cue. */
  cueStackId?: number
  sortOrder?: number
  autoAdvance?: boolean
  autoAdvanceDelayMs?: number | null
  fadeDurationMs?: number | null
  fadeCurve?: string
  cueNumber?: string | null
  notes?: string | null
  /** Cue-level stomp — see `Cue.stomp`. Not the per-layer flag on `CueLayer`. */
  stomp?: boolean
  /** Only honoured on POST; a PUT ignores it and a PATCH refuses it, so markers cannot be turned into standard cues. */
  cueType?: CueType
  /** The whole list; a PUT clears what it is not sent, a PATCH leaves an absent list alone. */
  speedMasters?: CueSpeedMaster[]
}

// Partial input for PATCH (inline edits — only send changed fields). The place fields — stack,
// order, number and follow — are the entry's: `patchCueStackEntry` writes them, and the desk
// refuses them here.
export type CuePatchInput = Partial<
  Omit<CueInput, 'cueStackId' | 'sortOrder' | 'cueType' | 'cueNumber' | 'autoAdvance' | 'autoAdvanceDelayMs'>
>

// Apply response
export interface ApplyCueResponse {
  fxCount: number
  cueName: string
}

// Stop response
export interface StopCueResponse {
  removedCount: number
  cueId: number
}

/**
 * One composed value from `GET /cues/{id}/cooked`, in the canonical assignment grammar
 * `parseProgrammerValue` already reads.
 */
export interface CookedRow {
  targetType: 'fixture' | 'group'
  targetKey: string
  attribute: string
  value: string
  /**
   * The layer that won this key, when one did. Null for the cue's **own** rows, which belong to no
   * layer — the distinction the grid draws as "the cue set this" versus "Warm Wash set this".
   */
  layerId?: number | null
  layerSource?: LayerSource | null
  /**
   * The own row's or layer row's **own** target, before group expansion (show-cue-editing plan
   * §3.8): a group own row's members all carry the group here, while [targetKey] names the head and
   * [targetType] says only that the value arrived through a group. Absent from a desk that predates
   * session 5, where a reader takes the head as its own source.
   */
  sourceTargetType?: 'fixture' | 'group' | null
  sourceTargetKey?: string | null
}

export interface CueCookedResponse {
  cueId: number
  rows: CookedRow[]
}
