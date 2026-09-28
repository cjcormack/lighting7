import type { GroupSummary } from '@/api/groupsApi'
import type { CueTarget } from '@/api/cuesApi'
import { BUSK_WIDTHS, type BuskFlow } from '@/api/buskApi'
import type { FixturePatch } from '@/api/patchApi'
import type {
  BuskRig,
  BuskRigCellMode,
  BuskRigElement,
  BuskRigPatch,
  BuskRigRequest,
  BuskRigRow,
  BuskRigRowInput,
  BuskRigTile,
  BuskRigTileInput,
} from '@/api/buskRigApi'
import type { Fixture } from '@/store/fixtures'
import { mintLocalKey } from './buskLayout'
import { withoutInfrastructure } from './infrastructure'

/**
 * The busk rig as a document, and every gesture that edits one — `buskLayout.ts`'s sibling, under
 * its three rules (busk-further plan §5 session 3):
 *
 * - **Addresses, not ids.** A row or tile the *previous* gesture minted has no id until the rig PUT
 *   answers, so every position is an index tuple. Ids are read in one place, {@link toRigRequest},
 *   at commit.
 * - **Normalise inside every mutator.** The server refuses an empty row (`BUSK_RIG_INVALID`), so
 *   {@link normaliseRig} is called by each mutator rather than left to the caller. It **keeps an
 *   empty rig** — that is the show-all fallback, not an error.
 * - **Null means nothing would change**, which is what lets a repeat hover write no state.
 *
 * And one rule of its own: **the show-all fallback is the client's** (D1). The server stores what
 * the operator built and answers an empty rig as empty; {@link effectiveRig} draws every group then
 * every fixture from the two lists the busk view already holds, in the order `GET /groups` and
 * `GET /fixtures` answer them. That order is also the desk's (`state/BuskRigOrder.kt`), which is
 * what `selection.subselect`'s *Next* / *Prev* walk — the two must agree, and `buskRig.test.ts` pins
 * this fallback against the server's own fixture. Element keys are **never parsed**: a cell's key
 * comes from `patch.elements[].key`, exactly as `rowModel.ts` publishes one.
 */

// ─── Addresses ──────────────────────────────────────────────────────────

export interface RigTileAddress {
  row: number
  tile: number
}

/** A target on its way onto the rig. Carries the record so the new tile can draw itself. */
export type RigPaletteRecord =
  | { kind: 'group'; group: GroupSummary }
  | { kind: 'fixture'; patch: BuskRigPatch }
  | { kind: 'cell'; patch: BuskRigPatch; element: BuskRigElement }

export type RigDragSource =
  | { kind: 'rig-palette'; record: RigPaletteRecord }
  | { kind: 'rig-tile'; at: RigTileAddress }
  | { kind: 'rig-row'; row: number }

/**
 * Where a lifted thing would land.
 *
 * `tile` means *insert before this index* — `at.tile === tiles.length` is the append the row-body
 * droppable produces. `row-gap` is where a lifted row lands: before row `row`, with `rows.length`
 * the trailing gap. `new-row` starts a row at the end holding the dropped target — the only way a
 * row is created, since the server refuses an empty one and so `+ Row` cannot mint a placeholder.
 */
export type RigDropTarget =
  | { kind: 'tile'; at: RigTileAddress }
  | { kind: 'row-gap'; row: number }
  | { kind: 'new-row' }

// ─── Drag ids ───────────────────────────────────────────────────────────

/**
 * Every rig id starts with `r`, and none of the page's does (`b…`, `palette:`), so `parseBuskDragId`
 * answers null for all of these and {@link parseRigDragId} null for all of the page's — the mutual
 * ignorance that lets both surfaces share the app's one `DndContext`.
 */
export const RIG_NEW_ROW_ID = 'rnewrow'

/**
 * `rtile:{r}.{t}`, plus an optional `#{suffix}` a **render** tile appends: a `PER_CELL` or `HALVES`
 * tile is one stored tile drawn as several, and dnd-kit registers draggables and droppables by id —
 * last mount wins, and the winner's unmount deregisters the id for the survivors — so each drawn
 * tile needs an id of its own. {@link parseRigDragId} drops the suffix, so every one of them still
 * names the stored tile's address.
 */
