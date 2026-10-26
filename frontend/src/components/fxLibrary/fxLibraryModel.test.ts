import { describe, expect, it } from 'vitest'
import type { FxLibraryEntry } from '@/store/fixtureFx'
import type { FxDefinition } from '@/store/fxDefinitions'
import {
  definitionsByFxId,
  fxRegisteredBy,
  entryName,
  forkRefusal,
  forkRequest,
  fxSourceOf,
  mintForkFxId,
  uniqueFxId,
} from './fxLibraryModel'

function entry(name: string, over: Partial<FxLibraryEntry> = {}): FxLibraryEntry {
  return {
    name,
    family: 'intensity',
    outputType: 'SLIDER',
    fxMode: 'STANDARD',
    timingSource: 'BEAT',
    parameters: [],
    compatibleAttributes: ['dimmer'],
    source: 'BUILT_IN',
    sourceDefinitionId: null,
    script: null,
    ...over,
  }
}

function definition(id: number, fxId: string, name = fxId): FxDefinition {
  return {
    id,
    fxId,
    name,
    family: 'intensity',
    outputType: 'SLIDER',
    fxMode: 'STANDARD',
    parameters: [],
    compatibleAttributes: ['dimmer'],
    script: 'FxOutput.Slider(0u)',
    defaultTiming: 'CYCLE',
    timingSource: 'BEAT',
  }
}

describe('fxSourceOf — the definition list is the discriminator', () => {
  // Both USER entries carry a `sourceDefinitionId`: 7 is a definition's row id, 12 a script's.
  const pulse = entry('Pulse')
  const flicker = entry('RandomFlicker', { source: 'USER', sourceDefinitionId: 7 })
  const warm = entry('WarmFlicker', { source: 'USER', sourceDefinitionId: 12 })
  const byFxId = definitionsByFxId([definition(7, 'RandomFlicker', 'Random Flicker')])

  it('reads a built-in, a custom definition and a script’s effect apart', () => {
    expect(fxSourceOf(pulse, byFxId)).toBe('builtIn')
    expect(fxSourceOf(flicker, byFxId)).toBe('custom')
    expect(fxSourceOf(warm, byFxId)).toBe('script')
  })

  it('does not read a script’s effect as custom because its script id happens to be a definition’s row id', () => {
    // The old page's bug: it read every USER `sourceDefinitionId` as an fx_definitions id.
    const clash = entry('WarmFlicker', { source: 'USER', sourceDefinitionId: 7 })
    expect(fxSourceOf(clash, byFxId)).toBe('script')
  })

  it('names a custom row by its definition and anything else by its id as words', () => {
    expect(entryName(flicker, byFxId.get('RandomFlicker'))).toBe('Random Flicker')
    expect(entryName(entry('SineWaveCustom2'), definition(9, 'SineWaveCustom2', 'Sine Wave (Custom 2)'))).toBe(
      'Sine Wave (Custom 2)',
    )
    expect(entryName(warm, undefined)).toBe('Warm Flicker')
  })

  it('finds the effects a script registers — and only script-registered ones', () => {
    const library = [pulse, flicker, warm, entry('Ember', { source: 'USER', sourceDefinitionId: 12 })]
    expect(fxRegisteredBy(12, library, byFxId).map((e) => e.name)).toEqual(['WarmFlicker', 'Ember'])
    // Script 7 registers nothing, though definition 7's effect carries a 7 too.
    expect(fxRegisteredBy(7, library, byFxId)).toEqual([])
  })
})

describe('forkRefusal', () => {
  it('refuses the step effects, the desk’s own, and nothing else', () => {
    expect(forkRefusal(entry('PaletteSteps', { outputType: 'STEPS', family: 'steps' }))).toMatch(/cannot be forked/)
    expect(forkRefusal(entry('SlotSteps', { outputType: 'STEPS', family: 'beam' }))).toMatch(/Slot Steps is the desk’s own/)
    expect(forkRefusal(entry('Pulse'))).toBeNull()
  })
})

