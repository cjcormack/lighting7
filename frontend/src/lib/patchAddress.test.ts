import { describe, expect, it } from 'vitest'
import {
  checkLanding,
  consecutiveLanding,
  findOverlaps,
  universeFill,
  type AddressedHead,
} from './patchAddress'

const head = (id: number, channel: number, footprint = 6, universe = 1, name = `Head ${id}`): AddressedHead => ({
  id,
  name,
  universe,
  channel,
  footprint,
})

/**
 * The patch list's batch Set: N addresses land consecutively by footprint from the typed one. This
 * is the rule every desk surveyed has and the one a naive "set them all to 7" would break —
 * pinned at the arithmetic so the editor, the Spread panel and the write cannot each spell it differently.
 */
describe('consecutiveLanding', () => {
  it('lands each head after the previous one by its own footprint, in the order given', () => {
    const rows = [head(1, 1), head(2, 100), head(3, 200, 18), head(4, 300, 16)]
    expect([...consecutiveLanding(rows, 7)]).toEqual([
      [1, 7],
      [2, 13],
      [3, 19],
      [4, 37],
    ])
  })

  it('walks each universe from the same start, since a PUT cannot move a head across universes', () => {
    const rows = [head(1, 1, 6, 1), head(2, 1, 6, 2), head(3, 50, 6, 1)]
    expect([...consecutiveLanding(rows, 101)]).toEqual([
      [1, 101],
      [3, 107],
      [2, 101],
    ])
  })

  it('is empty for no heads', () => {
    expect(consecutiveLanding([], 1).size).toBe(0)
  })
})

describe('findOverlaps', () => {
  it('names the other head for both sides of an overlap on one universe', () => {
    const a = head(1, 1, 6)
    const b = head(2, 6, 6)
    const overlaps = findOverlaps([a, b, head(3, 40)])
    expect(overlaps.get(1)?.id).toBe(2)
    expect(overlaps.get(2)?.id).toBe(1)
    expect(overlaps.has(3)).toBe(false)
  })

  it('does not see a clash across universes, and adjacent runs are clear', () => {
    expect(findOverlaps([head(1, 1, 6, 1), head(2, 1, 6, 2)]).size).toBe(0)
    expect(findOverlaps([head(1, 1, 6), head(2, 7, 6)]).size).toBe(0)
  })
})

describe('checkLanding', () => {
  const rig = [head(1, 1, 6, 1, 'PAR 1'), head(2, 7, 6, 1, 'PAR 2'), head(3, 25, 18, 1, 'Bar SL')]

  it('reports a landing clear when the moved heads sit in free space', () => {
    const report = checkLanding(rig, consecutiveLanding([rig[0], rig[1]], 100))
    expect(report.error).toBeNull()
    expect(report.lines).toEqual(['PAR 1 → 1-100', 'PAR 2 → 1-106'])
  })

  it('lets a batch land on channels its own members are vacating', () => {
    // PAR 1 moves to 7 — where PAR 2 was — and PAR 2 moves on to 13. Nothing overlaps.
    expect(checkLanding(rig, consecutiveLanding([rig[0], rig[1]], 7)).error).toBeNull()
  })

  it('names the head an outsider collides with, and where it sits', () => {
    // PAR 1 → 19 (19–24), PAR 2 → 25 (25–30): straight onto Bar SL at 25–42.
    const report = checkLanding(rig, consecutiveLanding([rig[0], rig[1]], 19))
    expect(report.error).toBe('1-025 overlaps Bar SL (1-025 to 1-042)')
  })

  it('names an overflow past 512 before any overlap', () => {
    const report = checkLanding(rig, new Map([[3, 500]]))
    expect(report.error).toBe('Bar SL runs past channel 512 on universe 1')
  })

  it('catches two members of the batch landing on each other', () => {
    const report = checkLanding(rig, new Map([[1, 100], [2, 103]]))
    expect(report.error).toMatch(/1-100 overlaps PAR 2/)
  })
})

describe('universeFill', () => {
  it('counts occupied addresses on one universe, clipped at 512', () => {
    expect(universeFill([head(1, 1, 6), head(2, 7, 6), head(3, 1, 6, 2)], 1)).toBe(12)
    expect(universeFill([head(1, 510, 8)], 1)).toBe(3)
    expect(universeFill([], 1)).toBe(0)
  })
})