export function rigTileId(at: RigTileAddress, suffix?: string): string {
  const base = `rtile:${at.row}.${at.tile}`
  return suffix == null ? base : `${base}#${suffix}`
}

/** The row's own draggable, on its grip. */
export function rigRowId(row: number): string {
  return `rrow:${row}`
}

/** The row's body — a drop here appends. */
export function rigRowBodyId(row: number): string {
  return `rbody:${row}`
}

/** The gap **before** row `row`; `row === rows.length` is the trailing one. */
export function rigRowGapId(row: number): string {
  return `rgap:${row}`
}

/** `rpal:group:{name}` / `rpal:fixture:{key}` / `rpal:cell:{elementKey}`. */
export function rigPaletteId(record: RigPaletteRecord): string {
  if (record.kind === 'group') return `rpal:group:${record.group.name}`
  if (record.kind === 'fixture') return `rpal:fixture:${record.patch.key}`
  return `rpal:cell:${record.element.key}`
}

export type ParsedRigId =
  | { kind: 'rig-tile'; at: RigTileAddress }
  | { kind: 'rig-row'; row: number }
  | { kind: 'rig-row-body'; row: number }
  | { kind: 'rig-row-gap'; row: number }
  | { kind: 'rig-new-row' }
  | { kind: 'rig-palette'; recordKind: RigPaletteRecord['kind']; key: string }

