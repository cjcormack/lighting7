import { describe, expect, it } from 'vitest'
import type { TemplateSummary } from '@/api/templatesApi'
import { lookOutsideMaskReason, outsideMaskReason, paletteFits } from './paletteOffer'
import { recentFirst } from './templateRecents'

/**
 * The offer the strip, the popup bands and the picker share — so the three offer one set — and the
 * band's order.
 */
function palette(over: Partial<TemplateSummary>): TemplateSummary {
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
    rows: [{ targetType: 'deferred', targetKey: '', propertyName: 'rgbColour', value: '#ff0000' }],
    effect: null,
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
    const wide = palette({ family: 'BEAM', rows: [{ targetType: 'deferred', targetKey: '', propertyName: 'zoom', value: 'pct:100' }] })
    expect(paletteFits(wide, { families: ['BEAM'], emitters: [], property: 'zoom' })).toBe(true)
    expect(paletteFits(wide, { families: ['BEAM'], emitters: [], property: 'iris' })).toBe(false)
    // A colour cell's property says nothing about which colour palettes fit: only Beam is narrowed.
    expect(paletteFits(palette({}), { families: ['COLOUR'], emitters: [], property: 'white' })).toBe(true)
  })
})

describe('the mask', () => {
  it('names a palette outside it, with both families', () => {
    expect(outsideMaskReason('COLOUR', ['POSITION'])).toBe('Colour — the selection is Position')
    expect(outsideMaskReason('COLOUR', ['COLOUR'])).toBeNull()
    expect(outsideMaskReason('COLOUR', null)).toBeNull()
  })

  it('puts a Look outside only when none of its families is inside', () => {
    expect(lookOutsideMaskReason(['COLOUR', 'INTENSITY'], ['COLOUR'])).toBeNull()
    expect(lookOutsideMaskReason(['POSITION'], ['COLOUR'])).toBe('Position — the selection is Colour')
    // Scenery alone lights nothing for a mask to cut, so the desk lands it whatever the mask.
    expect(lookOutsideMaskReason([], ['COLOUR'])).toBeNull()
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
