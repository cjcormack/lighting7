import type { ListSelectIntent } from './listSelectionModel'

/**
 * A row's id. A string on every sheet — `group:{name}` / `fixture:{key}` on the fixtures list,
 * `patch:{id}` on the patch list, `cue:{id}` on the cue sheet, `ch:{n}` on the DMX sheet.
 */
export type RowId = string

/**
 * One cell of a sheet: a row and a value column.
 *
 * Generic over the column key so each surface keeps its own closed column vocabulary (the
 * fixtures list's `ColumnKey`, the patch list's, the cue sheet's) while the selection, marquee and
 * editor machinery is written once. Defaults to `string` where the column set is not the point.
 */
export interface CellRef<C extends string = string> {
  rowId: RowId
  col: C
}

/**
 * A set of selected cells, keyed by row id and column.
 *
 * Keyed by **rowId, not row index**: indices shift the moment a group expands or the filter
 * changes, and the selection has to survive both.
 *
 * The separator is NUL because a `RowId` is `group:{name}` / `fixture:{key}` / `member:{g}:{k}` and
 * a group name is operator-typed — it can contain a colon, a pipe, anything. NUL cannot appear in
 * either half, so the split is unambiguous whatever the rig is called.
 */
export type CellSelectionState = ReadonlySet<string>

const SEP = '\u0000'

export function cellKey(rowId: RowId, col: string): string {
  return `${rowId}${SEP}${col}`
}

export function parseCellKey<C extends string = string>(key: string): CellRef<C> {
  const at = key.indexOf(SEP)
  return { rowId: key.slice(0, at), col: key.slice(at + 1) as C }
}

/**
 * Fold a marquee's hits into the existing selection.
 *
 * Reuses `ListSelectIntent` rather than inventing a second vocabulary — the modifiers mean the same
 * thing here as they do for rows, so `listSelectionIntentFor` can drive both. `range` has no
 * distinct meaning for a rectangle (the rectangle *is* the range), so it behaves as `replace`;
 * `range-add` and `toggle` both accumulate, which is what ⌘-dragging a second block should do.
 */
export function applyCellSelection(
  current: CellSelectionState,
  hits: readonly CellRef<string>[],
  intent: ListSelectIntent,
): CellSelectionState {
  const keys = hits.map((h) => cellKey(h.rowId, h.col))
  if (intent === 'replace' || intent === 'range') {
    // Bail out rather than allocate when the answer is the selection we already have — the same
    // rule `useCellSelection.clear()` keeps, and for a sharper reason. A click on a cell *replaces*
    // the selection with that one cell, so re-clicking the cell you already have selected (to
    // confirm it before pressing Set, or by double-clicking) recomputes an identical set. A fresh
    // `Set` is a new identity for `cellSelection`, which every `RowView` takes as a prop — so the
    // whole visible list of a virtualised grid re-renders for no change at all.
    if (keys.length === current.size && keys.every((key) => current.has(key))) return current
    return new Set(keys)
  }
  const next = new Set(current)
  for (const key of keys) next.add(key)
  return next
}

/**
 * Group the selection by column.
 *
 * The write path is per-column — `planBatchWrites` takes one column and a target list — so a
 * multi-column marquee becomes one batch call per column, each keeping `resolveTargetCells`'
 * parent-first precedence and per-target clamping exactly as a single-column write has them.
 */
export function cellsByColumn<C extends string = string>(
  selected: CellSelectionState,
): { col: C; rowIds: RowId[] }[] {
  const byCol = new Map<C, RowId[]>()
  for (const key of selected) {
    const { rowId, col } = parseCellKey<C>(key)
    const list = byCol.get(col)
    if (list) list.push(rowId)
    else byCol.set(col, [rowId])
  }
  return [...byCol].map(([col, rowIds]) => ({ col, rowIds }))
}

/**
 * Drop cells whose row is no longer on screen.
 *
 * Same rule row selection already follows: a filter that hides a selected row must not leave it
 * silently contributing to the next write. The stored state is left alone — re-showing the row
 * brings its cells back, which is what an operator toggling `Lit` expects.
 */
