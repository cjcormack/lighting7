// @vitest-environment jsdom
import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { useCellSelection } from './useCellSelection'
import type { CellGrid, CellRef } from './cellSelectionModel'

const ROWS = ['r0', 'r1', 'r2', 'r3']
const COLS = ['dimmer', 'colour', 'position']
afterEach(cleanup)

const grid: CellGrid = { rows: ROWS, cols: COLS }
const cell = (rowId: string, col: string): CellRef => ({ rowId, col })

function setup(rows: readonly string[] = ROWS) {
  const visible = new Set(rows)
  return renderHook(() => useCellSelection(visible))
}

const keys = (cells: readonly CellRef[]) => cells.map((c) => `${c.rowId}/${c.col}`).sort()

describe('useCellSelection.click', () => {
  it('a plain click selects the cell alone and puts the cursor on it', () => {
    const { result } = setup()
    act(() => result.current.select([cell('r0', 'dimmer'), cell('r1', 'dimmer')], 'replace'))
    act(() => result.current.click(cell('r2', 'colour'), 'replace', grid))
    expect(keys(result.current.cells)).toEqual(['r2/colour'])
    expect(result.current.cursor).toEqual({ anchor: cell('r2', 'colour'), head: cell('r2', 'colour') })
  })

  it('⌘ toggles one cell in and out, leaving the others', () => {
    const { result } = setup()
    act(() => result.current.click(cell('r0', 'dimmer'), 'replace', grid))
    act(() => result.current.click(cell('r2', 'dimmer'), 'toggle', grid))
    act(() => result.current.click(cell('r1', 'colour'), 'toggle', grid))
    expect(keys(result.current.cells)).toEqual(['r0/dimmer', 'r1/colour', 'r2/dimmer'])
    act(() => result.current.click(cell('r2', 'dimmer'), 'toggle', grid))
    expect(keys(result.current.cells)).toEqual(['r0/dimmer', 'r1/colour'])
    // The cursor moves to a cell a ⌘-click took out, too.
    expect(result.current.cursor?.anchor).toEqual(cell('r2', 'dimmer'))
  })

  it('⇧ selects the rectangle from the click’s anchor, and ⌘⇧ adds it', () => {
    const { result } = setup()
    act(() => result.current.click(cell('r0', 'dimmer'), 'replace', grid))
    act(() => result.current.click(cell('r1', 'colour'), 'range', grid))
    expect(keys(result.current.cells)).toEqual(['r0/colour', 'r0/dimmer', 'r1/colour', 'r1/dimmer'])
    expect(result.current.cursor).toEqual({ anchor: cell('r0', 'dimmer'), head: cell('r1', 'colour') })
    // A second ⇧ from the same anchor replaces the rectangle.
    act(() => result.current.click(cell('r0', 'position'), 'range', grid))
    expect(keys(result.current.cells)).toEqual(['r0/colour', 'r0/dimmer', 'r0/position'])

    act(() => result.current.click(cell('r3', 'position'), 'toggle', grid))
    act(() => result.current.click(cell('r2', 'position'), 'range-add', grid))
    expect(keys(result.current.cells)).toEqual(['r0/colour', 'r0/dimmer', 'r0/position', 'r2/position', 'r3/position'])
  })

  it('⇧ after a marquee anchors at its first cell in reading order', () => {
    const { result } = setup()
    // Added out of order, so insertion order and reading order differ.
    act(() => result.current.select([cell('r2', 'position'), cell('r1', 'colour')], 'replace'))
    expect(result.current.cursor).toBeNull()
    act(() => result.current.click(cell('r2', 'dimmer'), 'range', grid))
    expect(keys(result.current.cells)).toEqual(['r1/colour', 'r1/dimmer', 'r2/colour', 'r2/dimmer'])
    expect(result.current.cursor?.anchor).toEqual(cell('r1', 'colour'))
  })

  it('⇧ with nothing selected, or an anchor off the grid, is a plain click; ⌘⇧ a ⌘ click', () => {
    const { result } = setup()
    act(() => result.current.click(cell('r1', 'colour'), 'range', grid))
    expect(keys(result.current.cells)).toEqual(['r1/colour'])

    act(() => result.current.click(cell('r0', 'dimmer'), 'replace', grid))
    act(() => result.current.click(cell('r3', 'dimmer'), 'toggle', grid))
    // r3 collapses out of view: the anchor is gone from the grid.
    const shrunk: CellGrid = { rows: ['r0', 'r1', 'r2'], cols: COLS }
    act(() => result.current.click(cell('r2', 'colour'), 'range', shrunk))
    expect(keys(result.current.cells)).toEqual(['r2/colour'])

    act(() => result.current.click(cell('r3', 'dimmer'), 'replace', grid))
    act(() => result.current.click(cell('r1', 'colour'), 'range-add', shrunk))
    expect(keys(result.current.cells)).toEqual(['r1/colour', 'r3/dimmer'])
  })

  it('a ⌘ double-click leaves the cell selected, whether or not it started selected', () => {
    const { result } = setup()
    act(() => result.current.click(cell('r0', 'dimmer'), 'replace', grid))
    // Not selected: the first click adds it, the second keeps it.
    act(() => result.current.click(cell('r1', 'dimmer'), 'toggle', grid, 1))
    act(() => result.current.click(cell('r1', 'dimmer'), 'toggle', grid, 2))
    expect(keys(result.current.cells)).toEqual(['r0/dimmer', 'r1/dimmer'])
    // Selected: the first click takes it out, the second puts it back.
    act(() => result.current.click(cell('r0', 'dimmer'), 'toggle', grid, 1))
    expect(keys(result.current.cells)).toEqual(['r1/dimmer'])
    act(() => result.current.click(cell('r0', 'dimmer'), 'toggle', grid, 2))
    expect(keys(result.current.cells)).toEqual(['r0/dimmer', 'r1/dimmer'])
  })

  it('a quick ⌘-click on a neighbouring cell still toggles it', () => {
    const { result } = setup()
    act(() => result.current.click(cell('r0', 'dimmer'), 'replace', grid))
    act(() => result.current.click(cell('r1', 'dimmer'), 'toggle', grid, 1))
    // The browser counts a second click near the first as detail 2, on another cell.
    act(() => result.current.click(cell('r2', 'dimmer'), 'toggle', grid, 2))
    expect(keys(result.current.cells)).toEqual(['r0/dimmer', 'r1/dimmer', 'r2/dimmer'])
    act(() => result.current.click(cell('r1', 'dimmer'), 'toggle', grid, 2))
    expect(keys(result.current.cells)).toEqual(['r0/dimmer', 'r2/dimmer'])
  })

  it('removing the last cell drops the cursor', () => {
    const { result } = setup()
    act(() => result.current.click(cell('r0', 'dimmer'), 'toggle', grid))
    expect(result.current.cursor).not.toBeNull()
    act(() => result.current.click(cell('r0', 'dimmer'), 'toggle', grid))
    expect(result.current.count).toBe(0)
    expect(result.current.cursor).toBeNull()
  })

  it('a plain re-click of the one selected cell keeps the state', () => {
    const { result } = setup()
    act(() => result.current.click(cell('r0', 'dimmer'), 'replace', grid))
    const before = result.current
    act(() => result.current.click(cell('r0', 'dimmer'), 'replace', grid))
    expect(result.current).toBe(before)
  })

  it('a quick click after the arrows moved the selection replaces it, not a double-click repeat', () => {
    const { result } = setup()
    act(() => result.current.click(cell('r0', 'dimmer'), 'replace', grid, 1))
    act(() => result.current.place([cell('r1', 'dimmer')], { anchor: cell('r1', 'dimmer'), head: cell('r1', 'dimmer') }))
    act(() => result.current.click(cell('r0', 'dimmer'), 'replace', grid, 2))
    expect(keys(result.current.cells)).toEqual(['r0/dimmer'])
  })

  it('a modified click takes the focus off the cell trigger it landed on, and a plain one leaves it', () => {
    const { result } = setup()
    const wrap = document.createElement('div')
    wrap.setAttribute('data-cell', 'dimmer')
    const trigger = document.createElement('button')
    wrap.appendChild(trigger)
    document.body.appendChild(wrap)
    trigger.focus()
    act(() => result.current.click(cell('r0', 'dimmer'), 'replace', grid))
    expect(document.activeElement).toBe(trigger)
    act(() => result.current.click(cell('r0', 'dimmer'), 'toggle', grid))
    expect(document.activeElement).not.toBe(trigger)
    wrap.remove()
  })

})