/** Parse a rig drag id, or null for one belonging to something else. */
export function parseRigDragId(id: string): ParsedRigId | null {
  if (id === RIG_NEW_ROW_ID) return { kind: 'rig-new-row' }

  const tile = /^rtile:(\d+)\.(\d+)(?:#[\s\S]*)?$/.exec(id)
  if (tile) return { kind: 'rig-tile', at: { row: +tile[1], tile: +tile[2] } }

  const row = /^(rrow|rbody|rgap):(\d+)$/.exec(id)
  if (row) {
    const kind = row[1] === 'rrow' ? 'rig-row' : row[1] === 'rbody' ? 'rig-row-body' : 'rig-row-gap'
    return { kind, row: +row[2] }
  }

  // The key is everything after the second colon: a group name or an element key may hold one.
  const palette = /^rpal:(group|fixture|cell):(.+)$/s.exec(id)
  if (palette) return { kind: 'rig-palette', recordKind: palette[1] as RigPaletteRecord['kind'], key: palette[2] }

  return null
}

/**
 * The drop target a parsed droppable id names, or null when it names no landing place.
 *
 * The row **body** collapses to a tile target at the end of the row, which is how a drop past the
 * last tile appends — the page's bank-body rule.
 */
export function rigDropTargetFor(parsed: ParsedRigId, rig: BuskRig): RigDropTarget | null {
  switch (parsed.kind) {
    case 'rig-tile':
      return { kind: 'tile', at: parsed.at }
    case 'rig-row-body': {
      const row = rigRows(rig)[parsed.row]
      if (row == null) return null
      return { kind: 'tile', at: { row: parsed.row, tile: rowTiles(row).length } }
    }
    case 'rig-row-gap':
      return { kind: 'row-gap', row: parsed.row }
    case 'rig-new-row':
      return { kind: 'new-row' }
    default:
      return null
  }
}

// ─── Reading ────────────────────────────────────────────────────────────

/** The rows, with the wire's omitted-when-empty list read as empty. */
export function rigRows(rig: BuskRig): BuskRigRow[] {
  return rig.rows ?? []
}

export function rowTiles(row: BuskRigRow): BuskRigTile[] {
  return row.tiles ?? []
}

/** A row's default layout — what every row was before it had one: a whole line that scrolls sideways. */
export const DEFAULT_ROW_FLOW: BuskFlow = 'SCROLL'
export const DEFAULT_ROW_WIDTH = 12

/**
 * How a row lays its tiles out. **Absent is `SCROLL`** — a desk that predates the field serves
 * none, and a row this client minted has none until it chooses one — so every reader goes through
 * here rather than reading `row.flow`.
 */
export function rowFlow(row: BuskRigRow): BuskFlow {
  return row.flow ?? DEFAULT_ROW_FLOW
}

/** The row's width share in twelfths; absent, or a value outside `BUSK_WIDTHS`, is the whole line. */
export function rowWidth(row: BuskRigRow): number {
  return row.width != null && BUSK_WIDTHS.includes(row.width) ? row.width : DEFAULT_ROW_WIDTH
}

/**
 * The rows cut into the **lines** the band draws them on — a twelve-track grid filled in order, a
 * row starting a new line when it does not fit beside the last (`rowWidth`). Each line is the row
 * indices on it. This is the unit the Split handle snaps to and the default height counts: two
 * half-width rows side by side are one line, and showing "one row" of them would show half a line.
 */
export function rigLines(rows: readonly BuskRigRow[]): number[][] {
  const lines: number[][] = []
  let used = 0
  rows.forEach((row, index) => {
    const width = rowWidth(row)
    if (lines.length === 0 || used + width > 12) {
      lines.push([index])
      used = width
    } else {
      lines[lines.length - 1].push(index)
      used += width
    }
  })
  return lines
}

export function tileAt(rig: BuskRig, at: RigTileAddress): BuskRigTile | null {
  return rigRows(rig)[at.row]?.tiles?.[at.tile] ?? null
}

/** The cells a fixture tile's patch has, or none for a single head. */
export function tileCells(tile: BuskRigTile): BuskRigElement[] {
  return tile.patch?.elements ?? []
}

/**
 * `group:<name>` / `fixture:<key>` / `cell:<elementKey>` for every record with a tile here — the
 * palette's *on rig*. A `PER_CELL` or `HALVES` tile is the fixture's, not its cells'.
 */
export function recordsOnRig(rig: BuskRig): Set<string> {
  const keys = new Set<string>()
  for (const row of rigRows(rig)) {
    for (const tile of rowTiles(row)) {
      const key = recordKeyOf(tile)
      if (key != null) keys.add(key)
    }
  }
  return keys
}

export function recordKeyOf(tile: BuskRigTile): string | null {
  if (tile.kind === 'GROUP') return tile.group == null ? null : `group:${tile.group.name}`
  if (tile.patch == null) return null
  if (tile.elementKey != null) return `cell:${tile.elementKey}`
  return `fixture:${tile.patch.key}`
}

export function paletteRecordKey(record: RigPaletteRecord): string {
  if (record.kind === 'group') return `group:${record.group.name}`
  if (record.kind === 'fixture') return `fixture:${record.patch.key}`
  return `cell:${record.element.key}`
}

// ─── Minting ────────────────────────────────────────────────────────────

/**
 * A tile for a palette record. A cell dragged in is `WHOLE` — a single cell has no cells of its own
 * to show, and the server refuses a split on one.
 */
export function newTile(record: RigPaletteRecord): BuskRigTile {
  const base = { localKey: mintLocalKey() }
  if (record.kind === 'group') return { ...base, kind: 'GROUP', group: record.group, cellMode: 'PIPS' }
  if (record.kind === 'fixture') return { ...base, kind: 'FIXTURE', patch: record.patch, cellMode: 'PIPS' }
  return { ...base, kind: 'FIXTURE', patch: record.patch, elementKey: record.element.key, cellMode: 'WHOLE' }
}

/** `name` is required rather than defaulted: the server refuses a blank one (`BUSK_RIG_INVALID`). */
export function newRow(name: string, tiles: BuskRigTile[]): BuskRigRow {
  return { localKey: mintLocalKey(), name, tiles }
}

/** `Row N`, for the smallest N no row on the rig is already called. */
export function nextRowName(rig: BuskRig): string {
  const taken = new Set(rigRows(rig).map((row) => row.name.trim()))
  for (let n = 1; ; n += 1) {
    const candidate = `Row ${n}`
    if (!taken.has(candidate)) return candidate
  }
}

// ─── Normalising ────────────────────────────────────────────────────────

/**
 * Drop empty rows; **keep an empty rig**.
 *
 * The server refuses a row with no tiles, so a row whose last tile was crossed off must go with
 * the gesture — unlike the page's empty bank, which the server accepts and the operator may have
 * just made. An empty rig is a legal document *and* the show-all fallback, so `rows: []` stands.
 */
export function normaliseRig(rig: BuskRig): BuskRig {
  return { rows: rigRows(rig).filter((row) => rowTiles(row).length > 0) }
}

// ─── The wire body ──────────────────────────────────────────────────────

/**
 * The ids the write names records by, resolved from the **patch list**.
 *
 * The rig GET embeds `GroupSummaryDto`, which carries no id, while `PUT /busk/rig` names every group
 * tile by `groupId` — on a kept tile as much as a new one. The one place the desk publishes a
 * group's id is `FixturePatchDto.groups[].id`, so this is built from `usePatchListQuery` and read
 * at commit. The consequence is stated rather than hidden: **a group with no patched member has no
 * id here and cannot be placed on the rig** until the DTO carries one (busk-further plan, session 3
 * amendment). A patch's id is on the tile already; the map is the fallback for a tile the show-all
 * fallback minted, which is never written anyway.
 */
export interface RigIds {
  groupIdByName: ReadonlyMap<string, number>
  patchIdByKey: ReadonlyMap<string, number>
}

export function rigIdsFromPatches(patches: readonly FixturePatch[] | undefined): RigIds {
  const groupIdByName = new Map<string, number>()
  const patchIdByKey = new Map<string, number>()
  for (const patch of patches ?? []) {
    patchIdByKey.set(patch.key, patch.id)
    for (const group of patch.groups) groupIdByName.set(group.name, group.id)
  }
  return { groupIdByName, patchIdByKey }
}

/** A document the write could not name — refused **here**, before a PUT the server would 400. */
export class RigRequestError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'RigRequestError'
  }
}