export function visibleCells<C extends string = string>(
  selected: CellSelectionState,
  visibleRowIds: ReadonlySet<string>,
): CellRef<C>[] {
  const out: CellRef<C>[] = []
  for (const key of selected) {
    const ref = parseCellKey<C>(key)
    if (visibleRowIds.has(ref.rowId)) out.push(ref)
  }
  return out
}

/** A short description of the scope, for the drag chip and the selection bar. */
export function describeCellScope<C extends string>(
  cells: readonly CellRef<C>[],
  labelFor: (col: C) => string,
): string {
  if (cells.length === 0) return ''
  const cols = [...new Set(cells.map((c) => c.col))]
  const rowCount = new Set(cells.map((c) => c.rowId)).size
  return `${rowCount} × ${cols.map(labelFor).join(', ')}`
}

/**
 * How a sheet's cells run for the **plain** arrow keys (CLAUDE.md §Sheet kit):
 *
 * - **`grid`** — a spreadsheet. ← / → stop at the first and last column. Every sheet whose columns
 *   are different things: the patch list, the cue sheet, the libraries, the programmer.
 * - **`linear`** — the cells are one sequence read row by row, and the rows are only how it is
 *   wrapped onto the screen. A plain ← / → **wraps** across a row boundary and stops at the two
 *   ends. The DMX sheet, whose sequence is the address space: `008 → 009` moves to the next row.
 *
 * **Shift extends a rectangle in both**, and does not wrap: Shift+↓ ×4 then Shift+→ ×3 from one
 * cell is five rows by four columns, on the DMX sheet as on any other. It was a run of the reading
 * order on a `linear` sheet until Chris tried it on the desk (2026-09-24): a block of addresses
 * that starts mid-row selected the whole of every row in between, when the gesture on screen was
 * "these columns, down these rows". ↑ / ↓ are the same in both — one row, stopping at the first
 * and the last; on the DMX sheet that is ± the row width in whichever layout is showing.
 */
export type CellFlow = 'grid' | 'linear'

export type CellArrow = 'up' | 'down' | 'left' | 'right'

/** The arrow a `KeyboardEvent.key` names, or null. */
export function arrowOfKey(key: string): CellArrow | null {
  switch (key) {
    case 'ArrowUp':
      return 'up'
    case 'ArrowDown':
      return 'down'
    case 'ArrowLeft':
      return 'left'
    case 'ArrowRight':
      return 'right'
    default:
      return null
  }
}

/**
 * The cells an arrow walks: the selectable rows in display order and the selectable columns in
 * display order. Row-major over the two is the reading order — on the DMX sheet, address order.
 */
export interface CellGrid<C extends string = string> {
  rows: readonly RowId[]
  cols: readonly C[]
  /**
   * Whether this row has a cell in this column worth landing on — false for a column the row
   * resolves nothing for, which the tables draw **blank**: no `data-cell`, no selection ring, so a
   * cell selected there would be invisible and could not be scrolled to. The arrows step past such
   * a cell to the next one that takes, the way they step past a read-out column; a Shift rectangle
   * still covers blanks between its corners, as the marquee's does. Absent means every cell takes.
   */
  takes?: (rowId: RowId, col: C) => boolean
}

/**
 * Where a keyboard selection starts and where it has got to. The **anchor** is fixed while Shift
 * extends and is where a plain arrow steps from; the **head** is the end Shift moves, so Shift back
 * the other way shrinks the selection towards the anchor.
 */
export interface CellCursor<C extends string = string> {
  anchor: CellRef<C>
  head: CellRef<C>
}

/**
 * The cursor a selection made by the pointer implies: its first cell in reading order as the
 * anchor, its last as the head. A click is both at once; a marquee is anchored at its top-left
 * corner whichever way it was dragged — the pointer's own press point is not kept, and a drag
 * is a rectangle rather than a direction. [ordered] is `orderedSelectedCells`' answer.
 */
export function cursorOfSelection<C extends string>(ordered: readonly CellRef<C>[]): CellCursor<C> | null {
  if (ordered.length === 0) return null
  return { anchor: ordered[0], head: ordered[ordered.length - 1] }
}

