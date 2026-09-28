import type { BuskFlow } from './buskApi'
import type { GroupSummary } from './groupsApi'

/**
 * The busk **rig**: rows of tiles the operator builds, one per project (busk-further plan D1–D3).
 *
 * A tile is a group, a fixture, or one cell of a multi-head fixture, and the band presses whatever
 * the tile names — it is the target band as a document, in the page's own shape. Backend contract
 * in `lighting7/routes/projectBuskRig.kt` and `lighting7/docs/lighting-composition-model.md`
 * §"The rig".
 *
 * **These types are the document the client edits, not only the one the server sends** — the
 * `BuskPage` rule, for the same reason: a row or tile this client minted a gesture ago has no `id`
 * until the rig PUT answers with the ones it created, and `lib/buskRig.ts` wants one type over the
 * thing on screen. A `localKey` is this client's alone and is never sent.
 *
 * **Every defaulted list is optional here.** lighting7's converters set `encodeDefaults = false`,
 * so an empty `rows`, an empty `tiles` (never served, the write refuses it) or a single-head
 * fixture's empty `elements` is *omitted* from the frame rather than sent as `[]`. Declaring them
 * required is what let `TemplateSummary.rows` crash every route (CLAUDE.md §The hand); the readers
 * here take an absent list as an empty one.
 */

/** How a multi-head fixture's tile shows its cells (D3). Mirrors `BuskRigCellMode` in `models/buskRig.kt`. */
export type BuskRigCellMode = 'PIPS' | 'WHOLE' | 'PER_CELL' | 'HALVES'

export const BUSK_RIG_CELL_MODES: readonly BuskRigCellMode[] = ['PIPS', 'WHOLE', 'PER_CELL', 'HALVES']

export type BuskRigTileKind = 'GROUP' | 'FIXTURE'

/** One cell of a multi-head fixture, as the patch's own element list names it. Keys are opaque. */
export interface BuskRigElement {
  key: string
  name: string
}

/** What a fixture tile draws: the patch's id, key, name, and its cells in element order. */
export interface BuskRigPatch {
  /**
   * The patch's server id, which the write names it by. Absent only on a tile the show-all
   * fallback minted from the fixture list (`effectiveRig`), which is never written.
   */
  id?: number
  key: string
  name: string
  /** Omitted by the server for a single-head fixture — see the module note. */
  elements?: BuskRigElement[]
}

/**
 * One tile. Exactly one of [group] / [patch] is set, matching [kind]; [elementKey] only on a tile
 * that is one cell; [cellSplit] only with `HALVES`.
 *
 * The record's **own summary** is embedded (the busk pad's rule) so the band draws from one read
 * and a tile whose group is renamed reads its new name on the next frame.
 */
export interface BuskRigTile {
  id?: number
  uuid?: string
  localKey?: string
  kind: BuskRigTileKind
  group?: GroupSummary | null
  patch?: BuskRigPatch | null
  elementKey?: string | null
  cellMode: BuskRigCellMode
  cellSplit?: number | null
  label?: string | null
}

export interface BuskRigRow {
  id?: number
  uuid?: string
  localKey?: string
  name: string
  /**
   * How the row lays its tiles out — the bank's vocabulary. **Absent is `SCROLL`**: the REST
   * converter states it, but a desk that predates the field serves none at all, and this side
   * must not crash on a desk mid-upgrade; `rowFlow` in `lib/buskRig.ts` is the one reader.
   */
  flow?: BuskFlow
  /** The row's width share in twelfths, one of `BUSK_WIDTHS`. **Absent is 12** — see `rowWidth`. */
  width?: number
  /** Omitted by the server when empty — which it never is on a served rig, since the write refuses one. */
  tiles?: BuskRigTile[]
}

export interface BuskRig {
  /** `[]` (or absent, on the wire) is the empty rig: the show-all fallback is the client's (D1). */
  rows?: BuskRigRow[]
}

// ─── Write shapes ───────────────────────────────────────────────────────

/** Exactly one of [groupId] / [patchId]. Int ids, like every REST body. */
export interface BuskRigTileInput {
  tileId?: number
  groupId?: number
  patchId?: number
  elementKey?: string
  cellMode: BuskRigCellMode
  cellSplit?: number
  label?: string
}

export interface BuskRigRowInput {
  rowId?: number
  name: string
  /**
   * Sent only when they differ from the defaults (`SCROLL`, 12): the desk's Json is bare, so a
   * key it does not know fails the whole body, and a desk mid-upgrade — handler bodies hot-swapped,
   * the two columns not yet added — must go on accepting a rig nobody has re-laid-out.
   */
  flow?: BuskFlow
  width?: number
  tiles: BuskRigTileInput[]
}

/**
 * The **whole** rig, in one write. Rows and tiles are ordered by list position; a row or tile
 * carrying an id is moved and rewritten, one without is created, and one on the rig but absent here
 * is deleted. `rows: []` is a legal empty rig; an empty **row** is refused (`BUSK_RIG_INVALID`),
 * which is why `normaliseRig` drops one and is called inside every mutator.
 */
export interface BuskRigRequest {
  rows: BuskRigRowInput[]
}

/** The three codes the rig write refuses with, on `ErrorResponse.code`. */
export const BUSK_RIG_ERROR_CODES = ['BUSK_RIG_INVALID', 'BUSK_RIG_IDENTITY', 'BUSK_RIG_REF'] as const
export type BuskRigErrorCode = (typeof BUSK_RIG_ERROR_CODES)[number]
