// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'
import { render } from '@testing-library/react'
import {
  DEFAULT_FIXTURE_COLOUR,
  FixtureAppearanceSource,
  type FixtureAppearance,
} from './fixtureAppearance'
import { ChannelSourceProvider } from '../../hooks/useChannelSource'
import type { ChannelSource } from '../../api/channelSource'
import type { Fixture, FixtureTypeInfo } from '../../store/fixtures'
import type { FixturePatch } from '../../api/patchApi'
import type { GroupColourPropertyDescriptor } from '../../api/groupsApi'
import {
  chan,
  colourProp,
  makeFixture,
  settingProp,
  sliderProp,
} from '../../test/fixtureFactories'

// usePropertyValues imports lightingApi for its writers, and the real module opens a WebSocket
// at import time. The reads all go through the injected ChannelSource, not the mock.
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

/** A ChannelSource over a fixed map, so a test can state the DMX frame it wants. */
function sourceOf(values: Record<string, number>): ChannelSource {
  const map = new Map(Object.entries(values))
  return {
    get: (universe, channelNo) => map.get(`${universe}:${channelNo}`) ?? 0,
    getByKey: (key) => map.get(key) ?? 0,
    subscribeToChannel: () => ({ unsubscribe: () => {} }),
  }
}

const PATCH: FixturePatch = {
  id: 1,
  key: 'fx-1',
  displayName: 'Fixture 1',
} as FixturePatch

function patchWith(over: Partial<FixturePatch>): FixturePatch {
  return { ...PATCH, ...over }
}

function typeWith(over: Partial<FixtureTypeInfo>): FixtureTypeInfo {
  return { typeKey: 'test-type', ...over } as FixtureTypeInfo
}

/** Render the render prop and return the single appearance it yielded. */
function appearanceOf(
  values: Record<string, number>,
  fixture: Fixture | undefined,
  patch: FixturePatch = PATCH,
  fixtureType: FixtureTypeInfo | undefined = undefined,
): FixtureAppearance {
  let captured: FixtureAppearance | undefined
  render(
    <ChannelSourceProvider source={sourceOf(values)}>
      <FixtureAppearanceSource patch={patch} fixture={fixture} fixtureType={fixtureType}>
        {(appearance) => {
          captured = appearance
          return null
        }}
      </FixtureAppearanceSource>
    </ChannelSourceProvider>,
  )
  if (!captured) throw new Error('render prop never ran')
  return captured
}

