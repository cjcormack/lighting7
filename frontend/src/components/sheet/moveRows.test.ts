import { describe, expect, it } from 'vitest'
import { dragGroupOf, moveRows } from './sheetModel'

/**
 * Where a row drag lands (`rowDrag.group`, show-view-fixes plan D23): the group together, in its
 * order, before the row dropped on moving up and after it moving down.
 */
const ROWS = ['a', 'b', 'c', 'd', 'e', 'f', 'g']

/** The single-row splice, which `moveRows` is for a group of one. */
function splice(ids: readonly string[], from: string, over: string): string[] {
  const next = [...ids]
  next.splice(ids.indexOf(over), 0, ...next.splice(ids.indexOf(from), 1))
  return next
}

describe('moveRows', () => {
  it('moves a single row as a splice does, both ways', () => {
    for (const from of ROWS) {
      for (const over of ROWS) {
        if (from === over) continue
        expect(moveRows(ROWS, [from], from, over), `${from} over ${over}`).toEqual(splice(ROWS, from, over))
      }
    }
  })

  it('moves a group down, landing after the row dropped on', () => {
    expect(moveRows(ROWS, ['b', 'c', 'd'], 'c', 'f')).toEqual(['a', 'e', 'f', 'b', 'c', 'd', 'g'])
  })

  it('moves a group up, landing before the row dropped on', () => {
    expect(moveRows(ROWS, ['d', 'e'], 'e', 'b')).toEqual(['a', 'd', 'e', 'b', 'c', 'f', 'g'])
  })

  it('closes up a group with gaps, in row order whatever order it was given in', () => {
    expect(moveRows(ROWS, ['f', 'b', 'd'], 'b', 'e')).toEqual(['a', 'c', 'e', 'b', 'd', 'f', 'g'])
    expect(moveRows(ROWS, ['b', 'd', 'f'], 'f', 'c')).toEqual(['a', 'b', 'd', 'f', 'c', 'e', 'g'])
  })

  it('reads up and down from the dragged row, so a drop between the group’s rows can go either way', () => {
    // b and f selected, dropped on d: from b that is down, from f up.
    expect(moveRows(ROWS, ['b', 'f'], 'b', 'd')).toEqual(['a', 'c', 'd', 'b', 'f', 'e', 'g'])
    expect(moveRows(ROWS, ['b', 'f'], 'f', 'd')).toEqual(['a', 'c', 'b', 'f', 'd', 'e', 'g'])
  })

  it('changes nothing for a drop on a row of the group', () => {
    expect(moveRows(ROWS, ['b', 'c', 'd'], 'b', 'd')).toBeNull()
    expect(moveRows(ROWS, ['b'], 'b', 'b')).toBeNull()
  })

  it('carries a separator in the group like any row', () => {
    const rows = ['q1', 'sep', 'q2', 'q3', 'q4']
    expect(moveRows(rows, ['sep', 'q2'], 'q2', 'q4')).toEqual(['q1', 'q3', 'q4', 'sep', 'q2'])
  })

  it('adds the dragged row to a group that left it out, and ignores ids the order lacks', () => {
    expect(moveRows(ROWS, ['b', 'zz'], 'c', 'e')).toEqual(['a', 'd', 'e', 'b', 'c', 'f', 'g'])
    expect(moveRows(ROWS, ['b'], 'b', 'zz')).toBeNull()
    expect(moveRows(ROWS, ['b'], 'zz', 'c')).toBeNull()
  })
})

describe('dragGroupOf', () => {
  const selected = [{ id: 'b' }, { id: 'c' }, { id: 'e' }]

  it('is the selection when the dragged row is in it, else the row alone', () => {
    expect(dragGroupOf(selected, 'c')).toEqual(['b', 'c', 'e'])
    expect(dragGroupOf(selected, 'd')).toEqual(['d'])
    expect(dragGroupOf([], 'a')).toEqual(['a'])
  })
})
