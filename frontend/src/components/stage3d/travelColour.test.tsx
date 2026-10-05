// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'
import { act, render } from '@testing-library/react'
import { Color } from 'three'
import { ColourSync, type ColourTravel } from './FixtureModel'
import { ChannelSourceProvider } from '../../hooks/useChannelSource'
import type { ChannelSource } from '../../api/channelSource'
import { createColourTicker } from './colourTicker'
import { settingColourAt } from '../../lib/colourBands'
import { travelRates } from '../../lib/travel'
import { chan, settingProp, sliderProp } from '../../test/fixtureFactories'

// usePropertyValues imports lightingApi for its writers, and the real module opens a WebSocket at
// import time. The reads all go through the injected ChannelSource.
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

/**
 * Travel time on the colour arms (fixture-optics plan D14): a scroller winds through the frames
 * between two gels — the unit's own string, since the arm is handed the fitted options — at the
 * type's colour speed or over its Colour Timing, re-applied by the ticker while, and only while, it
 * is in flight.
 */

// The Revolution's standard 12-colour string on ch 13 (manual p15 [11]), as `GET /fixture-types` carries it.
const FRAMES: Array<[string, number, string]> = [
  ['OPEN_LEADER', 0, '#ffffff'],
  ['R02_BASTARD_AMBER', 18, '#fbcc9a'],
  ['R05_ROSE_TINT', 37, '#f6d3d6'],
  ['R09_PALE_AMBER_GOLD', 55, '#f7c587'],
  ['R54_SPECIAL_LAVENDER', 73, '#dac7ea'],
  ['R357_ROYAL_LAVENDER', 91, '#a87bc9'],
  ['R36_MEDIUM_PINK', 110, '#ef9fb9'],
  ['R25_ORANGE_RED', 128, '#e85b2b'],
  ['L203_QUARTER_CT_BLUE', 146, '#dbe7f2'],
  ['L201_FULL_CT_BLUE', 165, '#9bbede'],
  ['R68_SKY_BLUE', 183, '#65a8db'],
  ['R88_LIGHT_GREEN', 201, '#b6e09a'],
  ['L_HT115_PEACOCK_BLUE', 219, '#2aa5a6'],
  ['OPEN_TRAILER', 238, '#ffffff'],
]
const SCROLLER = {
  ...settingProp(
    'gelScroller',
    'colour',
    chan(13),
    FRAMES.map(([name, level, colourPreview]) => ({ name, level, displayName: name, colourPreview, loadable: true })),
  ),
  media: 'GEL' as const,
}
const MEDIA_FRAME = settingProp('mediaFrame', 'setting', chan(6), [
  { name: 'OUT', level: 0, displayName: 'Out' },
  { name: 'IN', level: 128, displayName: 'In', colourPreview: '#ff0000' },
])
const COLOUR_TIMING = sliderProp('colTime', 'speed', chan(10), { timing: 'COLOUR', timingSecondsPerStep: 1 })
// The Revolution's estimated string speed: the whole 0–255 range in 2.5 s.
const TRAVEL: ColourTravel = { rate: travelRates({ colourMs: 2500 }).colour, timing: COLOUR_TIMING }

const FRAME_1 = 18 // R02
const FRAME_9 = 165 // L201

/** A source the test writes to, notifying its subscribers as the wire would; and whose epoch it can bump. */
function liveSource(values: Record<string, number>) {
  const map = new Map(Object.entries(values))
  const listeners = new Map<string, Set<(v: number) => void>>()
  let epoch = 0
  const source: ChannelSource = {
    get: (u, c) => map.get(`${u}:${c}`) ?? 0,
    getByKey: (key) => map.get(key) ?? 0,
    subscribeToChannel: (key, fn) => {
      const set = listeners.get(key) ?? new Set()
      set.add(fn)
      listeners.set(key, set)
      return { unsubscribe: () => set.delete(fn) }
    },
    epoch: () => epoch,
  }
  return {
    source,
    async set(key: string, value: number) {
      map.set(key, value)
      listeners.get(key)?.forEach((fn) => fn(value))
      // subscribeToChannels coalesces a batch on a microtask.
      await act(async () => {})
    },
    async replace(key: string, value: number) {
      epoch++
      await this.set(key, value)
    },
  }
}

/** [travel] null mounts an arm that does not ease — a capture's. */
function mount(source: ChannelSource, filters: typeof MEDIA_FRAME[] = [], travel: ColourTravel | null = TRAVEL) {
  const invalidate = vi.fn()
  const ticker = createColourTicker(invalidate)
  const colorStateRef = { current: { color: new Color('#000000'), coneOpacity: 0, poolOpacity: 0 } }
  const lensRef = { current: null }
  const tree = (s: ChannelSource) => (
    <ChannelSourceProvider source={s}>
      <ColourSync
        hasFixture
        colourSource={{ type: 'setting', property: SCROLLER }}
        gel={null}
        filters={filters}
        dimmerProp={undefined}
        lensRef={lensRef}
        colorStateRef={colorStateRef}
        ticker={ticker}
        travel={travel ?? undefined}
      />
    </ChannelSourceProvider>
  )
  const view = render(tree(source))
  return {
    ticker,
    invalidate,
    /** Point the arm at another vis source, as the View menu does. */
    switchTo: (s: ChannelSource) => view.rerender(tree(s)),
    colour: () => `#${colorStateRef.current.color.getHexString()}`,
    frame: (t: number) => act(() => ticker.frame(t)),
  }
}

