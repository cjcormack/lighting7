import { createSlice, type PayloadAction } from '@reduxjs/toolkit'
import {
  applyListSelection,
  setListSelection,
  type ListSelectIntent,
} from '../components/sheet/listSelectionModel'
import type { RowId } from '../components/fixtures-list/rowModel'

/**
 * Fixtures-list row selection, lifted out of `FixturesListContainer`'s local state.
 *
 * ## Why it is in the store at all
 *
 * Selection used to be `useState` inside the container, which meant only components rendered
 * *inside* that container could see it — `SelectionToolbar` could (hence "Record palette" is
 * selection-scoped), but the programmer's own controls could not, because they were injected from
 * outside. That is why cue Record had no "selected fixtures only": the sheet it opens has no prop
 * path to the selection. Those controls are now a band of their own on the programmer page, which
 * is further outside still — so the store is what makes Record's scope work at all.
 *
 * Cell selection deliberately does **not** live here — see `useCellSelection`. Nothing outside the
 * grid reads it, and the reason above is the only reason row selection had to be lifted.
 *
 * ## Scoping
 *
 * One container component serves three different lists — Fixtures → List, Groups → List and
 * the programmer sheet — and their [RowId]s are not interchangeable (`group:front-wash` is a
 * row in two of them and means different things in each). So state is keyed by scope, and a
 * consumer outside the list says which list it means: `RecordSheet` wants `programmer`.
 *
 * ## `visibleOrder` is not stored
 *
 * Ranges are slices of the current row order, and that order is derived render state (rows,
 * minus dividers, after filtering and group expansion). It travels in the action payload
 * instead, which keeps these reducers exactly as pure as the `useState` versions they replace.
 */
export type SelectionScope = 'fixtures' | 'groups' | 'programmer'

interface ScopeState {
  /** Selected ids, in the order they were added (not display order). */
  ids: RowId[]
  anchor: RowId | null
  /**
   * The selection expanded to write-target keys, published by the container.
   *
   * Derived state in a store is normally a smell; it earns its place because the expansion
   * (`expandSelectionToTargets`) needs the container's `rows`, which depend on filter text,
   * group expansion and rollup mode. A consumer outside the list cannot rebuild that and must
   * not try — so the container, which has already computed it, publishes it.
   *
   * Element rows contribute their *element* key here (`bar-1.head-0`), matching what
   * `SelectionToolbar` already sends to the palette route. The record routes resolve fixture
   * keys, so an element key simply matches nothing and scopes its parent out.
   */
  targetKeys: string[]
}

type SelectionState = Record<SelectionScope, ScopeState>

const emptyScope = (): ScopeState => ({ ids: [], anchor: null, targetKeys: [] })

const initialState: SelectionState = {
  fixtures: emptyScope(),
  groups: emptyScope(),
  programmer: emptyScope(),
}

export const selectionSlice = createSlice({
  name: 'selection',
  initialState,
  reducers: {
    select(
      state,
      action: PayloadAction<{
        scope: SelectionScope
        id: RowId
        intent: ListSelectIntent
        visibleOrder: readonly RowId[]
      }>,
    ) {
      const { scope, id, intent, visibleOrder } = action.payload
      const s = state[scope]
      const next = applyListSelection({ ids: s.ids, anchor: s.anchor }, { id, intent }, visibleOrder)
      // `applyListSelection` hands back the state it was given when the action is a no-op (a
      // range click on a row that isn't in view). Assigning a fresh array anyway would break the
      // hook's "stable object identity while the selection is unchanged" contract, which the
      // list's memoised row callbacks hang off.
      if (next.ids !== s.ids) s.ids = [...next.ids]
      s.anchor = next.anchor
    },

    setSelection(
      state,
      action: PayloadAction<{
        scope: SelectionScope
        ids: readonly RowId[]
        visibleOrder: readonly RowId[]
      }>,
    ) {
      const { scope, ids, visibleOrder } = action.payload
      const next = setListSelection(ids, visibleOrder)
      state[scope].ids = [...next.ids]
      state[scope].anchor = next.anchor
    },

    selectAll(
      state,
      action: PayloadAction<{ scope: SelectionScope; visibleOrder: readonly RowId[] }>,
    ) {
      state[action.payload.scope].ids = [...action.payload.visibleOrder]
    },

    /**
     * Drop everything this scope holds.
     *
     * Also what a container dispatches on unmount, which is what keeps the lift behaviourally
     * identical to the `useState` version: a selection that outlived its list would let
     * `RecordSheet` scope a Record by heads the operator can no longer see.
     */
    clearSelection(state, action: PayloadAction<{ scope: SelectionScope }>) {
      state[action.payload.scope] = emptyScope()
    },

    publishTargets(
      state,
      action: PayloadAction<{ scope: SelectionScope; targetKeys: readonly string[] }>,
    ) {
      const { scope, targetKeys } = action.payload
      const current = state[scope].targetKeys
      // Idempotent on value, not identity. The container recomputes its expansion whenever
      // `rows` changes — a filter keystroke, a background fixture refetch — and most of those
      // produce the same keys. Bailing here keeps the state reference stable, so subscribers
      // don't re-render for a selection that didn't actually move.
      if (
        current.length === targetKeys.length &&
        current.every((key, i) => key === targetKeys[i])
      ) {
        return
      }
      state[scope].targetKeys = [...targetKeys]
    },
  },
})

export const { select, setSelection, selectAll, clearSelection, publishTargets } =
  selectionSlice.actions

export function selectScopeState(
  state: { selection: SelectionState },
  scope: SelectionScope,
): ScopeState {
  return state.selection[scope]
}

/** The selection's expanded write-target keys — what a scoped Record sends as `targets`. */
export function selectTargetKeys(
  state: { selection: SelectionState },
  scope: SelectionScope,
): readonly string[] {
  return state.selection[scope].targetKeys
}
