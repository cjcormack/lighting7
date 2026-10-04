// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'
import { act, render } from '@testing-library/react'
import { Color } from 'three'
import { ColourSync } from './FixtureModel'
import {
  FixtureAppearanceSource,
  type FixtureAppearance,
} from '../fixtures/fixtureAppearance'
import { ChannelSourceProvider } from '../../hooks/useChannelSource'
import type { ChannelSource } from '../../api/channelSource'
import {
  findColourSource,
  findDimmerProperty,
  type Fixture,
  type FixtureTypeInfo,
} from '../../store/fixtures'
import type { FixturePatch } from '../../api/patchApi'
import { findGel, indexGels, type Gel } from '../../lib/gels'
import { colourFilters, fittedProperties, type FittedMedia } from '../../lib/fittedMedia'
import gelsJson from '../../../../src/main/resources/gels.json'
import { chan, colourProp, makeFixture, settingProp, sliderProp } from '../../test/fixtureFactories'
import { BAND_STEP_S, ColourBandClockContext, type ColourBandClock } from '../../lib/colourBands'
import { createColourTicker, type ColourTicker } from './colourTicker'

// usePropertyValues imports lightingApi for its writers, and the real module opens a WebSocket
// at import time. The reads all go through the injected ChannelSource, not the mock.
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

// The served gel library (`GET /gels`), read from the resource the desk serves it from.
const GELS = indexGels(gelsJson as Gel[])
vi.mock('@/hooks/useGelIndex', async () => {
  const { indexGels } = await import('@/lib/gels')
  const gels = (await import('../../../../src/main/resources/gels.json')).default
  return { useGelIndex: () => indexGels(gels) }
})

/**
 * The 2D and 3D colour dispatches must agree, arm for arm.
 *
 * `FixtureAppearanceSource` (React, render prop, feeding the SVG plot and the DOM markers) and
 * `FixtureModel`'s `ColourSync` (imperative, writing the scene from the channel callback) are two
 * copies of one dispatch on purpose — see `docs/stage-vis-engineering.md`, "Why a render prop":
 * R3F is a separate reconciler root and store-driven re-renders drop beat-rate changes, so the 3D
 * path cannot go through hooks. The doc says changing the dispatch means changing both files. This
 * pins that: the same fixture must resolve to the same colour and the same level on both surfaces.
 *
 * It caught nothing when written — the 3D path had simply been *missing* the placeholder arm the
 * 2D path had, which is the divergence this exists to make loud next time.
 */

// Cone opacity is the linear intensity times this fixed scale — see applyColour in FixtureModel.
const CONE_SCALE = 0.32

interface Resolved {
  colour: string
  intensity: number
}

/**
 * Both surfaces at one time, so an animated band compares: the 2D clock and the 3D ticker are each
 * handed [timeS] — the decode itself reads no clock (`lib/colourBands.ts`).
 */
function clockAt(timeS: number): ColourBandClock {
  return { now: () => timeS, subscribe: () => () => {} }
}

function tickerAt(timeS: number): ColourTicker {
  return { now: () => timeS, onFrame: () => () => {} }
}

function sourceOf(values: Record<string, number>): ChannelSource {
  const map = new Map(Object.entries(values))
  return {
    get: (universe, channelNo) => map.get(`${universe}:${channelNo}`) ?? 0,
    getByKey: (key) => map.get(key) ?? 0,
    subscribeToChannel: () => ({ unsubscribe: () => {} }),
  }
}

/** Normalise both surfaces' colours — one yields a CSS string, the other a three Color. */
function hex(css: string): string {
  return `#${new Color(css).getHexString()}`
}

function resolve2D(scenario: Scenario, timeS = 0): Resolved {
  let captured: FixtureAppearance | undefined
  render(
    <ColourBandClockContext.Provider value={clockAt(timeS)}>
      <ChannelSourceProvider source={sourceOf(scenario.values)}>
        <FixtureAppearanceSource
          patch={scenario.patch}
          fixture={scenario.fixture}
          fixtureType={scenario.fixtureType}
        >
          {(appearance) => {
            captured = appearance
            return null
          }}
        </FixtureAppearanceSource>
      </ChannelSourceProvider>
    </ColourBandClockContext.Provider>,
  )
  if (!captured) throw new Error('render prop never ran')
  return { colour: hex(captured.color), intensity: captured.intensity }
}

