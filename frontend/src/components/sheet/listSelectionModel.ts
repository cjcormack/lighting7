import type { RowId } from './cellSelectionModel'

/**
 * The pure half of list selection: intent parsing and the state transitions.
 *
 * Separate from `useListSelection` so that `store/selectionSlice.ts` can reduce with these
 * without importing the hook that dispatches its actions — the two would otherwise form an
 * import cycle. Everything here is re-exported from `fixtures-list/useListSelection.ts`, which
 * stays that list's public entry point; the sheet kit's own `useLocalListSelection` reduces with
 * the same functions for the surfaces whose row selection is nobody's business but their own.
 */

export type ListSelectIntent = 'replace' | 'toggle' | 'range' | 'range-add'

/** Shift = range from the anchor, ⌘/Ctrl = toggle, ⌘/Ctrl+Shift = extend the
 *  selection by a range. Range semantics (not stage3d's shift-means-add): this
 *  is a list, and shift-click ranges are the list convention. */
export function listSelectionIntentFor(
  e: Pick<MouseEvent, 'shiftKey' | 'metaKey' | 'ctrlKey'>,
): ListSelectIntent {
  const cmd = e.metaKey || e.ctrlKey
  if (cmd && e.shiftKey) return 'range-add'
  if (e.shiftKey) return 'range'
  if (cmd) return 'toggle'
  return 'replace'
}

export interface ListSelectionState {
  /** Selected ids, in the order they were added (not display order). */
  ids: readonly RowId[]
  /** The range pivot: stays put across successive shift-clicks so the user can
   *  re-pivot a range from the same starting row. */
  anchor: RowId | null
}

/**
 * Pure selection reducer. `visibleOrder` is the current `buildRows` output
 * order — ranges are slices of it. An anchor that's been filtered out of view
 * degrades the range to a plain replace.
 */
export function applyListSelection(
  state: ListSelectionState,
  action: { id: RowId; intent: ListSelectIntent },
  visibleOrder: readonly RowId[],
): ListSelectionState {
  const { id, intent } = action

  if (intent === 'range' || intent === 'range-add') {
    const anchorIdx = state.anchor === null ? -1 : visibleOrder.indexOf(state.anchor)
    if (anchorIdx === -1) {
      return { ids: [id], anchor: id }
    }
    const clickedIdx = visibleOrder.indexOf(id)
    if (clickedIdx === -1) return state
    const [lo, hi] = anchorIdx <= clickedIdx ? [anchorIdx, clickedIdx] : [clickedIdx, anchorIdx]
    const slice = visibleOrder.slice(lo, hi + 1)
    if (intent === 'range') {
      return { ids: slice, anchor: state.anchor }
    }
    const merged = [...state.ids]
    const present = new Set(state.ids)
    for (const rangeId of slice) {
      if (!present.has(rangeId)) {
        present.add(rangeId)
        merged.push(rangeId)
      }
    }
    return { ids: merged, anchor: state.anchor }
  }

  if (intent === 'toggle') {
    if (state.ids.includes(id)) {
      const ids = state.ids.filter((existing) => existing !== id)
      // Anchor falls back to the last remaining selection when the anchor
      // itself was toggled off.
      const anchor = state.anchor === id ? (ids[ids.length - 1] ?? null) : state.anchor
      return { ids, anchor }
    }
    return { ids: [...state.ids, id], anchor: id }
  }

  return { ids: [id], anchor: id }
}

/**
 * Replace the whole selection with [ids], keeping only those currently in view.
 *
 * Separate from `select(id, 'replace')` because it is not a click: Include hands back a set
 * of fixtures and the sheet selects exactly them. Ids that aren't visible (filtered out, or a
 * group row in the wrong rollup mode) are dropped rather than silently selecting nothing.
 */
export function setListSelection(
  ids: readonly RowId[],
  visibleOrder: readonly RowId[],
): ListSelectionState {
  const wanted = new Set(ids)
  const present = visibleOrder.filter((id) => wanted.has(id))
  return { ids: present, anchor: present[present.length - 1] ?? null }
}

/**
 * Where ↑ or ↓ moves a list selection: the row to select next, or null when there are no rows.
 *
 * The rule every sheet's arrow keys share — the programmer's list and the kit's sheets alike — so
 * a list cannot step one way on one page and another way on the next. The caller selects the
 * answer with `'range'` when [extend] (Shift) and `'replace'` otherwise, and scrolls it into view.
 *
 * - **A plain step moves from the anchor**, one row, clamped at both ends. With no anchor in view
 *   — nothing selected, or the anchor filtered away — ↓ lands on the first row and ↑ on the last.
 * - **Shift steps the range's *moving* edge** — the end that is not the anchor — so a range
 *   extended upward keeps growing upward. Stepping from the bottom of the selection regardless
 *   capped an upward range at two rows, because extending up makes the bottom edge the anchor.
 */
export function arrowStepTarget(
  order: readonly RowId[],
  selection: { anchor: RowId | null; orderedSelected: readonly RowId[] },
  direction: 'up' | 'down',
  extend: boolean,
): RowId | null {
  if (order.length === 0) return null
  const anchorIdx = selection.anchor ? order.indexOf(selection.anchor) : -1
  let fromIdx = anchorIdx
  const selected = selection.orderedSelected
  if (extend && selected.length > 0) {
    const firstIdx = order.indexOf(selected[0])
    const lastIdx = order.indexOf(selected[selected.length - 1])
    fromIdx = firstIdx < anchorIdx ? firstIdx : lastIdx
  }
  if (fromIdx === -1) return direction === 'down' ? order[0] : order[order.length - 1]
  const delta = direction === 'down' ? 1 : -1
  return order[Math.max(0, Math.min(order.length - 1, fromIdx + delta))]
}