const hex = (level: number) => `#${new Color(settingColourAt(SCROLLER.options, level, 0)).getHexString()}`

describe('a scroller travelling', () => {
  it('with Colour Timing at 5 scrolls from frame 1 to 9 over 5 s, through the frames between (plan §9)', async () => {
    const wire = liveSource({ '0:13': FRAME_1, '0:10': 5 })
    const m = mount(wire.source)
    expect(m.colour()).toBe('#fbcc9a') // first paint lands: R02

    await wire.set('0:13', FRAME_9)
    expect(m.colour()).toBe('#fbcc9a') // the plan's clock waits for the next frame
    expect(m.ticker.listening).toBe(1)

    const seen: string[] = []
    for (let i = 0; i <= 50; i++) {
      m.frame(100 + i * 0.1)
      if (seen[seen.length - 1] !== m.colour()) seen.push(m.colour())
      if (i === 25) expect(m.colour()).toBe(hex(FRAME_1 + (FRAME_9 - FRAME_1) / 2)) // 2.5 s: half way
      if (i === 49) expect(m.colour()).not.toBe('#9bbede') // 4.9 s: not there yet
    }
    // Every frame from R02 to L201 in string order, each once: the string winds through the gate.
    expect(seen).toEqual(FRAMES.slice(1, 10).map(([, , c]) => c))
  })

  it('travels at the type’s speed with Colour Timing at 0, and lands', async () => {
    const wire = liveSource({ '0:13': FRAME_1, '0:10': 0 })
    const m = mount(wire.source)
    await wire.set('0:13', FRAME_9)
    m.frame(10)
    // 147 steps at 102 a second (the whole string in 2.5 s): 1.44 s.
    m.frame(10.72)
    expect(m.colour()).toBe(hex(FRAME_1 + 0.72 * TRAVEL.rate))
    m.frame(11.5)
    expect(m.colour()).toBe('#9bbede')
  })

  it('asks for frames only while a move is in flight: a settled scroller costs the canvas nothing', async () => {
    const wire = liveSource({ '0:13': FRAME_1, '0:10': 1 })
    const m = mount(wire.source)
    expect(m.ticker.listening).toBe(0)
    await wire.set('0:13', FRAME_9)
    expect(m.ticker.listening).toBe(1)
    m.frame(0)
    m.frame(0.5)
    m.frame(1.01) // landed
    expect(m.colour()).toBe('#9bbede')
    expect(m.ticker.listening).toBe(0)
    m.invalidate.mockClear()
    for (let t = 2; t < 20; t++) m.frame(t)
    expect(m.invalidate).not.toHaveBeenCalled()
  })

  it('lands rather than travels when the source’s values are replaced, and with no travel at all', async () => {
    const wire = liveSource({ '0:13': FRAME_1, '0:10': 5 })
    const m = mount(wire.source)
    await wire.replace('0:13', FRAME_9) // the wire's snapshot after a reconnect: a new picture
    expect(m.colour()).toBe('#9bbede')
    expect(m.ticker.listening).toBe(0)

    const still = liveSource({ '0:13': FRAME_1, '0:10': 5 })
    const plain = mount(still.source, [], null) // a capture: no travel
    await still.set('0:13', FRAME_9)
    expect(plain.colour()).toBe('#9bbede')
  })

  it('lands on a vis-source switch rather than travelling from the old source’s colour', async () => {
    const output = liveSource({ '0:13': FRAME_1, '0:10': 5 })
    const nextGo = liveSource({ '0:13': FRAME_9, '0:10': 5 })
    const m = mount(output.source)
    expect(m.colour()).toBe('#fbcc9a')
    m.switchTo(nextGo.source)
    expect(m.colour()).toBe('#9bbede')
    expect(m.ticker.listening).toBe(0)
  })

  it('times the scroller but not the media frame, which the manual leaves off Colour Timing', async () => {
    const wire = liveSource({ '0:13': 0, '0:6': 0, '0:10': 100 })
    const m = mount(wire.source, [MEDIA_FRAME])
    expect(m.colour()).toBe('#ffffff')
    await wire.set('0:6', 128)
    m.frame(0)
    // 128 steps at the type's 102 a second, not 100 s: in by 1.3 s, filtering open white red.
    m.frame(1.3)
    expect(m.colour()).toBe('#ff0000')
  })
})
