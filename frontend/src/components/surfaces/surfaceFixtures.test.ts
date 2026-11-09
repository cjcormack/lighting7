import { describe, expect, it } from 'vitest'
import { inputOf, partKey } from '@/api/surfacesApi'
import { entriesFor, isWhole, doesFor } from './BindingForm'
import { CATALOGUE, FIXTURE_PROFILE, MAP_FRAME } from './surfaceFixtures'

/**
 * The two fixtures lighting7's `SurfaceCatalogueTest` pins, read the way this side reads them
 * (surface-setup plan P5). If the desk changes the wire, the fixture moves with it, and these
 * fail on whatever this side still assumes.
 */
describe('the catalogue fixture', () => {
  it('offers a strip slot the strip alone, and every control input its functions', () => {
    expect(entriesFor(CATALOGUE, 'STRIP').map((e) => e.id)).toEqual(['strip'])
    const continuous = entriesFor(CATALOGUE, 'CONTINUOUS').map((e) => e.id)
    const buttons = entriesFor(CATALOGUE, 'BUTTON').map((e) => e.id)
    expect(continuous).toEqual(expect.arrayContaining(['level', 'fan', 'fxSpeed', 'fxAmount', 'grandMaster', 'tempo']))
    expect(continuous).not.toContain('go')
    expect(buttons).toEqual(expect.arrayContaining(['go', 'flash', 'select', 'locate', 'tap', 'grandMasterLevel', 'tempoSet', 'layer']))
    expect(buttons).not.toContain('level')
  })

  it('builds every entry a binding that is whole once its subject is chosen', () => {
    for (const entry of CATALOGUE) {
      if (entry.strip) continue
      const does = doesFor(entry) as unknown as Record<string, unknown>
      expect(does.type).toBe(entry.does)
      for (const param of entry.params) {
        if (param.required && param.default == null) does[param.key] = param.kind === 'NUMBER' ? 1 : 'x'
      }
      const kind = entry.subjects[0]
      const on =
        kind === 'NONE'
          ? undefined
          : kind === 'SELECTION'
            ? { type: 'selection' as const }
            : kind === 'GROUP'
              ? { type: 'group' as const, key: 'g' }
              : kind === 'FIXTURE'
                ? { type: 'fixture' as const, key: 'f' }
                : kind === 'SLOT'
                  ? { type: 'slot' as const }
                  : { type: 'record' as const, kind, uuid: 'u' }
      expect(isWhole(entry, { does: does as never, on }), entry.id).toBe(true)
    }
  })
})

describe('the map fixture', () => {
  it('names every part of every layer by the key this side reads', () => {
    for (const parts of Object.values(MAP_FRAME.layers)) {
      for (const control of FIXTURE_PROFILE.controls) {
        expect(parts[partKey(control.controlId, 'MAIN')], control.controlId).toBeDefined()
        if (inputOf(control, 'PUSH') != null) expect(parts[partKey(control.controlId, 'PUSH')]).toBeDefined()
      }
    }
  })

  it('says where each part comes from, and names each one the catalogue has', () => {
    const a = MAP_FRAME.layers['layer-a']
    expect(a['fader-1:MAIN'].from).toBe('STRIP')
    expect(a['fader-1:MAIN'].stripId).toBe('strip-1')
    expect(MAP_FRAME.layers['layer-b']['fader-1:MAIN'].from).toBe('DIRECT')
    expect(a['btn-5:MAIN'].from).toBe('NONE')
    for (const parts of Object.values(MAP_FRAME.layers)) {
      for (const part of Object.values(parts)) {
        if (part.from === 'NONE') continue
        expect(CATALOGUE.some((entry) => entry.id === part.entry), part.label ?? '').toBe(true)
      }
    }
  })
})
