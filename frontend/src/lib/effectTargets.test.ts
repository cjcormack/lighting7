import { describe, expect, it } from 'vitest'
import { makeActiveEffect, makeFixture, makePixelBar } from '@/test/fixtureFactories'
import {
  effectAimKeys,
  effectNames,
  effectTargetLabel,
  parseSelectionKey,
  selectionFixtures,
  selectionUnits,
  storedEffectTargets,
} from './effectTargets'

const selection = (targets: { type: 'fixture' | 'group'; key: string }[]) =>
  makeActiveEffect({ targetKey: JSON.stringify(targets.map((t) => ({ key: t.key, type: t.type }))), targetType: 'selection', targets })

describe('an effect over a selection (effects plan D3)', () => {
  it('reads a stored selection key, the canonical form the desk writes', () => {
    // `EffectTargetRef.Selection.key`: keys alphabetical, no whitespace, in selection order.
    const key = '[{"key":"par-1","type":"fixture"},{"key":"Front wash","type":"group"}]'
    expect(parseSelectionKey(key)).toEqual([
      { type: 'fixture', key: 'par-1' },
      { type: 'group', key: 'Front wash' },
    ])
    for (const bad of ['', 'not json', '{}', '[{"key":"x"}]', '[{"key":"x","type":"selection"}]']) {
      expect(parseSelectionKey(bad)).toEqual([])
    }
    expect(storedEffectTargets('selection', key)).toHaveLength(2)
    expect(storedEffectTargets('group', 'Front wash')).toEqual([{ type: 'group', key: 'Front wash' }])
    expect(storedEffectTargets('deferred', '')).toEqual([])
  })

  it('names what its items name, and nothing else', () => {
    const effect = selection([{ type: 'fixture', key: 'par-1' }, { type: 'group', key: 'Front wash' }])
    expect(effectNames(effect, new Set(['par-1']), new Set())).toBe(true)
    expect(effectNames(effect, new Set(), new Set(['Front wash']))).toBe(true)
    expect(effectNames(effect, new Set(['par-2']), new Set(['Back wash']))).toBe(false)
    // The canonical key names neither a fixture nor a group, however it is spelled.
    expect(effectNames(effect, new Set([effect.targetKey]), new Set([effect.targetKey]))).toBe(false)
  })

  it('aims at its items flattened in order, each head once', () => {
    const effect = selection([
      { type: 'fixture', key: 'par-3' },
      { type: 'group', key: 'quad' },
      { type: 'fixture', key: 'g-1' },
    ])
    expect(effectAimKeys(effect, () => ['g-0', 'g-1', 'g-2'])).toEqual(['par-3', 'g-0', 'g-1', 'g-2'])
    expect(effectAimKeys(makeActiveEffect({ targetKey: 'quad', isGroupTarget: true }), () => ['g-0'])).toEqual(['g-0'])
  })

  it('is labelled by its first two items and how many more', () => {
    const three = selection([
      { type: 'group', key: 'Front wash' },
      { type: 'fixture', key: 'par-1' },
      { type: 'fixture', key: 'par-2' },
    ])
    expect(effectTargetLabel(three)).toBe('Front wash, par-1 +1')
    expect(effectTargetLabel(makeActiveEffect({ targetKey: 'hex-1' }))).toBe('hex-1')
  })

  it('walks the fixtures and cells the desk walks', () => {
    const par = makeFixture('par-1', [], { groups: ['wash'] })
    const bar = makePixelBar('bar-1', 4, [], { groups: ['wash'] })
    const items = [
      { type: 'fixture' as const, key: 'bar-1.pixel-2' },
      { type: 'group' as const, key: 'wash' },
    ]
    expect(selectionUnits(items, [par, bar]).map((u) => u.key)).toEqual(['bar-1.pixel-2', 'par-1', 'bar-1'])
    expect(selectionFixtures(items, [par, bar]).map((f) => f.key)).toEqual(['bar-1', 'par-1'])
  })
})