function resolve3D(scenario: Scenario, timeS = 0): Resolved {
  // The derivation FixtureModel performs before handing ColourSync its props. Duplicated here
  // rather than exported, because the seam under test is the *dispatch*, not the lookups.
  const { patch, fixture, fixtureType } = scenario
  const properties = fittedProperties(fixture?.properties, patch.media, GELS)
  const colourSource = properties ? findColourSource(properties) : undefined
  const gel = !colourSource && fixtureType?.acceptsGel && patch.gelCode ? findGel(GELS, patch.gelCode) : null

  const colorStateRef = {
    current: { color: new Color('#000000'), coneOpacity: -1, poolOpacity: -1 },
  }
  render(
    <ChannelSourceProvider source={sourceOf(scenario.values)}>
      <ColourSync
        hasFixture={!!fixture}
        colourSource={colourSource}
        gel={gel}
        filters={colourFilters(properties, colourSource)}
        dimmerProp={findDimmerProperty(properties)}
        lensRef={{ current: null }}
        colorStateRef={colorStateRef}
        ticker={tickerAt(timeS)}
      />
    </ChannelSourceProvider>,
  )
  return {
    colour: `#${colorStateRef.current.color.getHexString()}`,
    intensity: colorStateRef.current.coneOpacity / CONE_SCALE,
  }
}

interface Scenario {
  patch: FixturePatch
  fixture: Fixture | undefined
  fixtureType: FixtureTypeInfo | undefined
  values: Record<string, number>
}

const PATCH = { id: 1, key: 'fx-1', displayName: 'Fixture 1' } as FixturePatch
const DIMMER = sliderProp('dimmer', 'dimmer', chan(1))
const RGB = colourProp('rgb', chan(2), chan(3), chan(4))
const WHEEL = settingProp('colourWheel', 'colour', chan(5), [
  { name: 'open', level: 0, displayName: 'Open' },
  { name: 'red', level: 10, displayName: 'Red', colourPreview: '#ff0000' },
])

// A Revolution's three loadable settings, as `GET /fixture-types` carries them: a gel scroller (the
// colour source), a media frame whose wing takes a gel, and a module wheel slot that takes either.
const SCROLLER = {
  ...settingProp('gelScroller', 'colour', chan(6), [
    { name: 'OPEN_LEADER', level: 0, displayName: 'Open', colourPreview: '#FFFFFF', loadable: true },
    { name: 'L201_FULL_CT_BLUE', level: 165, displayName: 'L201', colourPreview: '#9bbede', loadable: true },
  ]),
  media: 'GEL' as const,
}
const MEDIA_FRAME = {
  ...settingProp('mediaFrame', 'setting', chan(7), [
    { name: 'OUT', level: 0, displayName: 'Out', loadable: false },
    { name: 'IN', level: 128, displayName: 'In', loadable: true },
  ]),
  media: 'GEL' as const,
}
const MODULE_WHEEL = {
  ...settingProp('fbWheelPos', 'gobo', chan(8), [
    { name: 'OPEN', level: 0, displayName: 'Open', loadable: false },
    { name: 'SLOT_1', level: 14, displayName: 'Slot 1', loadable: true },
  ]),
  media: 'GOBO_OR_GEL' as const,
}
const REVOLUTION = makeFixture('rev-1', [DIMMER, MEDIA_FRAME, SCROLLER, MODULE_WHEEL])
const R26: FittedMedia = { slots: { gelScroller: { L201_FULL_CT_BLUE: { gel: 'R26' } } } }

// The Robe ColorSpot 575's two colour wheels (fixture-optics plan session 4): the second's dichroics
// sit in series with the first's, so its slot multiplies the beam. A scroll band carries no single
// colour, and animates through its own wheel's (fixture-optics plan D8).
const ROBE_WHEEL_1 = settingProp('colour1', 'colour', chan(9), [
  { name: 'OPEN', level: 0, displayName: 'Open', colourPreview: '#FFFFFF' },
  { name: 'LIGHT_BLUE', level: 133, displayName: 'Light blue', colourPreview: '#ADD8E6' },
  { name: 'YELLOW', level: 160, displayName: 'Yellow', colourPreview: '#FFFF00' },
  { name: 'SCROLL_CW', level: 190, displayName: 'Scroll CW', noColour: true },
])
const ROBE_WHEEL_2 = settingProp('colour2', 'colour', chan(10), [
  { name: 'OPEN', level: 0, displayName: 'Open', colourPreview: '#FFFFFF' },
  { name: 'DEEP_RED', level: 133, displayName: 'Deep red', colourPreview: '#8B0000' },
  { name: 'CYAN', level: 155, displayName: 'Cyan', colourPreview: '#00FFFF' },
  { name: 'SCROLL_CW', level: 190, displayName: 'Scroll CW', noColour: true },
])
const ROBE = makeFixture('robe-1', [DIMMER, ROBE_WHEEL_1, ROBE_WHEEL_2])