/**
 * A row's layout on the wire — **only where it differs from the defaults**. The desk's Json is
 * bare, so a key it does not know fails the whole body, and a desk mid-upgrade (handler bodies
 * hot-swapped, the two columns not yet added) must keep accepting a rig nobody has re-laid-out.
 */
function rowLayoutInput(row: BuskRigRow): Pick<BuskRigRowInput, 'flow' | 'width'> {
  const out: Pick<BuskRigRowInput, 'flow' | 'width'> = {}
  const flow = rowFlow(row)
  const width = rowWidth(row)
  if (flow !== DEFAULT_ROW_FLOW) out.flow = flow
  if (width !== DEFAULT_ROW_WIDTH) out.width = width
  return out
}

function tileInput(tile: BuskRigTile, at: RigTileAddress, ids: RigIds): BuskRigTileInput {
  const input: BuskRigTileInput = { cellMode: tile.kind === 'GROUP' ? 'PIPS' : tile.cellMode }
  if (tile.id != null) input.tileId = tile.id
  const where = `row ${at.row + 1}, tile ${at.tile + 1}`
  // The label is either kind's, so it is set before the kinds part — the GROUP arm returns early,
  // and a label set only on the patch path silently reverted every group rename on the drained
  // response (found by the session 8 review).
  const label = tile.label?.trim()
  if (label) input.label = label
  if (tile.kind === 'GROUP') {
    const name = tile.group?.name
    const id = name == null ? undefined : ids.groupIdByName.get(name)
    if (id == null) {
      // The write is whole-document, so a group the desk cannot name blocks *every* gesture while
      // its tile is on the rig — say which tile, and the way out, rather than describing a placing
      // that is not what the operator did.
      throw new RigRequestError(
        name == null
          ? `The tile at ${where} names no group`
          : tile.id != null
            ? `“${name}” (${where}) has no patched member, so the desk has no id for it — remove that tile from the rig, or add a member to the group`
            : `“${name}” has no patched member, so the desk has no id for it — add a member before placing it on the rig`,
      )
    }
    input.groupId = id
    return input
  }
  const patch = tile.patch
  const patchId = patch?.id ?? (patch == null ? undefined : ids.patchIdByKey.get(patch.key))
  if (patchId == null) throw new RigRequestError(`The tile at ${where} names no patch`)
  input.patchId = patchId
  if (tile.elementKey != null) input.elementKey = tile.elementKey
  // `cellSplit` only with `HALVES`: the server ignores it elsewhere, and a stale one riding along
  // would be a second copy of a fact the mode already states.
  if (tile.cellMode === 'HALVES' && tile.cellSplit != null) input.cellSplit = tile.cellSplit
  return input
}

/**
 * The whole rig as the PUT takes it: ids where a node has one, omitted where it does not, and never
 * a `uuid` (sync's) or a `localKey` (this client's).
 *
 * @throws RigRequestError for a tile the write could not name — see {@link RigIds}.
 */
