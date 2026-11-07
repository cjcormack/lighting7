import type { PaletteReference } from './paletteReference'
import type { SpreadUnit } from '@/lib/spreadShape'
import type { SceneryChange, SceneryState } from './sceneryApi'
import type { AttributeFamily } from '@/lib/attributeFamily'
import type { AssignmentHealth } from './cuesApi'
import type { FxDirection, Timing } from './fxApi'

/**
 * The `targetType` an older desk wrote for a Look row naming no target of its own. Neither a row nor
 * an effect can carry it now — the write boundary refuses both, a Look being whole (absolute-looks
 * plan D1, D2) — and a value pointed at a selection is a palette (`palettesApi.ts`). Kept so the
 * focused layer's grid can list such a row rather than draw it on a fixture.
 */
export const DEFERRED_TARGET_TYPE = 'deferred' as const

export type LookTargetType = 'fixture' | 'group' | typeof DEFERRED_TARGET_TYPE

/** An effect's target that is an ordered list of fixtures and groups (effects plan D3). */
export const SELECTION_TARGET_TYPE = 'selection' as const

/**
 * One stored Look row: "for this target, this property is this value".
 *
 * `value` is a **literal** in the canonical cue-side grammar (hex for colour, `"pan,tilt"` for
 * position, `"0".."255"` for slider/setting), or a `pal:{uuid}` palette reference the desk resolves
 * per head (`reference`, `resolvedByHead`). A `ref:` is rejected at the backend's write boundary,
 * which is what keeps Looks from nesting.
 *
 * **Always bound**: a Look row names a fixture or a group. `deferred` is refused at the write
 * boundary — see [DEFERRED_TARGET_TYPE].
 */
export interface LookRow {
  targetType: LookTargetType
  targetKey: string
  attribute: string
  value: string
  fadeDurationMs?: number | null
  /** The cell this row is on, by its own full key (`shehds-1.zone-2`). Null = the whole fixture. */
  elementKey?: string | null
  sortOrder?: number
  /** Resolved server-side on read; ignored on write — the server never trusts client health. */
  health?: AssignmentHealth
  /** The stored row's uuid, on read only. */
  uuid?: string
  /** The palette `value` references when it is `pal:{uuid}` (palette-references plan D6); on read only. */
  reference?: PaletteReference
  /** What the reference resolves to on each head it reaches, head key → literal; the live project's reads only. */
  resolvedByHead?: Record<string, string>
  /** The property a head's resolved literal lands under, where it is not the row's (a colour wheel). */
  resolvedAttributeByHead?: Record<string, string>
}

/**
 * One stored Look effect.
 *
 * The `delayMs` / `intervalMs` / `randomWindowMs` triple is deliberately absent: timing belongs to
 * the layer applying the Look, not to the Look itself.
 */
export interface LookFx {
  /**
   * A fixture, a group, or `selection` (effects plan D3): one effect over an ordered list of
   * fixtures and groups, its [targetKey] the list's canonical JSON. Only an effect may name one.
   */
  targetType: 'fixture' | 'group' | typeof SELECTION_TARGET_TYPE
  targetKey: string
  fxType: string
  family: string
  attribute?: string | null
  cycle: number
  blendMode: string
  order: string
  phaseOffset?: number
  elementMode?: string | null
  elementFilter?: string | null
  timing?: Timing | null
  parameters: Record<string, string>
  /** Speed master this effect subscribes to, as the master's uuid (null → master 1). */
  speedMasterUuid?: string | null
  /** Wall-clock rate master (null → unscaled); only WALL_CLOCK effects read it. */
  rateSpeedMasterUuid?: string | null
  /**
   * A beat effect's own tempo, 20–300 BPM, in place of a master (both masters then null); `cycle`
   * stays beats. Never on a wall-clock effect, whose *Natural* is a null rate master. Absent = none.
   */
  ownBpm?: number | null
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
  sortOrder?: number
}

/**
 * A Look as the library lists it.
 *
 * Two ids, and they are not interchangeable. `id` is the int PK and addresses REST paths and a cue
 * layer's `lookId`; `uuid` is the portable identity and is what a stored `ref:{uuid}` value names —
 * int PKs never appear in the backend's sync export and are re-minted on import, so a `ref:12`
 * would dangle after any import or clone.
 *
 * Everything a row needs to render is here, so the library never fetches a detail to draw a list.
 */
