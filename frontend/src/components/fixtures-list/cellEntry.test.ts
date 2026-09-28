// @vitest-environment jsdom — `marqueeOwnsKeyTarget` reads the DOM; the rest needs none.
import { afterEach, describe, expect, it } from 'vitest'
import {
  cellActionCopy,
  cellKeyboardPermission,
  marqueeOwnsKeyTarget,
  openCellEditorTarget,
  orderedSelectedCells,
} from './cellEntry'
import type { CellRef } from '../sheet/cellSelectionModel'

/**
 * Which cell a keystroke opens. Display order on both axes, which is the whole of the rule: the
 * operator pressed Enter over a rectangle and the editor has to appear at its top-left corner, not
 * at whichever block they happened to draw last. The caller then walks this order for the first
 * cell that has an editor at all — a marquee is geometric and happily covers a Colour cell on a
 * dimmer-only par.
 */
describe('orderedSelectedCells', () => {
  const ROWS = ['fixture:a', 'fixture:b', 'fixture:c']
  const COLS = ['dimmer', 'colour', 'position'] as const

  it('orders by displayed row, then by visible column', () => {
    const cells: CellRef[] = [
      { rowId: 'fixture:c', col: 'dimmer' },
      { rowId: 'fixture:a', col: 'position' },
      { rowId: 'fixture:a', col: 'dimmer' },
    ]
    expect(orderedSelectedCells(cells, ROWS, COLS)).toEqual([
      { rowId: 'fixture:a', col: 'dimmer' },
      { rowId: 'fixture:a', col: 'position' },
      { rowId: 'fixture:c', col: 'dimmer' },
    ])
  })

  it('ranks a filtered-out row last rather than dropping it', () => {
    const cells: CellRef[] = [
      { rowId: 'fixture:hidden', col: 'dimmer' },
      { rowId: 'fixture:b', col: 'dimmer' },
    ]
    expect(orderedSelectedCells(cells, ROWS, COLS)[0]).toEqual({
      rowId: 'fixture:b',
      col: 'dimmer',
    })
  })

  it('still offers a selection made entirely of off-display cells', () => {
    const cells: CellRef[] = [{ rowId: 'fixture:hidden', col: 'dimmer' }]
    expect(orderedSelectedCells(cells, ROWS, COLS)).toEqual(cells)
  })

  it('is empty for an empty selection', () => {
    expect(orderedSelectedCells([], ROWS, COLS)).toEqual([])
  })
})

describe('cellKeyboardPermission', () => {
  it('offers both keys in Local and on the plain lists', () => {
    expect(cellKeyboardPermission(null, false)).toEqual({ entry: true, clear: true })
    expect(cellKeyboardPermission({ kind: 'local' }, false)).toEqual({ entry: true, clear: true })
  })

  it('refuses both in Output — a read of the cook', () => {
    expect(cellKeyboardPermission({ kind: 'output' }, false)).toEqual({ entry: false, clear: false })
  })

  it('takes a typed value into a focused Look layer but has no clear there', () => {
    // A value lands in the row draft the way a cell edit does; the draft has no removal.
    expect(cellKeyboardPermission({ kind: 'layer', layerId: 4 }, false)).toEqual({
      entry: true,
      clear: false,
    })
  })

  it('refuses both on a focused template layer — a read, never an edit', () => {
    expect(cellKeyboardPermission({ kind: 'layer', layerId: 4 }, true)).toEqual({
      entry: false,
      clear: false,
    })
  })
})

/**
 * The DOM half of the marquee-keyboard guard (`PD-ENTER-FOCUS`).
 *
 * Pinned here rather than through the container because the whole defect was one `closest`: a cell
 * trigger is a `<button>`, so the guard that keeps chips and menu items from stealing Enter also
 * kept the marquee's *own* cells from taking it — and that is precisely where the focus sits after
 * a drag.
 */
