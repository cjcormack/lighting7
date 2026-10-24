import { describe, expect, it } from 'vitest'
import type { FxLibraryEntry } from '@/store/fixtureFx'
import type { Fixture, AttributeDescriptor } from '@/store/fixtures'
import {
  compatibleFxFor,
  fxByFamily,
  extraSliderAttributesFor,
  groupMemberFixtures,
  attributesFor,
  resolveFxAttribute,
  settingAttributesFor,
  type FxAttributeTarget,
} from './fxTargetAttributes'

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

function slider(name: string, category: string): AttributeDescriptor {
  return {
    type: 'slider',
    name,
    displayName: name,
    category,
    channel,
    min: 0,
    max: 255,
  } as AttributeDescriptor
}

function setting(name: string): AttributeDescriptor {
  return {
    type: 'setting',
    name,
    displayName: name,
    category: 'gobo',
    channel,
    options: [],
  } as unknown as AttributeDescriptor
}

function colour(name: string): AttributeDescriptor {
  return {
    type: 'colour',
    name,
    displayName: name,
    category: 'colour',
    redChannel: channel,
    greenChannel: channel,
    blueChannel: channel,
  } as AttributeDescriptor
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
    attributes: [],
    capabilities: [],
    groups: [],
    compatibleLookIds: [],
    ...over,
  }
}

function effect(over: Partial<FxLibraryEntry> = {}): FxLibraryEntry {
  return {
    name: 'SineWave',
    family: 'intensity',
    outputType: 'SLIDER',
    fxMode: 'PHASE',
    parameters: [],
    compatibleAttributes: ['dimmer'],
    ...over,
  }
}

const SETTING_STEP = effect({
  name: 'SettingStep',
  family: 'controls',
  compatibleAttributes: ['setting'],
})
const SLIDER_LEVEL = effect({
  name: 'SliderLevel',
  family: 'controls',
  compatibleAttributes: ['slider'],
})
const RAINBOW = effect({ name: 'RainbowCycle', family: 'colour', compatibleAttributes: ['rgbColour'] })

describe('attributesFor', () => {
  it('mints the setting sentinel for a fixture with a setting property', () => {
    const target: FxAttributeTarget = {
      type: 'fixture',
      fixture: fixture({ attributes: [setting('gobo')] }),
    }
    expect(attributesFor([target])).toEqual(new Set(['gobo', 'setting']))
  })

  it('mints the slider sentinel only for sliders that are neither dimmer nor uv', () => {
    const plain: FxAttributeTarget = {
      type: 'fixture',
      fixture: fixture({ attributes: [slider('dimmer', 'dimmer'), slider('uv', 'uv')] }),
    }
    expect(attributesFor([plain]).has('slider')).toBe(false)

    const extra: FxAttributeTarget = {
      type: 'fixture',
      fixture: fixture({ attributes: [slider('dimmer', 'dimmer'), slider('zoom', 'beam')] }),
    }
    expect(attributesFor([extra]).has('slider')).toBe(true)
  })

  it('mints no sentinel for a fixture with neither', () => {
    const target: FxAttributeTarget = {
      type: 'fixture',
      fixture: fixture({ attributes: [colour('rgbColour')] }),
    }
    expect(attributesFor([target])).toEqual(new Set(['rgbColour']))
  })

  it('takes a group from its capabilities and its members together', () => {
    const target: FxAttributeTarget = {
      type: 'group',
      capabilities: ['dimmer'],
      members: [fixture({ attributes: [setting('gobo')] })],
    }
    expect(attributesFor([target])).toEqual(new Set(['dimmer', 'gobo', 'setting']))
  })

  it('mints no sentinel for a group whose member list has not arrived', () => {
    const target: FxAttributeTarget = { type: 'group', capabilities: ['dimmer'], members: [] }
    expect(attributesFor([target])).toEqual(new Set(['dimmer']))
  })

  it('includes element group properties', () => {
    const target: FxAttributeTarget = {
      type: 'fixture',
      fixture: fixture({
        attributes: [],
        elementGroupAttributes: [{ type: 'slider', name: 'headDimmer' }] as never,
      }),
    }
    expect(attributesFor([target]).has('headDimmer')).toBe(true)
  })

  it('unions across several targets', () => {
    const a: FxAttributeTarget = { type: 'fixture', fixture: fixture({ attributes: [colour('rgbColour')] }) }
    const b: FxAttributeTarget = {
      type: 'fixture',
      fixture: fixture({ key: 'spot-1', attributes: [slider('zoom', 'beam')] }),
    }
    expect(attributesFor([a, b])).toEqual(new Set(['rgbColour', 'zoom', 'slider']))
  })
})

