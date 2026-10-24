import { describe, expect, it } from 'vitest'
// The source itself, as text. `?raw` rather than `node:fs`, for `shortViewport.test.ts`'s reason:
// this file is compiled by `tsc` under the app's tsconfig, which has no Node types.
import spreadIntentSrc from './spreadIntent.ts?raw'
import spreadPanelSrc from '@/components/editor/SpreadPanel.tsx?raw'
import spreadPopoverSrc from '@/components/fixtures-list/SpreadPopover.tsx?raw'
import {
  SPREAD_CURVES,
  SPREAD_ORDERS,
  colourEndpointOf,
  defaultSpreadEndpoints,
  isCompleteSpreadEndpoint,
  serializeSpreadEndpoint,
  spreadEditorKind,
  spreadPropertiesFor,
} from './spreadIntent'
import { PALETTE_PROPERTIES, parsePaletteIntent, serializePaletteIntent, palettePropertyFor } from './paletteIntent'
import { serializePaletteRef } from '@/components/fx/colourUtils'

/**
 * The Spread tab's endpoints (busk-further plan D9): serialised through `paletteIntent.ts`'s own
 * grammar and `colourUtils`' `pal:` reference, so the desk reads a spread endpoint exactly as it
 * reads a template row — and nothing here resolves, interpolates or knows a head.
 */