function scenario(over: Partial<Scenario>): Scenario {
  return { patch: PATCH, fixture: undefined, fixtureType: undefined, values: {}, ...over }
}

const SCENARIOS: Array<{ name: string; scenario: Scenario }> = [
  {
    name: 'a patch whose fixture record has not resolved',
    scenario: scenario({ fixture: undefined }),
  },
  {
    name: 'a dimmer-only fixture, half up',
    scenario: scenario({
      fixture: makeFixture('fx-1', [DIMMER]),
      values: { '0:1': 128 },
    }),
  },
  {
    name: 'a gelled fixture on a type that accepts gel',
    scenario: scenario({
      patch: { ...PATCH, gelCode: 'L106' } as FixturePatch,
      fixture: makeFixture('fx-1', [DIMMER]),
      fixtureType: { typeKey: 't', acceptsGel: true } as FixtureTypeInfo,
      values: { '0:1': 255 },
    }),
  },
  {
    name: 'a gel code on a type that does not accept gel (stale data)',
    scenario: scenario({
      patch: { ...PATCH, gelCode: 'L106' } as FixturePatch,
      fixture: makeFixture('fx-1', [DIMMER]),
      fixtureType: { typeKey: 't', acceptsGel: false } as FixtureTypeInfo,
      values: { '0:1': 255 },
    }),
  },
  {
    name: 'an RGB fixture beating out its gel code',
    scenario: scenario({
      patch: { ...PATCH, gelCode: 'L106' } as FixturePatch,
      fixture: makeFixture('fx-1', [DIMMER, RGB]),
      fixtureType: { typeKey: 't', acceptsGel: true } as FixtureTypeInfo,
      values: { '0:1': 200, '0:2': 255, '0:3': 40, '0:4': 0 },
    }),
  },
  {
    name: 'a colour-wheel fixture on a selected preset',
    scenario: scenario({
      fixture: makeFixture('fx-1', [DIMMER, WHEEL]),
      values: { '0:1': 255, '0:5': 10 },
    }),
  },
  {
    name: 'a scroller unit on frame 9 with nothing fitted (the stock L201)',
    scenario: scenario({ fixture: REVOLUTION, values: { '0:1': 255, '0:6': 165 } }),
  },
  {
    name: 'a scroller unit with R26 fitted where frame 9 holds L201',
    scenario: scenario({
      patch: { ...PATCH, media: R26 } as FixturePatch,
      fixture: REVOLUTION,
      values: { '0:1': 255, '0:6': 165 },
    }),
  },
  {
    name: 'a scroller unit with an empty fitted frame (open white)',
    scenario: scenario({
      patch: { ...PATCH, media: { slots: { gelScroller: { L201_FULL_CT_BLUE: {} } } } } as FixturePatch,
      fixture: REVOLUTION,
      values: { '0:1': 255, '0:6': 165 },
    }),
  },
  {
    name: 'a media frame in with a fitted gel, filtering the scroller',
    scenario: scenario({
      patch: { ...PATCH, media: { slots: { mediaFrame: { IN: { gel: 'L106' } } } } } as FixturePatch,
      fixture: REVOLUTION,
      values: { '0:1': 255, '0:6': 165, '0:7': 128 },
    }),
  },
  {
    name: 'a dichroic in the module wheel and the frame out',
    scenario: scenario({
      patch: { ...PATCH, media: { slots: { fbWheelPos: { SLOT_1: { gel: 'R26' } }, mediaFrame: { IN: { gel: 'L106' } } } } } as FixturePatch,
      fixture: REVOLUTION,
      values: { '0:1': 255, '0:6': 0, '0:7': 0, '0:8': 14 },
    }),
  },
  {
    name: 'a two-wheel head with yellow on wheel 1 and cyan on wheel 2',
    scenario: scenario({ fixture: ROBE, values: { '0:1': 255, '0:9': 160, '0:10': 155 } }),
  },
  {
    name: 'a two-wheel head with wheel 1 open and deep red on wheel 2',
    scenario: scenario({ fixture: ROBE, values: { '0:1': 255, '0:9': 0, '0:10': 133 } }),
  },
  {
    name: 'a two-wheel head with wheel 2 on a scroll band',
    scenario: scenario({ fixture: ROBE, values: { '0:1': 255, '0:9': 133, '0:10': 200 } }),
  },
  {
    name: 'a two-wheel head with wheel 1 on a scroll band',
    scenario: scenario({ fixture: ROBE, values: { '0:1': 255, '0:9': 200, '0:10': 0 } }),
  },
  {
    name: 'an RGB head whose colour preset setting is not glass in the beam',
    scenario: scenario({
      fixture: makeFixture('fx-1', [DIMMER, RGB, WHEEL]),
      values: { '0:1': 255, '0:2': 255, '0:3': 40, '0:4': 0, '0:5': 10 },
    }),
  },
  {
    name: 'a colour-wheel fixture parked at open',
    scenario: scenario({
      fixture: makeFixture('fx-1', [DIMMER, WHEEL]),
      values: { '0:1': 255, '0:5': 0 },
    }),
  },
]

