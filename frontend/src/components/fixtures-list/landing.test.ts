// @vitest-environment jsdom
import { act, renderHook } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import { chan, makeFixture, makePixelBar, sliderProp } from '../../test/fixtureFactories'
import { resetListExpansionStores, useListExpansion } from '../../lib/listExpansion'
import { foldLandingKeys, useLandingOpenings } from './landing'

const shehds = makePixelBar('shehds', 3, [sliderProp('dimmer', 'dimmer', chan(1))])
const wash = makeFixture('wash', [sliderProp('dimmer', 'dimmer', chan(90))])
const spot = makeFixture('spot', [sliderProp('dimmer', 'dimmer', chan(91))], { groups: ['Front'] })
const FIXTURES = [shehds, wash, spot]
const heads = (key: string, ...n: number[]) => n.map((i) => `${key}.pixel-${i}`)
const fold = (keys: string[]) => {
  const { keys: folded, open } = foldLandingKeys(keys, FIXTURES)
  return { keys: folded, open }
}

describe('foldLandingKeys', () => {
  it('answers a fixture all of whose heads came in as the fixture, opening nothing', () => {
    expect(fold(heads('shehds', 0, 1, 2))).toEqual({ keys: ['shehds'], open: [] })
  })

  it('answers some heads as those heads, and opens their fixture', () => {
    expect(fold(heads('shehds', 2, 0))).toEqual({ keys: heads('shehds', 2, 0), open: ['shehds'] })
  })

  it('keeps a fixture with no heads as its own key', () => {
    expect(fold(['wash'])).toEqual({ keys: ['wash'], open: [] })
  })

  it('keeps a parent key, folding any of its heads beside it into it', () => {
    expect(fold(['shehds'])).toEqual({ keys: ['shehds'], open: [] })
    expect(fold([...heads('shehds', 1), 'shehds'])).toEqual({ keys: ['shehds'], open: [] })
  })

  it('keeps a group’s members, in the order they came, each folded on its own', () => {
    expect(fold(['spot', ...heads('shehds', 0, 1, 2), 'wash'])).toEqual({
      keys: ['spot', 'shehds', 'wash'],
      open: [],
    })
    expect(fold(['spot', ...heads('shehds', 1), 'wash'])).toEqual({
      keys: ['spot', ...heads('shehds', 1), 'wash'],
      open: ['shehds'],
    })
  })

  it('answers each key with the fixture whose row shows it', () => {
    const { fixtureOf } = foldLandingKeys(['wash', ...heads('shehds', 1), 'gone'], FIXTURES)
    expect(Object.fromEntries([...fixtureOf].map(([key, fixture]) => [key, fixture.key]))).toEqual({
      wash: 'wash',
      'shehds.pixel-1': 'shehds',
    })
    expect(foldLandingKeys(heads('shehds', 0, 1, 2), FIXTURES).fixtureOf.get('shehds')?.key).toBe('shehds')
  })

  it('keeps a key the rig does not know, once', () => {
    expect(fold(['gone', 'gone', 'wash'])).toEqual({ keys: ['gone', 'wash'], open: [] })
  })
})

describe('useLandingOpenings', () => {
  beforeEach(() => {
    resetListExpansionStores()
    window.sessionStorage.clear()
  })

  function useHarness(scope = '1:programmer') {
    const stored = useListExpansion(1, 'programmer', null)
    return { stored, drawn: useLandingOpenings(stored, scope) }
  }

  it('draws a landing’s openings without storing them, and the next landing replaces them', () => {
    const { result } = renderHook(() => useHarness())
    act(() => result.current.drawn.openForLanding({ groups: ['Front'], fixtures: ['shehds'] }))
    expect([...result.current.drawn.expandedFixtures]).toEqual(['shehds'])
    expect([...result.current.drawn.expandedGroups]).toEqual(['Front'])
    expect(result.current.stored.expandedFixtures.size).toBe(0)
    expect(result.current.stored.expandedGroups.size).toBe(0)

    act(() => result.current.drawn.openForLanding({ fixtures: ['bar'] }))
    expect([...result.current.drawn.expandedFixtures]).toEqual(['bar'])
    expect(result.current.drawn.expandedGroups.size).toBe(0)
  })

  it('closes a row the landing opened when the operator closes it, storing nothing', () => {
    const { result } = renderHook(() => useHarness())
    act(() => result.current.drawn.openForLanding({ fixtures: ['shehds'] }))
    act(() =>
      result.current.drawn.setExpandedFixtures((prev) => {
        const next = new Set(prev)
        next.delete('shehds')
        return next
      }),
    )
    expect(result.current.drawn.expandedFixtures.size).toBe(0)
    expect(result.current.stored.expandedFixtures.size).toBe(0)
  })

  it('stores a row the operator opens, and leaves the landing’s own open rows the landing’s', () => {
    const { result } = renderHook(() => useHarness())
    act(() => result.current.drawn.openForLanding({ fixtures: ['shehds'] }))
    act(() => result.current.drawn.setExpandedFixtures((prev) => new Set([...prev, 'bar'])))
    expect([...result.current.stored.expandedFixtures]).toEqual(['bar'])
    expect(new Set(result.current.drawn.expandedFixtures)).toEqual(new Set(['shehds', 'bar']))

    act(() => result.current.drawn.openForLanding({}))
    expect([...result.current.drawn.expandedFixtures]).toEqual(['bar'])
  })

  it('stores a landing’s row the operator means to open, so the next landing leaves it open', () => {
    const { result } = renderHook(() => useHarness())
    act(() => result.current.drawn.openForLanding({ fixtures: ['shehds', 'bar'] }))
    act(() => result.current.drawn.setExpandedFixtures((prev) => prev, ['shehds']))
    expect([...result.current.stored.expandedFixtures]).toEqual(['shehds'])

    act(() => result.current.drawn.openForLanding({}))
    expect([...result.current.drawn.expandedFixtures]).toEqual(['shehds'])

    act(() =>
      result.current.drawn.setExpandedFixtures((prev) => {
        const next = new Set(prev)
        next.delete('shehds')
        return next
      }),
    )
    expect(result.current.drawn.expandedFixtures.size).toBe(0)
  })

  it('draws no openings for another scope', () => {
    const { result, rerender } = renderHook(({ scope }) => useHarness(scope), { initialProps: { scope: '1:programmer' } })
    act(() => result.current.drawn.openForLanding({ fixtures: ['shehds'] }))
    rerender({ scope: '2:programmer' })
    expect(result.current.drawn.expandedFixtures.size).toBe(0)
  })
})
