import { describe, expect, it } from 'vitest'
import type { LookSummary } from '@/api/looksApi'
import type { TemplateSummary } from '@/api/templatesApi'
import { describeLookContents, describeTemplate } from './padFace'

function look(over: Partial<LookSummary>): LookSummary {
  return {
    id: 1, uuid: 'l1', name: 'L', notes: null, families: [], rowCount: 0, effectCount: 0, targetCount: 0,
    hasDeferredEffects: false, preview: [], layerCount: 0, buskPageCount: 0, ...over,
  }
}

describe('describeLookContents (pad face)', () => {
  it('counts effects and values, and says empty when there are none', () => {
    expect(describeLookContents(look({ effectCount: 2, rowCount: 3 }))).toBe('2 effects · 3 values')
    expect(describeLookContents(look({}))).toBe('empty')
  })

  it('counts scenery, so a Look that only flies the moon is not an empty pad (scenery-programmer D10)', () => {
    const moon = { elementUuid: 'moon', elementName: 'Moon', state: { trimM: 3 } }
    expect(describeLookContents(look({ scenery: [moon] }))).toBe('1 scenery')
    expect(describeLookContents(look({ rowCount: 1, scenery: [moon, { ...moon, elementUuid: 'tabs' }] }))).toBe('1 value · 2 scenery')
  })
})

describe('describeTemplate — around (fixture-fx-sheets plan D16)', () => {
  const circle = (blendMode: string, centre: string): TemplateSummary =>
    ({
      id: 1,
      uuid: 'u',
      name: 'Big circle',
      kind: 'effect',
      family: 'POSITION',
      isGeneric: true,
      effect: {
        effectType: 'Circle',
        category: 'position',
        beatDivision: 0.5,
        blendMode,
        distribution: 'LINEAR',
        parameters: { panCenter: centre, tiltCenter: centre, panRadius: '64', tiltRadius: '64' },
        timingSource: 'BEAT',
      },
    }) as unknown as TemplateSummary

  it('says around for a movement template spelled Around, and nothing for an Absolute one', () => {
    expect(describeTemplate(circle('ADDITIVE', '128'))).toMatch(/· around$/)
    // A stored Override movement template reads as Absolute (call 6): no word.
    expect(describeTemplate(circle('OVERRIDE', '128'))).not.toMatch(/around/)
    // Additive with a centre of its own is neither question's answer.
    expect(describeTemplate(circle('ADDITIVE', '90'))).not.toMatch(/around/)
  })
})
