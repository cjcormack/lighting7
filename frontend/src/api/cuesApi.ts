import type { CueType } from './cueStacksApi'

// Cue target (fixture or group)
export interface CueTarget {
  type: 'group' | 'fixture'
  key: string
}

/**
 * One line of a cue's ordered Look composition.
 *
 * The cue's layers are cooked down — in `sortOrder`, later winning — to exactly one contributor per
 * (fixture, property) before the resolver ever sees them, and the cue's own `propertyAssignments`
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
  kind: 'LOOK' | 'TEMPLATE'
  id: number
  uuid: string
  name: string
}

export interface CueLayer {
  /**
   * What this layer applies: **exactly one** of `lookId` / `templateId`.
   *
   * Two ids rather than a `(kind, id)` pair, because that is what a client has in hand — it picked a
   * row out of one library or the other — and because a request naming both is then a shape error
   * the route refuses rather than a discriminator it has to trust.
   */
  lookId?: number | null
  templateId?: number | null
  sortOrder?: number
  enabled?: boolean
  /**
   * The target set this layer operates over. One meaning, two jobs: it **supplies** targets to a
   * template's generic rows and **filters** a Look's bound ones. Empty means the source's own
   * targets, so a template layer with no targets contributes nothing.
   */
  targets: CueTarget[]
  /**
   * Comma-separated `PropertyMaskGroup` names; null/absent = every property. This is what replaces
   * value-level references: "this cue's colour comes from Warm, everything else local" is one
   * COLOUR-masked layer rather than a separate feature.
   */
  propertyMask?: string | null
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
 * `source` is **read-only** and must never be sent back: `lookId` / `templateId` are the write
 * fields. Same contract as `presetName` was and as `health` is on an assignment. `buildCueInput`
 * strips it, and a regression test pins that.
 */
export interface CueLayerDetail extends CueLayer {
  source: LayerSource | null
}

// Ad-hoc effect stored inline in a cue (with optional timing)
export interface CueAdHocEffect {
  targetType: 'group' | 'fixture'
  targetKey: string
  effectType: string
  category: string
  propertyName: string | null
  beatDivision: number
  blendMode: string
  distribution: string
  phaseOffset: number
  elementMode: string | null
  elementFilter: string | null
  stepTiming: boolean | null
  parameters: Record<string, string>
  delayMs?: number | null
  intervalMs?: number | null
  randomWindowMs?: number | null
  sortOrder?: number
  /** Speed master this effect subscribes to, as the master's uuid (null → master 1). */
  speedMasterUuid?: string | null
  /** Wall-clock rate master (null → unscaled); only WALL_CLOCK effects read it. */
  rateSpeedMasterUuid?: string | null
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
  | { type: 'missingProperty'; targetKey: string; propertyName: string }
// Four arms, down from seven. `missingPalette` / `missingPaletteEntry` described a row whose value
// was `ref:{uuid}` and whose Look was gone or no longer covered the target, and `paletteTypeMismatch`
// before them named a wrong-type reference. All three retired with the `ref:` value grammar in
// session 4: a row's value is always a literal, so a cue's only remaining dependency on a Look is a
// layer — guarded by an indexed FK rather than by a health diagnosis.

// Layer 3 property assignment on a cue.
export interface CuePropertyAssignment {
  targetType: 'fixture' | 'group'
  targetKey: string
  propertyName: string
  /** Canonical property-level form: "0".."255" for sliders/settings, extended-colour, "pan,tilt". */
  value: string
  fadeDurationMs?: number | null
  sortOrder?: number
  /**
   * Move-in-dark: only meaningful on `propertyName === 'position'`. When true and the
   * outgoing cue ends with intensity 0 on the same fixture, the resolver pre-applies the
   * incoming pan/tilt across the whole crossfade rather than blending — the head moves
   * while dark and is already aimed when the dimmer comes up. Defaults to false.
   */
  moveInDark?: boolean
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
  adHocEffects: CueAdHocEffect[]
  propertyAssignments: CuePropertyAssignment[]
  triggers: CueTriggerDetail[]
  // Every cue belongs to a stack — standalone cues no longer exist.
  cueStackId: number
  cueStackName: string
  sortOrder: number
  autoAdvance: boolean
  autoAdvanceDelayMs: number | null
  fadeDurationMs: number | null
  fadeCurve: string
  cueNumber: string | null
  /** True when `cueNumber` was derived from the cue's position rather than typed by the operator. */
  cueNumberAuto: boolean
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
}

// Input for create/update
export interface CueInput {
  name: string
  layers: CueLayer[]
  adHocEffects: CueAdHocEffect[]
  propertyAssignments?: CuePropertyAssignment[]
  triggers?: CueTrigger[]
  // Required — every cue belongs to a stack. (PUT/PATCH ignore it server-side; it's used on POST.)
  cueStackId: number
  sortOrder?: number
  autoAdvance?: boolean
  autoAdvanceDelayMs?: number | null
  fadeDurationMs?: number | null
  fadeCurve?: string
  cueNumber?: string | null
  notes?: string | null
  /** Cue-level stomp — see `Cue.stomp`. Not the per-layer flag on `CueLayer`. */
  stomp?: boolean
  /** Only honoured on POST; PUT/PATCH ignore it so markers cannot be turned into standard cues. */
  cueType?: CueType
}

// Partial input for PATCH (inline edits — only send changed fields).
// Excludes cueStackId/sortOrder: the stack is fixed at POST (the server ignores it on PATCH
// anyway) and the order within one moves through `reorderCueStackCues`.
export type CuePatchInput = Partial<
  Omit<CueInput, 'cueStackId' | 'sortOrder' | 'cueType'>
>

// Apply response
export interface ApplyCueResponse {
  effectCount: number
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
  propertyName: string
  value: string
  /**
   * The layer that won this key, when one did. Null for the cue's **own** rows, which belong to no
   * layer — the distinction the grid draws as "the cue set this" versus "Warm Wash set this".
   */
  layerId?: number | null
  layerSource?: LayerSource | null
}

export interface CueCookedResponse {
  cueId: number
  rows: CookedRow[]
}
