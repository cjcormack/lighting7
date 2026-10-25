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
      segments?: number
      parts?: number
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

  it('gives segments of 3 over 12 heads 4 slots', () => {
    const { offsets, slots } = spreadOffsets('NORMAL', 12, { ...DEFAULT_SPREAD_SHAPE, segments: 3 })
    expect(slots).toBe(4)
    expect([...new Set(offsets)]).toEqual([0, 0.25, 0.5, 0.75])
  })

  it('runs a mirrored order unwinged', () => {
    for (const order of ['CENTRE_OUT', 'CENTRE_IN']) {
      expect(isMirroredOrder(order)).toBe(true)
      expect(spreadOffsets(order, 8, { ...DEFAULT_SPREAD_SHAPE, wings: 2 })).toEqual(
        spreadOffsets(order, 8, DEFAULT_SPREAD_SHAPE),
      )
    }
    expect(isMirroredOrder('NORMAL')).toBe(false)
  })

  it('draws nothing for no heads', () => {
    expect(spreadOffsets('NORMAL', 0, DEFAULT_SPREAD_SHAPE)).toEqual({ offsets: [], slots: 0 })
    expect(orderRanks('RANDOM', 0).ranks).toEqual([])
  })
})

describe('spreadSummary', () => {
  it('says every non-default field, the order apart', () => {
    expect(spreadSummary('NORMAL', DEFAULT_SPREAD_SHAPE)).toEqual([])
    expect(spreadSummary('NORMAL', { spread: 0.5, wings: 2, segments: 1, parts: 0 })).toEqual(['50 %', '2 wings'])
    expect(spreadSummary('REVERSE', { spread: 1, wings: 1, segments: 3, parts: 2 })).toEqual([
      'segments of 3',
      'parts of 2',
    ])
  })

  it('leaves wings under a mirrored order unsaid', () => {
    expect(spreadSummary('CENTRE_OUT', { spread: 1, wings: 2, segments: 1, parts: 0 })).toEqual([])
  })
})