export function toRigRequest(rig: BuskRig, ids: RigIds): BuskRigRequest {
  return {
    rows: rigRows(rig).map((row, rowIndex) => {
      const input: BuskRigRowInput = {
        name: row.name,
        ...rowLayoutInput(row),
        tiles: rowTiles(row).map((tile, tileIndex) => tileInput(tile, { row: rowIndex, tile: tileIndex }, ids)),
      }
      if (row.id != null) input.rowId = row.id
      return input
    }),
  }
}

// ─── Mutators ───────────────────────────────────────────────────────────

/**
 * Rows **and tiles** copied, not only the arrays: `setTile` assigns into a tile, and the rig it is
 * handed comes out of the RTK Query cache, which Immer freezes — `buskLayout.ts`'s `clone` spreads
 * each bank for the same reason. A clone one level short threw `Cannot assign to read only
 * property` out of every cell-mode menu press on a saved rig.
 */
function clone(rig: BuskRig): BuskRig {
  return { rows: rigRows(rig).map((row) => ({ ...row, tiles: rowTiles(row).map((tile) => ({ ...tile })) })) }
}

/**
 * Did the edit change anything the server would store? The universal no-op guard.
 *
 * Compared through a shape rather than {@link toRigRequest}, which needs the id map and would
 * throw for a group the desk cannot name — a no-op check must never refuse.
 */
function shapeOf(rig: BuskRig): string {
  return JSON.stringify(
    rigRows(rig).map((row) => ({
      id: row.id,
      name: row.name,
      flow: rowFlow(row),
      width: rowWidth(row),
      tiles: rowTiles(row).map((tile) => ({
        id: tile.id,
        record: recordKeyOf(tile),
        cellMode: tile.cellMode,
        cellSplit: tile.cellMode === 'HALVES' ? tile.cellSplit : undefined,
        label: tile.label,
      })),
    })),
  )
}

function changed(before: BuskRig, after: BuskRig): boolean {
  return shapeOf(before) !== shapeOf(after)
}

export function removeTile(rig: BuskRig, at: RigTileAddress): BuskRig {
  const next = clone(rig)
  const row = rigRows(next)[at.row]
  if (row == null || row.tiles == null || at.tile >= row.tiles.length) return rig
  row.tiles.splice(at.tile, 1)
  return normaliseRig(next)
}

export function removeRow(rig: BuskRig, row: number): BuskRig {
  const next = clone(rig)
  const rows = rigRows(next)
  if (row >= rows.length) return rig
  rows.splice(row, 1)
  return normaliseRig(next)
}

export function renameRow(rig: BuskRig, row: number, name: string): BuskRig {
  const next = clone(rig)
  const target = rigRows(next)[row]
  if (target == null) return rig
  target.name = name
  return normaliseRig(next)
}

/**
 * Set a row's flow or width — the bank's two layout facts, on the row (the band's `…` menu). A
 * width outside `BUSK_WIDTHS` is refused here rather than sent, since the write would 400 it.
 */
export function setRowLayout(rig: BuskRig, row: number, patch: { flow?: BuskFlow; width?: number }): BuskRig {
  if (patch.width != null && !BUSK_WIDTHS.includes(patch.width)) return rig
  const next = clone(rig)
  const target = rigRows(next)[row]
  if (target == null) return rig
  if (patch.flow != null) target.flow = patch.flow
  if (patch.width != null) target.width = patch.width
  return normaliseRig(next)
}

/**
 * Set a fixture tile's cell mode, split or label.
 *
 * A group tile has no cell mode (D3) and is left alone. Leaving `HALVES` clears the split, so the
 * next `HALVES` starts from the default rather than from a number chosen for a different fixture.
 */
export function setTile(
  rig: BuskRig,
  at: RigTileAddress,
  patch: Partial<Pick<BuskRigTile, 'cellMode' | 'cellSplit' | 'label'>>,
): BuskRig {
  const next = clone(rig)
  const tile = tileAt(next, at)
  if (tile == null || tile.kind !== 'FIXTURE') return rig
  Object.assign(tile, patch)
  if (tile.cellMode !== 'HALVES') tile.cellSplit = null
  return normaliseRig(next)
}

/**
 * Set a tile's **label** — the name it wears on the band in place of its record's own (§11's
 * *Rename tile…*). Either kind takes one, which is why this is not [setTile]: that mutator leaves a
 * group tile alone because a group has no cell mode, and a label is the one field both kinds
 * share. A blank or null clears it, so the tile reads its record's name again; `toRigRequest`
 * omits an empty label and the desk stores none.
 */
