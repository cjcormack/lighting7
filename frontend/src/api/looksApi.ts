import type { AttributeFamily } from '@/lib/attributeFamily'
import type { AssignmentHealth } from './cuesApi'

/**
 * The `targetType` discriminator marking an **effect** as deferred: it names no target of its own and
 * takes its targets from the layer referencing the Look.
 *
 * A *row* can no longer carry it — the write boundary refuses one, because a value you point at a
 * selection is a template now (`templatesApi.ts`). Effects keep it, because fanning an effect over
 * the layer's targets is a different thing from holding a value for nobody.
 *
 * Deliberately a magic string rather than an arm of the fixture/group union, mirroring the
 * backend's `DEFERRED_TARGET_TYPE` — a deferred row reaching code that expects a resolvable target
 * is a bug we want loud rather than silently fanned out over the whole rig.
 */
export const DEFERRED_TARGET_TYPE = 'deferred' as const

export type LookTargetType = 'fixture' | 'group' | typeof DEFERRED_TARGET_TYPE

/**
 * One stored Look row: "for this target, this property is this value".
 *
 * `value` is always a **literal** in the canonical cue-side grammar (hex for colour, `"pan,tilt"`
 * for position, `"0".."255"` for slider/setting). A `ref:` is rejected at the backend's write
 * boundary, which is what keeps Looks from nesting.
 *
 * **Always bound**: a Look row names a fixture or a group. `deferred` is refused at the write
 * boundary — see [DEFERRED_TARGET_TYPE].
 */
export interface LookRow {
  targetType: LookTargetType
  targetKey: string
  propertyName: string
  value: string
  fadeDurationMs?: number | null
  /** Element-local suffix of a multi-element fixture's element key. Null = the whole fixture. */
  elementKey?: string | null
  sortOrder?: number
  /** Resolved server-side on read; ignored on write — the server never trusts client health. */
  health?: AssignmentHealth
}

/**
 * One stored Look effect.
 *
 * The `delayMs` / `intervalMs` / `randomWindowMs` triple is deliberately absent: timing belongs to
 * the layer applying the Look, not to the Look itself.
 */
export interface LookEffect {
  targetType: LookTargetType
  targetKey: string
  effectType: string
  category: string
  propertyName?: string | null
  beatDivision: number
  blendMode: string
  distribution: string
  phaseOffset?: number
  elementMode?: string | null
  elementFilter?: string | null
  stepTiming?: boolean | null
  parameters: Record<string, string>
  /** Speed master this effect subscribes to, as the master's uuid (null → master 1). */
  speedMasterUuid?: string | null
  /** Wall-clock rate master (null → unscaled); only WALL_CLOCK effects read it. */
  rateSpeedMasterUuid?: string | null
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
  effectCount: number
  targetCount: number
  /**
   * True when any **effect** takes its targets from the layer applying this Look rather than naming
   * one.
   *
   * Rows can no longer be deferred at all — session 3 moved that half of the entity out to
   * templates — so this is now only about effects, where a deferred target still means "fan over
   * whatever the layer points at". It is what makes a Look eligible for a busking pad: a pad
   * supplies the targets on the press.
   *
   * There was an `editorFixtureType` beside it, naming the type the form editor built a synthetic
   * fixture from. It went with the deferred rows, and with it the compatibility gate that refused
   * "Amber Key" to every head it was not authored against (D6).
   */
  hasDeferredEffects: boolean
  /** Up to 8 distinct literals, most-frequent first, so a row can preview without a detail fetch. */
  preview: string[]
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
  effects: LookEffect[]
  layerCount: number
  usedByCueIds: number[]
  usedByCueNames: string[]
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
  effects?: LookEffect[]
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

export interface ToggleLookTarget {
  type: 'group' | 'fixture'
  key: string
}

export interface ToggleLookRequest {
  targets: ToggleLookTarget[]
  /**
   * The selection's attribute mask (multi-screen plan D4, D5): the layer lands with
   * `propertyMask = mask ∩ the Look's families`, the cook skips the rows outside it, and the
   * response names them; nothing inside is 400 `LOOK_OUTSIDE_MASK`. Absent is every attribute.
   * Tested on the **on** arm only — an off press comes off under any mask.
   */
  families?: AttributeFamily[]
  beatDivision?: number
}

export interface ToggleLookResponse {
  action: 'applied' | 'removed'
  effectCount: number
  /**
   * The Look's families the mask left out, in declaration order — the pressing window's toast
   * (D6). Empty for an unmasked press and on the off arm. Says *rows* when toasted: the layer's
   * mask filters its rows, not its effects.
   */
  skippedFamilies: string[]
}
