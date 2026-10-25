import { describe, expect, it } from 'vitest'
import type { PaletteSummary } from '@/api/palettesApi'
import { outsideMaskReason, paletteFits } from './paletteOffer'
import { recentFirst } from './paletteRecents'

/**
 * The offer the strip, the popup bands and the picker share — so the three offer one set — and the
 * band's order.
 */
function palette(over: Partial<PaletteSummary>): PaletteSummary {
  return {
    id: 1,
    uuid: 'u1',
    name: 'P',
    notes: null,
    fadeDurationMs: null,
    family: 'COLOUR',
    isGeneric: true,
    kind: 'value',
    requiredEmitters: [],
    rows: [{ targetType: 'deferred', targetKey: '', attribute: 'rgbColour', value: '#ff0000' }],
    fx: null,
    layerCount: 0,
    buskPageCount: 0,
    ...over,
  }
}

describe('paletteFits', () => {
  it('needs the family, and every emitter the palette names somewhere in the heads', () => {
    const amber = palette({ requiredEmitters: ['amber'] })
    expect(paletteFits(amber, { families: ['COLOUR'], emitters: ['amber'] })).toBe(true)
    expect(paletteFits(amber, { families: ['COLOUR'], emitters: ['white'] })).toBe(false)
    expect(paletteFits(amber, { families: ['INTENSITY'], emitters: ['amber'] })).toBe(false)
    // Nothing known about the heads filters nothing.
    expect(paletteFits(amber, { families: null, emitters: null })).toBe(true)
  })

  it('narrows a Beam offer to the palettes with a row for the property', () => {
    const wide = palette({ family: 'BEAM', rows: [{ targetType: 'deferred', targetKey: '', attribute: 'zoom', value: 'pct:100' }] })
    expect(paletteFits(wide, { families: ['BEAM'], emitters: [], attribute: 'zoom' })).toBe(true)
    expect(paletteFits(wide, { families: ['BEAM'], emitters: [], attribute: 'iris' })).toBe(false)
    // A colour cell's property says nothing about which colour palettes fit: only Beam is narrowed.
    expect(paletteFits(palette({}), { families: ['COLOUR'], emitters: [], attribute: 'white' })).toBe(true)
  })

  it('narrows a Beam cell’s effect palettes to the property the effect drives', () => {
    const effect = (fxType: string, extra: Partial<NonNullable<PaletteSummary['fx']>> = {}) =>
      palette({
        family: 'BEAM',
        kind: 'fx',
        rows: [],
        fx: { fxType, family: 'beam', cycle: 4, blendMode: 'OVERRIDE', order: 'NORMAL', parameters: {}, ...extra },
      })
    const zoomFit = { families: ['BEAM' as const], emitters: null, attribute: 'zoom' }
    const library = [{ name: 'ZoomWave', compatibleAttributes: ['zoom'] }, { name: 'IrisPulse', compatibleAttributes: ['iris'] }]
    // A beam built-in by the library's property.
    expect(paletteFits(effect('ZoomWave'), zoomFit, { library })).toBe(true)
    expect(paletteFits(effect('IrisPulse'), zoomFit, { library })).toBe(false)
    // Slot Steps by the setting it names.
    expect(paletteFits(effect('SlotSteps', { attribute: 'gobo' }), { ...zoomFit, attribute: 'gobo' })).toBe(true)
    expect(paletteFits(effect('SlotSteps', { attribute: 'prism' }), { ...zoomFit, attribute: 'gobo' })).toBe(false)
    // Palette Steps by its palettes' rows.
    const wide = palette({ uuid: 'w', family: 'BEAM', rows: [{ targetType: 'deferred', targetKey: '', attribute: 'zoom', value: 'pct:100' }] })
    const steps = effect('PaletteSteps', { parameters: { palettes: 'pal:w' } })
    expect(paletteFits(steps, zoomFit, { palettes: [wide] })).toBe(true)
    expect(paletteFits(steps, { ...zoomFit, attribute: 'iris' }, { palettes: [wide] })).toBe(false)
    // Without what it needs to read, it is not narrowed.
    expect(paletteFits(effect('IrisPulse'), zoomFit)).toBe(true)
  })
})

describe('the mask', () => {
  it('names a palette outside it, with both families', () => {
    expect(outsideMaskReason('COLOUR', ['POSITION'])).toBe('Colour — the selection is Position')
    expect(outsideMaskReason('COLOUR', ['COLOUR'])).toBeNull()
    expect(outsideMaskReason('COLOUR', null)).toBeNull()
  })
})

describe('recentFirst', () => {
  it('puts the pressed most recent first, then the rest in the order given', () => {
    const a = palette({ id: 1, name: 'A' })
    const b = palette({ id: 2, name: 'B', lastPressedAt: '2026-10-16T10:00:00Z' })
    const c = palette({ id: 3, name: 'C', lastPressedAt: '2026-10-16T10:00:00.500Z' })
    const d = palette({ id: 4, name: 'D' })
    expect(recentFirst([a, b, c, d]).map((t) => t.name)).toEqual(['C', 'B', 'A', 'D'])
  })
})
