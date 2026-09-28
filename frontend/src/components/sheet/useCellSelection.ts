import { useCallback, useMemo, useRef, useState } from 'react'
import type { ListSelectIntent } from './listSelectionModel'
import {
  applyCellSelection,
  cellArrowStep,
  cellKey,
  cellsByColumn,
  cursorOfSelection,
  visibleCells,
  type CellArrow,
  type CellCursor,
  type CellFlow,
  type CellGrid,
  type CellRef,
  type CellSelectionState,
  type RowId,
} from './cellSelectionModel'
import { orderedSelectedCells } from './cellEntry'

export interface CellSelection<C extends string = string> {
  /** Cells still on screen. Filtered rows drop out; the stored state keeps them. */
  cells: CellRef<C>[]
  count: number
  isSelected: (rowId: RowId, col: C) => boolean
  /** Grouped for the write path — one entry per column. */
  byColumn: () => { col: C; rowIds: RowId[] }[]
  select: (hits: readonly CellRef<C>[], intent: ListSelectIntent) => void
  clear: () => void
  /**
   * The keyboard's anchor and head, set by [place] and dropped by every other change — a click, a
   * marquee, a clear. Null means "derive it from the selection" (`cursorOfSelection`), which is
   * what the arrows do after a pointer gesture.
   */
  cursor: CellCursor<C> | null
  /** Replace the selection with [cells] and remember [cursor] — the arrow keys' door. */
  place: (cells: readonly CellRef<C>[], cursor: CellCursor<C>) => void
}

interface State<C extends string> {
  keys: CellSelectionState
  cursor: CellCursor<C> | null
}

/**
 * Cell selection — the other shape of the one selection (CLAUDE.md §One selection, two shapes).
 *
 * It was a transient edit scope orthogonal to the row selection; it is exclusive with it now, and
 * a marquee's rows *are* the fixture selection, narrowed to some of their attributes. Nothing
 * outside the grid reads the cells themselves: what leaves the container is the rows they sit on,
 * folded into `selectedRowIds` and published as targets — which is why this can stay **local
 * state, not Redux**. Row selection lives in `selectionSlice` because `RecordSheet` opens outside
 * the grid and needs the targets; the container publishes the marquee's rows through that same
 * door. Redux here would also inherit `useListSelection`'s unmount-clear hazard for no benefit,
 * and the scope-swappable grid wants per-instance state anyway.
 *
 * **The cursor rides in the same state as the keys**, so the two can never describe different
 * selections: a pointer gesture replaces both in one update, leaving no cursor behind that names a
 * cell the click just moved away from.
 */
export function useCellSelection<C extends string = string>(
  visibleRowIds: ReadonlySet<string>,
): CellSelection<C> {
  const [state, setState] = useState<State<C>>(() => ({ keys: new Set<string>(), cursor: null }))

  // The pointer handlers in `FixturesTable` need to read the current selection without being
  // re-created on every change — a changing handler identity would detach mid-drag.
  const stateRef = useRef(state)
  stateRef.current = state

  const cells = useMemo(() => visibleCells<C>(state.keys, visibleRowIds), [state.keys, visibleRowIds])

  const select = useCallback((hits: readonly CellRef<C>[], intent: ListSelectIntent) => {
    setState((prev) => {
      const keys = applyCellSelection(prev.keys, hits, intent)
      // Unchanged keys keep the keys' identity — the same bail-out `applyCellSelection` makes for
      // a re-click, and for the same reason. The cursor still goes: a pointer gesture is a fresh
      // selection, so the next arrow re-derives its anchor rather than resuming a Shift-arrow
      // session that happened to leave these same cells.
      if (keys !== prev.keys) return { keys, cursor: null }
      return prev.cursor == null ? prev : { keys, cursor: null }
    })
  }, [])

  const place = useCallback((next: readonly CellRef<C>[], cursor: CellCursor<C>) => {
    setState((prev) => {
      // An arrow held against an edge places the selection it already has on every repeat; keep
      // the state object then, as `select` and `clear` do, rather than re-render the grid per key.
      const keys = next.map((cell) => cellKey(cell.rowId, cell.col))
      if (
        prev.cursor != null &&
        sameCell(prev.cursor.anchor, cursor.anchor) &&
        sameCell(prev.cursor.head, cursor.head) &&
        keys.length === prev.keys.size &&
        keys.every((key) => prev.keys.has(key))
      ) {
        return prev
      }
      return { keys: new Set(keys), cursor }
    })
  }, [])

  const clear = useCallback(() => {
    // Bail out rather than allocate: `clear()` runs on every Escape and every click outside the
    // marquee, and a fresh empty Set would re-render the whole grid each time.
    setState((prev) => (prev.keys.size === 0 ? prev : { keys: new Set<string>(), cursor: null }))
  }, [])

  const isSelected = useCallback(
    (rowId: RowId, col: C) => stateRef.current.keys.has(cellKey(rowId, col)),
    [],
  )

  const byColumn = useCallback(() => cellsByColumn<C>(stateRef.current.keys), [])

  return useMemo(
    () => ({ cells, count: cells.length, isSelected, byColumn, select, clear, cursor: state.cursor, place }),
    [cells, isSelected, byColumn, select, clear, state.cursor, place],
  )
}

function sameCell<C extends string>(a: CellRef<C>, b: CellRef<C>): boolean {
  return a.rowId === b.rowId && a.col === b.col
}

/**
 * Move [selection] by one arrow key over [grid] — `cellArrowStep`'s rule, from the stored cursor or
 * the one the selection implies (`cursorOfSelection`, its first and last cells in the grid's
 * reading order). Answers the head to bring into view, or null when the grid has no cells.
 *
 * The one copy of the sequence every host runs — the kit's `useSheet` and the programmer's
 * container — so the two cannot step differently. [beforePlace] is the host's cell door: each
 * clears its own row selection there, since a cell selection and a row selection are exclusive.
 */
export function stepCellSelection<C extends string>(
  selection: Pick<CellSelection<C>, 'cells' | 'cursor' | 'place'>,
  grid: CellGrid<C>,
  flow: CellFlow,
  direction: CellArrow,
  extend: boolean,
  beforePlace?: () => void,
): CellRef<C> | null {
  const cursor = selection.cursor ?? cursorOfSelection(orderedSelectedCells(selection.cells, grid.rows, grid.cols))
  const next = cellArrowStep(grid, flow, cursor, direction, extend)
  if (next == null) return null
  beforePlace?.()
  selection.place(next.cells, next.cursor)
  return next.cursor.head
}
