import { useEffect } from 'react'
import type { CellRef, RowId } from './cellSelectionModel'

/** How many frames a reveal waits for a virtualised row to render before giving up. */
const REVEAL_FRAMES = 6

/**
 * Scroll [scroller] the least distance that shows [cell] whole, clear of the sticky header row and
 * the sticky first column — the arrow keys' scroll, as a spreadsheet's is: nothing moves while the
 * cell is already in view, and a step off an edge scrolls by about one cell.
 *
 * Answers false when the cell is not in the DOM (its row virtualised away), so the caller can ask
 * the virtualiser for the row first. Read through the grid's addressing contract — `data-row-id`,
 * `data-cell`, `data-grid-header` — which `SheetTable` and `FixturesTable` both hang, so the two
 * tables reveal a cell alike; a row id carries operator-typed names, hence `CSS.escape`, as
 * `useCellEditorRequests` does. The sticky column is the header's **first child**, not
 * `[data-grid-name-header]`: that mark is hung only where the sheet selects rows, and the DMX
 * sheet's 48px row head is sticky all the same.
 */
export function revealCellInScroller(scroller: HTMLElement, cell: CellRef<string>): boolean {
  const element = scroller.querySelector(
    `[data-row-id="${CSS.escape(cell.rowId)}"] [data-cell="${CSS.escape(cell.col)}"]`,
  )
  if (element == null) return false
  const box = scroller.getBoundingClientRect()
  const header = scroller.querySelector('[data-grid-header]')
  const headerHeight = header?.getBoundingClientRect().height ?? 0
  const stickyWidth = header?.firstElementChild?.getBoundingClientRect().width ?? 0
  const rect = element.getBoundingClientRect()
  const top = box.top + headerHeight
  const bottom = box.top + scroller.clientHeight
  const left = box.left + stickyWidth
  const right = box.left + scroller.clientWidth
  if (rect.top < top) scroller.scrollTop -= top - rect.top
  else if (rect.bottom > bottom) scroller.scrollTop += rect.bottom - bottom
  if (rect.left < left) scroller.scrollLeft -= left - rect.left
  else if (rect.right > right) scroller.scrollLeft += rect.right - right
  return true
}

/**
 * The tables' half of a reveal request: show [request] once, then report it done. A cell whose row
 * is virtualised away is scrolled to by the virtualiser (`align: 'auto'`, the least move) and
 * revealed once the row has rendered — asked again each frame, up to `REVEAL_FRAMES`, because the
 * virtualiser renders the row from its own scroll listener and one frame is not a promise.
 */
export function useRevealCell(
  scrollRef: React.RefObject<HTMLElement | null>,
  rowIds: readonly RowId[],
  scrollToIndex: (index: number) => void,
  request: CellRef<string> | null | undefined,
  onDone: (() => void) | undefined,
): void {
  useEffect(() => {
    if (request == null) return
    const scroller = scrollRef.current
    if (scroller == null) return
    if (revealCellInScroller(scroller, request)) {
      onDone?.()
      return
    }
    const index = rowIds.indexOf(request.rowId)
    if (index < 0) {
      onDone?.()
      return
    }
    scrollToIndex(index)
    // Reported done only once the frames have run: reporting first would clear the request, and
    // this effect's cleanup would cancel the frame that was about to reveal it.
    let frame = 0
    let tries = 0
    const attempt = () => {
      tries += 1
      const current = scrollRef.current
      if ((current != null && revealCellInScroller(current, request)) || tries >= REVEAL_FRAMES) {
        onDone?.()
        return
      }
      frame = requestAnimationFrame(attempt)
    }
    frame = requestAnimationFrame(attempt)
    return () => cancelAnimationFrame(frame)
  }, [request, rowIds, scrollRef, scrollToIndex, onDone])
}
