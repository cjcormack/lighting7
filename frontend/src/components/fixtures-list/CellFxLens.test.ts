// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { chan, makeActiveEffect as effect, makeFixture, sliderProp } from '@/test/fixtureFactories'
import { buildCellEffectIndex, createCellEffectStore, useCellEffects } from './cellEffects'
import { lensDivision, lensHeldReason, type LensDesk } from './CellFxLens'

vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

function desk(over: Partial<LensDesk> = {}): LensDesk {
  return { blind: false, holds: () => false, source: () => 'EFFECT', stackSize: () => 1, ...over }
}

/**
 * Why the lens strikes a chip through (tables-colours plan D3): the desk's three reasons. Blind and
 * *your value* are `lib/heldBack.ts`'s rules; a stomp is read off provenance, which names no effect
 * on a key whose every painter the desk skipped — and only for an effect that could be painting.
 */
describe('lensHeldReason', () => {
  const cue = effect({ id: 1, programmerOwned: false, cueId: 4, cueStackId: 2 })
  const busk = effect({ id: 2, programmerOwned: true })

  it('is Blind for the programmer’s own effects while blind, and nothing else is held by your value then', () => {
    const d = desk({ blind: true, holds: () => true })
    expect(lensHeldReason(busk, ['a', 'b'], d)).toEqual({ reason: 'BLIND', on: 2 })
    expect(lensHeldReason(cue, ['a'], { ...d, source: () => 'EFFECT' })).toBeNull()
  })

  it('is your value where the programmer holds an entry on the effect’s own key, counting the heads', () => {
    const d = desk({ holds: (head) => head === 'b' })
    expect(lensHeldReason(cue, ['a', 'b'], d)).toEqual({ reason: 'PROGRAMMER', on: 1 })
    // The programmer's own band modulates on top of its values: never held back by them.
    expect(lensHeldReason(busk, ['a', 'b'], d)).toBeNull()
  })

  it('is a stomp where provenance gives the key to a cue under a running, weighted effect', () => {
    expect(lensHeldReason(cue, ['a'], desk({ source: () => 'CUE' }))).toEqual({ reason: 'STOMP', on: 1 })
    expect(lensHeldReason(cue, ['a'], desk({ source: () => 'EFFECT' }))).toBeNull()
  })

  it('never calls an effect stomped that could not be painting anyway', () => {
    const d = desk({ source: () => 'CUE' })
    expect(lensHeldReason({ ...cue, isRunning: false }, ['a'], d)).toBeNull()
    expect(lensHeldReason({ ...cue, spent: true }, ['a'], d)).toBeNull()
    expect(lensHeldReason({ ...cue, leaving: true }, ['a'], d)).toBeNull()
    expect(lensHeldReason({ ...cue, size: 0 }, ['a'], d)).toBeNull()
    expect(lensHeldReason({ ...cue, intensityMultiplier: 0 }, ['a'], d)).toBeNull()
    // Its stack at FX size 0 weighs it to nothing: the desk skips it for that, not for a stomp.
    expect(lensHeldReason(cue, ['a'], { ...d, stackSize: () => 0 })).toBeNull()
  })
})

describe('lensDivision', () => {
  it('says a fraction of a beat as its glyph, and every other division as its label', () => {
    expect(lensDivision(effect({ beatDivision: 0.5 }))).toBe('½')
    expect(lensDivision(effect({ beatDivision: 0.25 }))).toBe('¼')
    expect(lensDivision(effect({ beatDivision: 4 }))).toBe('1 bar')
    expect(lensDivision(effect({ beatDivision: 2 }))).toBe('2 beats')
    expect(lensDivision(effect({ beatDivision: 4, timingSource: 'WALL_CLOCK' }))).toBe('4 s')
    expect(lensDivision(effect({ beatDivision: 0.5, seconds: true }))).toBe('0.5 s')
  })
})

/**
 * A cell keeps the array it read while nothing the chip draws has changed, so a refetch that moves
 * every instance's phase re-renders no lensed cell.
 */
describe('useCellEffects', () => {
  const keys = [{ targetKey: 'a', propertyName: 'dimmer' }]
  const fixtures = [makeFixture('a', [sliderProp('dimmer', 'dimmer', chan(1))])]

  it('keeps a cell’s array across a refetch that moves only the phase, and drops it when the chip would change', () => {
    const store = createCellEffectStore()
    store.set(buildCellEffectIndex([effect({ id: 1, targetKey: 'a', currentPhase: 0.1 })], fixtures))
    const { result } = renderHook(() => useCellEffects(store, keys))
    const first = result.current
    act(() => store.set(buildCellEffectIndex([effect({ id: 1, targetKey: 'a', currentPhase: 0.6 })], fixtures)))
    expect(result.current).toBe(first)
    act(() => store.set(buildCellEffectIndex([effect({ id: 1, targetKey: 'a', isRunning: false })], fixtures)))
    expect(result.current).not.toBe(first)
  })
})
