import { describe, expect, it } from 'vitest'
import type { PaletteSummary } from '@/api/palettesApi'
import type { SettingAttributeDescriptor } from '@/store/fixtures'
import {
  categoryForFamily,
  isPaletteSteps,
  drivenCategoryOf,
  isSlotSteps,
  normaliseSlotName,
  paletteStepsAttribute,
  paletteStepsOffers,
  rigSettingUnion,
  stepFxPaletteShape,
  parseStepList,
  seedSlots,
  serializeStepList,
  slotOption,
  stepPalettesFor,
} from './stepFx'

const palette = (name: string, family: PaletteSummary['family'], rows: [string, string][], over: Partial<PaletteSummary> = {}) =>
  ({
    id: 1,
    uuid: `uuid-${name}`,
    name,
    notes: null,
    fadeDurationMs: null,
    family,
    isGeneric: true,
    kind: 'value',
    requiredEmitters: [],
    rows: rows.map(([attribute, value]) => ({ targetType: 'deferred', targetKey: '', attribute, value })),
    fx: null,
    ...over,
  }) as unknown as PaletteSummary

const GOBO = {
  type: 'setting',
  name: 'gobo',
  displayName: 'Gobo',
  category: 'gobo',
  options: [
    { name: 'OPEN', level: 0, displayName: 'Open' },
    { name: 'GOBO_1', level: 10, displayName: 'Gobo 1' },
    { name: 'GOBO_2', level: 20, displayName: 'Gobo 2' },
    { name: 'GOBO_3', level: 30, displayName: 'Gobo 3' },
  ],
} as unknown as SettingAttributeDescriptor

