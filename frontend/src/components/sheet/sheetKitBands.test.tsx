// @vitest-environment jsdom
import { act, cleanup, render } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/hooks/useMediaQuery', () => ({ useMediaQuery: () => false }))
vi.mock('@tanstack/react-virtual', () => ({
  useVirtualizer: ({ count, estimateSize }: { count: number; estimateSize: () => number }) => ({
    getTotalSize: () => count * estimateSize(),
    getVirtualItems: () =>
      Array.from({ length: count }, (_, index) => ({ index, key: index, start: index * estimateSize(), size: estimateSize() })),
    scrollToIndex: () => {},
  }),
}))

import { useSheet } from './useSheet'
import { SheetTable } from './SheetTable'
import { cellSelectionClass } from './cellSelection'
import type { SheetColumn, SheetRow } from './sheetModel'

/**
 * The sheet kit's three timeline changes (stack-timeline plan §4.2), each generic: a selection
 * ring that joins down a column, a divider row whose cells a run passes through, and header bands.
 */

interface Row extends SheetRow {
  on: boolean
}

const ROWS: Row[] = [
  { id: 'a', on: true },
  { id: 'b', on: true },
  { id: 'm', on: false, divider: 'Scene 2' },
  { id: 'c', on: true },
  { id: 'd', on: false },
]

type Col = 'x' | 'y'

function column(key: Col, extra: Partial<SheetColumn<Row, Col>> = {}): SheetColumn<Row, Col> {
  return {
    key,
    label: key.toUpperCase(),
    width: '84px',
    value: (row) => row.on,
    cell: (row) => <span>{row.on ? 'on' : ''}</span>,
    ...extra,
  }
}

let api: ReturnType<typeof useSheet<Row, Col>> | null = null

function Harness({ columns }: { columns: SheetColumn<Row, Col>[] }) {
  const sheet = useSheet<Row, Col>({
    rows: ROWS,
    columns,
    permission: { entry: false, clear: false },
    copy: () => ({ setTitle: '', clearTitle: '' }),
  })
  api = sheet
  return <SheetTable<Row, Col> {...sheet.tableProps} firstColumn={{ label: 'Cue', width: '100px', render: (row) => row.id }} />
}

function cellEl(rowId: string, col: Col): HTMLElement {
  return document.querySelector(`[data-row-id="${rowId}"] [data-cell="${col}"]`) as HTMLElement
}

afterEach(() => {
  cleanup()
  api = null
})

describe('a selection ring that joins down a column', () => {
  it('drops the edges two selected neighbours share, and keeps a lone cell whole', () => {
    expect(cellSelectionClass(true)).toContain('after:ring-2')
    const top = cellSelectionClass(true, true, false, { above: false, below: true })
    expect(top).not.toContain('after:ring-2')
    expect(top).toContain('after:-bottom-px')
    expect(top).toContain('after:rounded-t-sm')
    expect(cellSelectionClass(true, true, false, { above: true, below: true })).toContain('after:rounded-none')
    expect(cellSelectionClass(false, true, false, { above: true, below: true })).toBe('')
  })

  it('joins a range in one column, and a divider between two cells breaks it', () => {
    render(<Harness columns={[column('x'), column('y')]} />)
    act(() =>
      api!.tableProps.cellSelection.select(
        [
          { rowId: 'a', col: 'x' },
          { rowId: 'b', col: 'x' },
          { rowId: 'c', col: 'x' },
          { rowId: 'a', col: 'y' },
        ],
        'replace',
      ),
    )
    // a·x and b·x are one ring: a keeps its top, b its bottom.
    expect(cellEl('a', 'x').className).toContain('after:rounded-t-sm')
    expect(cellEl('b', 'x').className).toContain('after:rounded-b-sm')
    // The marker between b and c breaks the ring, so c·x is a box of its own.
    expect(cellEl('c', 'x').className).toContain('after:ring-2')
    // a·y has no selected neighbour in its column — its row neighbour a·x does not join it.
    expect(cellEl('a', 'y').className).toContain('after:ring-2')
  })
})

describe('a joined ring over a blank cell', () => {
  it('keeps the edge towards a selected neighbour that draws no cell', () => {
    // b has nothing to set in x: a marquee sweeps it up, but it draws no ring to join.
    const blank = column('x', { value: (row) => (row.id === 'b' ? undefined : row.on) })
    render(<Harness columns={[blank]} />)
    act(() =>
      api!.tableProps.cellSelection.select(
        [
          { rowId: 'a', col: 'x' },
          { rowId: 'b', col: 'x' },
        ],
        'replace',
      ),
    )
    expect(cellEl('a', 'x').className).toContain('after:ring-2')
  })
})

describe('a divider row', () => {
  it('keeps its full-width rule where no column draws on it', () => {
    render(<Harness columns={[column('x')]} />)
    const divider = document.querySelector('[data-row-id="m"]')!
    expect(divider.textContent).toContain('Scene 2')
    expect(divider.querySelector('[data-divider-cell]')).toBeNull()
  })

  it('draws a cell per column where one does, so a run can pass through it', () => {
    render(<Harness columns={[column('x', { dividerCell: () => <i data-testid="through" /> }), column('y')]} />)
    const divider = document.querySelector('[data-row-id="m"]')!
    expect(divider.textContent).toContain('Scene 2')
    const cells = divider.querySelectorAll('[data-divider-cell]')
    expect([...cells].map((c) => c.getAttribute('data-divider-cell'))).toEqual(['x', 'y'])
    expect(cells[0].querySelector('[data-testid="through"]')).not.toBeNull()
    expect(cells[1].childElementCount).toBe(0)
    // Still no selectable row: no cell of it is a `data-cell`.
    expect(divider.querySelector('[data-cell]')).toBeNull()
  })
})

describe('header bands', () => {
  it('draws one band over consecutive columns of a group, and none for a sheet with no group', () => {
    const { unmount } = render(<Harness columns={[column('x'), column('y')]} />)
    expect(document.querySelector('[data-header-band]')).toBeNull()
    unmount()
    const looks = { key: 'looks', label: 'Looks · 2' }
    render(<Harness columns={[column('x', { group: looks }), column('y', { group: looks })]} />)
    const bands = document.querySelectorAll('[data-header-band]')
    expect(bands).toHaveLength(1)
    expect(bands[0].textContent).toBe('Looks · 2')
    expect((bands[0] as HTMLElement).style.gridColumn).toBe('span 2')
    // The band's rule runs down its first column, not its second.
    expect(cellEl('a', 'x').className).toContain('border-l')
    expect(cellEl('a', 'y').className).not.toContain('border-l')
  })
})