describe('compatibleFxFor', () => {
  it('offers a sentinel effect exactly when the target minted the sentinel', () => {
    const library = [RAINBOW, SETTING_STEP, SLIDER_LEVEL]
    const goboHead: FxAttributeTarget = {
      type: 'fixture',
      fixture: fixture({ attributes: [setting('gobo')] }),
    }
    const dimmerOnly: FxAttributeTarget = {
      type: 'fixture',
      fixture: fixture({ attributes: [slider('dimmer', 'dimmer')] }),
    }

    expect(compatibleFxFor(library, attributesFor([goboHead])).map((e) => e.name)).toEqual([
      'SettingStep',
    ])
    expect(compatibleFxFor(library, attributesFor([dimmerOnly]))).toEqual([])
  })

  it('is empty while the library is still arriving', () => {
    expect(compatibleFxFor(undefined, new Set(['dimmer']))).toEqual([])
  })
})

describe('fxByFamily', () => {
  it('groups in library order and honours the exclusion list', () => {
    const grouped = fxByFamily([RAINBOW, SETTING_STEP, SLIDER_LEVEL])
    expect(Object.keys(grouped)).toEqual(['colour', 'controls'])
    expect(grouped.controls.map((e) => e.name)).toEqual(['SettingStep', 'SliderLevel'])

    expect(Object.keys(fxByFamily([RAINBOW, SETTING_STEP], { exclude: ['controls'] }))).toEqual([
      'colour',
    ])
  })
})

describe('resolveFxAttribute', () => {
  const goboAndColourWheel: FxAttributeTarget = {
    type: 'fixture',
    fixture: fixture({ attributes: [setting('gobo'), setting('colourWheel')] }),
  }

  it('returns a non-sentinel match unchanged', () => {
    const target: FxAttributeTarget = {
      type: 'fixture',
      fixture: fixture({ attributes: [colour('rgbColour')] }),
    }
    expect(resolveFxAttribute(target, RAINBOW)).toBe('rgbColour')
  })

  it('spends the setting sentinel on the first setting property', () => {
    expect(resolveFxAttribute(goboAndColourWheel, SETTING_STEP)).toBe('gobo')
  })

  it('honours an explicit setting pick', () => {
    expect(
      resolveFxAttribute(goboAndColourWheel, SETTING_STEP, { setting: 'colourWheel' }),
    ).toBe('colourWheel')
  })

  it('falls back to the first when the explicit pick is not on this target', () => {
    expect(resolveFxAttribute(goboAndColourWheel, SETTING_STEP, { setting: 'prism' })).toBe(
      'gobo',
    )
  })

  it('spends the slider sentinel on an extra slider, never on dimmer or uv', () => {
    const target: FxAttributeTarget = {
      type: 'fixture',
      fixture: fixture({
        attributes: [slider('dimmer', 'dimmer'), slider('uv', 'uv'), slider('zoom', 'beam')],
      }),
    }
    expect(resolveFxAttribute(target, SLIDER_LEVEL)).toBe('zoom')
  })

  it('returns null when the effect fits nothing on the target', () => {
    const target: FxAttributeTarget = {
      type: 'fixture',
      fixture: fixture({ attributes: [slider('dimmer', 'dimmer')] }),
    }
    expect(resolveFxAttribute(target, RAINBOW)).toBeNull()
  })

  it('resolves a group sentinel from its members', () => {
    const target: FxAttributeTarget = {
      type: 'group',
      capabilities: ['dimmer'],
      members: [fixture({ attributes: [] }), fixture({ key: 'spot-2', attributes: [setting('gobo')] })],
    }
    expect(resolveFxAttribute(target, SETTING_STEP)).toBe('gobo')
  })
})

describe('property collections', () => {
  const targets: FxAttributeTarget[] = [
    { type: 'fixture', fixture: fixture({ attributes: [setting('gobo'), slider('zoom', 'beam')] }) },
    {
      type: 'fixture',
      fixture: fixture({ key: 'spot-2', attributes: [setting('gobo'), setting('prism'), slider('dimmer', 'dimmer')] }),
    },
  ]

  it('dedupes by name, first occurrence winning', () => {
    expect(settingAttributesFor(targets).map((p) => p.name)).toEqual(['gobo', 'prism'])
    expect(extraSliderAttributesFor(targets).map((p) => p.name)).toEqual(['zoom'])
  })
})

describe('groupMemberFixtures', () => {
  it('matches on group membership and tolerates an absent fixture list', () => {
    const members = [fixture({ key: 'a', groups: ['front'] }), fixture({ key: 'b', groups: ['back'] })]
    expect(groupMemberFixtures(members, 'front').map((f) => f.key)).toEqual(['a'])
    expect(groupMemberFixtures(undefined, 'front')).toEqual([])
  })
})