describe('mintForkFxId — unique across the whole library', () => {
  it('takes <id>Custom when it is free', () => {
    expect(mintForkFxId('Pulse', ['Pulse', 'SineWave'])).toEqual({ fxId: 'PulseCustom', ordinal: 1 })
  })

  it('counts past <id>Custom when the library already holds it', () => {
    expect(mintForkFxId('Pulse', ['Pulse', 'PulseCustom'])).toEqual({ fxId: 'PulseCustom2', ordinal: 2 })
    expect(mintForkFxId('Pulse', ['Pulse', 'PulseCustom', 'PulseCustom2'])).toEqual({
      fxId: 'PulseCustom3',
      ordinal: 3,
    })
  })

  it('treats ids the registry would resolve alike as taken — its lookup ignores case, spaces and underscores', () => {
    expect(mintForkFxId('Pulse', ['pulse_custom']).fxId).toBe('PulseCustom2')
  })

  it('leaves a free base alone and never returns the source’s own id', () => {
    expect(uniqueFxId('MyFx', ['Pulse']).fxId).toBe('MyFx')
    expect(uniqueFxId('Pulse', ['Pulse']).fxId).toBe('Pulse2')
  })
})

describe('forkRequest', () => {
  const pulse = entry('Pulse', {
    family: 'intensity',
    outputType: 'SLIDER',
    fxMode: 'STATEFUL',
    timingSource: 'WALL_CLOCK',
    parameters: [{ name: 'min', type: 'ubyte', defaultValue: '0', description: 'Floor' }],
    compatibleAttributes: ['dimmer', 'uv'],
    script: 'val x = 1',
  })

  it('copies the built-in’s script, category, output type, mode, parameters, properties and timing', () => {
    const body = forkRequest(pulse, [pulse], [])
    expect(body).toEqual({
      fxId: 'PulseCustom',
      name: 'Pulse (Custom)',
      family: 'intensity',
      outputType: 'SLIDER',
      fxMode: 'STATEFUL',
      parameters: [{ name: 'min', type: 'ubyte', defaultValue: '0', description: 'Floor' }],
      compatibleAttributes: ['dimmer', 'uv'],
      script: 'val x = 1',
      timingSource: 'WALL_CLOCK',
      defaultTiming: 'CYCLE',
    })
    // Copies, not the entry's own arrays — the form may edit them.
    expect(body.parameters).not.toBe(pulse.parameters)
    expect(body.compatibleAttributes).not.toBe(pulse.compatibleAttributes)
  })

  it('keeps a parameter’s metadata but not a curve’s options, which are the desk’s own list', () => {
    const curved = entry('Ramp', {
      parameters: [
        { name: 'min', type: 'ubyte', defaultValue: '0', description: '', label: 'Low', min: 0, max: 255, unit: '%' },
        { name: 'curve', type: 'easingCurve', defaultValue: 'LINEAR', description: '', label: 'Curve', options: [{ value: 'LINEAR', label: 'Linear' }] },
      ],
    })
    const [min, curve] = forkRequest(curved, [curved], []).parameters ?? []
    expect(min).toMatchObject({ label: 'Low', min: 0, max: 255, unit: '%' })
    expect(curve.label).toBe('Curve')
    expect(curve.options).toBeUndefined()
  })

  it('mints against the library and the definitions both, and numbers the name to match', () => {
    const library = [pulse, entry('PulseCustom', { source: 'USER', sourceDefinitionId: 3 })]
    // A definition whose script failed to register still holds its id.
    const body = forkRequest(pulse, library, [definition(3, 'PulseCustom'), definition(4, 'PulseCustom2')])
    expect(body.fxId).toBe('PulseCustom3')
    expect(body.name).toBe('Pulse (Custom 3)')
  })
})
