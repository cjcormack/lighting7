// @vitest-environment jsdom
import { act, renderHook } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import { expansionKey, parseListExpansion, resetListExpansionStores, useListExpansion, type KnownRig } from './listExpansion'

beforeEach(() => {
  resetListExpansionStores()
  window.sessionStorage.clear()
})

const stored = (projectId: number, scope: 'programmer' | 'fixtures' | 'groups') =>
  JSON.parse(window.sessionStorage.getItem(expansionKey(projectId, scope)) ?? 'null')

describe('parseListExpansion', () => {
  it('keeps two string arrays and drops everything else', () => {
    expect(parseListExpansion({ groups: ['Front', 3, 'Front'], fixtures: 'bar', extra: 1 })).toEqual({ groups: ['Front'], fixtures: [] })
    expect(parseListExpansion(null)).toEqual({ groups: [], fixtures: [] })
    expect(parseListExpansion([1, 2])).toEqual({ groups: [], fixtures: [] })
  })

  it('reads junk in storage as nothing open', () => {
    window.sessionStorage.setItem(expansionKey(1, 'programmer'), '{not json')
    const { result } = renderHook(() => useListExpansion(1, 'programmer', null))
    expect([...result.current.expandedGroups]).toEqual([])
  })
})

describe('useListExpansion', () => {
  it('keeps each scope and each project apart', () => {
    const programmer = renderHook(() => useListExpansion(1, 'programmer', null))
    act(() => programmer.result.current.setExpandedGroups((prev) => new Set([...prev, 'Front'])))
    expect(stored(1, 'programmer')).toEqual({ groups: ['Front'], fixtures: [] })

    expect([...renderHook(() => useListExpansion(1, 'groups', null)).result.current.expandedGroups]).toEqual([])
    expect([...renderHook(() => useListExpansion(2, 'programmer', null)).result.current.expandedGroups]).toEqual([])
  })

  it('composes several updates in one turn', () => {
    const { result } = renderHook(() => useListExpansion(1, 'programmer', null))
    act(() => {
      result.current.setExpandedFixtures((prev) => new Set([...prev, 'bar-1']))
      result.current.setExpandedFixtures((prev) => new Set([...prev, 'bar-2']))
    })
    expect([...result.current.expandedFixtures]).toEqual(['bar-1', 'bar-2'])
  })

  it('ignores a name the rig has lost, and drops it on the next write once the rig has loaded', () => {
    window.sessionStorage.setItem(expansionKey(1, 'programmer'), JSON.stringify({ groups: ['Gone', 'Front'], fixtures: ['old-bar'] }))
    const rig: KnownRig = { groups: new Set(['Front', 'Back']), fixtures: new Set(['bar-1']) }
    const { result, rerender } = renderHook(({ known }: { known: KnownRig | null }) => useListExpansion(1, 'programmer', known), {
      initialProps: { known: null as KnownRig | null },
    })
    // Still loading: nothing is judged stale.
    expect([...result.current.expandedGroups]).toEqual(['Gone', 'Front'])
    rerender({ known: rig })
    expect([...result.current.expandedGroups]).toEqual(['Front'])
    expect(stored(1, 'programmer')).toEqual({ groups: ['Gone', 'Front'], fixtures: ['old-bar'] })
    act(() => result.current.setExpandedGroups((prev) => new Set([...prev, 'Back'])))
    expect(stored(1, 'programmer')).toEqual({ groups: ['Front', 'Back'], fixtures: [] })
  })
})
