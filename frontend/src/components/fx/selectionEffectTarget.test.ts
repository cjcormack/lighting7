// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { groupSummary, makeFixture, makePixelBar } from '@/test/fixtureFactories'
import { fxCreateAddition } from '@/lib/fxCreateRequest'
import { effectTargetOf } from './selectionEffectTarget'

describe('starting one', () => {
  const par1 = makeFixture('par-1', [])
  const par2 = makeFixture('par-2', [])
  const bar = makePixelBar('bar-1', 2)
  const wash = groupSummary('wash', 2)

  it('is that fixture, cell or group alone for one row, and one selection for several', () => {
    expect(effectTargetOf([{ type: 'fixture', key: 'par-1' }], [par1], [wash])).toEqual({ type: 'fixture', fixture: par1 })
    expect(effectTargetOf([{ type: 'group', key: 'wash' }], [par1], [wash])).toEqual({ type: 'group', group: wash })
    const cell = effectTargetOf([{ type: 'fixture', key: 'bar-1.pixel-1' }], [bar], [])
    expect(cell?.type === 'fixture' && cell.fixture.key).toBe('bar-1.pixel-1')

    const several = effectTargetOf(
      [{ type: 'fixture', key: 'par-2' }, { type: 'group', key: 'wash' }, { type: 'fixture', key: 'par-1' }],
      [par1, par2],
      [wash],
    )
    expect(several?.type).toBe('selection')
    if (several?.type !== 'selection') return
    expect(several.targets.map((t) => t.key)).toEqual(['par-2', 'wash', 'par-1'])
    expect(several.label).toBe('par-2, wash +1')

    expect(effectTargetOf([], [par1], [wash])).toBeNull()
  })

  it('sends a selection to the fixture route as targets, in order', () => {
    const targets = [{ type: 'fixture' as const, key: 'par-2' }, { type: 'group' as const, key: 'wash' }]
    const addition = fxCreateAddition(
      { type: 'selection', targets },
      { effectType: 'Pulse', propertyName: 'dimmer', beatDivision: 4, blendMode: 'OVERRIDE', phaseOffset: 0, parameters: {} },
    )
    expect(addition.kind).toBe('fixture')
    expect(addition.payload).toMatchObject({ targets })
    expect(addition.payload).not.toHaveProperty('fixtureKey')
  })
})