describe('FixtureAppearanceSource', () => {
  it('reads an RGB fixture as its full-brightness hue at dimmer × colour', () => {
    const fixture = makeFixture('fx-1', [
      sliderProp('dimmer', 'dimmer', chan(1)),
      colourProp('rgbColour', chan(2), chan(3), chan(4)),
    ])
    // Half dimmer, red at half: hue normalises to pure red, level is 0.5 × 0.5.
    const a = appearanceOf({ '0:1': 128, '0:2': 128 }, fixture)
    expect(a.color).toBe('rgb(255, 0, 0)')
    expect(a.intensity).toBeCloseTo((128 / 255) * (128 / 255), 5)
    expect(a.segments).toBeUndefined()
  })

  it('reads a colour-only fixture at RGB 0 as dark', () => {
    // No dimmer at all, so the colour magnitude is the only brightness signal. Without this a
    // dimmerless fixture would beam at full whatever its colour said.
    const fixture = makeFixture('fx-1', [colourProp('rgbColour', chan(2), chan(3), chan(4))])
    expect(appearanceOf({}, fixture).intensity).toBe(0)
  })

  it('folds white, amber and UV into the hue', () => {
    const fixture = makeFixture('fx-1', [
      colourProp('rgbColour', chan(2), chan(3), chan(4), {
        whiteChannel: chan(5),
        amberChannel: chan(6),
        uvChannel: chan(7),
      }),
    ])
    const a = appearanceOf({ '0:5': 255 }, fixture)
    expect(a.color).toBe('rgb(255, 255, 255)')
    expect(a.intensity).toBe(1)
  })

  it('reads a colour wheel through its selected option preview', () => {
    const fixture = makeFixture('fx-1', [
      sliderProp('dimmer', 'dimmer', chan(1)),
      settingProp('colourWheel', 'colour', chan(2), [
        { name: 'open', level: 0, displayName: 'Open' },
        { name: 'blue', level: 10, displayName: 'Blue', colourPreview: '#0000ff' },
      ]),
    ])
    const a = appearanceOf({ '0:1': 255, '0:2': 10 }, fixture)
    expect(a.color).toBe('#0000ff')
    expect(a.intensity).toBe(1)
  })

  it('reads a wheel option with no preview as dark', () => {
    const fixture = makeFixture('fx-1', [
      sliderProp('dimmer', 'dimmer', chan(1)),
      settingProp('colourWheel', 'colour', chan(2), [
        { name: 'open', level: 0, displayName: 'Open' },
      ]),
    ])
    expect(appearanceOf({ '0:1': 255 }, fixture).intensity).toBe(0)
  })

  it('uses the gel colour when the type accepts gel and there is no colour source', () => {
    const fixture = makeFixture('fx-1', [sliderProp('dimmer', 'dimmer', chan(1))])
    const a = appearanceOf(
      { '0:1': 255 },
      fixture,
      patchWith({ gelCode: 'L106' }),
      typeWith({ acceptsGel: true }),
    )
    expect(a.color).not.toBe(DEFAULT_FIXTURE_COLOUR)
    expect(a.intensity).toBe(1)
  })

  it('ignores a gel code when the type does not accept gel', () => {
    // Stale data: colouring by it would contradict the other two surfaces, which both gate on
    // `acceptsGel`. The 2D plot's old gel-only path did not, which is the divergence this fixes.
    const fixture = makeFixture('fx-1', [sliderProp('dimmer', 'dimmer', chan(1))])
    const a = appearanceOf(
      { '0:1': 255 },
      fixture,
      patchWith({ gelCode: 'L106' }),
      typeWith({ acceptsGel: false }),
    )
    expect(a.color).toBe(DEFAULT_FIXTURE_COLOUR)
  })

  it('falls back to tungsten and the dimmer alone for a dimmer-only fixture', () => {
    const fixture = makeFixture('fx-1', [sliderProp('dimmer', 'dimmer', chan(1))])
    const a = appearanceOf({ '0:1': 64 }, fixture)
    expect(a.color).toBe(DEFAULT_FIXTURE_COLOUR)
    expect(a.intensity).toBeCloseTo(64 / 255, 5)
  })

  it('reads a fixture with no dimmer and no colour as fully on', () => {
    // Deliberate: there is no brightness signal to gate on, so treating it as dark would hide
    // every generic fixture on the plot.
    const fixture = makeFixture('fx-1', [])
    expect(appearanceOf({}, fixture).intensity).toBe(1)
  })

  it('renders a patch with no fixture as a dim grey placeholder', () => {
    const a = appearanceOf({}, undefined)
    expect(a.color).toBe('#666')
    expect(a.intensity).toBe(0.2)
  })

  it('emits per-pixel segments for a multi-element fixture', () => {
    const groupColour: GroupColourPropertyDescriptor = {
      type: 'colour',
      name: 'rgbColour',
      displayName: 'Colour',
      category: 'colour',
      memberColourChannels: [
        { fixtureKey: 'fx-1.pixel-0', redChannel: chan(2), greenChannel: chan(3), blueChannel: chan(4) },
        { fixtureKey: 'fx-1.pixel-1', redChannel: chan(5), greenChannel: chan(6), blueChannel: chan(7) },
      ],
    } as GroupColourPropertyDescriptor

    const fixture = makeFixture('fx-1', [sliderProp('dimmer', 'dimmer', chan(1))], {
      elementGroupProperties: [groupColour],
    })

    // Pixel 0 full red, pixel 1 off, dimmer full.
    const a = appearanceOf({ '0:1': 255, '0:2': 255 }, fixture)
    expect(a.segments).toHaveLength(2)
    expect(a.segments?.[0]).toEqual({ css: 'rgb(255, 0, 0)', intensity: 1 })
    expect(a.segments?.[1]).toEqual({ css: 'rgb(0, 0, 0)', intensity: 0 })
  })

  it('does not treat a single-element group as a pixel strip', () => {
    // One member is not a bar; it should take the plain colour path so it draws as a dot.
    const groupColour: GroupColourPropertyDescriptor = {
      type: 'colour',
      name: 'rgbColour',
      displayName: 'Colour',
      category: 'colour',
      memberColourChannels: [
        { fixtureKey: 'fx-1.pixel-0', redChannel: chan(2), greenChannel: chan(3), blueChannel: chan(4) },
      ],
    } as GroupColourPropertyDescriptor

    const fixture = makeFixture('fx-1', [colourProp('rgbColour', chan(2), chan(3), chan(4))], {
      elementGroupProperties: [groupColour],
    })
    expect(appearanceOf({ '0:2': 255 }, fixture).segments).toBeUndefined()
  })
})
