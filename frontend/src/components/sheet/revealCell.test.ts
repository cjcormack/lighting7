// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { revealCellInScroller } from './revealCell'

/**
 * The arrow keys' scroll (CLAUDE.md §Sheet kit): the least move that shows a cell whole, clear of
 * the sticky header row and first column. jsdom lays nothing out, so every box is stubbed — the
 * scroller's viewport is 400×300 at the origin, the header 30 tall and its sticky first child 48
 * wide, and a cell's box is given in viewport coordinates.
 */
function sheet(cellBox: { top: number; left: number; width?: number; height?: number }) {
  const box = (top: number, left: number, width: number, height: number) => () =>
    ({ top, left, width, height, bottom: top + height, right: left + width, x: left, y: top, toJSON: () => ({}) }) as DOMRect
  const scroller = document.createElement('div')
  Object.defineProperty(scroller, 'clientHeight', { value: 300 })
  Object.defineProperty(scroller, 'clientWidth', { value: 400 })
  scroller.getBoundingClientRect = box(0, 0, 400, 300)
  const header = document.createElement('div')
  header.setAttribute('data-grid-header', '')
  header.getBoundingClientRect = box(0, 0, 400, 30)
  const sticky = document.createElement('div')
  sticky.getBoundingClientRect = box(0, 0, 48, 30)
  header.appendChild(sticky)
  // A row id with a quote and a colon in it — an operator-typed name — to pin the escaping.
  const row = document.createElement('div')
  row.setAttribute('data-row-id', 'group:Front "wash"')
  const cell = document.createElement('div')
  cell.setAttribute('data-cell', 'c3')
  cell.getBoundingClientRect = box(cellBox.top, cellBox.left, cellBox.width ?? 64, cellBox.height ?? 44)
  row.appendChild(cell)
  scroller.append(header, row)
  return scroller
}
const target = { rowId: 'group:Front "wash"', col: 'c3' }

describe('revealCellInScroller', () => {
  it('leaves the scroll alone while the cell is already in view', () => {
    const scroller = sheet({ top: 100, left: 100 })
    expect(revealCellInScroller(scroller, target)).toBe(true)
    expect(scroller.scrollTop).toBe(0)
    expect(scroller.scrollLeft).toBe(0)
  })

  it('scrolls down just far enough to show a cell below the viewport', () => {
    const scroller = sheet({ top: 280, left: 100 })
    scroller.scrollTop = 50
    revealCellInScroller(scroller, target)
    expect(scroller.scrollTop).toBe(50 + 24)
  })

  it('scrolls up to clear the sticky header, not just the viewport edge', () => {
    const scroller = sheet({ top: 10, left: 100 })
    scroller.scrollTop = 100
    revealCellInScroller(scroller, target)
    expect(scroller.scrollTop).toBe(100 - 20)
  })

  it('scrolls sideways to clear the sticky first column on the left and the edge on the right', () => {
    const left = sheet({ top: 100, left: 20 })
    left.scrollLeft = 200
    revealCellInScroller(left, target)
    expect(left.scrollLeft).toBe(200 - 28)
    const right = sheet({ top: 100, left: 380 })
    revealCellInScroller(right, target)
    expect(right.scrollLeft).toBe(44)
  })

  it('answers false for a cell that is not rendered', () => {
    const scroller = sheet({ top: 100, left: 100 })
    expect(revealCellInScroller(scroller, { rowId: 'row:9', col: 'c3' })).toBe(false)
    expect(revealCellInScroller(scroller, { rowId: target.rowId, col: 'c4' })).toBe(false)
  })
})
