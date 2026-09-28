import { describe, expect, it } from 'vitest'
import { arrowStepTarget } from './listSelectionModel'

/**
 * `arrowStepTarget` is the one rule every sheet's ↑ / ↓ steps by — the programmer's list and the
 * kit's sheets alike (CLAUDE.md §Sheet kit), so each case here is a behaviour both surfaces have.
 */
const order = ['a', 'b', 'c', 'd', 'e']

describe('arrowStepTarget', () => {
  it('answers null when there are no rows', () => {
    expect(arrowStepTarget([], { anchor: null, orderedSelected: [] }, 'down', false)).toBeNull()
  })

  it('lands on the first row for ↓ and the last for ↑ when nothing is selected', () => {
    const none = { anchor: null, orderedSelected: [] }
    expect(arrowStepTarget(order, none, 'down', false)).toBe('a')
    expect(arrowStepTarget(order, none, 'up', false)).toBe('e')
  })

  it('treats an anchor filtered out of view as no anchor', () => {
    const gone = { anchor: 'z', orderedSelected: [] }
    expect(arrowStepTarget(order, gone, 'down', false)).toBe('a')
    expect(arrowStepTarget(order, gone, 'up', false)).toBe('e')
  })

  it('steps one row from the anchor, clamped at both ends', () => {
    expect(arrowStepTarget(order, { anchor: 'c', orderedSelected: ['c'] }, 'down', false)).toBe('d')
    expect(arrowStepTarget(order, { anchor: 'c', orderedSelected: ['c'] }, 'up', false)).toBe('b')
    expect(arrowStepTarget(order, { anchor: 'e', orderedSelected: ['e'] }, 'down', false)).toBe('e')
    expect(arrowStepTarget(order, { anchor: 'a', orderedSelected: ['a'] }, 'up', false)).toBe('a')
  })

  it('steps a plain arrow from the anchor even with a range selected', () => {
    expect(arrowStepTarget(order, { anchor: 'b', orderedSelected: ['b', 'c', 'd'] }, 'down', false)).toBe('c')
  })

  it('extends from the moving edge — downward from the bottom, upward from the top', () => {
    expect(arrowStepTarget(order, { anchor: 'b', orderedSelected: ['b', 'c'] }, 'down', true)).toBe('d')
    // Extending up past two rows: the anchor is the bottom, so the moving edge is the top.
    expect(arrowStepTarget(order, { anchor: 'd', orderedSelected: ['c', 'd'] }, 'up', true)).toBe('b')
    // Shrinking a downward range steps its bottom edge back up.
    expect(arrowStepTarget(order, { anchor: 'b', orderedSelected: ['b', 'c', 'd'] }, 'up', true)).toBe('c')
  })
})