/**
 * Where an arrow key moves a cell selection: the new cursor and the cells it selects, or null when
 * the grid has no cells.
 *
 * The rule every sheet's cell arrows share — the kit's sheets through `useSheet`, the programmer
 * through its own listener — the cell counterpart of `arrowStepTarget`, and on the same terms:
 *
 * - **A plain arrow moves from the anchor**, one cell, and collapses the selection to that cell.
 * - **Shift moves the head** and keeps the anchor, so the selection grows away from the anchor and
 *   shrinks back towards it. The shape is a **rectangle** from the anchor to the head whatever
 *   the [flow], and a Shift step does not wrap — [flow] governs only a plain ← / →.
 * - **With no cursor on the grid** — nothing selected, or its cells gone from view — ↓ and → land
 *   on the first cell and ↑ and ← on the last, whether or not Shift is held.
 * - **A cell the row does not take is stepped past** (`CellGrid.takes`), to the next one in the
 *   arrow's direction that does — ↓ in Colour skips the dimmer-only pars. None before the edge is
 *   the edge.
 * - **At an edge the arrow stops** — a plain arrow collapses onto the cell it was on, Shift leaves
 *   the selection as it was. It still answers, so the caller claims the key: letting it fall
 *   through at an edge would hand it to the page, which would scroll the sheet under the cell.
 */
export function cellArrowStep<C extends string>(
  grid: CellGrid<C>,
  flow: CellFlow,
  cursor: CellCursor<C> | null,
  direction: CellArrow,
  extend: boolean,
): { cursor: CellCursor<C>; cells: CellRef<C>[] } | null {
  const { rows, cols } = grid
  const width = cols.length
  const count = rows.length * width
  if (count === 0) return null
  const at = (index: number): CellRef<C> => ({ rowId: rows[Math.floor(index / width)], col: cols[index % width] })
  const takes = (index: number): boolean => {
    if (grid.takes == null) return true
    const cell = at(index)
    return grid.takes(cell.rowId, cell.col)
  }
  const indexOf = (cell: CellRef<C>): number => {
    const r = rows.indexOf(cell.rowId)
    const c = cols.indexOf(cell.col)
    return r === -1 || c === -1 ? -1 : r * width + c
  }

  const anchorIdx = cursor ? indexOf(cursor.anchor) : -1
  if (anchorIdx === -1) {
    const forward = direction === 'down' || direction === 'right'
    let index = forward ? 0 : count - 1
    while (index >= 0 && index < count && !takes(index)) index += forward ? 1 : -1
    if (index < 0 || index >= count) return null
    const only = at(index)
    return { cursor: { anchor: only, head: only }, cells: [only] }
  }
  const headIdx = extend && cursor ? indexOf(cursor.head) : -1
  const from = headIdx === -1 ? anchorIdx : headIdx

  // One move at a time in the arrow's direction, until a cell that takes or the edge. At the edge
  // — or with no taking cell before it — the arrow stays where it was.
  const move = (index: number): number | null => {
    if (direction === 'up') return index >= width ? index - width : null
    if (direction === 'down') return index + width < count ? index + width : null
    const delta = direction === 'right' ? 1 : -1
    if (flow === 'linear' && !extend) return index + delta >= 0 && index + delta < count ? index + delta : null
    const c = (index % width) + delta
    return c >= 0 && c < width ? index + delta : null
  }
  let to = from
  for (let probe = move(from); probe != null; probe = move(probe)) {
    if (takes(probe)) {
      to = probe
      break
    }
  }

  if (!extend) {
    const only = at(to)
    return { cursor: { anchor: only, head: only }, cells: [only] }
  }
  const anchor = at(anchorIdx)
  const head = at(to)
  const cells: CellRef<C>[] = []
  const [r0, r1] = [Math.floor(anchorIdx / width), Math.floor(to / width)].sort((a, b) => a - b)
  const [c0, c1] = [anchorIdx % width, to % width].sort((a, b) => a - b)
  for (let r = r0; r <= r1; r++) for (let c = c0; c <= c1; c++) cells.push(at(r * width + c))
  return { cursor: { anchor, head }, cells }
}