describe('step effects (effects plan §3.6, §4.4)', () => {
  it('names the two built-ins however a stored row spells them', () => {
    expect(isPaletteSteps('PaletteSteps')).toBe(true)
    expect(isPaletteSteps('Palette Steps')).toBe(true)
    expect(isSlotSteps('slot_steps')).toBe(true)
    expect(isSlotSteps('ZoomWave')).toBe(false)
  })

  it('files Slot Steps under the Beam it drives, though the library files it under Steps', () => {
    expect(drivenCategoryOf({ name: 'SlotSteps', family: 'steps' })).toBe('beam')
    expect(drivenCategoryOf({ name: 'ZoomWave', family: 'beam' })).toBe('beam')
    expect(drivenCategoryOf({ name: 'PaletteSteps', family: 'steps' })).toBe('steps')
  })

  it('files Palette Steps under its palettes’ family’s category, the desk’s spelling', () => {
    expect(['INTENSITY', 'COLOUR', 'POSITION', 'BEAM'].map((f) => categoryForFamily(f as never))).toEqual([
      'intensity',
      'colour',
      'position',
      'beam',
    ])
  })

  it('offers palettes a step list may name: generic, a value, of the family and naming the property', () => {
    const templates = [
      palette('Wide', 'BEAM', [['zoom', 'pct:90'], ['frost', 'pct:20']]),
      palette('Tight', 'BEAM', [['zoom', 'pct:10']]),
      palette('Soft', 'BEAM', [['frost', 'pct:80']]),
      palette('Aimed', 'BEAM', [['zoom', 'pct:50']], { isGeneric: false }),
      palette('Breathe', 'BEAM', [], { kind: 'fx' }),
    ]
    expect(stepPalettesFor(templates, 'BEAM', 'zoom').map((t) => t.name)).toEqual(['Wide', 'Tight'])
    expect(stepPalettesFor(templates, 'BEAM', 'frost').map((t) => t.name)).toEqual(['Wide', 'Soft'])
  })

  it('reads a running step effect as a palette: Palette Steps its palettes\' family, Slot Steps Beam on its setting', () => {
    const red = palette('Red', 'COLOUR', [['rgbColour', '#FF0000']])
    const uv = palette('UV', 'COLOUR', [['uv', 'dmx:200']])
    const down = palette('Down', 'POSITION', [['position', 'pos:0,0']])
    const ref = (t: PaletteSummary) => `pal:${t.uuid}`
    const steps = (...list: PaletteSummary[]) => ({ fxType: 'PaletteSteps', parameters: { palettes: list.map(ref).join(',') } })

    expect(stepFxPaletteShape(steps(red, uv), [red, uv, down])).toEqual({ family: 'COLOUR', fxFamily: 'colour', attribute: null })
    expect(stepFxPaletteShape(steps(red, down), [red, uv, down])).toHaveProperty('reason')
    expect(stepFxPaletteShape(steps(red, uv), undefined)).toEqual({ reason: 'Reading the palette library…' })
    expect(stepFxPaletteShape({ fxType: 'SlotSteps', attribute: 'gobo' }, [])).toEqual({
      family: 'BEAM',
      fxFamily: 'beam',
      attribute: 'gobo',
    })
    expect(stepFxPaletteShape({ fxType: 'SlotSteps', attribute: null }, [])).toHaveProperty('reason')
    expect(stepFxPaletteShape({ fxType: 'Pulse' }, [])).toBeNull()
  })

  it('reads Palette Steps’ attribute off its palettes as the desk does: colour, else the first the palettes all name', () => {
    const list = (...ps: PaletteSummary[]) => ps.map((p) => `pal:${p.uuid}`).join(',')
    const wide = palette('Wide', 'BEAM', [['zoom', 'pct:90'], ['frost', 'pct:50']])
    const soft = palette('Soft', 'BEAM', [['frost', 'pct:80']])
    const flash = palette('Flash', 'INTENSITY', [['strobe', 'pct:80']])
    const fast = palette('Fast', 'INTENSITY', [['strobe', 'pct:100']])
    const red = palette('Red', 'COLOUR', [['rgbColour', '#ff0000']])
    const blue = palette('Blue', 'COLOUR', [['rgbColour', '#0000ff']])
    const all = [wide, soft, flash, fast, red, blue]
    expect(paletteStepsAttribute(list(wide, soft), all)).toBe('frost')
    expect(paletteStepsAttribute(list(flash, fast), all)).toBe('strobe')
    expect(paletteStepsAttribute(list(red, blue), all)).toBe('rgbColour')
    expect(paletteStepsAttribute(list(wide, flash), all)).toBeNull()
    expect(paletteStepsAttribute(list(wide, soft), undefined)).toBeNull()
  })

  it('offers Palette Steps in each family with two palettes on a property the target has, the row’s first', () => {
    const templates = [
      palette('Wide', 'BEAM', [['zoom', 'pct:90'], ['iris', 'pct:90']]),
      palette('Tight', 'BEAM', [['zoom', 'pct:10'], ['iris', 'pct:10']]),
      palette('Half', 'INTENSITY', [['dimmer', 'pct:50']]),
      palette('Red', 'COLOUR', [['rgbColour', '#FF0000']]),
      palette('Blue', 'COLOUR', [['rgbColour', '#0000FF']]),
    ]
    const has = (name: string) => ['zoom', 'iris', 'dimmer', 'rgbColour'].includes(name)
    const offers = paletteStepsOffers(templates, has)
    expect(offers.map((o) => [o.family, o.attribute, o.palettes.length])).toEqual([
      ['COLOUR', 'rgbColour', 2],
      ['BEAM', 'zoom', 2],
    ])
    expect(paletteStepsOffers(templates, has, 'iris').find((o) => o.family === 'BEAM')?.attribute).toBe('iris')
    expect(paletteStepsOffers(templates, (n) => n === 'dimmer')).toEqual([])
  })

  it('spells a step list as its entries, comma-separated, empty entries dropped', () => {
    expect(parseStepList(' pal:a , ,pal:b')).toEqual(['pal:a', 'pal:b'])
    expect(parseStepList(undefined)).toEqual([])
    expect(serializeStepList(['GOBO_1', 'GOBO_2'])).toBe('GOBO_1,GOBO_2')
  })

  it('matches a slot name as the desk does — case, spaces, underscores and dashes aside', () => {
    expect(normaliseSlotName('Gobo 3')).toBe(normaliseSlotName('GOBO_3'))
    expect(slotOption(GOBO, 'gobo3')?.level).toBe(30)
    expect(slotOption(GOBO, 'Gobo 9')).toBeUndefined()
    expect(slotOption(undefined, 'Gobo 3')).toBeUndefined()
  })

  it('starts Slot Steps on the first two slots past the open one', () => {
    expect(seedSlots(GOBO)).toBe('GOBO_1,GOBO_2')
    expect(seedSlots({ ...GOBO, options: GOBO.options.slice(0, 2) })).toBe('OPEN,GOBO_1')
    expect(seedSlots(undefined)).toBe('')
  })

  it('merges every patched head\'s options per setting name for a palette, a name two models share kept once', () => {
    const wheel = (options: [string, number][]) => ({
      type: 'setting',
      name: 'gobo',
      displayName: 'Gobo',
      category: 'gobo',
      options: options.map(([name, level]) => ({ name, level, displayName: name })),
    })
    const union = rigSettingUnion([
      { attributes: [wheel([['OPEN', 0], ['GOBO_3', 59]])] },
      { attributes: [wheel([['Open', 0], ['GOBO_3', 70], ['GOBO_6', 124]]), { type: 'slider', name: 'dimmer' }] },
    ])
    expect(union).toHaveLength(1)
    expect(union[0].options.map((o) => o.name)).toEqual(['OPEN', 'GOBO_3', 'GOBO_6'])
    expect(slotOption(union[0], 'Gobo 6')?.name).toBe('GOBO_6')
  })
})