export function relabelTile(rig: BuskRig, at: RigTileAddress, label: string | null): BuskRig {
  const next = clone(rig)
  const tile = tileAt(next, at)
  if (tile == null) return rig
  const trimmed = label?.trim() ?? ''
  tile.label = trimmed === '' ? null : trimmed
  return normaliseRig(next)
}

// ─── The drop ───────────────────────────────────────────────────────────

/**
 * Apply a drop, or answer null when nothing would change.
 *
 * A palette record and a tile land on a tile target or start a new row; a row lands on a row gap.
 * Any other pairing is not a gesture and answers null. A **tile target is an insertion point, not a
 * destination index** — the page's rule, with the page's one-line correction: a tile moving later
 * within its own row lands one earlier than the slot's index, because lifting it shifted the gap.
 */
export function applyDrop(rig: BuskRig, source: RigDragSource, target: RigDropTarget): BuskRig | null {
  const next = source.kind === 'rig-row' ? dropRow(rig, source.row, target) : dropTile(rig, source, target)
  if (next == null) return null
  const pruned = normaliseRig(next)
  return changed(rig, pruned) ? pruned : null
}

function dropTile(
  rig: BuskRig,
  source: Extract<RigDragSource, { kind: 'rig-palette' | 'rig-tile' }>,
  target: RigDropTarget,
): BuskRig | null {
  if (target.kind === 'row-gap') return null
  const next = clone(rig)
  const rows = rigRows(next)

  if (source.kind === 'rig-palette') {
    const tile = newTile(source.record)
    if (target.kind === 'new-row') {
      rows.push(newRow(nextRowName(next), [tile]))
      return next
    }
    const row = rows[target.at.row]
    if (row == null) return null
    const tiles = rowTiles(row)
    tiles.splice(clampIndex(target.at.tile, tiles.length), 0, tile)
    row.tiles = tiles
    return next
  }

  const originRow = rows[source.at.row]
  const origin = originRow == null ? null : rowTiles(originRow)
  const moving = origin?.[source.at.tile]
  if (originRow == null || origin == null || moving == null) return null

  if (target.kind === 'new-row') {
    origin.splice(source.at.tile, 1)
    originRow.tiles = origin
    rows.push(newRow(nextRowName(next), [moving]))
    return next
  }

  const destinationRow = rows[target.at.row]
  if (destinationRow == null) return null
  const destination = rowTiles(destinationRow)

  if (originRow === destinationRow) {
    if (source.at.tile === target.at.tile) return null
    origin.splice(source.at.tile, 1)
    const insertAt = source.at.tile < target.at.tile ? target.at.tile - 1 : target.at.tile
    origin.splice(clampIndex(insertAt, origin.length), 0, moving)
    originRow.tiles = origin
    return next
  }

  origin.splice(source.at.tile, 1)
  originRow.tiles = origin
  destination.splice(clampIndex(target.at.tile, destination.length), 0, moving)
  destinationRow.tiles = destination
  return next
}

function dropRow(rig: BuskRig, from: number, target: RigDropTarget): BuskRig | null {
  if (target.kind !== 'row-gap') return null
  const next = clone(rig)
  const rows = rigRows(next)
  const moving = rows[from]
  if (moving == null) return null
  // The gap on either side of the row itself puts it back where it started.
  if (target.row === from || target.row === from + 1) return null
  rows.splice(from, 1)
  const insertAt = from < target.row ? target.row - 1 : target.row
  rows.splice(clampIndex(insertAt, rows.length), 0, moving)
  return next
}

function clampIndex(index: number, length: number): number {
  return Math.max(0, Math.min(index, length))
}

// ─── The show-all fallback ──────────────────────────────────────────────

export interface EffectiveRig {
  rows: BuskRigRow[]
  /** True when the rig is empty and these rows are every group then every fixture (D1). */
  fallback: boolean
}

const FALLBACK_GROUPS_ROW = 'Groups'
const FALLBACK_FIXTURES_ROW = 'Fixtures'

