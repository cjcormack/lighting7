import { describe, expect, it } from 'vitest'
import {
  buildProgrammerChannelMap,
  channelMapKey,
  descriptorsByTarget,
  resolveEntryChannels,
  type ResolvableEntry,
} from './programmerChannels'
import { serializeColour } from './programmerValue'
import {
  chan,
  colourProp,
  element,
  makeFixture,
  makePixelBar,
  positionProp,
  settingProp,
  sliderProp,
} from '../test/fixtureFactories'


/** Resolved channels as a plain object, so assertions read as key → value. */
function asMap(channels: { key: string; value: number }[]): Record<string, number> {
  return Object.fromEntries(channels.map((c) => [c.key, c.value]))
}

function entry(propertyName: string, value: string, over: Partial<ResolvableEntry> = {}) {
  return { targetKey: 'fx-1', propertyName, value, ...over }
}

describe('resolveEntryChannels', () => {
  it('maps a slider level onto its one channel', () => {
    const dimmer = sliderProp('dimmer', 'dimmer', chan(1))
    expect(asMap(resolveEntryChannels([dimmer], entry('dimmer', '200')))).toEqual({ '0:1': 200 })
  })

  it('maps a setting level onto its one channel', () => {
    // Slider and setting share the wire form; only the descriptor distinguishes them.
    const gobo = settingProp('goboWheel', 'gobo', chan(7))
    expect(asMap(resolveEntryChannels([gobo], entry('goboWheel', '42')))).toEqual({ '0:7': 42 })
  })

  it('maps a colour onto red, green and blue', () => {
    const colour = colourProp('rgbColour', chan(2), chan(3), chan(4))
    const value = serializeColour(10, 20, 30)
    expect(asMap(resolveEntryChannels([colour], entry('rgbColour', value)))).toEqual({
      '0:2': 10,
      '0:3': 20,
      '0:4': 30,
    })
  })

  it('emits white, amber and UV only where the descriptor carries that channel', () => {
    const withWhite = colourProp('rgbColour', chan(2), chan(3), chan(4), {
      whiteChannel: chan(5),
    })
    const value = serializeColour(1, 2, 3, 40, 50, 60)

    // White is present, so it lands; amber and UV have no channel on this fixture and are
    // dropped rather than written somewhere arbitrary.
    expect(asMap(resolveEntryChannels([withWhite], entry('rgbColour', value)))).toEqual({
      '0:2': 1,
      '0:3': 2,
      '0:4': 3,
      '0:5': 40,
    })

    const withAll = colourProp('rgbColour', chan(2), chan(3), chan(4), {
      whiteChannel: chan(5),
      amberChannel: chan(6),
      uvChannel: chan(7),
    })
    expect(asMap(resolveEntryChannels([withAll], entry('rgbColour', value)))).toEqual({
      '0:2': 1,
      '0:3': 2,
      '0:4': 3,
      '0:5': 40,
      '0:6': 50,
      '0:7': 60,
    })
  })

  it('maps a position onto the coarse pan and tilt channels only', () => {
    // Fine channels are deliberately absent: the backend writes none for a position
    // assignment either, so emitting them would disagree with the wire.
    const position = positionProp('position', chan(1), chan(2))
    const fine = sliderProp('panFine', 'pan', chan(3), { axis: 'PAN' })
    expect(asMap(resolveEntryChannels([position, fine], entry('position', '100,150')))).toEqual({
      '0:1': 100,
      '0:2': 150,
    })
  })

  // Two tests stood here: a palette reference resolving through its `resolvedValue`, and one with
  // nothing resolved yielding no channels rather than a guess. Both retired with the `ref:` grammar
  // in session 4 — an entry's `value` is now always the literal.
  it('yields nothing when the parsed shape does not match the descriptor', () => {
    const dimmer = sliderProp('dimmer', 'dimmer', chan(1))
    const colour = colourProp('rgbColour', chan(2), chan(3), chan(4))
    // A colour value on a slider, and a level on a colour property.
    expect(resolveEntryChannels([dimmer], entry('dimmer', serializeColour(1, 2, 3)))).toEqual([])
    expect(resolveEntryChannels([colour], entry('rgbColour', '200'))).toEqual([])
  })

  it('yields nothing for an unknown property name, absent descriptors, or junk', () => {
    const dimmer = sliderProp('dimmer', 'dimmer', chan(1))
    expect(resolveEntryChannels([dimmer], entry('strobe', '200'))).toEqual([])
    expect(resolveEntryChannels(undefined, entry('dimmer', '200'))).toEqual([])
    expect(resolveEntryChannels([dimmer], entry('dimmer', 'not-a-value'))).toEqual([])
  })
})

