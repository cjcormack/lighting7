import { useCallback, useEffect, useMemo, useRef } from 'react'
import { useDispatch, useSelector } from 'react-redux'
import {
  clearSelection as clearSelectionAction,
  publishTargets as publishTargetsAction,
  select as selectAction,
  selectAll as selectAllAction,
  selectScopeState,
  setSelection as setSelectionAction,
  type SelectionScope,
} from '../../store/selectionSlice'
import type { ListSelectIntent } from '../sheet/listSelectionModel'
import type { RowId } from './rowModel'

// The pure model lives in the sheet kit (`sheet/listSelectionModel`) so the slice can reduce with it without
// importing this hook. Re-exported here because this stays the public entry point — callers
// and tests import `listSelectionIntentFor` / `applyListSelection` from this module.
export {
  applyListSelection,
  listSelectionIntentFor,
  setListSelection,
} from '../sheet/listSelectionModel'
export type { ListSelectIntent, ListSelectionState } from '../sheet/listSelectionModel'

export interface ListSelection {
  selectedIds: ReadonlySet<RowId>
  /** Selected ids in *visible row order* (top→bottom) — the order a raw spread and
   *  batch operations run in. */
  orderedSelected: readonly RowId[]
  anchor: RowId | null
  count: number
  isSelected: (id: RowId) => boolean
  select: (id: RowId, intent?: ListSelectIntent) => void
  selectAll: () => void
  clear: () => void
  /** Select exactly these rows — see [setListSelection]. */
  setSelection: (ids: readonly RowId[]) => void
}

/**
 * Row selection for one list, backed by `store/selectionSlice`.
 *
 * The state moved to Redux so that surfaces outside the list — `RecordSheet`, opened from the
 * programmer toolbar and from a cue card — can read the selection; see the slice for why. The
 * returned [ListSelection] is unchanged by that move, which is what kept the keyboard and
 * range machinery in `FixturesListContainer` untouched: it depends on this shape, not on where
 * the state lives.
 *
 * [scope] names which list this is. Two lists mounted at once (the programmer sheet inside the
 * Program view, say) must not share a selection — their row ids collide without meaning the
 * same rows.
 */
export function useListSelection(
  visibleOrder: readonly RowId[],
  scope: SelectionScope,
): ListSelection {
  const dispatch = useDispatch()
  const state = useSelector((s: Parameters<typeof selectScopeState>[0]) =>
    selectScopeState(s, scope),
  )

  // Read through a ref so the callbacks stay referentially stable — they're
  // dependencies of keyboard-shortcut effects that must not re-bind per render.
  const orderRef = useRef(visibleOrder)
  orderRef.current = visibleOrder

  const selectedIds = useMemo(() => new Set(state.ids), [state.ids])

  const orderedSelected = useMemo(
    () => visibleOrder.filter((id) => selectedIds.has(id)),
    [visibleOrder, selectedIds],
  )

  // `dispatch` and `scope` are both stable for the life of the mount, so these keep the
  // no-rebind property the effects below rely on.
  const select = useCallback(
    (id: RowId, intent: ListSelectIntent = 'replace') => {
      dispatch(selectAction({ scope, id, intent, visibleOrder: orderRef.current }))
    },
    [dispatch, scope],
  )

  const selectAll = useCallback(() => {
    dispatch(selectAllAction({ scope, visibleOrder: orderRef.current }))
  }, [dispatch, scope])

  const clear = useCallback(() => {
    dispatch(clearSelectionAction({ scope }))
  }, [dispatch, scope])

  const setSelection = useCallback(
    (ids: readonly RowId[]) => {
      dispatch(setSelectionAction({ scope, ids, visibleOrder: orderRef.current }))
    },
    [dispatch, scope],
  )

  const isSelected = useCallback((id: RowId) => selectedIds.has(id), [selectedIds])

  // Drop the scope when the list goes away. This is what keeps the lift behaviour-neutral —
  // local state died with the component — and it lives here rather than beside the publish
  // below so that no list can hold a selection without also releasing it.
  //
  // Only one list per scope is ever mounted at a time today (the three scopes belong to
  // mutually exclusive routes), so a plain teardown clear is enough. If a future surface ever
  // mounts two lists on the same scope, this needs a mount count — one unmounting would
  // otherwise clear the other's selection.
  useEffect(
    () => () => {
      dispatch(clearSelectionAction({ scope }))
    },
    [dispatch, scope],
  )

  // A stable object identity while the selection is unchanged — consumers
  // hang callbacks and memo deps off `selection`, so a fresh literal per
  // render would defeat every one of them.
  return useMemo(
    () => ({
      selectedIds,
      orderedSelected,
      anchor: state.anchor,
      count: state.ids.length,
      isSelected,
      select,
      selectAll,
      clear,
      setSelection,
    }),
    [
      selectedIds,
      orderedSelected,
      state.anchor,
      state.ids.length,
      isSelected,
      select,
      selectAll,
      clear,
      setSelection,
    ],
  )
}

/**
 * Publish the selection's expanded write-target keys for consumers outside the list.
 *
 * Paired with [useListSelection], which owns the scope's lifecycle: this only writes the
 * derived key list. Depending on `targetKeys` by identity is safe because `publishTargets`
 * compares by value and bails when nothing moved.
 */
export function usePublishSelectionTargets(
  scope: SelectionScope,
  targetKeys: readonly string[],
): void {
  const dispatch = useDispatch()

  useEffect(() => {
    dispatch(publishTargetsAction({ scope, targetKeys }))
  }, [dispatch, scope, targetKeys])
}
