import { describe, expect, it } from 'vitest'
import type { EffectLibraryEntry } from '@/store/fixtureFx'
import type { Fixture, PropertyDescriptor } from '@/store/fixtures'
import {
  compatibleEffectsFor,
  effectsByCategory,
  extraSliderPropertiesFor,
  groupMemberFixtures,
  propertyNamesFor,
  resolveEffectProperty,
  settingPropertiesFor,
  type FxPropertyTarget,
} from './fxTargetProperties'

/**
 * The sentinel rule, pinned.
 *
 * `setting` and `slider` are names the effect library uses and no fixture descriptor carries, so
 * they have to be minted on the way in and spent on the way out. The two halves used to live in two
 * files, and the tests below are what stops them drifting apart again: the *offer* side (does the
 * target grow the sentinel, and is the effect therefore offered) and the *apply* side (does the
 * sentinel resolve back to a real property name) are asserted against the same targets.
 *
 * The dimmer/uv exclusion is asserted rather than described because it is the one rule that reads
 * like an oversight: a dimmer *is* a slider, and the reason it must not answer to the `slider`
 * sentinel is that dimmer effects already declare `dimmer` by name.
 */

const channel = { universe: 0, channelNo: 1 }

function slider(name: string, category: string): PropertyDescriptor {
  return {
    type: 'slider',
    name,
    displayName: name,
    category,
    channel,
    min: 0,
    max: 255,
  } as PropertyDescriptor
}

function setting(name: string): PropertyDescriptor {
  return {
    type: 'setting',
    name,
    displayName: name,
    category: 'gobo',
    channel,
    options: [],
  } as unknown as PropertyDescriptor
}

function colour(name: string): PropertyDescriptor {
  return {
    type: 'colour',
    name,
    displayName: name,
    category: 'colour',
    redChannel: channel,
    greenChannel: channel,
    blueChannel: channel,
  } as PropertyDescriptor
}

function fixture(over: Partial<Fixture> = {}): Fixture {
  return {
    key: 'hex-1',
    name: 'Hex 1',
    typeKey: 'hex',
    universe: 0,
    firstChannel: 1,
    channelCount: 12,
    channels: [],
    properties: [],
    capabilities: [],
    groups: [],
    compatibleLookIds: [],
    ...over,
  }
}

function effect(over: Partial<EffectLibraryEntry> = {}): EffectLibraryEntry {
  return {
    name: 'SineWave',
    category: 'dimmer',
    outputType: 'SLIDER',
    effectMode: 'PHASE',
    parameters: [],
    compatibleProperties: ['dimmer'],
    ...over,
  }
}

const STATIC_SETTING = effect({
  name: 'StaticSetting',
  category: 'controls',
  compatibleProperties: ['setting'],
})
const STATIC_VALUE = effect({
  name: 'StaticValue',
  category: 'controls',
  compatibleProperties: ['slider'],
})
const RAINBOW = effect({ name: 'RainbowCycle', category: 'colour', compatibleProperties: ['rgbColour'] })

describe('propertyNamesFor', () => {
  it('mints the setting sentinel for a fixture with a setting property', () => {
    const target: FxPropertyTarget = {
      type: 'fixture',
      fixture: fixture({ properties: [setting('gobo')] }),
    }
    expect(propertyNamesFor([target])).toEqual(new Set(['gobo', 'setting']))
  })

  it('mints the slider sentinel only for sliders that are neither dimmer nor uv', () => {
    const plain: FxPropertyTarget = {
      type: 'fixture',
      fixture: fixture({ properties: [slider('dimmer', 'dimmer'), slider('uv', 'uv')] }),
    }
    expect(propertyNamesFor([plain]).has('slider')).toBe(false)

    const extra: FxPropertyTarget = {
      type: 'fixture',
      fixture: fixture({ properties: [slider('dimmer', 'dimmer'), slider('zoom', 'beam')] }),
    }
    expect(propertyNamesFor([extra]).has('slider')).toBe(true)
  })

  it('mints no sentinel for a fixture with neither', () => {
    const target: FxPropertyTarget = {
      type: 'fixture',
      fixture: fixture({ properties: [colour('rgbColour')] }),
    }
    expect(propertyNamesFor([target])).toEqual(new Set(['rgbColour']))
  })

  it('takes a group from its capabilities and its members together', () => {
    const target: FxPropertyTarget = {
      type: 'group',
      capabilities: ['dimmer'],
      members: [fixture({ properties: [setting('gobo')] })],
    }
    expect(propertyNamesFor([target])).toEqual(new Set(['dimmer', 'gobo', 'setting']))
  })

  it('mints no sentinel for a group whose member list has not arrived', () => {
    const target: FxPropertyTarget = { type: 'group', capabilities: ['dimmer'], members: [] }
    expect(propertyNamesFor([target])).toEqual(new Set(['dimmer']))
  })

  it('includes element group properties', () => {
    const target: FxPropertyTarget = {
      type: 'fixture',
      fixture: fixture({
        properties: [],
        elementGroupProperties: [{ type: 'slider', name: 'headDimmer' }] as never,
      }),
    }
    expect(propertyNamesFor([target]).has('headDimmer')).toBe(true)
  })

  it('unions across several targets', () => {
    const a: FxPropertyTarget = { type: 'fixture', fixture: fixture({ properties: [colour('rgbColour')] }) }
    const b: FxPropertyTarget = {
      type: 'fixture',
      fixture: fixture({ key: 'spot-1', properties: [slider('zoom', 'beam')] }),
    }
    expect(propertyNamesFor([a, b])).toEqual(new Set(['rgbColour', 'zoom', 'slider']))
  })
})

