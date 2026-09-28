// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'

// Extend the standard lightingApi mock with a real channel-value store the
// tests can drive; everything else keeps the no-op subscription fallback.
const { channelValues } = vi.hoisted(() => ({ channelValues: new Map<string, number>() }))
vi.mock('@/api/lightingApi', async () => {
  const { lightingApiMock } = await import('@/test/backendMock')
  const base = lightingApiMock().lightingApi as Record<string, unknown>
  return {
    lightingApi: new Proxy(base, {
      get: (target, prop: string) =>
        prop === 'channels'
          ? {
              getAll: () => channelValues,
              subscribe: () => ({ unsubscribe: () => {} }),
            }
          : target[prop],
    }),
  }
})

import { renderHook } from '@testing-library/react'
import { useLitFixtureKeys } from './useLitFixtureKeys'
import { chan, element, makeFixture, sliderProp } from '@/test/fixtureFactories'
import type { Fixture } from '@/store/fixtures'

function litKeys(fixtures: Fixture[], values: Record<string, number>): ReadonlySet<string> {
  channelValues.clear()
  for (const [key, value] of Object.entries(values)) channelValues.set(key, value)
  const { result, unmount } = renderHook(() => useLitFixtureKeys(fixtures))
  const lit = result.current
  unmount()
  return lit
}

describe('useLitFixtureKeys', () => {
  const master = makeFixture('spot', [sliderProp('dimmer', 'dimmer', chan(1))])
  const bar = makeFixture('bar', [], {
    elements: [
      element(0, 'bar.pixel-0', [sliderProp('dimmer', 'dimmer', chan(10))]),
      element(1, 'bar.pixel-1', [sliderProp('dimmer', 'dimmer', chan(11))]),
    ],
  })
  const gated = makeFixture('gated', [sliderProp('dimmer', 'dimmer', chan(20))], {
    elements: [
      element(0, 'gated.pixel-0', [sliderProp('dimmer', 'dimmer', chan(21))]),
      element(1, 'gated.pixel-1', [sliderProp('dimmer', 'dimmer', chan(22))]),
    ],
  })
  const dimmerless = makeFixture('rgbOnly', [])

  it('counts a master-only fixture lit iff its master is above zero', () => {
    expect(litKeys([master], { '0:1': 0 }).has('spot')).toBe(false)
    expect(litKeys([master], { '0:1': 128 }).has('spot')).toBe(true)
  })

  it('counts a heads-only fixture lit when any head is above zero', () => {
    expect(litKeys([bar], { '0:10': 0, '0:11': 0 }).has('bar')).toBe(false)
    expect(litKeys([bar], { '0:10': 0, '0:11': 5 }).has('bar')).toBe(true)
  })

  it('requires master AND some head when dimmers exist at both levels', () => {
    // Master at zero gates the heads dark.
    expect(litKeys([gated], { '0:20': 0, '0:21': 255, '0:22': 255 }).has('gated')).toBe(false)
    // Master up over all-zero heads emits nothing either.
    expect(litKeys([gated], { '0:20': 255, '0:21': 0, '0:22': 0 }).has('gated')).toBe(false)
    expect(litKeys([gated], { '0:20': 255, '0:21': 0, '0:22': 40 }).has('gated')).toBe(true)
  })

  it('never counts fixtures with no dimmer at any level', () => {
    expect(litKeys([dimmerless], {}).has('rgbOnly')).toBe(false)
  })
})
