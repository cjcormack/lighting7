import { describe, expect, it } from 'vitest'
import vector from '../../../src/test/resources/fx/spreadShape.fixture.json'
import {
  DEFAULT_SPREAD_SHAPE,
  isMirroredOrder,
  newFxSpread,
  orderRanks,
  stepCountOf,
  spreadOffsets,
  spreadShapeOf,
  spreadSummary,
} from './spreadShape'
import { ELEMENT_MODE_OPTIONS } from '@/components/fx/fxConstants'
import { spreadHeadCount } from '@/components/fx/fxEditorModel'

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
      spreadUnit?: string
      steps?: number
      offsets: number[]
      slots: number
    }>) {
      const shape = spreadShapeOf({ ...c, spreadUnit: c.spreadUnit === 'STEP' ? 'STEP' : 'CYCLE' })
      const { offsets, slots } = spreadOffsets(c.order, c.heads, shape, c.steps ?? null)
      expect(slots, `${c.name}: slots`).toBe(c.slots)
      expect(offsets.length, c.name).toBe(c.offsets.length)
      c.offsets.forEach((expected, i) => {
        expect(Math.abs(offsets[i] - expected), `${c.name}: head ${i} is at ${offsets[i]}, not ${expected}`).toBeLessThan(1e-9)
      })
    }
  })

  it('counts the units each element mode spreads over as the desk does', () => {
    const cases = (vector.cases as Array<{ name: string; heads: number; over?: { mode: string; fixtures: number; elementsEach: number } }>).filter(
      (c) => c.over != null,
    )
    expect(new Set(cases.map((c) => c.over!.mode))).toEqual(new Set(ELEMENT_MODE_OPTIONS.map((o) => o.value)))
    for (const c of cases) {
      const { mode, fixtures, elementsEach } = c.over!
      const rig = Array.from({ length: fixtures }, () => ({ attributes: [], elements: Array.from({ length: elementsEach }, () => ({})) }))
      expect(spreadHeadCount(rig, true, 'rgbColour', mode, 'ALL'), c.name).toBe(c.heads)
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

describe('a spread in steps', () => {
  it('has cases in the shared vector after Segments, Wings and Parts and above 200 %', () => {
    const steps = (vector.cases as Array<{ name: string; spreadUnit?: string; segments?: number; wings?: number; parts?: number; offsets: number[] }>).filter(
      (c) => c.spreadUnit === 'STEP',
    )
    expect(steps.some((c) => (c.segments ?? 1) > 1)).toBe(true)
    expect(steps.some((c) => (c.wings ?? 1) > 1)).toBe(true)
    expect(steps.some((c) => (c.parts ?? 0) > 0)).toBe(true)
    expect(steps.some((c) => Math.max(...c.offsets) > 2)).toBe(true)
  })

  it('runs as the cycle with no step count, as the desk does', () => {
    const step = { ...DEFAULT_SPREAD_SHAPE, spreadUnit: 'STEP' as const }
    expect(spreadOffsets('NORMAL', 4, step, null)).toEqual(spreadOffsets('NORMAL', 4, DEFAULT_SPREAD_SHAPE))
    expect(spreadOffsets('NORMAL', 4, step, 3).offsets).toEqual([0, 1 / 3, 2 / 3, 1])
  })

  it('counts an FX\'s steps from the list it names', () => {
    const cycle = {
      stepsFrom: 'colours',
      parameters: [{ name: 'colours', type: 'colourList', defaultValue: '#ff0000,#00ff00,#0000ff' }],
    }
    expect(stepCountOf(cycle, {})).toBe(3)
    expect(stepCountOf(cycle, { colours: '#ff0000, ,pal:abc,#0000ff' })).toBe(2)
    expect(stepCountOf(cycle, { colours: ' ' }), 'a blank list is the default, as the desk reads one').toBe(3)
    const palettes = { stepsFrom: 'palettes', parameters: [{ name: 'palettes', type: 'paletteList', defaultValue: '' }] }
    expect(stepCountOf(palettes, { palettes: 'pal:a,pal:b' })).toBe(2)
    expect(stepCountOf({ parameters: [] }, {})).toBeNull()
  })

  it('starts a new step FX one step behind and anything else as it was', () => {
    expect(newFxSpread('colours')).toEqual({ spread: 1, spreadUnit: 'STEP' })
    expect(newFxSpread(undefined)).toEqual({})
  })

  it('says the spread in steps', () => {
    expect(spreadSummary('NORMAL', { ...DEFAULT_SPREAD_SHAPE, spreadUnit: 'STEP' })).toEqual(['1 step'])
    expect(spreadSummary('NORMAL', { ...DEFAULT_SPREAD_SHAPE, spread: 2, spreadUnit: 'STEP' })).toEqual(['2 steps'])
  })
})

describe('spreadSummary', () => {
  it('says every non-default field, the order apart', () => {
    expect(spreadSummary('NORMAL', DEFAULT_SPREAD_SHAPE)).toEqual([])
    expect(spreadSummary('NORMAL', { ...DEFAULT_SPREAD_SHAPE, spread: 0.5, wings: 2 })).toEqual(['50 %', '2 wings'])
    expect(spreadSummary('REVERSE', { ...DEFAULT_SPREAD_SHAPE, segments: 3, parts: 2 })).toEqual([
      'segments of 3',
      'parts of 2',
    ])
  })

  it('leaves wings under a mirrored order unsaid', () => {
    expect(spreadSummary('CENTRE_OUT', { ...DEFAULT_SPREAD_SHAPE, wings: 2 })).toEqual([])
  })
})
