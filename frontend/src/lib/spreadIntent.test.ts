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
import { TEMPLATE_PROPERTIES, parseTemplateIntent, serializeTemplateIntent, templatePropertyFor } from './templateIntent'
import { serializeTemplateRef } from '@/components/fx/colourUtils'

/**
 * The Spread tab's endpoints (busk-further plan D9): serialised through `templateIntent.ts`'s own
 * grammar and `colourUtils`' `tmpl:` reference, so the desk reads a spread endpoint exactly as it
 * reads a template row — and nothing here resolves, interpolates or knows a head.
 */
describe('spreadIntent', () => {
  it('serialises every endpoint shape to exactly the string templateIntent.ts writes', () => {
    const colour = { kind: 'colour', hex: '#f5b342', policy: 'extract' } as const
    expect(serializeSpreadEndpoint(colour)).toBe(serializeTemplateIntent(colour))
    expect(serializeSpreadEndpoint(colour)).toBe('#F5B342;policy=extract')
    expect(serializeSpreadEndpoint({ kind: 'percent', value: 37.25 })).toBe('pct:37.3')
    expect(serializeSpreadEndpoint({ kind: 'position', panDeg: -30, tiltDeg: 12.5 })).toBe('deg:-30,12.5')
    expect(serializeSpreadEndpoint({ kind: 'level', value: 300 })).toBe('dmx:255')
    // Each round-trips through the parser the library rows use, so the desk's parser will take it.
    for (const endpoint of [colour, { kind: 'percent', value: 50 }, { kind: 'position', panDeg: 1, tiltDeg: 2 }, { kind: 'level', value: 7 }] as const) {
      expect(parseTemplateIntent(serializeSpreadEndpoint(endpoint))).not.toBeNull()
    }
  })

  it('serialises a template endpoint as the tmpl: reference an FX colour parameter uses', () => {
    const uuid = '0b2a4c6e-1111-2222-3333-444455556666'
    expect(serializeSpreadEndpoint({ kind: 'template', uuid })).toBe(serializeTemplateRef(uuid))
    expect(serializeSpreadEndpoint({ kind: 'template', uuid })).toBe(`tmpl:${uuid}`)
    // A reference is not an intent: the row parser refuses it, which is the desk's `spreadEndpoint`
    // branch — a colour property resolves it, any other property is a 400.
    expect(parseTemplateIntent(`tmpl:${uuid}`)).toBeNull()
  })

  it('offers the template vocabulary per family, minus the switch', () => {
    const offered = (['INTENSITY', 'POSITION', 'COLOUR', 'BEAM'] as const).flatMap((family) => spreadPropertiesFor(family))
    const expected = TEMPLATE_PROPERTIES.filter((p) => p.intent !== 'switch')
    expect(offered.map((p) => p.propertyName).sort()).toEqual(expected.map((p) => p.propertyName).sort())
    expect(offered.some((p) => p.propertyName === 'prism')).toBe(false)
    // The first of each family is the default the tab lands on.
    expect(spreadPropertiesFor('INTENSITY')[0].propertyName).toBe('dimmer')
    expect(spreadPropertiesFor('COLOUR')[0].propertyName).toBe('rgbColour')
    expect(spreadPropertiesFor('POSITION')[0].propertyName).toBe('position')
    expect(spreadPropertiesFor('BEAM')[0].propertyName).toBe('zoom')
  })

  it('gives each property the editor of its intent, and defaults its endpoints in that shape', () => {
    for (const property of TEMPLATE_PROPERTIES.filter((p) => p.intent !== 'switch')) {
      const kind = spreadEditorKind(property)
      expect(kind).toBe(property.intent)
      const { from, to } = defaultSpreadEndpoints(property)
      expect(from.kind).toBe(kind)
      expect(to.kind).toBe(kind)
      expect(isCompleteSpreadEndpoint(from)).toBe(true)
      expect(isCompleteSpreadEndpoint(to)).toBe(true)
    }
    // An emitter's default runs the whole byte; a level the whole percent.
    expect(defaultSpreadEndpoints(templatePropertyFor('white')!)).toEqual({ from: { kind: 'level', value: 0 }, to: { kind: 'level', value: 255 } })
    expect(defaultSpreadEndpoints(templatePropertyFor('dimmer')!)).toEqual({ from: { kind: 'percent', value: 0 }, to: { kind: 'percent', value: 100 } })
    // A position is absolute degrees in each head's own range, so the default swings about the
    // desk's centre (270 / 135, TemplateEditor's convention) rather than about zero, which is a
    // head's hard stop.
    expect(defaultSpreadEndpoints(templatePropertyFor('position')!)).toEqual({
      from: { kind: 'position', panDeg: 240, tiltDeg: 135 },
      to: { kind: 'position', panDeg: 300, tiltDeg: 135 },
    })
  })

  it('makes a colour endpoint from the Colour tab’s channels, extract by default', () => {
    expect(colourEndpointOf({ r: 245, g: 179, b: 66 })).toEqual({ kind: 'colour', hex: '#F5B342', policy: 'extract' })
    expect(colourEndpointOf({ r: 0, g: 0, b: 0 }, 'rgbonly')).toEqual({ kind: 'colour', hex: '#000000', policy: 'rgbonly' })
  })

  it('refuses an endpoint the desk could not take', () => {
    expect(isCompleteSpreadEndpoint({ kind: 'colour', hex: '#abc', policy: 'extract' })).toBe(false)
    expect(isCompleteSpreadEndpoint({ kind: 'percent', value: Number.NaN })).toBe(false)
    expect(isCompleteSpreadEndpoint({ kind: 'position', panDeg: 1, tiltDeg: Number.NaN })).toBe(false)
    expect(isCompleteSpreadEndpoint({ kind: 'template', uuid: ' ' })).toBe(false)
  })

  it('names the desk’s four curves and four orders, and says which design label the desk has no order for', () => {
    expect(SPREAD_CURVES.map((c) => c.id)).toEqual(['LINE', 'MIRROR', 'ARROW', 'WINGS'])
    expect(SPREAD_ORDERS.filter((o) => o.id != null).map((o) => o.id)).toEqual(['LINEAR', 'REVERSE', 'CENTER_OUT', 'RANDOM'])
    const stage = SPREAD_ORDERS.find((o) => o.label === 'Stage L→R')
    expect(stage?.id).toBeNull()
    expect(stage?.hint).toMatch(/POSITIONAL/)
  })

  it('imports no resolver — it serialises and nothing more (D9)', () => {
    const imports = [...spreadIntentSrc.matchAll(/from '([^']+)'/g)].map((m) => m[1]).sort()
    expect(imports).toEqual(['./attributeFamily', './templateIntent', '@/components/fx/colourUtils', '@/store/programmerOps'])
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
