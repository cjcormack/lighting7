// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'

// Extend the standard lightingApi mock with a real channel-value store the
// tests can drive; everything else keeps the no-op subscription fallback.
const { channelValues, channelListeners } = vi.hoisted(() => ({
  channelValues: new Map<string, number>(),
  channelListeners: new Set<(updates: Map<string, number>) => void>(),
}))
vi.mock('@/api/lightingApi', async () => {
  const { lightingApiMock } = await import('@/test/backendMock')
  const base = lightingApiMock().lightingApi as Record<string, unknown>
  return {
    lightingApi: new Proxy(base, {
      get: (target, prop: string) =>
        prop === 'channels'
          ? {
              getAll: () => channelValues,
              subscribe: (listener: (updates: Map<string, number>) => void) => {
                channelListeners.add(listener)
                return { unsubscribe: () => channelListeners.delete(listener) }
              },
            }
          : target[prop],
    }),
  }
})

import { act, renderHook } from '@testing-library/react'
import { useLitFixtureKeys } from './useLitFixtureKeys'
import { takeLitHold } from '@/lib/litHold'
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

describe('useLitFixtureKeys under a hold (programmer-fixes plan D21)', () => {
  const spot = makeFixture('spot', [sliderProp('dimmer', 'dimmer', chan(1))])
  const wash = makeFixture('wash', [sliderProp('dimmer', 'dimmer', chan(2))])
  const setLevel = (key: string, value: number) =>
    act(() => {
      channelValues.set(key, value)
      for (const listener of channelListeners) listener(new Map([[key, value]]))
    })
  const mount = () => {
    channelValues.clear()
    channelListeners.clear()
    channelValues.set('0:1', 255)
    channelValues.set('0:2', 0)
    return renderHook(() => useLitFixtureKeys([spot, wash]))
  }

  it('keeps the set through a dimmer going to 0 and catches up on release', () => {
    const { result, unmount } = mount()
    expect([...result.current]).toEqual(['spot'])
    const release = takeLitHold()
    setLevel('0:1', 0)
    setLevel('0:2', 200)
    expect([...result.current]).toEqual(['spot'])
    act(() => release())
    expect([...result.current]).toEqual(['wash'])
    unmount()
  })

  it.each([
    ['first', 'second'],
    ['second', 'first'],
  ])('holds until the last of two holds goes, the %s released first', (first) => {
    const { result, unmount } = mount()
    const holds = { first: takeLitHold(), second: takeLitHold() }
    const other = first === 'first' ? 'second' : 'first'
    setLevel('0:1', 0)
    act(() => holds[first as 'first' | 'second']())
    expect([...result.current]).toEqual(['spot'])
    act(() => holds[other as 'first' | 'second']())
    expect([...result.current]).toEqual([])
    unmount()
  })

  it('releases once however often the release is called', () => {
    const { result, unmount } = mount()
    const a = takeLitHold()
    const b = takeLitHold()
    setLevel('0:1', 0)
    act(() => {
      a()
      a()
    })
    expect([...result.current]).toEqual(['spot'])
    act(() => b())
    expect([...result.current]).toEqual([])
    unmount()
  })
})