describe('2D and 3D colour dispatch parity', () => {
  it.each(SCENARIOS)('resolves $name identically on both surfaces', ({ scenario: s }) => {
    const twoD = resolve2D(s)
    const threeD = resolve3D(s)
    expect(threeD.colour).toBe(twoD.colour)
    expect(threeD.intensity).toBeCloseTo(twoD.intensity, 10)
  })

  // An animated band at a given time: a scroll on a single wheel, and a scroll on the Robe's second
  // wheel filtering the first — mid-hold and mid-move.
  const TIMES = [0, BAND_STEP_S * 1.2, BAND_STEP_S * 1.85, BAND_STEP_S * 6.5]
  it.each(
    TIMES.flatMap((t) => [
      { name: `a single wheel scrolling, at ${t.toFixed(2)} s`, t, values: { '0:1': 255, '0:9': 200, '0:10': 0 } },
      { name: `the second wheel scrolling over yellow, at ${t.toFixed(2)} s`, t, values: { '0:1': 255, '0:9': 160, '0:10': 200 } },
    ]),
  )('resolves $name identically on both surfaces', ({ t, values }) => {
    const s = scenario({ fixture: ROBE, values })
    expect(resolve3D(s, t).colour).toBe(resolve2D(s, t).colour)
    expect(resolve3D(s, t).intensity).toBeCloseTo(resolve2D(s, t).intensity, 10)
  })
})

describe('animated colour bands (fixture-optics plan D8)', () => {
  const at = (values: Record<string, number>, t: number) => {
    const s = scenario({ fixture: ROBE, values })
    return [resolve2D(s, t).colour, resolve3D(s, t).colour]
  }

  it("cycles a scrolling wheel through its own colours over time, on both dispatches, never black", () => {
    const seen = new Set<string>()
    for (let step = 0; step < 3; step++) {
      const [twoD, threeD] = at({ '0:1': 255, '0:9': 200 }, BAND_STEP_S * (step + 0.1))
      expect(threeD).toBe(twoD)
      expect(twoD).not.toBe('#000000')
      seen.add(twoD)
    }
    // Open, light blue, yellow — the wheel's own previews in level order.
    expect([...seen]).toEqual(['#ffffff', '#add8e6', '#ffff00'])
  })

  it('cycles the second wheel as a filter: the first wheel passes through its colours in turn', () => {
    // Yellow on wheel 1; wheel 2 scrolling open → deep red → cyan.
    expect(at({ '0:1': 255, '0:9': 160, '0:10': 200 }, BAND_STEP_S * 0.1)).toEqual(['#ffff00', '#ffff00'])
    expect(at({ '0:1': 255, '0:9': 160, '0:10': 200 }, BAND_STEP_S * 1.1)).toEqual(['#8b0000', '#8b0000'])
    expect(at({ '0:1': 255, '0:9': 160, '0:10': 200 }, BAND_STEP_S * 2.1)).toEqual(['#00ff00', '#00ff00'])
  })
})

