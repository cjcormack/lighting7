import { describe, expect, it } from 'vitest'
import { columnRange, rectFrom, rowIndexRange } from './cellMarquee'
import type { ColumnBand } from './cellMarquee'
import type { ColumnKey } from '../fixtures-list/columns'

const GEO = { scrollTop: 0, headerHeight: 32, rowHeight: 36, rowCount: 10 }

describe('rectFrom', () => {
  it('normalises an up-left drag', () => {
    // The operator drags in whichever direction is convenient; the rectangle is the same either way.
    expect(rectFrom({ x: 200, y: 300 }, { x: 50, y: 100 })).toEqual({
      left: 50,
      right: 200,
      top: 100,
      bottom: 300,
    })
  })
})

describe('rowIndexRange', () => {
  it('maps client y to row indices past the sticky header', () => {
    // Row 0 spans content 0-36, i.e. client 32-68 with the header above it.
    expect(rowIndexRange({ top: 33, bottom: 67 }, GEO)).toEqual([0, 0])
    // 140 is client for content 108, exactly row 3's top edge — zero pixels of it are covered, so
    // it is not selected. Same strictness as `columnRange`.
    expect(rowIndexRange({ top: 33, bottom: 140 }, GEO)).toEqual([0, 2])
    expect(rowIndexRange({ top: 33, bottom: 141 }, GEO)).toEqual([0, 3])
  })

  it('accounts for scroll — the rows it selects have no DOM node', () => {
    // The point of doing this arithmetically: at this offset rows 0-2 are not rendered at all, so
    // any hit-test against elements would miss them.
    expect(
      rowIndexRange({ top: 33, bottom: 67 }, { ...GEO, scrollTop: 360, rowCount: 20 }),
    ).toEqual([10, 10])
  })

  it('clamps to the ends rather than running past them', () => {
    expect(rowIndexRange({ top: 33, bottom: 5000 }, GEO)).toEqual([0, 9])
  })

  it('selects nothing for a drag entirely outside the rows', () => {
    // Started in the header, or in the empty space under a short list. Clamping onto an edge row
    // would select a row the operator never touched.
    expect(rowIndexRange({ top: 0, bottom: 20 }, GEO)).toBeNull()
    expect(rowIndexRange({ top: 3000, bottom: 3200 }, GEO)).toBeNull()
    expect(rowIndexRange({ top: 33, bottom: 67 }, { ...GEO, rowCount: 0 })).toBeNull()
  })

  it('handles a zero-height drag as the one row under the pointer', () => {
    expect(rowIndexRange({ top: 50, bottom: 50 }, GEO)).toEqual([0, 0])
  })
})

describe('columnRange', () => {
  const bands: ColumnBand[] = [
    { col: 'dimmer' as ColumnKey, left: 260, right: 360 },
    { col: 'colour' as ColumnKey, left: 360, right: 460 },
    { col: 'position' as ColumnKey, left: 460, right: 560 },
  ]

  it('takes every column the rectangle overlaps, even partly', () => {
    expect(columnRange({ left: 340, right: 470 }, bands)).toEqual(['dimmer', 'colour', 'position'])
  })

  it('ignores a band it only grazes at the edge', () => {
    // A drag starting exactly on a boundary should pick one column, not two.
    expect(columnRange({ left: 360, right: 460 }, bands)).toEqual(['colour'])
  })

  it('is empty over the sticky name column', () => {
    // Left of the first band: the name cell owns row selection and must keep it.
    expect(columnRange({ left: 10, right: 200 }, bands)).toEqual([])
  })
})
