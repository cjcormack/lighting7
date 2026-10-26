// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'
import { renderHook } from '@testing-library/react'
import type { Fixture } from '@/store/fixtures'
import { useFxScope } from './useFxScope'

// A bar with a dimmer of its own and a colour on each of its three cells.
const bar = {
  key: 'bar',
  attributes: [{ name: 'dimmer', type: 'slider' }],
  elements: ['a', 'b', 'c'].map((c) => ({ key: `bar.${c}`, attributes: [{ name: 'rgbColour', type: 'colour' }] })),
  elementGroupAttributes: [{ name: 'rgbColour', type: 'colour' }],
} as unknown as Fixture

vi.mock('@/hooks/useFixtureLookup', () => ({
  useFixtureLookup: () => ({ fixtures: [bar], fixtureByKey: new Map([['bar', bar]]) }),
}))

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
})
