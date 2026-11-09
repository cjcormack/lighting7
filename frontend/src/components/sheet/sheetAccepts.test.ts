import { describe, expect, it, vi } from 'vitest'
import { acceptsValue, commitToSelectedCells, type SheetColumn, type SheetRow } from './sheetModel'

/** `SheetColumn.accepts`: a commit over mixed rows skips by value, and names what it skipped. */

interface Row extends SheetRow {
  takes: string[]
}

const rows: Row[] = [
  { id: 'a', takes: ['group'] },
  { id: 'b', takes: ['stack'] },
  { id: 'c', takes: ['group', 'stack'] },
]

describe('accepts', () => {
  it('drops the rows a column refuses this value before it writes, and hands them to refused', () => {
    const write = vi.fn(() => true)
    const refused = vi.fn()
    const column: SheetColumn<Row, 'on'> = {
      key: 'on',
      label: 'On',
      width: '1fr',
      value: () => '',
      accepts: (row, value) => row.takes.includes(value as string),
      write,
      refused,
    }
    expect(commitToSelectedCells([{ col: 'on', rows }], [column], 'on', 'group')).toBe(1)
    expect(write).toHaveBeenCalledWith([rows[0], rows[2]], 'group')
    expect(refused).toHaveBeenCalledWith([rows[1]], 'group')
    expect(acceptsValue(column, rows[1], 'stack')).toBe(true)
    expect(acceptsValue(column, rows[0], 'stack')).toBe(false)
  })

  it('takes every row a column with no accepts takes, as before', () => {
    const write = vi.fn(() => true)
    const column: SheetColumn<Row, 'on'> = { key: 'on', label: 'On', width: '1fr', value: () => '', write }
    commitToSelectedCells([{ col: 'on', rows }], [column], 'on', 'x')
    expect(write).toHaveBeenCalledWith(rows, 'x')
  })
})