describe('compatibleEffectsFor', () => {
  it('offers a sentinel effect exactly when the target minted the sentinel', () => {
    const library = [RAINBOW, STATIC_SETTING, STATIC_VALUE]
    const goboHead: FxPropertyTarget = {
      type: 'fixture',
      fixture: fixture({ properties: [setting('gobo')] }),
    }
    const dimmerOnly: FxPropertyTarget = {
      type: 'fixture',
      fixture: fixture({ properties: [slider('dimmer', 'dimmer')] }),
    }

    expect(compatibleEffectsFor(library, propertyNamesFor([goboHead])).map((e) => e.name)).toEqual([
      'StaticSetting',
    ])
    expect(compatibleEffectsFor(library, propertyNamesFor([dimmerOnly]))).toEqual([])
  })

  it('is empty while the library is still arriving', () => {
    expect(compatibleEffectsFor(undefined, new Set(['dimmer']))).toEqual([])
  })
})

describe('effectsByCategory', () => {
  it('groups in library order and honours the exclusion list', () => {
    const grouped = effectsByCategory([RAINBOW, STATIC_SETTING, STATIC_VALUE])
    expect(Object.keys(grouped)).toEqual(['colour', 'controls'])
    expect(grouped.controls.map((e) => e.name)).toEqual(['StaticSetting', 'StaticValue'])

    expect(Object.keys(effectsByCategory([RAINBOW, STATIC_SETTING], { exclude: ['controls'] }))).toEqual([
      'colour',
    ])
  })
})

describe('resolveEffectProperty', () => {
  const goboAndColourWheel: FxPropertyTarget = {
    type: 'fixture',
    fixture: fixture({ properties: [setting('gobo'), setting('colourWheel')] }),
  }

  it('returns a non-sentinel match unchanged', () => {
    const target: FxPropertyTarget = {
      type: 'fixture',
      fixture: fixture({ properties: [colour('rgbColour')] }),
    }
    expect(resolveEffectProperty(target, RAINBOW)).toBe('rgbColour')
  })

  it('spends the setting sentinel on the first setting property', () => {
    expect(resolveEffectProperty(goboAndColourWheel, STATIC_SETTING)).toBe('gobo')
  })

  it('honours an explicit setting pick', () => {
    expect(
      resolveEffectProperty(goboAndColourWheel, STATIC_SETTING, { setting: 'colourWheel' }),
    ).toBe('colourWheel')
  })

  it('falls back to the first when the explicit pick is not on this target', () => {
    expect(resolveEffectProperty(goboAndColourWheel, STATIC_SETTING, { setting: 'prism' })).toBe(
      'gobo',
    )
  })

  it('spends the slider sentinel on an extra slider, never on dimmer or uv', () => {
    const target: FxPropertyTarget = {
      type: 'fixture',
      fixture: fixture({
        properties: [slider('dimmer', 'dimmer'), slider('uv', 'uv'), slider('zoom', 'beam')],
      }),
    }
    expect(resolveEffectProperty(target, STATIC_VALUE)).toBe('zoom')
  })

  it('returns null when the effect fits nothing on the target', () => {
    const target: FxPropertyTarget = {
      type: 'fixture',
      fixture: fixture({ properties: [slider('dimmer', 'dimmer')] }),
    }
    expect(resolveEffectProperty(target, RAINBOW)).toBeNull()
  })

  it('resolves a group sentinel from its members', () => {
    const target: FxPropertyTarget = {
      type: 'group',
      capabilities: ['dimmer'],
      members: [fixture({ properties: [] }), fixture({ key: 'spot-2', properties: [setting('gobo')] })],
    }
    expect(resolveEffectProperty(target, STATIC_SETTING)).toBe('gobo')
  })
})

describe('property collections', () => {
  const targets: FxPropertyTarget[] = [
    { type: 'fixture', fixture: fixture({ properties: [setting('gobo'), slider('zoom', 'beam')] }) },
    {
      type: 'fixture',
      fixture: fixture({ key: 'spot-2', properties: [setting('gobo'), setting('prism'), slider('dimmer', 'dimmer')] }),
    },
  ]

  it('dedupes by name, first occurrence winning', () => {
    expect(settingPropertiesFor(targets).map((p) => p.name)).toEqual(['gobo', 'prism'])
    expect(extraSliderPropertiesFor(targets).map((p) => p.name)).toEqual(['zoom'])
  })
})

describe('groupMemberFixtures', () => {
  it('matches on group membership and tolerates an absent fixture list', () => {
    const members = [fixture({ key: 'a', groups: ['front'] }), fixture({ key: 'b', groups: ['back'] })]
    expect(groupMemberFixtures(members, 'front').map((f) => f.key)).toEqual(['a'])
    expect(groupMemberFixtures(undefined, 'front')).toEqual([])
  })
})
