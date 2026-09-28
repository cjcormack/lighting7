import type {
  ChannelRef,
  ColourPropertyDescriptor,
  ElementDescriptor,
  Fixture,
  PositionPropertyDescriptor,
  PropertyCategory,
  PropertyDescriptor,
  SettingOption,
  SettingPropertyDescriptor,
  SliderPropertyDescriptor,
} from '@/store/fixtures'
import type { GroupSummary } from '@/api/groupsApi'
import type { ActiveEffect } from '@/store/fixtureFx'

// Hand-built descriptor/fixture factories for fixtures-list tests. Type-only
// imports from store/fixtures, so importing this file never touches the store
// or the WebSocket layer.

export function chan(channelNo: number, universe = 0): ChannelRef {
  return { universe, channelNo }
}

export function sliderProp(
  name: string,
  category: PropertyCategory,
  channel: ChannelRef,
  over: Partial<SliderPropertyDescriptor> = {},
): SliderPropertyDescriptor {
  return {
    type: 'slider',
    name,
    displayName: name,
    category,
    channel,
    min: 0,
    max: 255,
    ...over,
  }
}

export function colourProp(
  name: string,
  red: ChannelRef,
  green: ChannelRef,
  blue: ChannelRef,
  over: Partial<ColourPropertyDescriptor> = {},
): ColourPropertyDescriptor {
  return {
    type: 'colour',
    name,
    displayName: name,
    category: 'colour',
    redChannel: red,
    greenChannel: green,
    blueChannel: blue,
    ...over,
  }
}

export function settingProp(
  name: string,
  category: PropertyCategory,
  channel: ChannelRef,
  options: SettingOption[] = [{ name: 'open', level: 0, displayName: 'Open' }],
): SettingPropertyDescriptor {
  return {
    type: 'setting',
    name,
    displayName: name,
    category,
    channel,
    options,
  }
}

export function positionProp(
  name: string,
  pan: ChannelRef,
  tilt: ChannelRef,
  over: Partial<PositionPropertyDescriptor> = {},
): PositionPropertyDescriptor {
  return {
    type: 'position',
    name,
    displayName: name,
    category: 'position',
    panChannel: pan,
    tiltChannel: tilt,
    panMin: 0,
    panMax: 255,
    tiltMin: 0,
    tiltMax: 255,
    ...over,
  }
}

let nextChannel = 1

export function makeFixture(
  key: string,
  properties: PropertyDescriptor[],
  over: Partial<Fixture> = {},
): Fixture {
  const firstChannel = nextChannel
  nextChannel += 8
  return {
    key,
    name: key,
    typeKey: 'test-type',
    universe: 0,
    firstChannel,
    channelCount: 8,
    channels: [],
    properties,
    capabilities: [],
    groups: [],
    compatibleLookIds: [],
    ...over,
  }
}

export function groupSummary(name: string, memberCount = 0): GroupSummary {
  return {
    name,
    memberCount,
    capabilities: [],
    symmetricMode: 'NONE',
    defaultDistribution: 'LINEAR',
    compatibleLookIds: [],
  }
}

export function element(
  index: number,
  key: string,
  properties: PropertyDescriptor[],
  displayName = `Head ${index + 1}`,
): ElementDescriptor {
  return { index, key, displayName, properties }
}

/**
 * N-head pixel bar: no parent-level properties unless given; one RGB colour
 * property per head on fresh channels, elements keyed `${key}.pixel-${i}`
 * (matching the live LED Lightbar shape).
 */
export function makePixelBar(
  key: string,
  heads: number,
  parentProps: PropertyDescriptor[] = [],
  over: Partial<Fixture> = {},
): Fixture {
  const elements: ElementDescriptor[] = []
  for (let i = 0; i < heads; i++) {
    const first = nextChannel
    nextChannel += 3
    elements.push(
      element(i, `${key}.pixel-${i}`, [
        colourProp('rgbColour', chan(first), chan(first + 1), chan(first + 2)),
      ]),
    )
  }
  return makeFixture(key, parentProps, { elements, ...over })
}

/**
 * One running effect, defaulted to the common case: a busked, programmer-band instance on
 * `hex-1`'s dimmer with no owner at all. Override `lookId` / `templateId` / `programmerLayerId` /
 * `cueId` to make it something else's — which is the distinction `cellEffects.ts` turns on.
 *
 * Here rather than in either test file because two of them wanted the same 25-field literal, and a
 * copy in each is one that goes stale the day `ActiveEffect` gains a field.
 */
export function makeActiveEffect(over: Partial<ActiveEffect> = {}): ActiveEffect {
  return {
    id: 1,
    effectType: 'sinewave',
    targetKey: 'hex-1',
    propertyName: 'dimmer',
    beatDivision: 1,
    blendMode: 'OVERRIDE',
    isRunning: true,
    phaseOffset: 0,
    currentPhase: 0,
    parameters: {},
    isGroupTarget: false,
    distributionStrategy: null,
    elementMode: null,
    elementFilter: null,
    stepTiming: false,
    lookId: null,
    templateId: null,
    sourceName: null,
    programmerLayerId: null,
    cueId: null,
    timingSource: 'BEAT',
    programmerOwned: true,
    intensityMultiplier: 1,
    speedMasterUuid: null,
    rateSpeedMasterUuid: null,
    ...over,
  }
}