describe('marqueeOwnsKeyTarget', () => {
  function grid(): HTMLElement {
    const root = document.createElement('div')
    root.innerHTML = `
      <div data-row-id="fixture:a">
        <div data-cell="dimmer"><button id="a-dimmer">x</button></div>
        <div data-cell="colour"><button id="a-colour">x</button></div>
      </div>
      <div data-row-id="fixture:b">
        <div data-cell="dimmer"><button id="b-dimmer">x</button></div>
        <input id="b-check" type="checkbox" />
      </div>
      <button id="toolbar-chip">chip</button>
    `
    return root
  }
  const selected = (rowId: string, col: string) => rowId === 'fixture:a' && col === 'dimmer'

  it('claims a cell trigger the marquee covers, however deep the press landed', () => {
    const root = grid()
    const button = root.querySelector('#a-dimmer')!
    expect(marqueeOwnsKeyTarget(button, selected)).toBe(true)
    // The label span inside the trigger, which is what a real click's target usually is.
    const inner = document.createElement('span')
    button.appendChild(inner)
    expect(marqueeOwnsKeyTarget(inner, selected)).toBe(true)
  })

  it('does not claim a cell outside the selection — Tab-then-Enter still opens its own editor', () => {
    const root = grid()
    expect(marqueeOwnsKeyTarget(root.querySelector('#a-colour'), selected)).toBe(false)
    expect(marqueeOwnsKeyTarget(root.querySelector('#b-dimmer'), selected)).toBe(false)
  })

  it('does not claim a control that is in no cell at all', () => {
    const root = grid()
    expect(marqueeOwnsKeyTarget(root.querySelector('#toolbar-chip'), selected)).toBe(false)
    expect(marqueeOwnsKeyTarget(root.querySelector('#b-check'), selected)).toBe(false)
    expect(marqueeOwnsKeyTarget(null, selected)).toBe(false)
  })

  it('does not claim a cell with no row above it — neither attribute is enough alone', () => {
    const orphan = document.createElement('div')
    orphan.innerHTML = '<div data-cell="dimmer"><button id="o">x</button></div>'
    expect(marqueeOwnsKeyTarget(orphan.querySelector('#o'), () => true)).toBe(false)
  })
})

/**
 * The bar's Set and Clear say where the value lands, and say why when they cannot — following the
 * permission above rather than restating it, so a title can never promise a refused gesture.
 */
describe('cellActionCopy', () => {
  it('names Local as the destination in Local, and for the (unreachable) no-scope default', () => {
    expect(cellActionCopy({ kind: 'local' }, false, 3).setTitle).toMatch(/3 selected cells in Local/)
    expect(cellActionCopy(null, false, 1).setTitle).toMatch(/1 selected cell in Local/)
    expect(cellActionCopy({ kind: 'local' }, false, 3).clearTitle).toMatch(/out of Local/)
  })

  it('gives Output the read-only reason on both verbs', () => {
    const copy = cellActionCopy({ kind: 'output' }, false, 2)
    expect(copy.setTitle).toMatch(/read of the cook/)
    expect(copy.clearTitle).toBe(copy.setTitle)
  })

  it('names the layer as the destination on a focused Look, and refuses only Clear there', () => {
    const copy = cellActionCopy({ kind: 'layer', layerId: 7 }, false, 2)
    expect(copy.setTitle).toMatch(/focused layer/)
    expect(copy.clearTitle).toMatch(/switch to Local/)
  })

  it("uses Spread's own template wording on a focused template layer", () => {
    const copy = cellActionCopy({ kind: 'layer', layerId: 7 }, true, 2)
    expect(copy.setTitle).toMatch(/applies a template/)
    expect(copy.clearTitle).toBe(copy.setTitle)
  })
})

describe('openCellEditorTarget', () => {
  // Which editor the bar's Set shuts. Set has to be able to close what it opened, because a press
  // on that button is not the outside click that dismisses a popover — the button is that popover's
  // own anchor — and the DOM is where the answer lives, the open state being per cell.
  afterEach(() => {
    document.body.innerHTML = ''
  })

  function grid(html: string) {
    document.body.innerHTML = html
  }

  it('is null with nothing open', () => {
    grid('<div data-row-id="fixture:a"><div data-cell="dimmer"><button data-state="closed"></button></div></div>')
    expect(openCellEditorTarget()).toBeNull()
  })

  it('names the row and column of the open cell', () => {
    grid(
      '<div data-row-id="fixture:a"><div data-cell="dimmer"><button data-state="closed"></button></div></div>' +
        '<div data-row-id="fixture:b"><div data-cell="colour"><button data-state="open"></button></div></div>',
    )
    expect(openCellEditorTarget()).toEqual({ rowId: 'fixture:b', col: 'colour' })
  })

  it('ignores an open panel that is not a cell\'s — Spread opens from the toolbar', () => {
    // `SpreadPanel` is a cell editor in every way but this one: its trigger lives in the selection
    // bar, outside any `[data-cell]`. Set must not try to close it.
    grid('<div><button data-state="open" aria-label="Spread"></button></div>')
    expect(openCellEditorTarget()).toBeNull()
  })
})