describe('descriptorsByTarget', () => {
  it('indexes element keys alongside fixture keys', () => {
    // Multi-head entries are keyed by element key, so an index of fixture keys alone would
    // silently drop every per-head write.
    const bar = makePixelBar('bar-1', 2, [sliderProp('dimmer', 'dimmer', chan(1))])
    const index = descriptorsByTarget([bar])

    expect(index.get('bar-1')?.map((p) => p.name)).toEqual(['dimmer'])
    expect(index.get('bar-1.pixel-0')?.map((p) => p.name)).toEqual(['rgbColour'])
    expect(index.get('bar-1.pixel-1')?.map((p) => p.name)).toEqual(['rgbColour'])
  })

  it('resolves an element-keyed entry against the element descriptor', () => {
    const head = colourProp('rgbColour', chan(20), chan(21), chan(22))
    const fixture = makeFixture('mover-1', [sliderProp('dimmer', 'dimmer', chan(19))], {
      elements: [element(0, 'mover-1.head-0', [head])],
    })
    const index = descriptorsByTarget([fixture])

    const resolved = resolveEntryChannels(index.get('mover-1.head-0'), {
      targetKey: 'mover-1.head-0',
      propertyName: 'rgbColour',
      value: serializeColour(5, 6, 7),
    })
    expect(asMap(resolved)).toEqual({ '0:20': 5, '0:21': 6, '0:22': 7 })
  })
})

describe('buildProgrammerChannelMap', () => {
  it('merges every entry across targets', () => {
    const one = makeFixture('fx-1', [sliderProp('dimmer', 'dimmer', chan(1))])
    const two = makeFixture('fx-2', [colourProp('rgbColour', chan(2), chan(3), chan(4))])
    const map = buildProgrammerChannelMap(
      [
        { targetKey: 'fx-1', propertyName: 'dimmer', value: '255' },
        { targetKey: 'fx-2', propertyName: 'rgbColour', value: serializeColour(11, 12, 13) },
      ],
      [],
      descriptorsByTarget([one, two]),
    )
    expect(Object.fromEntries(map)).toEqual({ '0:1': 255, '0:2': 11, '0:3': 12, '0:4': 13 })
  })

  it('lets a sideband channel win over a property-derived one', () => {
    // Documented approximation: the backend arbitrates these by write sequence, which the
    // client never sees, so the sideband is applied last.
    const fixture = makeFixture('fx-1', [sliderProp('dimmer', 'dimmer', chan(1))])
    const map = buildProgrammerChannelMap(
      [{ targetKey: 'fx-1', propertyName: 'dimmer', value: '255' }],
      [{ universe: 0, channel: 1, value: 64 }],
      descriptorsByTarget([fixture]),
    )
    expect(map.get('0:1')).toBe(64)
  })

  it('carries a sideband channel with no backing property', () => {
    const map = buildProgrammerChannelMap([], [{ universe: 2, channel: 300, value: 7 }], new Map())
    expect(Object.fromEntries(map)).toEqual({ '2:300': 7 })
  })

  it('is empty for an empty programmer', () => {
    expect(buildProgrammerChannelMap([], [], new Map()).size).toBe(0)
  })
})

describe('channelMapKey', () => {
  it('matches the channelsApi map key form', () => {
    expect(channelMapKey({ universe: 3, channelNo: 12 })).toBe('3:12')
  })
})
