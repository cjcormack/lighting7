import { useCallback, useEffect, useState } from 'react'
import { openCellEditorTarget } from './cellEntry'
import type { CellRef, RowId } from './cellSelectionModel'

/** The container's request that a cell open its editor — Enter, a typed character, or the bar's Set. */
export interface CellOpenRequest<C extends string> {
  rowId: RowId
  col: C
  /** The character that opened it, or `''` for a bare Enter or Set. */
  seed: string
  /** Set's press, which opens the editor at that button rather than beside the cell. */
  atButton: boolean
}

/**
 * The two one-shots a sheet's container hands its table: **open this cell's editor** and **close
 * it** — and the two gestures behind them, the keyboard's and the selection bar's Set.
 *
 * Extracted from `FixturesListContainer` for every sheet (CLAUDE.md §Sheet kit). Both requests
 * are dropped on the commit after they are set, for the reason the table's own `autoOpenCell`
 * gives: the request names a cell by `(rowId, col)`, and a request left standing would re-open that
 * editor the next time the row it names is re-rendered into the virtualiser's window.
 *
 * **A cell that is not on screen is scrolled to rather than opened.** The rows are virtualised, so
 * an operator who selects cells, scrolls away and then presses Enter names a row that no row view
 * is mounted for — and the request, being a one-shot the table drops on the very next commit,
 * would be swallowed in silence. So the first press brings the cell into view and the second opens
 * it, which is what [onScrollTo] is for.
 *
 * **Set has to close what it opened, because nothing else does.** The Set button is the open
 * popover's own anchor, and a press on it is not treated as the outside click that dismisses one —
 * so the second press asks the cell to close, which is what makes Set behave the way Spread's own
 * button always has. Which cell is read from the DOM (`openCellEditorTarget`), scoped to a cell's
 * own anchor so `SpreadPanel`'s panel does not answer.
 */
export function useCellEditorRequests<C extends string>({
  firstEditableCell,
  onScrollTo,
  intercept,
}: {
  /** The cell Set, Enter and a typed character all name: first in display order that has an editor. */
  firstEditableCell: () => CellRef<C> | undefined
  /** Bring a row into the virtualiser's window; the next press opens it. */
  onScrollTo: (rowId: RowId) => void
  /**
   * **The one branch where an open is claimed** rather than handed to the cell — the programmer
   * rail's Colour tab, which is that column's editor while it is open (editor-kit plan session 4,
   * call 9). Asked with the request the cell would have received; answering true means the caller
   * has taken it and no cell opens. Asked **before** the scroll-to check, because a claimed open
   * lands in the rail and needs no cell on screen.
   */
  intercept?: (request: CellOpenRequest<C>) => boolean
}): {
  /** Hand to the table's `keyboardOpen`. */
  keyboardOpen: CellOpenRequest<C> | null
  /** Hand to the table's `closeEditorCell`. */
  closeEditorCell: CellRef<C> | null
  /** Enter or a typed character: open the selection's editor beside the cell, seeded with `seed`. */
  openCellEditor: (seed: string) => void
  /** The bar's Set: open the selection's editor at the button, or close the one it opened. */
  toggleCellEditor: () => void
  /**
   * Close whatever cell editor is open, if any, and say whether there was one.
   *
   * For a surface that changes *under* an open editor rather than deselecting — the programmer's
   * scope switch is the one caller (CLAUDE.md §The programmer's scoped grid). `selectionEmpty` is
   * the other way an editor is closed for it, and it cannot answer this: the editor is open for a
   * selection that still exists, it is only pointed at something else now.
   */
  closeCellEditor: () => boolean
} {
  const [keyboardOpen, setKeyboardOpen] = useState<CellOpenRequest<C> | null>(null)
  useEffect(() => {
    if (keyboardOpen) setKeyboardOpen(null)
  }, [keyboardOpen])
  const [closeEditorCell, setCloseEditorCell] = useState<CellRef<C> | null>(null)
  useEffect(() => {
    if (closeEditorCell) setCloseEditorCell(null)
  }, [closeEditorCell])

  const request = useCallback(
    (seed: string, atButton: boolean) => {
      const first = firstEditableCell()
      if (!first) return
      if (intercept?.({ rowId: first.rowId, col: first.col, seed, atButton })) return
      // The DOM is the only thing that knows what the virtualiser rendered, and `data-row-id` is
      // the sheet's own addressing contract — the same attribute `marqueeOwnsKeyTarget` reads,
      // walked the other way. Asked rather than always scrolling, because recentring the list
      // under an operator who pressed Enter on a cell they were already looking at is worse than
      // the problem.
      if (document.querySelector(`[data-row-id="${CSS.escape(first.rowId)}"]`) == null) {
        onScrollTo(first.rowId)
        return
      }
      setKeyboardOpen({ rowId: first.rowId, col: first.col, seed, atButton })
    },
    [firstEditableCell, onScrollTo, intercept],
  )

  const openCellEditor = useCallback((seed: string) => request(seed, false), [request])

  const closeCellEditor = useCallback(() => {
    const open = openCellEditorTarget<C>()
    if (!open) return false
    setCloseEditorCell(open)
    return true
  }, [])

  const toggleCellEditor = useCallback(() => {
    if (closeCellEditor()) return
    // Set is pressed at the toolbar, so its editor opens there. Enter and a typed character are
    // made at the selection and open beside the cell — see `anchorAtButton` in `useEditorOpen`.
    request('', true)
  }, [closeCellEditor, request])

  return { keyboardOpen, closeEditorCell, openCellEditor, toggleCellEditor, closeCellEditor }
}
