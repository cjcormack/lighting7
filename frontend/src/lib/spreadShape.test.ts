import { describe, expect, it } from 'vitest'
import vector from '../../../src/test/resources/fx/spreadShape.fixture.json'
import {
  DEFAULT_SPREAD_SHAPE,
  isMirroredOrder,
  orderRanks,
  spreadOffsets,
  spreadShapeOf,
  spreadSummary,
} from './spreadShape'

/**
 * The dial preview's copy of the spread's shape against the desk's (effects plan P2). The vector is
 * `src/test/resources/fx/spreadShape.fixture.json`, read here and by lighting7's `SpreadShapeTest`, so
 * a dial drawn here is the offset the desk runs. Change the rule, the vector and both pins together.
 */
describe('spreadOffsets', () => {
  it('matches the shared vector', () => {
    expect(vector.cases.length).toBeGreaterThanOrEqual(20)
    for (const c of vector.cases as Array<{
      name: string
      order: string
      heads: number
      spread?: number
      wings?: number
      blocks?: number
      groups?: number
      offsets: number[]
      slots: number
    }>) {
      const { offsets, slots } = spreadOffsets(c.order, c.heads, spreadShapeOf(c))
      expect(slots, `${c.name}: slots`).toBe(c.slots)
      expect(offsets.length, c.name).toBe(c.offsets.length)
      c.offsets.forEach((expected, i) => {
        expect(Math.abs(offsets[i] - expected), `${c.name}: head ${i} is at ${offsets[i]}, not ${expected}`).toBeLessThan(1e-9)
      })
    }
  })

  it('gives blocks of 3 over 12 heads 4 slots', () => {
    const { offsets, slots } = spreadOffsets('LINEAR', 12, { ...DEFAULT_SPREAD_SHAPE, blocks: 3 })
    expect(slots).toBe(4)
    expect([...new Set(offsets)]).toEqual([0, 0.25, 0.5, 0.75])
  })

  it('runs a mirrored order unwinged', () => {
    for (const order of ['CENTER_OUT', 'EDGES_IN', 'SPLIT']) {
      expect(isMirroredOrder(order)).toBe(true)
      expect(spreadOffsets(order, 8, { ...DEFAULT_SPREAD_SHAPE, wings: 2 })).toEqual(
        spreadOffsets(order, 8, DEFAULT_SPREAD_SHAPE),
      )
    }
    expect(isMirroredOrder('LINEAR')).toBe(false)
  })

  it('draws nothing for no heads', () => {
    expect(spreadOffsets('LINEAR', 0, DEFAULT_SPREAD_SHAPE)).toEqual({ offsets: [], slots: 0 })
    expect(orderRanks('RANDOM', 0).ranks).toEqual([])
  })
})

describe('spreadSummary', () => {
  it('says the order and every non-default field', () => {
    expect(spreadSummary('LINEAR', DEFAULT_SPREAD_SHAPE)).toEqual(['Rig'])
    expect(spreadSummary('LINEAR', { spread: 0.5, wings: 2, blocks: 1, groups: 0 })).toEqual(['Rig', '50 %', '2 wings'])
    expect(spreadSummary('REVERSE', { spread: 1, wings: 1, blocks: 3, groups: 2 })).toEqual([
      'Reverse',
      'blocks of 3',
      'groups of 2',
    ])
  })

  it('leaves wings under a mirrored order unsaid', () => {
    expect(spreadSummary('CENTER_OUT', { spread: 1, wings: 2, blocks: 1, groups: 0 })).toEqual(['Centre out'])
  })
})
