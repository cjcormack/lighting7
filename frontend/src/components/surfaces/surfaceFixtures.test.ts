import { describe, expect, it } from 'vitest'
import { inputOf, partKey, surfaceUseKey } from '@/api/surfacesApi'
import { entriesFor, isWhole, doesFor } from './BindingForm'
import { CATALOGUE, FIXTURE_PROFILE, MAP_FRAME, USES_FRAME } from './surfaceFixtures'

/**
 * The two fixtures lighting7's `SurfaceCatalogueTest` pins, read the way this side reads them
 * (surface-setup plan P5). If the desk changes the wire, the fixture moves with it, and these
 * fail on whatever this side still assumes.
 */
describe('the catalogue fixture', () => {
  it('offers every control input its functions', () => {
    const continuous = entriesFor(CATALOGUE, 'CONTINUOUS').map((e) => e.id)
    const buttons = entriesFor(CATALOGUE, 'BUTTON').map((e) => e.id)
    expect(continuous).toEqual(expect.arrayContaining(['level', 'fan', 'fxSpeed', 'fxAmount', 'grandMaster', 'tempo']))
    expect(continuous).not.toContain('go')
    expect(buttons).toEqual(expect.arrayContaining(['go', 'flash', 'select', 'locate', 'tap', 'grandMasterLevel', 'tempoSet', 'layer']))
    expect(buttons).not.toContain('level')
  })

  it('builds every entry a binding that is whole once its subject is chosen', () => {
    for (const entry of CATALOGUE) {
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
  it('lists the type\'s jobs with their items named', () => {
    expect(MAP_FRAME.jobs?.map((job) => [job.region, job.kind])).toEqual([
      ['strips', 'groupStrips'],
      ['encoders', 'wheels'],
    ])
    expect(MAP_FRAME.jobs?.[1].items?.map((item) => item?.label)).toEqual(['Saturation', 'Tempo · M2 · Chase'])
  })

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
    expect(a['fader-1:MAIN'].from).toBe('JOB')
    expect(a['fader-1:MAIN'].jobRegion).toBe('strips')
    expect(a['fader-1:MAIN'].jobLabel).toBe('Groups as channel strips · item 1')
    expect(MAP_FRAME.layers['layer-b']['fader-1:MAIN'].from).toBe('DIRECT')
    expect(MAP_FRAME.layers['layer-b']['fader-1:MAIN'].beats).toBe('Groups as channel strips · item 1')
    expect(a['btn-5:MAIN'].from).toBe('NONE')
    for (const parts of Object.values(MAP_FRAME.layers)) {
      for (const part of Object.values(parts)) {
        if (part.from === 'NONE') continue
        expect(CATALOGUE.some((entry) => entry.id === part.entry), part.label ?? '').toBe(true)
      }
    }
  })
})

describe('the uses fixture', () => {
  it('files every subject the map names under the key this side reads', () => {
    for (const parts of Object.values(MAP_FRAME.layers)) {
      for (const part of Object.values(parts)) {
        const key = surfaceUseKey(part.on)
        if (key == null || part.from === 'NONE') continue
        expect(USES_FRAME.uses[key], key).toBeDefined()
      }
    }
  })

  it('says where each use is in the desk\'s words, a layer named only where it is one layer\'s', () => {
    const hex = USES_FRAME.uses['fixture:hex-1']
    expect(hex.map((use) => [use.label, use.layer])).toEqual([['Fader 1 · B', 'layer-b']])
    const stack = USES_FRAME.uses['record:STACK:11111111-1111-4111-8111-111111111111']
    expect(stack.map((use) => use.label)).toEqual(['Button 3'])
    expect(stack[0].layer ?? null).toBeNull()
    expect(USES_FRAME.uses['record:PALETTE:33333333-3333-4333-8333-333333333333']?.[0].label).toBe('Button 6')
  })
})