/**
 * The rig the band draws: the built one, or — for an empty rig — every group then every fixture,
 * **in the order the two lists answer them**, which is the desk's own rig order for an empty rig
 * (`state/BuskRigOrder.kt`; pinned against its fixture in `buskRig.test.ts`). An infrastructure
 * fixture is left out of the fixture row, as the desk leaves it out of its fallback order — so the
 * band and *Next* / *All* agree, and neither reaches a power dimmer.
 *
 * Two rows rather than one, so the band's shape does not change the day a rig is built: a
 * `Groups` row and a `Fixtures` row, each present only when non-empty. Their tiles carry no ids
 * and are never written — editing an empty rig starts from `rows: []`, with these drawn dimmed
 * behind the new-row zone.
 */
export function effectiveRig(
  rig: BuskRig | undefined,
  groups: readonly GroupSummary[] | undefined,
  fixtures: readonly Fixture[] | undefined,
): EffectiveRig {
  const built = rig == null ? [] : rigRows(rig)
  if (built.length > 0) return { rows: built, fallback: false }

  const rows: BuskRigRow[] = []
  const groupTiles: BuskRigTile[] = (groups ?? []).map((group) => ({
    localKey: `fallback:group:${group.name}`,
    kind: 'GROUP',
    group,
    cellMode: 'PIPS',
  }))
  if (groupTiles.length > 0) rows.push({ localKey: 'fallback:groups', name: FALLBACK_GROUPS_ROW, tiles: groupTiles })

  const fixtureTiles: BuskRigTile[] = withoutInfrastructure(fixtures ?? []).map((fixture) => ({
    localKey: `fallback:fixture:${fixture.key}`,
    kind: 'FIXTURE',
    patch: {
      key: fixture.key,
      name: fixture.name,
      elements: (fixture.elements ?? []).map((element) => ({ key: element.key, name: element.displayName })),
    },
    cellMode: 'PIPS',
  }))
  if (fixtureTiles.length > 0) {
    rows.push({ localKey: 'fallback:fixtures', name: FALLBACK_FIXTURES_ROW, tiles: fixtureTiles })
  }
  return { rows, fallback: true }
}

// ─── Expansion: what a tile draws and presses ───────────────────────────

/**
 * What one stored tile becomes on screen. A `PER_CELL` tile is one tile per cell; a `HALVES` tile
 * is `cellSplit` tiles, each a contiguous run; a cell tile is one cell; a `PIPS` or `WHOLE` tile is
 * the fixture. Each carries the target(s) a press toggles, in the shape the desk takes — a cell is
 * `{type: 'fixture', key: element.key}`, never parsed.
 */
export type RenderTile =
  | { kind: 'group'; key: string; name: string; group: GroupSummary; target: CueTarget }
  | {
      kind: 'fixture'
      key: string
      name: string
      patch: BuskRigPatch
      /** Draw the cells as read-only pips (`PIPS`), or not (`WHOLE`). */
      pips: boolean
      cells: BuskRigElement[]
      target: CueTarget
    }
  | { kind: 'cell'; key: string; name: string; patch: BuskRigPatch; element: BuskRigElement; target: CueTarget }
  | {
      kind: 'run'
      key: string
      name: string
      patch: BuskRigPatch
      cells: BuskRigElement[]
      /** One per cell in the run — a run press toggles each. */
      targets: CueTarget[]
    }

/**
 * Cut `cells` into `split` contiguous runs, the first `cells.length % split` one longer — the
 * desk's rule (`BuskRigOrder.kt`), so twelve cells in five runs are 3 · 3 · 2 · 2 · 2.
 */
export function runsOf<T>(cells: readonly T[], split: number): T[][] {
  const count = Math.max(1, Math.min(Math.floor(split), cells.length || 1))
  const base = Math.floor(cells.length / count)
  const extra = cells.length % count
  const runs: T[][] = []
  let at = 0
  for (let i = 0; i < count; i += 1) {
    const size = base + (i < extra ? 1 : 0)
    runs.push(cells.slice(at, at + size))
    at += size
  }
  return runs.filter((run) => run.length > 0)
}

/**
 * What a cell tile is called. The desk names an element `<Fixture> Element N`, so prefixing the
 * fixture again would read it twice; a fixture whose elements are named `Cell N` still gets its
 * parent in front, since two bars' cells would otherwise be indistinguishable. A label, never a
 * key: nothing here reads the element's key.
 */
