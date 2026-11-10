// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'
import { renderHook } from '@testing-library/react'
import type { Fixture } from '@/store/fixtures'
import type { FxLibraryEntry } from '@/store/fixtureFx'
import { chan, colourProp, sliderProp } from '@/test/fixtureFactories'
import { useFxScope } from './useFxScope'

// A bar with a dimmer of its own and a colour on each of its three cells.
const bar = {
  key: 'bar',
  attributes: [{ name: 'dimmer', type: 'slider' }],
  elements: ['a', 'b', 'c'].map((c) => ({ key: `bar.${c}`, attributes: [{ name: 'rgbColour', type: 'colour' }] })),
  elementGroupAttributes: [{ name: 'rgbColour', type: 'colour' }],
} as unknown as Fixture

// A Shehds 24-channel: a dimmer and position of its own, its colour and white on its three zones.
const shehds = {
  key: 'shehds-1',
  attributes: [sliderProp('dimmer', 'dimmer', chan(7))],
  elements: [1, 2, 3].map((n) => ({
    index: n - 1,
    key: `shehds-1.zone-${n}`,
    displayName: `Zone ${n}`,
    attributes: [colourProp('rgbColour', chan(5 + 4 * n), chan(6 + 4 * n), chan(7 + 4 * n), { whiteChannel: chan(8 + 4 * n) })],
  })),
  elementGroupAttributes: [{ name: 'rgbColour', type: 'colour' }],
} as unknown as Fixture

vi.mock('@/hooks/useFixtureLookup', () => ({
  useFixtureLookup: () => ({
    fixtures: [bar, shehds],
    fixtureByKey: new Map([
      ['bar', bar],
      ['shehds-1', shehds],
    ]),
  }),
}))

const COLOUR_CYCLE = { name: 'ColourCycle', family: 'colour' } as FxLibraryEntry

const scopeOf = (attribute: string) =>
  renderHook(() =>
    useFxScope({
      target: { targetKey: 'bar', isGroupTarget: false, targetType: 'fixture' },
      attribute,
      entry: undefined,
      elementMode: 'WHOLE',
      elementFilter: 'ALL',
    }),
  ).result.current.scope

describe('useFxScope', () => {
  it('offers Over only where the FX lands on the fixture’s elements', () => {
    expect(scopeOf('rgbColour').elementMode).toBe(true)
    expect(scopeOf('dimmer').elementMode).toBe(false)
    expect(scopeOf('dimmer').heads).toBe(true)
  })

  it('reads a colour FX’s emitters from the heads, a Shehds’ zones included', () => {
    const channelsOf = (targetKey: string) =>
      renderHook(() =>
        useFxScope({
          target: { targetKey, isGroupTarget: false, targetType: 'fixture' },
          attribute: 'rgbColour',
          entry: COLOUR_CYCLE,
          elementMode: 'WHOLE',
          elementFilter: 'ALL',
        }),
      ).result.current.extendedChannels
    expect(channelsOf('shehds-1')).toEqual({ white: true, amber: false, uv: false })
    expect(channelsOf('bar')).toBeUndefined()
  })
})
