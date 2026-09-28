import { describe, expect, it } from 'vitest'
import { cellEffectKey, effectsToStop, isLocalEffect, partialSweepMessage } from './cellEffects'
import { makeActiveEffect as effect } from '@/test/fixtureFactories'


const cleared = (...pairs: [string, string][]) =>
  new Set(pairs.map(([key, prop]) => cellEffectKey(key, prop)))

const noMembers = () => []

describe('isLocalEffect', () => {
  it('takes a busked programmer-band effect', () => {
    expect(isLocalEffect(effect({}))).toBe(true)
  })

  it('leaves anything with an owner alone', () => {
    expect(isLocalEffect(effect({ programmerOwned: false }))).toBe(false)
    expect(isLocalEffect(effect({ lookId: 3 }))).toBe(false)
    expect(isLocalEffect(effect({ templateId: 3 }))).toBe(false)
    expect(isLocalEffect(effect({ programmerLayerId: 3 }))).toBe(false)
    expect(isLocalEffect(effect({ cueId: 3 }))).toBe(false)
  })
})

describe('effectsToStop', () => {
  it('stops one instance per cleared cell — the effect-template press this exists for', () => {
    const effects = [
      effect({ id: 1, targetKey: 'hex-1', propertyName: 'rgbColour' }),
      effect({ id: 2, targetKey: 'hex-2', propertyName: 'rgbColour' }),
      effect({ id: 3, targetKey: 'hex-3', propertyName: 'rgbColour' }),
    ]
    const { stop, partial } = effectsToStop(
      effects,
      noMembers,
      cleared(['hex-1', 'rgbColour'], ['hex-2', 'rgbColour'], ['hex-3', 'rgbColour']),
    )
    expect(stop.map((e) => e.id)).toEqual([1, 2, 3])
    expect(partial).toEqual([])
  })

  it('matches on the property as well as the head', () => {
    const effects = [effect({ id: 1, targetKey: 'hex-1', propertyName: 'position' })]
    expect(effectsToStop(effects, noMembers, cleared(['hex-1', 'dimmer'])).stop).toEqual([])
  })

  it('leaves a Look layer’s effect running even where the cells are cleared', () => {
    const effects = [effect({ id: 9, lookId: 2, programmerLayerId: 4 })]
    const { stop, partial } = effectsToStop(effects, noMembers, cleared(['hex-1', 'dimmer']))
    expect(stop).toEqual([])
    expect(partial).toEqual([])
  })

  it('stops a group effect only when the clear covers every member', () => {
    const members = (name: string) => (name === 'wash' ? ['hex-1', 'hex-2'] : [])
    const effects = [effect({ id: 5, targetKey: 'wash', isGroupTarget: true })]

    const half = effectsToStop(effects, members, cleared(['hex-1', 'dimmer']))
    expect(half.stop).toEqual([])
    expect(half.partial.map((e) => e.id)).toEqual([5])

    const whole = effectsToStop(
      effects,
      members,
      cleared(['hex-1', 'dimmer'], ['hex-2', 'dimmer']),
    )
    expect(whole.stop.map((e) => e.id)).toEqual([5])
    expect(whole.partial).toEqual([])
  })

  it('never sweeps a group with no members — "all of nothing" is not coverage', () => {
    const effects = [effect({ id: 5, targetKey: 'empty', isGroupTarget: true })]
    expect(effectsToStop(effects, noMembers, cleared(['hex-1', 'dimmer'])).stop).toEqual([])
  })

  it('does nothing for an empty clear', () => {
    expect(effectsToStop([effect({})], noMembers, new Set()).stop).toEqual([])
  })
})

describe('partialSweepMessage', () => {
  it('reads as one sentence in both numbers', () => {
    expect(partialSweepMessage([effect({})])).toContain('1 effect left running')
    expect(partialSweepMessage([effect({})])).toContain('it covers')
    expect(partialSweepMessage([effect({ id: 1 }), effect({ id: 2 })])).toContain(
      '2 effects left running',
    )
    expect(partialSweepMessage([effect({ id: 1 }), effect({ id: 2 })])).toContain('they cover')
  })
})
