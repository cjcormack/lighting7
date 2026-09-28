import { useCallback, useMemo, useState } from 'react'
import {
  applyListSelection,
  setListSelection,
  type ListSelectIntent,
  type ListSelectionState,
} from './listSelectionModel'
import type { RowId } from './cellSelectionModel'

/** The row half of a sheet's selection — the same shape `fixtures-list/useListSelection` returns. */
export interface LocalListSelection {
  selectedIds: ReadonlySet<RowId>
  /** Selected ids in *visible row order* (top→bottom) — the order a raw spread and a batch run in. */
  orderedSelected: readonly RowId[]
  anchor: RowId | null
  count: number
  isSelected: (id: RowId) => boolean
  select: (id: RowId, intent?: ListSelectIntent) => void
  selectAll: () => void
  clear: () => void
  /** Select exactly these rows — a marquee's arrival, or a deep link. */
  setSelection: (ids: readonly RowId[]) => void
}

const EMPTY: ListSelectionState = { ids: [], anchor: null }

/**
 * Row selection held in component state, for the sheets whose selection is nobody else's business.
 *
 * The fixtures list keeps its rows in `store/selectionSlice` because `RecordSheet` and the desk
 * bridge read them from outside the list. The patch list, the DMX sheet and the cue sheet have no
 * such reader — nothing outside the page acts on "the selected cues" — so their row selection is
 * local, which also spares them `useListSelection`'s unmount-clear hazard. The reducer is the
 * shared one (`listSelectionModel`), so ⌘-click, Shift-range and select-all mean the same on every
 * sheet.
 */
export function useLocalListSelection(visibleOrder: readonly RowId[]): LocalListSelection {
  const [state, setState] = useState<ListSelectionState>(EMPTY)

  const selectedIds = useMemo(() => new Set(state.ids), [state.ids])
  const orderedSelected = useMemo(
    () => visibleOrder.filter((id) => selectedIds.has(id)),
    [visibleOrder, selectedIds],
  )

  const select = useCallback(
    (id: RowId, intent: ListSelectIntent = 'replace') => {
      setState((prev) => applyListSelection(prev, { id, intent }, visibleOrder))
    },
    [visibleOrder],
  )
  const selectAll = useCallback(() => {
    setState({ ids: [...visibleOrder], anchor: visibleOrder[visibleOrder.length - 1] ?? null })
  }, [visibleOrder])
  const clear = useCallback(() => {
    // Bail out rather than allocate: `clear()` runs on every Escape and every click on the grid's
    // background, and a fresh state would re-render every row for nothing.
    setState((prev) => (prev.ids.length === 0 && prev.anchor === null ? prev : EMPTY))
  }, [])
  const setSelection = useCallback(
    (ids: readonly RowId[]) => setState(setListSelection(ids, visibleOrder)),
    [visibleOrder],
  )
  const isSelected = useCallback((id: RowId) => selectedIds.has(id), [selectedIds])

  return useMemo(
    () => ({
      selectedIds,
      orderedSelected,
      anchor: state.anchor,
      count: selectedIds.size,
      isSelected,
      select,
      selectAll,
      clear,
      setSelection,
    }),
    [selectedIds, orderedSelected, state.anchor, isSelected, select, selectAll, clear, setSelection],
  )
}
