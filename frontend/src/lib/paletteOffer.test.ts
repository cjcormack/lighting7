import { describe, expect, it } from 'vitest'
import type { PaletteSummary } from '@/api/palettesApi'
import { outsideMaskReason, paletteFits, paletteReach, pressReach, reachCount, reachVerb, splitByMask } from './paletteOffer'
import type { ReachFixture } from './paletteOffer'
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

describe('splitByMask', () => {
  it('keeps what the mask covers, and gives the rest with the reason, in order', () => {
    const red = palette({ id: 1, name: 'Red' })
    const full = palette({ id: 2, name: 'Full', family: 'INTENSITY' })
    const blue = palette({ id: 3, name: 'Blue' })
    expect(splitByMask([red, full, blue], ['COLOUR'])).toEqual({
      inside: [red, blue],
      outside: [{ palette: full, reason: outsideMaskReason('INTENSITY', ['COLOUR']) }],
    })
    expect(splitByMask([red, full], null)).toEqual({ inside: [red, full], outside: [] })
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

/**
 * A per-fixture palette's reach over a press (palette-references plan D21), counted the desk's way —
 * `LayerPressRules.paletteHeads` / `omittedHeads`, the cases `ProgrammerLayerPressTest` presses.
 */
describe('paletteReach', () => {
  const rows = (...heads: string[]) =>
    heads.map((targetKey) => ({ targetType: 'fixture' as const, targetKey, attribute: 'position', value: 'pos:0,0' }))
  const spot = palette({ isGeneric: false, family: 'POSITION', rows: rows('hex-1', 'hex-2') })
  const rig: ReachFixture[] = [
    { key: 'hex-1', groups: ['front'] },
    { key: 'hex-2', groups: ['front'] },
    { key: 'hex-3', groups: [] },
    { key: 'bar', groups: [], elements: [{ key: 'bar.1' }, { key: 'bar.2' }] },
  ]
  const fixture = (key: string) => ({ type: 'fixture', key })
  const reach = (p: PaletteSummary, targets: { type: string; key: string }[], fixtures: ReachFixture[] = rig) =>
    paletteReach(p, pressReach(targets, fixtures))

  it('reaches the pressed heads its rows name, and leaves the rest out', () => {
    // `a per-fixture palette replaces only on the heads its rows name`: Spot on hex-1 and hex-2,
    // pressed on hex-1 and hex-3 — hex-2 omitted, hex-3 not one of its heads at all.
    const r = reach(spot, [fixture('hex-1'), fixture('hex-3')])
    expect(r).toEqual({ reached: ['hex-1'], leftOut: ['hex-2'] })
    expect(reachCount(r)).toBe('1 of 2')
  })

  it('reaches every head a group press names', () => {
    const r = reach(spot, [{ type: 'group', key: 'front' }])
    expect(r).toEqual({ reached: ['hex-1', 'hex-2'], leftOut: [] })
    expect(reachCount(r)).toBeNull()
  })

  it('reaches a cell row through its fixture, and a fixture row through one of its cells', () => {
    const cells = palette({ isGeneric: false, family: 'POSITION', rows: rows('bar.1', 'bar.2') })
    expect(reach(cells, [fixture('bar')])).toEqual({ reached: ['bar.1', 'bar.2'], leftOut: [] })
    const whole = palette({ isGeneric: false, family: 'POSITION', rows: rows('bar', 'hex-1') })
    expect(reach(whole, [fixture('bar.2')])).toEqual({ reached: ['bar'], leftOut: ['hex-1'] })
  })

  it('says nothing for a generic palette, an effect palette or a press naming nothing', () => {
    expect(reach(palette({}), [fixture('hex-1')])).toBeNull()
    expect(reach(palette({ kind: 'fx', isGeneric: true, rows: [] }), [fixture('hex-1')])).toBeNull()
    // No targets: the desk lands it on its own heads, so nothing is left out.
    expect(reach(spot, [])).toBeNull()
  })

  it('names the heads in the hold menu’s first line, and counts them past four', () => {
    const four = palette({ isGeneric: false, family: 'POSITION', rows: rows('s1', 's2', 's3', 's4') })
    const name = (k: string) => `Spot ${k.slice(1)}`
    const r = reach(four, [fixture('s2'), fixture('s3')], [])
    expect(reachCount(r)).toBe('2 of 4')
    expect(reachVerb(r, name)).toBe('Put on Spot 2 and Spot 3 — its Spot 1 and Spot 4 are left out')
    expect(reachVerb(reach(four, [fixture('x')], []), name)).toBe(
      'Put on nothing here — its Spot 1, Spot 2, Spot 3 and Spot 4 are not selected',
    )
    expect(reachVerb(reach(four, ['s1', 's2', 's3', 's4'].map(fixture), []), name)).toBeNull()
    const many = palette({ isGeneric: false, family: 'POSITION', rows: rows('s1', 's2', 's3', 's4', 's5', 's6', 's7') })
    expect(reachVerb(reach(many, [fixture('s1')], []), name)).toBe('Put on Spot 1 — its 6 heads are left out')
  })
})