describe('the Varytec Easymove XL 60 (fixture-optics plan D8)', () => {
  // Its wheel as the desk sends it since session 5: a preview on every position, the rainbow bands
  // marked as having no single colour. It used to carry no previews, and its beam drew black.
  const VARYTEC_WHEEL = settingProp('colourWheel', 'colour', chan(11), [
    { name: 'OPEN', level: 0, displayName: 'Open', colourPreview: '#FFFFFF' },
    { name: 'COLOR_1', level: 20, displayName: 'Color 1', colourPreview: '#FF0000' },
    { name: 'COLOR_2', level: 40, displayName: 'Color 2', colourPreview: '#00FF00' },
    { name: 'COLOR_3', level: 60, displayName: 'Color 3', colourPreview: '#0000FF' },
    { name: 'COLOR_4', level: 80, displayName: 'Color 4', colourPreview: '#FFFF00' },
    { name: 'COLOR_5', level: 95, displayName: 'Color 5', colourPreview: '#FF00FF' },
    { name: 'COLOR_6', level: 110, displayName: 'Color 6', colourPreview: '#00FFFF' },
    { name: 'COLOR_7', level: 123, displayName: 'Color 7', colourPreview: '#FFA500' },
    { name: 'RAINBOW_FORWARD', level: 160, displayName: 'Rainbow forward', noColour: true },
    { name: 'RAINBOW_REVERSE', level: 225, displayName: 'Rainbow reverse', noColour: true },
  ])
  const VARYTEC = makeFixture('vary-1', [DIMMER, VARYTEC_WHEEL])

  it('draws a colour at every DMX value, lit, on both dispatches — never black', () => {
    for (let level = 0; level <= 255; level += 5) {
      for (const t of [0, 2.5]) {
        const s = scenario({ fixture: VARYTEC, values: { '0:1': 255, '0:11': level } })
        const twoD = resolve2D(s, t)
        const threeD = resolve3D(s, t)
        expect(threeD.colour).toBe(twoD.colour)
        expect(twoD.colour).not.toBe('#000000')
        expect(twoD.intensity).toBe(1)
        expect(threeD.intensity).toBeCloseTo(1, 10)
      }
    }
  })
})

describe('the 3D dispatch asks for frames only while a band animates', () => {
  // A source whose values can move, notifying its subscribers as the live feed does.
  function mutableSource(values: Record<string, number>) {
    const map = new Map(Object.entries(values))
    const listeners = new Map<string, Set<(v: number) => void>>()
    const source: ChannelSource = {
      get: (universe, channelNo) => map.get(`${universe}:${channelNo}`) ?? 0,
      getByKey: (key) => map.get(key) ?? 0,
      subscribeToChannel: (key, fn) => {
        const set = listeners.get(key) ?? new Set()
        set.add(fn)
        listeners.set(key, set)
        return { unsubscribe: () => set.delete(fn) }
      },
    }
    const set = async (key: string, value: number) => {
      map.set(key, value)
      await act(async () => {
        for (const fn of listeners.get(key) ?? []) fn(value)
        await Promise.resolve()
      })
    }
    return { source, set }
  }

  function mount(source: ChannelSource, ticker: ColourTicker) {
    const properties = ROBE.properties
    const colourSource = findColourSource(properties)
    const colorStateRef = { current: { color: new Color('#000000'), coneOpacity: -1, poolOpacity: -1 } }
    const view = render(
      <ChannelSourceProvider source={source}>
        <ColourSync
          hasFixture
          colourSource={colourSource}
          gel={null}
          filters={colourFilters(properties, colourSource)}
          dimmerProp={findDimmerProperty(properties)}
          lensRef={{ current: null }}
          colorStateRef={colorStateRef}
          ticker={ticker}
        />
      </ChannelSourceProvider>,
    )
    return { colorStateRef, view }
  }

  it('registers while the wheel scrolls, re-applies per frame, and lets go when it stops', async () => {
    const invalidate = vi.fn()
    const ticker = createColourTicker(invalidate)
    const { source, set } = mutableSource({ '0:1': 255, '0:9': 133, '0:10': 0 })
    const { colorStateRef, view } = mount(source, ticker)
    expect(ticker.listening).toBe(0)

    await set('0:9', 200)
    expect(ticker.listening).toBe(1)
    ticker.frame(BAND_STEP_S * 1.1)
    expect(`#${colorStateRef.current.color.getHexString()}`).toBe('#add8e6')
    ticker.frame(BAND_STEP_S * 2.1)
    expect(`#${colorStateRef.current.color.getHexString()}`).toBe('#ffff00')

    invalidate.mockClear()
    // Light blue: a fixed colour, so the arm lets go and a frame redraws nothing.
    await set('0:9', 133)
    expect(ticker.listening).toBe(0)
    expect(`#${colorStateRef.current.color.getHexString()}`).toBe('#add8e6')
    ticker.frame(BAND_STEP_S * 3.1)
    expect(invalidate).not.toHaveBeenCalled()
    expect(`#${colorStateRef.current.color.getHexString()}`).toBe('#add8e6')
    view.unmount()
  })

  it('registers for a scrolling second wheel too, and unregisters on unmount', async () => {
    const ticker = createColourTicker(() => {})
    const { source, set } = mutableSource({ '0:1': 255, '0:9': 160, '0:10': 0 })
    const { view } = mount(source, ticker)
    await set('0:10', 200)
    expect(ticker.listening).toBe(1)
    view.unmount()
    expect(ticker.listening).toBe(0)
  })
})