function cellName(patchName: string, element: BuskRigElement): string {
  return element.name.includes(patchName) ? element.name : `${patchName} · ${element.name}`
}

/**
 * The name a stored tile wears with **no label** — what *Rename tile…* is seeded with, and what
 * clears the label when typed back. It follows how [expandTile] applies a label: on a group, a
 * whole fixture and a **single-cell tile** the label *replaces* the name shown, so the own name is
 * the name shown (`Bar L · Cell 3` for a cell tile); on a tile drawn per cell or in halves the
 * label is the base the cell names *compose on* (`Left 1–2`, `Left · Cell 3`), so the own name is
 * the fixture's. Kept beside [cellName] so the two cannot drift.
 */
export function tileOwnName(tile: BuskRigTile): string {
  if (tile.kind === 'GROUP') return tile.group?.name ?? ''
  const patch = tile.patch
  if (patch == null) return ''
  if (tile.elementKey != null) {
    const element = patch.elements?.find((cell) => cell.key === tile.elementKey) ?? { key: tile.elementKey, name: tile.elementKey }
    return cellName(patch.name, element)
  }
  return patch.name
}

export function expandTile(tile: BuskRigTile, tileKey: string): RenderTile[] {
  if (tile.kind === 'GROUP') {
    if (tile.group == null) return []
    return [
      {
        kind: 'group',
        key: tileKey,
        name: tile.label?.trim() || tile.group.name,
        group: tile.group,
        target: { type: 'group', key: tile.group.name },
      },
    ]
  }
  const patch = tile.patch
  if (patch == null) return []
  const cells = patch.elements ?? []
  const label = tile.label?.trim()

  if (tile.elementKey != null) {
    const element = cells.find((cell) => cell.key === tile.elementKey) ?? { key: tile.elementKey, name: tile.elementKey }
    return [
      {
        kind: 'cell',
        key: tileKey,
        name: label || cellName(patch.name, element),
        patch,
        element,
        target: { type: 'fixture', key: element.key },
      },
    ]
  }

  const mode: BuskRigCellMode = cells.length === 0 ? 'WHOLE' : tile.cellMode
  if (mode === 'PER_CELL') {
    return cells.map((element) => ({
      kind: 'cell',
      key: `${tileKey}:${element.key}`,
      name: cellName(label || patch.name, element),
      patch,
      element,
      target: { type: 'fixture', key: element.key },
    }))
  }
  if (mode === 'HALVES') {
    let position = 1
    return runsOf(cells, tile.cellSplit ?? 2).map((run, index) => {
      const start = position
      position += run.length
      const end = position - 1
      return {
        kind: 'run',
        key: `${tileKey}:run${index}`,
        name: `${label || patch.name} ${start}–${end}`,
        patch,
        cells: run,
        targets: run.map((element) => ({ type: 'fixture', key: element.key })),
      }
    })
  }
  return [
    {
      kind: 'fixture',
      key: tileKey,
      name: label || patch.name,
      patch,
      pips: mode === 'PIPS' && cells.length > 0,
      cells,
      target: { type: 'fixture', key: patch.key },
    },
  ]
}

/** The React/drag key of a stored tile: the server's uuid, or this client's local key. */
export function tileKeyOf(tile: BuskRigTile, at: RigTileAddress): string {
  return tile.uuid ?? tile.localKey ?? `tile-${at.row}-${at.tile}`
}

/**
 * The rig order over heads, as the desk answers it (`BuskRigOrder.kt`, `stepsHeads`): one step per
 * render tile — a group as a group, a run as its cells — with a repeated step dropped. Exists to be
 * pinned against the server's fixture; the band reads {@link expandTile} directly.
 */
export function rigSteps(rows: readonly BuskRigRow[]): CueTarget[][] {
  const steps: CueTarget[][] = []
  const seen = new Set<string>()
  rows.forEach((row, rowIndex) => {
    rowTiles(row).forEach((tile, tileIndex) => {
      for (const rendered of expandTile(tile, tileKeyOf(tile, { row: rowIndex, tile: tileIndex }))) {
        const step = rendered.kind === 'run' ? rendered.targets : [rendered.target]
        const signature = JSON.stringify(step)
        if (seen.has(signature)) continue
        seen.add(signature)
        steps.push(step)
      }
    })
  })
  return steps
}