export interface LookSummary {
  id: number
  uuid: string
  name: string
  notes: string | null
  /**
   * Attribute families this Look touches, **derived server-side** from its rows rather than
   * stored. A Look spanning colour and position reports both — which is the point of there being
   * no type column.
   */
  families: AttributeFamily[]
  rowCount: number
  fxCount: number
  targetCount: number
  /**
   * Up to 8 distinct literals, most-frequent first, so a row can preview without a detail fetch. A
   * palette reference is not a literal: it is counted in [referenceCount].
   */
  preview: string[]
  /** How many rows are palette references (palette-references plan D22). Absent from an older desk. */
  referenceCount?: number
  /** The effects it runs, by type, each once in the Look's order. Absent from an older desk. */
  fxTypes?: string[]
  /**
   * How many cue layers reference this Look. Gates delete.
   *
   * There was a sibling `refRowCount` beside it — cue rows whose value was `ref:{uuid}`, a second
   * reference mechanism gating the same delete. It retired with the `ref:` grammar in session 4, so
   * a layer's FK is the only dependency a Look can have.
   */
  layerCount: number
  /**
   * How many busk pages hold a pad for this record — the row's "on *n* pages" hint, and the line
   * the delete confirm shows before those pads go with it.
   *
   * A hint, never a guard: it does not gate delete, `layerCount` alone does. Pages, not pads, so
   * two pads on one page count once — the question the operator is really asking is how many
   * places they would have to go and look.
   */
  buskPageCount: number
  /**
   * What this Look shows while live, one entry per element in its own order — the library's Scenery
   * read-out (scenery-programmer plan D10). Optional: a desk before that plan's session 3 sends none.
   */
  scenery?: LookScenerySummary[]
}

/** One element a Look's scenery moves, as the library row summarises it. */
export interface LookScenerySummary {
  elementUuid: string
  elementName: string
  state: SceneryState
}

/**
 * One Look's full contents.
 *
 * Deliberately **not** `extends LookSummary`: the summary's derived counts would then be duplicated
 * state that goes stale the moment a row is edited. Same argument the palette DTOs carried.
 */
export interface LookDetails {
  id: number
  uuid: string
  name: string
  notes: string | null
  families: AttributeFamily[]
  rows: LookRow[]
  fx: LookFx[]
  layerCount: number
  usedByCueIds: number[]
  usedByCueNames: string[]
  /** What this Look shows while it is live (stage-view plan session 8). Omitted when empty. */
  scenery?: SceneryChange[]
}

/**
 * Create / update payload.
 *
 * On PUT, **absent and empty are different**: omitting `rows` leaves the contents alone (a
 * metadata-only edit), while sending `[]` clears them. So build this object with the keys you mean
 * to write and no others.
 */
export interface LookInput {
  name?: string
  notes?: string | null
  rows?: LookRow[]
  fx?: LookFx[]
}

export interface CopyLookRequest {
  targetProjectId: number
  newName?: string
}

export interface CopyLookResponse {
  lookId: number
  lookName: string
  targetProjectId: number
  targetProjectName: string
  message: string
}

/** 409 body when a Look is still referenced. Rendered inline; the flow offers "delete anyway". */
export interface LookInUseError {
  error: string
  code: 'LOOK_IN_USE'
  layerCount: number
  cueIds: number[]
  cueNames: string[]
}

/**
 * A Look's press, `POST /looks/{id}/toggle`: on puts one layer of it on top, off takes every layer of
 * it off. It names no targets and no mask — a Look lands whole (absolute-looks plan D1, D3).
 */
export interface ToggleLookRequest {
  cycle?: number
}

/**
 * *Apply as values* for a Look (apply-model plan §3.5): its rows as Local `web` values, its effects
 * as Local effects, and nothing left on the stack. Not a toggle; the whole Look, as a press lands it.
 */
export interface ApplyLookRequest {
  /** Over every value; absent is each row's own fade, as a press lands them. */
  fadeMs?: number
}

export interface ApplyLookResponse {
  /** Programmer values written into Local. */
  written: number
  /** The Local effects started, one per effect. */
  fxIds: number[]
}

export interface ToggleLookResponse {
  action: 'applied' | 'removed'
  fxCount: number
}

/**
 * `POST looks/{id}/press` (lighting7 palette-references plan D23): a value palette tapped into a Look
 * focused in the programmer, its reference rows written into the Look's own rows or taken out again
 * by the cue press's rule.
 */
export interface LookPressRequest {
  paletteId: number
  targets: { type: 'fixture' | 'group'; key: string }[]
  families?: string[]
}

export interface LookPressResponse {
  action: 'applied' | 'removed'
  /** The other palettes whose references the press took out. */
  released?: number
  look: LookDetails
  /** A per-fixture palette's heads the press did not name, and the pressed heads it lands nothing on (D14). */
  omitted?: string[]
  notCovered?: string[]
}

/**
 * *Copy the values* (palette-references plan D9): `POST looks/{id}/harden` and `cues/{id}/harden`.
 * The rows by uuid, or absent for every reference the owner holds.
 */
export interface HardenRequest {
  rowUuids?: string[]
}

/** Rows made literal, and rows left as references because a head they reach had nothing to keep. */
export interface HardenResponse {
  hardened: number
  kept?: number
}