describe('fitted media, per unit', () => {
  // The session's symptom: every unit drew ETC's stock string. Two units of one type, the same
  // scroller DMX, one fitted with R26 where frame 9 holds L201 — each draws its own on both surfaces.
  it('two units of one type draw different colours from the same scroller DMX', () => {
    const values = { '0:1': 255, '0:6': 165 }
    const fitted = scenario({ patch: { ...PATCH, media: R26 } as FixturePatch, fixture: REVOLUTION, values })
    const stock = scenario({ patch: { ...PATCH, key: 'rev-2' } as FixturePatch, fixture: REVOLUTION, values })
    expect(resolve3D(fitted).colour).toBe(GELS.byCode.get('R26')!.color)
    expect(resolve2D(fitted).colour).toBe(GELS.byCode.get('R26')!.color)
    expect(resolve3D(stock).colour).toBe('#9bbede')
    expect(resolve2D(stock).colour).toBe('#9bbede')
  })

  it('a media frame in multiplies its fitted gel into the beam, and out passes it unchanged', () => {
    const media: FittedMedia = { slots: { mediaFrame: { IN: { gel: 'L106' } } } }
    const inFrame = resolve3D(scenario({ patch: { ...PATCH, media } as FixturePatch, fixture: REVOLUTION, values: { '0:1': 255, '0:6': 0, '0:7': 128 } }))
    expect(inFrame.colour).toBe(GELS.byCode.get('L106')!.color.toLowerCase())
    const outFrame = resolve3D(scenario({ patch: { ...PATCH, media } as FixturePatch, fixture: REVOLUTION, values: { '0:1': 255, '0:6': 0, '0:7': 0 } }))
    expect(outFrame.colour).toBe('#ffffff')
  })
})

describe('stacked colour wheels', () => {
  const resolveBoth = (values: Record<string, number>, fixture: Fixture = ROBE) => {
    const s = scenario({ fixture, values })
    return [resolve2D(s).colour, resolve3D(s).colour]
  }

  it("multiplies the second wheel's slot over the first's, subtractively, on both dispatches", () => {
    // Yellow passes red and green, cyan green and blue: in series only green gets through.
    expect(resolveBoth({ '0:1': 255, '0:9': 160, '0:10': 155 })).toEqual(['#00ff00', '#00ff00'])
    expect(resolveBoth({ '0:1': 255, '0:9': 0, '0:10': 133 })).toEqual(['#8b0000', '#8b0000'])
    // A light blue through a deep red: each channel the product of the two (0xad × 0x8b / 255).
    expect(resolveBoth({ '0:1': 255, '0:9': 133, '0:10': 133 })).toEqual(['#5e0000', '#5e0000'])
  })

  it('passes the first wheel unchanged while the second is open or on a band with no colour', () => {
    expect(resolveBoth({ '0:1': 255, '0:9': 133, '0:10': 0 })).toEqual(['#add8e6', '#add8e6'])
    expect(resolveBoth({ '0:1': 255, '0:9': 133, '0:10': 200 })).toEqual(['#add8e6', '#add8e6'])
  })

  it("leaves an RGB head's colour preset out of the beam: it is not a second wheel", () => {
    const rgbOnly = resolveBoth({ '0:1': 255, '0:2': 255, '0:3': 40, '0:4': 0 }, makeFixture('fx-1', [DIMMER, RGB]))
    expect(resolveBoth({ '0:1': 255, '0:2': 255, '0:3': 40, '0:4': 0, '0:5': 10 }, makeFixture('fx-1', [DIMMER, RGB, WHEEL]))).toEqual(rgbOnly)
  })
})