describe('spreadIntent', () => {
  it('serialises every endpoint shape to exactly the string paletteIntent.ts writes', () => {
    const colour = { kind: 'colour', hex: '#f5b342', policy: 'extract' } as const
    expect(serializeSpreadEndpoint(colour)).toBe(serializePaletteIntent(colour))
    expect(serializeSpreadEndpoint(colour)).toBe('#F5B342;policy=extract')
    expect(serializeSpreadEndpoint({ kind: 'percent', value: 37.25 })).toBe('pct:37.3')
    expect(serializeSpreadEndpoint({ kind: 'position', pan: -30, tilt: 12, unit: 'centre' })).toBe('pos:-30,12')
    expect(serializeSpreadEndpoint({ kind: 'level', value: 300 })).toBe('dmx:255')
    // Each round-trips through the parser the library rows use, so the desk's parser will take it.
    for (const endpoint of [colour, { kind: 'percent', value: 50 }, { kind: 'position', pan: 1, tilt: 2, unit: 'centre' }, { kind: 'level', value: 7 }] as const) {
      expect(parsePaletteIntent(serializeSpreadEndpoint(endpoint))).not.toBeNull()
    }
  })

  it('serialises a palette endpoint as the pal: reference an FX colour parameter uses', () => {
    const uuid = '0b2a4c6e-1111-2222-3333-444455556666'
    expect(serializeSpreadEndpoint({ kind: 'palette', uuid })).toBe(serializePaletteRef(uuid))
    expect(serializeSpreadEndpoint({ kind: 'palette', uuid })).toBe(`pal:${uuid}`)
    // A reference is not an intent: the row parser refuses it, which is the desk's `spreadEndpoint`
    // branch — a colour property resolves it, any other property is a 400.
    expect(parsePaletteIntent(`pal:${uuid}`)).toBeNull()
  })

  it('offers the palette vocabulary per family, minus the switch', () => {
    const offered = (['INTENSITY', 'POSITION', 'COLOUR', 'BEAM'] as const).flatMap((family) => spreadPropertiesFor(family))
    const expected = PALETTE_PROPERTIES.filter((p) => p.intent !== 'switch')
    expect(offered.map((p) => p.propertyName).sort()).toEqual(expected.map((p) => p.propertyName).sort())
    expect(offered.some((p) => p.propertyName === 'prism')).toBe(false)
    // The first of each family is the default the tab lands on.
    expect(spreadPropertiesFor('INTENSITY')[0].propertyName).toBe('dimmer')
    expect(spreadPropertiesFor('COLOUR')[0].propertyName).toBe('rgbColour')
    expect(spreadPropertiesFor('POSITION')[0].propertyName).toBe('position')
    expect(spreadPropertiesFor('BEAM')[0].propertyName).toBe('zoom')
  })

  it('gives each property the editor of its intent, and defaults its endpoints in that shape', () => {
    for (const property of PALETTE_PROPERTIES.filter((p) => p.intent !== 'switch')) {
      const kind = spreadEditorKind(property)
      expect(kind).toBe(property.intent)
      const { from, to } = defaultSpreadEndpoints(property)
      expect(from.kind).toBe(kind)
      expect(to.kind).toBe(kind)
      expect(isCompleteSpreadEndpoint(from)).toBe(true)
      expect(isCompleteSpreadEndpoint(to)).toBe(true)
    }
    // An emitter's default runs the whole byte; a level the whole percent.
    expect(defaultSpreadEndpoints(palettePropertyFor('white')!)).toEqual({ from: { kind: 'level', value: 0 }, to: { kind: 'level', value: 255 } })
    expect(defaultSpreadEndpoints(palettePropertyFor('dimmer')!)).toEqual({ from: { kind: 'percent', value: 0 }, to: { kind: 'percent', value: 100 } })
    // A position is centre-relative (venue-visit plan D3), so the default swings about 0 — the
    // centre of travel on every head.
    expect(defaultSpreadEndpoints(palettePropertyFor('position')!)).toEqual({
      from: { kind: 'position', pan: -16, tilt: 0, unit: 'centre' },
      to: { kind: 'position', pan: 16, tilt: 0, unit: 'centre' },
    })
  })

  it('makes a colour endpoint from the Colour tab’s channels, extract by default', () => {
    expect(colourEndpointOf({ r: 245, g: 179, b: 66 })).toEqual({ kind: 'colour', hex: '#F5B342', policy: 'extract' })
    expect(colourEndpointOf({ r: 0, g: 0, b: 0 }, 'rgbonly')).toEqual({ kind: 'colour', hex: '#000000', policy: 'rgbonly' })
  })

  it('refuses an endpoint the desk could not take', () => {
    expect(isCompleteSpreadEndpoint({ kind: 'colour', hex: '#abc', policy: 'extract' })).toBe(false)
    expect(isCompleteSpreadEndpoint({ kind: 'percent', value: Number.NaN })).toBe(false)
    expect(isCompleteSpreadEndpoint({ kind: 'position', pan: 1, tilt: Number.NaN, unit: 'centre' })).toBe(false)
    expect(isCompleteSpreadEndpoint({ kind: 'palette', uuid: ' ' })).toBe(false)
  })

  it('names the desk’s four curves and four orders, and says which design label the desk has no order for', () => {
    expect(SPREAD_CURVES.map((c) => c.id)).toEqual(['LINE', 'MIRROR', 'ARROW', 'MIRROR_OUT'])
    expect(SPREAD_ORDERS.filter((o) => o.id != null).map((o) => o.id)).toEqual(['NORMAL', 'REVERSE', 'CENTRE_OUT', 'RANDOM'])
    const stage = SPREAD_ORDERS.find((o) => o.label === 'Stage L→R')
    expect(stage?.id).toBeNull()
    expect(stage?.hint).toMatch(/POSITIONAL/)
  })

  it('imports no resolver — it serialises and nothing more (D9)', () => {
    const imports = [...spreadIntentSrc.matchAll(/from '([^']+)'/g)].map((m) => m[1]).sort()
    expect(imports).toEqual(['./attributeFamily', './orderLabels', './paletteIntent', '@/components/fx/colourUtils', '@/store/programmerOps'])
    // The docblock may *name* the resolver; the import list may not reach one, nor the client walk.
    expect(imports.some((name) => /spreadPlans|fanMath|colourMath|[Rr]esolver/.test(name))).toBe(false)
    expect(spreadIntentSrc).not.toMatch(/lerp\(|mixLab|interpolateIntent/)
  })

  it('neither the panel nor the programmer’s popover reaches a lerp — only `rawValues`, and only from the raw arm (editor-kit D3, D15)', () => {
    const importsOf = (src: string) => [...src.matchAll(/from '([^']+)'/g)].map((m) => m[1])
    // The panel imports the client walk for its raw and duration kinds, and calls `rawValues` in
    // exactly one place — the raw arm's send; nothing resolves or interpolates an intent.
    const panelImports = importsOf(spreadPanelSrc)
    expect(panelImports).toContain('./spreadPlans')
    expect(panelImports.some((name) => /fanMath|colourMath\b.*lerp|[Rr]esolver/.test(name))).toBe(false)
    expect(spreadPanelSrc.match(/\brawValues\(/g)).toHaveLength(1)
    expect(spreadPanelSrc).not.toMatch(/\bmixLab\(|\binterpolateIntent\(|\bfanColours\(|\bfanValues\(/)
    // The popover builds plans and never walks a value at all.
    const popoverImports = importsOf(spreadPopoverSrc)
    expect(popoverImports.some((name) => /spreadPlans|fanMath|colourMath|[Rr]esolver/.test(name))).toBe(false)
    expect(spreadPopoverSrc).not.toMatch(/\brawValues\(|\bspreadFractions\(|\bmixLab\(|\binterpolateIntent\(/)
  })
})
