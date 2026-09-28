import { describe, expect, it } from 'vitest'
import type { EffectLibraryEntry } from '@/store/fixtureFx'
import type { FxDefinition } from '@/store/fxDefinitions'
import {
  definitionsByEffectId,
  effectsRegisteredBy,
  entryName,
  forkRequest,
  fxSourceOf,
  mintForkEffectId,
  uniqueEffectId,
} from './fxLibraryModel'

function entry(name: string, over: Partial<EffectLibraryEntry> = {}): EffectLibraryEntry {
  return {
    name,
    category: 'dimmer',
    outputType: 'SLIDER',
    effectMode: 'STANDARD',
    timingSource: 'BEAT',
    parameters: [],
    compatibleProperties: ['dimmer'],
    source: 'BUILT_IN',
    sourceDefinitionId: null,
    script: null,
    ...over,
  }
}

function definition(id: number, effectId: string, name = effectId): FxDefinition {
  return {
    id,
    effectId,
    name,
    category: 'dimmer',
    outputType: 'SLIDER',
    effectMode: 'STANDARD',
    parameters: [],
    compatibleProperties: ['dimmer'],
    script: 'FxOutput.Slider(0u)',
    defaultStepTiming: false,
    timingSource: 'BEAT',
  }
}

describe('fxSourceOf — the definition list is the discriminator', () => {
  // Both USER entries carry a `sourceDefinitionId`: 7 is a definition's row id, 12 a script's.
  const pulse = entry('Pulse')
  const flicker = entry('RandomFlicker', { source: 'USER', sourceDefinitionId: 7 })
  const warm = entry('WarmFlicker', { source: 'USER', sourceDefinitionId: 12 })
  const byEffectId = definitionsByEffectId([definition(7, 'RandomFlicker', 'Random Flicker')])

  it('reads a built-in, a custom definition and a script’s effect apart', () => {
    expect(fxSourceOf(pulse, byEffectId)).toBe('builtIn')
    expect(fxSourceOf(flicker, byEffectId)).toBe('custom')
    expect(fxSourceOf(warm, byEffectId)).toBe('script')
  })

  it('does not read a script’s effect as custom because its script id happens to be a definition’s row id', () => {
    // The old page's bug: it read every USER `sourceDefinitionId` as an fx_definitions id.
    const clash = entry('WarmFlicker', { source: 'USER', sourceDefinitionId: 7 })
    expect(fxSourceOf(clash, byEffectId)).toBe('script')
  })

  it('names a custom row by its definition and anything else by its id as words', () => {
    expect(entryName(flicker, byEffectId.get('RandomFlicker'))).toBe('Random Flicker')
    expect(entryName(entry('SineWaveCustom2'), definition(9, 'SineWaveCustom2', 'Sine Wave (Custom 2)'))).toBe(
      'Sine Wave (Custom 2)',
    )
    expect(entryName(warm, undefined)).toBe('Warm Flicker')
  })

  it('finds the effects a script registers — and only script-registered ones', () => {
    const library = [pulse, flicker, warm, entry('Ember', { source: 'USER', sourceDefinitionId: 12 })]
    expect(effectsRegisteredBy(12, library, byEffectId).map((e) => e.name)).toEqual(['WarmFlicker', 'Ember'])
    // Script 7 registers nothing, though definition 7's effect carries a 7 too.
    expect(effectsRegisteredBy(7, library, byEffectId)).toEqual([])
  })
})

describe('mintForkEffectId — unique across the whole library', () => {
  it('takes <id>Custom when it is free', () => {
    expect(mintForkEffectId('Pulse', ['Pulse', 'SineWave'])).toEqual({ effectId: 'PulseCustom', ordinal: 1 })
  })

  it('counts past <id>Custom when the library already holds it', () => {
    expect(mintForkEffectId('Pulse', ['Pulse', 'PulseCustom'])).toEqual({ effectId: 'PulseCustom2', ordinal: 2 })
    expect(mintForkEffectId('Pulse', ['Pulse', 'PulseCustom', 'PulseCustom2'])).toEqual({
      effectId: 'PulseCustom3',
      ordinal: 3,
    })
  })

  it('treats ids the registry would resolve alike as taken — its lookup ignores case, spaces and underscores', () => {
    expect(mintForkEffectId('Pulse', ['pulse_custom']).effectId).toBe('PulseCustom2')
  })

  it('leaves a free base alone and never returns the source’s own id', () => {
    expect(uniqueEffectId('MyEffect', ['Pulse']).effectId).toBe('MyEffect')
    expect(uniqueEffectId('Pulse', ['Pulse']).effectId).toBe('Pulse2')
  })
})

describe('forkRequest', () => {
  const pulse = entry('Pulse', {
    category: 'dimmer',
    outputType: 'SLIDER',
    effectMode: 'STATEFUL',
    timingSource: 'WALL_CLOCK',
    parameters: [{ name: 'min', type: 'ubyte', defaultValue: '0', description: 'Floor' }],
    compatibleProperties: ['dimmer', 'uv'],
    script: 'val x = 1',
  })

  it('copies the built-in’s script, category, output type, mode, parameters, properties and timing', () => {
    const body = forkRequest(pulse, [pulse], [])
    expect(body).toEqual({
      effectId: 'PulseCustom',
      name: 'Pulse (Custom)',
      category: 'dimmer',
      outputType: 'SLIDER',
      effectMode: 'STATEFUL',
      parameters: [{ name: 'min', type: 'ubyte', defaultValue: '0', description: 'Floor' }],
      compatibleProperties: ['dimmer', 'uv'],
      script: 'val x = 1',
      timingSource: 'WALL_CLOCK',
      defaultStepTiming: false,
    })
    // Copies, not the entry's own arrays — the form may edit them.
    expect(body.parameters).not.toBe(pulse.parameters)
    expect(body.compatibleProperties).not.toBe(pulse.compatibleProperties)
  })

  it('mints against the library and the definitions both, and numbers the name to match', () => {
    const library = [pulse, entry('PulseCustom', { source: 'USER', sourceDefinitionId: 3 })]
    // A definition whose script failed to register still holds its id.
    const body = forkRequest(pulse, library, [definition(3, 'PulseCustom'), definition(4, 'PulseCustom2')])
    expect(body.effectId).toBe('PulseCustom3')
    expect(body.name).toBe('Pulse (Custom 3)')
  })
})
